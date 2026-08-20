package com.hfstudio.guidenh

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer

data class GuideNhRuntimeStatus(val state: String, val message: String? = null)
data class GuideNhRuntimeEntry(val id: String, val label: String? = null, val detail: String? = null)
data class GuideNhRuntimeCache(val version: Long, val entries: List<GuideNhRuntimeEntry>, val stale: Boolean = false, val nextCursor: String? = null)
data class GuideNhRuntimeDiagnostic(val message: String, val severity: String = "error", val start: Int = 0, val end: Int = start)

@Service(Service.Level.APP)
class GuideNhRuntimeBridgeService {
    private val gson = Gson()
    private val pending = ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private val semanticCache = ConcurrentHashMap<String, GuideNhRuntimeCache>()
    private val semanticGuardStates = ConcurrentHashMap<String, SemanticGuardState>()
    private val previewCache = ConcurrentHashMap<String, JsonObject>()
    private val previewCacheOrder = ConcurrentLinkedDeque<String>()
    private val validationDiagnostics = ConcurrentHashMap<String, List<GuideNhRuntimeDiagnostic>>()
    private val validationRequests = ConcurrentHashMap<String, String>()
    private val prefetches = ConcurrentHashMap<String, CompletableFuture<List<JsonObject>>>()
    private val previewPrefetches = ConcurrentHashMap<String, CompletableFuture<*>>()
    private val supportedCapabilities = ConcurrentHashMap.newKeySet<String>()
    private val logs = ConcurrentLinkedDeque<String>()
    private var socket: WebSocket? = null
    private var lastConnection: RuntimeConnection? = null
    private var reconnectAttempts = 0
    @Volatile private var connectionGeneration = 0L
    @Volatile private var reconnectScheduled = false
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "guidenh-runtime-reconnect").apply { isDaemon = true } }
    @Volatile var status: GuideNhRuntimeStatus = GuideNhRuntimeStatus("disconnected")
        private set
    val isConnected: Boolean get() = status.state == "connected" && socket != null
    fun recentLogs(): List<String> = logs.toList()

    fun connect(host: String, port: Int, token: String, allowRemote: Boolean = false) {
        require(port in 1..65535) { "Runtime bridge port must be between 1 and 65535" }
        if (!allowRemote && host !in setOf("localhost", "127.0.0.1", "::1")) require(false) { "Remote runtime bridge requires explicit permission" }
        disconnect(false)
        lastConnection = RuntimeConnection(host, port, token, allowRemote)
        reconnectAttempts = 0
        openConnection(host, port, token)
    }

    private fun openConnection(host: String, port: Int, token: String) {
        val generation = ++connectionGeneration
        status = GuideNhRuntimeStatus("connecting")
        log("Connecting to $host:$port")
        val uri = URI("ws://$host:$port")
        HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, Listener(token, generation)).whenComplete { value, error ->
            if (generation != connectionGeneration) {
                value?.sendClose(WebSocket.NORMAL_CLOSURE, "superseded")
                return@whenComplete
            }
            if (error != null) {
                status = GuideNhRuntimeStatus("error", error.message)
                log("Runtime connection failed: ${error.message}")
                scheduleReconnect()
            } else {
                socket = value
            }
        }
    }

    fun disconnect() = disconnect(true)

    private fun disconnect(clearConnection: Boolean) {
        connectionGeneration++
        reconnectScheduled = false
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "closed")
        socket = null
        pending.clear()
        validationRequests.clear()
        previewPrefetches.values.forEach { it.cancel(true) }
        previewPrefetches.clear()
        semanticCache.replaceAll { _, value -> value.copy(stale = true) }
        semanticGuardStates.clear()
        previewCache.clear()
        previewCacheOrder.clear()
        supportedCapabilities.clear()
        if (clearConnection) lastConnection = null
        status = GuideNhRuntimeStatus("disconnected")
        log("Runtime disconnected")
    }

    fun validate(uri: String, languageId: String, text: String) {
        require(uri.isNotBlank() && languageId.isNotBlank()) { "Runtime validation requires a URI and language id" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES / 2) { "Runtime validation document is too large" }
        val id = UUID.randomUUID().toString()
        validationRequests[id] = uri
        pending[id] = { payload -> handleValidation(payload, id) }
        sendEnvelope(id, "document.validate", mapOf("uri" to uri, "languageId" to languageId, "text" to text))
    }

    fun diagnostics(uri: String): List<GuideNhRuntimeDiagnostic> = validationDiagnostics[uri].orEmpty()

    fun previewSearch(capability: String, prefix: String = "", limit: Int = 80, filters: Map<String, String> = emptyMap()): List<JsonObject> =
        previewSearchPage(capability, "", prefix, limit, filters).entries

    fun previewSearchPage(capability: String, cursor: String = "", prefix: String = "", limit: Int = 80, filters: Map<String, String> = emptyMap()): RuntimePage =
        parseRuntimePage(capability, requestPayload("preview.search", queryPayload(capability, cursor, prefix, limit, filters)), "preview.search", cacheKey(capability, filters, prefix), cursor.isEmpty())

    fun previewResolve(capability: String, id: String, count: Int? = null, nbt: String? = null, renderVariant: String? = null, filters: Map<String, String> = emptyMap()): JsonObject? {
        val key = previewKey(capability, id, count, nbt, renderVariant, filters)
        previewCache[key]?.let { return it.deepCopy() }
        val value = validatePreviewResolve(capability, id, requestPayload("preview.resolve", mapOf(
            "capability" to capability, "id" to id, "count" to count, "nbt" to nbt,
            "renderVariant" to renderVariant, "filters" to filters
        ))) ?: return null
        cachePreview(key, value)
        return value.deepCopy()
    }

    fun previewCached(capability: String, id: String, count: Int? = null, nbt: String? = null, renderVariant: String? = null, filters: Map<String, String> = emptyMap()): JsonObject? =
        previewCache[previewKey(capability, id, count, nbt, renderVariant, filters)]?.deepCopy()

    fun prefetchPreview(capability: String, id: String, count: Int? = null, nbt: String? = null, renderVariant: String? = null, filters: Map<String, String> = emptyMap()) {
        val key = previewKey(capability, id, count, nbt, renderVariant, filters)
        if (!isConnected || previewCache.containsKey(key)) return
        previewPrefetches.computeIfAbsent(key) {
            CompletableFuture.runAsync({ previewResolve(capability, id, count, nbt, renderVariant, filters) }, previewExecutor)
                .whenComplete { _, _ -> previewPrefetches.remove(key) }
        }
    }

    fun query(capability: String, prefix: String = "", limit: Int = 200, filters: Map<String, String> = emptyMap()): List<JsonObject> {
        val key = cacheKey(capability, filters, prefix)
        val cached = semanticCache[key] ?: semanticCache[cacheKey(capability, filters)]
        if (cached != null && !cached.stale && cached.nextCursor == null) {
            return GuideNhRuntimeSemanticMatcher.query(capability, cached.entries, prefix, limit).map { entry ->
                JsonObject().apply { addProperty("id", entry.id); entry.label?.let { addProperty("label", it) }; entry.detail?.let { addProperty("detail", it) } }
            }
        }
        val result = mutableListOf<JsonObject>()
        var cursor = ""
        var remaining = limit.coerceIn(1, GuideNhProtocolSchema.maxTotalEntries)
        do {
            val page = queryPage(capability, cursor, prefix, remaining.coerceAtMost(GuideNhProtocolSchema.maxPageSize), filters)
            result += page.entries
            remaining -= page.entries.size
            cursor = page.nextCursor.orEmpty()
        } while (cursor.isNotEmpty() && remaining > 0)
        return result.take(limit)
    }

    fun queryPage(capability: String, cursor: String = "", prefix: String = "", limit: Int = 200, filters: Map<String, String> = emptyMap()): RuntimePage {
        val payload = requestPayload("semantic.query", queryPayload(capability, cursor, prefix, limit, filters))
        val key = cacheKey(capability, filters, prefix)
        val page = parseRuntimePage(capability, payload, "semantic.query", key, cursor.isEmpty())
        cacheSemanticPayload(capability, key, page, cursor.isNotEmpty())
        return page
    }

    fun queryCached(capability: String, prefix: String = "", limit: Int = 200, filters: Map<String, String> = emptyMap()): List<JsonObject> {
        val cached = semanticCache[cacheKey(capability, filters, prefix)] ?: semanticCache[cacheKey(capability, filters)]
        return cached?.takeUnless { it.stale }?.entries
            ?.let { GuideNhRuntimeSemanticMatcher.query(capability, it, prefix, limit) }?.map { entry ->
                JsonObject().apply { addProperty("id", entry.id); entry.label?.let { addProperty("label", it) }; entry.detail?.let { addProperty("detail", it) } }
            }.orEmpty()
    }

    fun prefetch(capability: String, prefix: String = "", limit: Int = 200, filters: Map<String, String> = emptyMap()) {
        if (!isConnected || queryCached(capability, prefix, limit, filters).isNotEmpty()) return
        val key = "${cacheKey(capability, filters, prefix)}\u0000$prefix"
        prefetches.computeIfAbsent(key) {
            prefetchSemanticPages(capability, prefix, limit, filters)
                .whenComplete { _, _ -> prefetches.remove(key) }
        }
    }

    private fun prefetchSemanticPages(capability: String, prefix: String, limit: Int, filters: Map<String, String>): CompletableFuture<List<JsonObject>> {
        val key = cacheKey(capability, filters, prefix)
        fun fetch(cursor: String, remaining: Int, first: Boolean, result: MutableList<JsonObject>): CompletableFuture<List<JsonObject>> =
            requestPayloadAsync("semantic.query", queryPayload(capability, cursor, prefix, remaining.coerceAtMost(GuideNhProtocolSchema.maxPageSize), filters))
                .thenCompose { payload ->
                    val page = parseRuntimePage(capability, payload, "semantic.query", key, first)
                    cacheSemanticPayload(capability, key, page, !first)
                    result += page.entries
                    val next = page.nextCursor
                    if (next.isNullOrEmpty() || result.size >= limit) CompletableFuture.completedFuture(result.take(limit))
                    else fetch(next, limit - result.size, false, result)
                }
        return fetch("", limit.coerceIn(1, GuideNhProtocolSchema.maxTotalEntries), true, mutableListOf())
    }

    private fun requestPayload(method: String, payload: Any): JsonObject {
        val id = UUID.randomUUID().toString()
        val result = CompletableFuture<JsonObject>()
        pending[id] = { result.complete(it) }
        sendEnvelope(id, method, payload)
        return runCatching { result.get(500, TimeUnit.MILLISECONDS) }
            .onFailure { pending.remove(id) }
            .getOrDefault(JsonObject())
    }

    private fun requestPayloadAsync(method: String, payload: Any): CompletableFuture<JsonObject> {
        val id = UUID.randomUUID().toString()
        val result = CompletableFuture<JsonObject>()
        pending[id] = { result.complete(it) }
        sendEnvelope(id, method, payload)
        result.orTimeout(750, TimeUnit.MILLISECONDS).whenComplete { _, _ -> pending.remove(id) }
        return result
    }

    private fun send(method: String, payload: Any) { sendEnvelope(UUID.randomUUID().toString(), method, payload) }

    private fun sendEnvelope(id: String, method: String, payload: Any) {
        val current = socket ?: return
        val objectPayload = gson.toJsonTree(payload)
        val envelope = JsonObject().apply {
            addProperty("id", id); addProperty("type", "request"); addProperty("method", method); addProperty("protocol", 1); add("payload", objectPayload)
        }
        val text = gson.toJson(envelope)
        if (text.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) current.sendText(text, true)
        else log("Rejected oversized runtime request: $method")
    }

    private inner class Listener(private val token: String, private val generation: Long) : WebSocket.Listener {
        private val buffer = StringBuilder()
        private fun isCurrent(): Boolean = generation == connectionGeneration
        override fun onOpen(webSocket: WebSocket) {
            if (!isCurrent()) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "superseded")
                return
            }
            socket = webSocket; status = GuideNhRuntimeStatus("connecting")
            sendEnvelope("hello", "hello", mapOf("token" to token, "clientName" to "guide-idea", "supportedProtocols" to listOf(1)))
            webSocket.request(1)
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (!isCurrent()) {
                webSocket.request(1)
                return null
            }
            buffer.append(data)
            if (last) {
                val raw = buffer.toString()
                val parsed = if (raw.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull() else null
                buffer.setLength(0)
                if (parsed == null || !validateEnvelope(parsed)) {
                    status = GuideNhRuntimeStatus("error", "Runtime bridge sent an invalid or oversized envelope")
                    webSocket.request(1); return null
                }
                val id = parsed.get("id")?.asString
                val payload = parsed.getAsJsonObject("payload") ?: JsonObject()
                if (parsed.get("type")?.asString == "event") handleEvent(parsed.get("method")?.asString.orEmpty(), payload)
                if (parsed.get("type")?.asString == "response" && parsed.get("method")?.asString == "hello") {
                    reconnectAttempts = 0
                    status = GuideNhRuntimeStatus("connected")
                    log("Runtime hello acknowledged")
                    sendEnvelope("capabilities", "capabilities", emptyMap<String, String>())
                }
                if (parsed.get("type")?.asString == "response" && parsed.get("method")?.asString == "capabilities") {
                    handleCapabilities(payload)
                }
                if (id != null) pending.remove(id)?.invoke(payload)
            }
            webSocket.request(1); return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (!isCurrent()) return
            status = GuideNhRuntimeStatus("error", error.message)
            log("Runtime socket error: ${error.message}")
            scheduleReconnect()
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (!isCurrent()) return null
            socket = null
            log("Runtime socket closed: $reason")
            if (lastConnection != null && reconnectAttempts < 3) scheduleReconnect() else status = GuideNhRuntimeStatus("disconnected", reason)
            return null
        }
    }

    private fun scheduleReconnect() {
        val connection = lastConnection ?: return
        if (reconnectAttempts >= 3) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val attempt = ++reconnectAttempts
        reconnectExecutor.schedule({
            reconnectScheduled = false
            if (lastConnection != connection) return@schedule
            runCatching { openConnection(connection.host, connection.port, connection.token) }
                .onFailure { status = GuideNhRuntimeStatus("error", it.message) }
        }, attempt.toLong(), TimeUnit.SECONDS)
    }

    private data class RuntimeConnection(val host: String, val port: Int, val token: String, val allowRemote: Boolean)

    private fun cacheSemanticPayload(capability: String, key: String, page: RuntimePage, append: Boolean) {
        val entries = page.entries.mapNotNull { value ->
            val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.get("id")?.asString ?: return@mapNotNull null
            GuideNhRuntimeEntry(id, obj.get("label")?.asString, obj.get("detail")?.asString)
        }
        val previous = semanticCache[key]
        val source = if (append && previous != null) previous.entries + entries else entries
        val merged = source.asSequence()
            .distinctBy { it.id.lowercase() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.id })
            .take(GuideNhProtocolSchema.maxTotalEntries)
            .toList()
        semanticCache[key] = GuideNhRuntimeCache(page.version, merged, false, page.nextCursor)
        ProjectManager.getInstance().openProjects.forEach { project ->
            GuideNhRuntimeSemanticIndex.get(project).record(capability, entries)
        }
    }

    private fun handleEvent(method: String, payload: JsonObject) {
        when (method) {
            "capabilities" -> handleCapabilities(payload)
            "semantic.invalidate", "semantic.stale" -> payload.get("capability")?.asString?.let { capability ->
                semanticCache.filterKeys { it == capability || it.startsWith("$capability\u0000") }
                    .forEach { (key, value) -> semanticCache[key] = value.copy(stale = true) }
            }
            "log" -> payload.get("message")?.asString?.let(::log)
        }
    }

    private fun handleCapabilities(payload: JsonObject) {
        supportedCapabilities.clear()
        payload.getAsJsonArray("capabilities")?.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString }
            ?.filter { it in GuideNhProtocolSchema.capabilities }
            ?.let(supportedCapabilities::addAll)
        log("Runtime capabilities: ${supportedCapabilities.sorted().joinToString(", ")}")
        PREFERRED_WARMUP_CAPABILITIES.filter { it in supportedCapabilities }.forEach { capability ->
            prefetch(capability, limit = MAX_WARMUP_ENTRIES)
        }
    }

    private fun log(message: String) {
        logs.addLast("[${java.time.LocalTime.now().withNano(0)}] $message")
        while (logs.size > MAX_LOG_ENTRIES) logs.pollFirst()
    }

    private fun handleValidation(payload: JsonObject, requestId: String) {
        val uri = validationRequests.remove(requestId) ?: payload.get("uri")?.asString ?: return
        val diagnostics = payload.getAsJsonArray("diagnostics")?.take(MAX_DIAGNOSTICS)?.mapNotNull { value ->
            val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val message = obj.get("message")?.asString ?: return@mapNotNull null
            val start = obj.get("start")?.asInt ?: 0
            val end = obj.get("end")?.asInt ?: start
            if (message.length > MAX_DIAGNOSTIC_TEXT || start < 0 || end < start || end - start > MAX_DIAGNOSTIC_RANGE) return@mapNotNull null
            GuideNhRuntimeDiagnostic(
                message = message,
                severity = obj.get("severity")?.asString ?: "error",
                start = start,
                end = end
            )
        }.orEmpty()
        validationDiagnostics[uri] = diagnostics
        val file = runCatching { VirtualFileManager.getInstance().findFileByUrl(uri) }.getOrNull() ?: return
        ProjectManager.getInstance().openProjects.filter { project -> file.isValid && ProjectFileIndex.getInstance(project).isInContent(file) }
            .forEach { project -> DaemonCodeAnalyzer.getInstance(project).restart(file) }
    }

    private fun validateEnvelope(value: JsonObject): Boolean {
        val protocol = value.get("protocol")?.asInt ?: return false
        val type = value.get("type")?.asString ?: return false
        val method = value.get("method")?.asString ?: return false
        if (protocol != GuideNhProtocolSchema.protocolVersion || type !in setOf("request", "response", "event", "error") || method.length !in 1..128) return false
        val id = value.get("id")?.asString
        return (id == null || id.length in 1..128) && value.has("payload")
    }

    private fun queryPayload(capability: String, cursor: String, prefix: String, limit: Int, filters: Map<String, String>): Map<String, Any> = mapOf(
        "capability" to capability,
        "cursor" to cursor.take(GuideNhProtocolSchema.maxCursorLength),
        "limit" to limit.coerceIn(1, GuideNhProtocolSchema.maxPageSize),
        "prefix" to prefix.take(GuideNhProtocolSchema.maxEntryTextLength),
        "filters" to filters.filterKeys { it.length <= GuideNhProtocolSchema.maxEntryTextLength }
            .mapValues { (_, value) -> value.take(GuideNhProtocolSchema.maxEntryTextLength) }
    )

    private fun cacheKey(capability: String, filters: Map<String, String>, prefix: String = ""): String =
        capability + "\u0000" + prefix + "\u0000" + filters.toSortedMap().entries.joinToString("\u0001") { "${it.key}=${it.value}" }

    private fun parseRuntimePage(capability: String, payload: JsonObject, method: String, guardKey: String, resetState: Boolean): RuntimePage {
        val actualCapability = payload.get("capability")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalArgumentException("Runtime $method payload is missing capability")
        if (actualCapability != capability || !isAllowedCapability(actualCapability)) throw IllegalArgumentException("Runtime $method payload has unsupported capability")
        val version = payload.get("version")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: throw IllegalArgumentException("Runtime $method payload has invalid version")
        if (version < 0) throw IllegalArgumentException("Runtime $method payload has invalid version")
        val array = payload.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException("Runtime $method payload has invalid entries")
        if (array.size() > GuideNhProtocolSchema.maxPageSize) throw IllegalArgumentException("Runtime $method payload page is too large")
        val state = if (resetState) {
            SemanticGuardState().also { semanticGuardStates[guardKey] = it }
        } else {
            semanticGuardStates.computeIfAbsent(guardKey) { SemanticGuardState() }
        }
        if (state.totalEntries + array.size() > GuideNhProtocolSchema.maxTotalEntries) throw IllegalArgumentException("Runtime $method payload entry total is too large")
        val ids = HashSet<String>()
        val entries = array.map { value ->
            val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: throw IllegalArgumentException("Runtime $method entry is not an object")
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() && it.length <= GuideNhProtocolSchema.maxEntryTextLength }
                ?: throw IllegalArgumentException("Runtime $method entry id is invalid")
            if (!ids.add(id)) throw IllegalArgumentException("Runtime $method payload contains duplicate entry id")
            val label = boundedOptionalText(obj, "label")
            val detail = boundedOptionalText(obj, "detail")
            JsonObject().apply { addProperty("id", id); label?.let { addProperty("label", it) }; detail?.let { addProperty("detail", it) } }
        }
        val nextCursorElement = payload.get("nextCursor")
        val nextCursor = when {
            nextCursorElement == null || nextCursorElement.isJsonNull -> ""
            nextCursorElement.isJsonPrimitive && nextCursorElement.asJsonPrimitive.isString -> nextCursorElement.asString
            else -> throw IllegalArgumentException("Runtime $method payload cursor is invalid")
        }
        if (nextCursor.length > GuideNhProtocolSchema.maxCursorLength || (nextCursor.isNotEmpty() && !state.seenCursors.add(nextCursor)))
            throw IllegalArgumentException("Runtime $method payload cursor is invalid or repeated")
        state.totalEntries += entries.size
        return RuntimePage(version, entries, nextCursor.ifEmpty { null })
    }

    private fun boundedOptionalText(obj: JsonObject, key: String): String? {
        val value = obj.get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw IllegalArgumentException("Runtime entry $key is invalid")
        return value.asString.takeIf { it.length <= GuideNhProtocolSchema.maxEntryTextLength }
            ?: throw IllegalArgumentException("Runtime entry $key is too long")
    }

    private fun validatePreviewResolve(capability: String, requestedId: String, payload: JsonObject): JsonObject? {
        if (payload.entrySet().isEmpty()) return null
        val actualCapability = payload.get("capability")?.takeIf { it.isJsonPrimitive }?.asString
        val actualId = payload.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        if (actualCapability != capability || !isAllowedCapability(capability) || !previewIdsMatch(requestedId, actualId)) return null
        val lines = payload.get("tooltipLines")
        if (lines != null) {
            if (!lines.isJsonArray || lines.asJsonArray.size() > MAX_TOOLTIP_LINES || lines.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.length > MAX_DIAGNOSTIC_TEXT }) return null
        }
        val width = payload.get("pixelWidth")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val height = payload.get("pixelHeight")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (width !in 0..MAX_PREVIEW_DIMENSION || height !in 0..MAX_PREVIEW_DIMENSION) return null
        val icon = payload.get("iconPngBase64")?.takeIf { it.isJsonPrimitive }?.asString
        if (icon != null && icon.length > MAX_ICON_BASE64_LENGTH) return null
        return payload
    }

    private fun previewIdsMatch(requestedId: String, actualId: String?): Boolean {
        if (actualId == requestedId) return true
        val metaSuffix = Regex("^(.+):(-?\\d+)$").matchEntire(requestedId) ?: return false
        return actualId == metaSuffix.groupValues[1]
    }

    private fun previewKey(capability: String, id: String, count: Int?, nbt: String?, renderVariant: String?, filters: Map<String, String>): String =
        "$capability\u0000$id\u0000${count ?: 1}\u0000${nbt.orEmpty()}\u0000${renderVariant.orEmpty()}\u0000${filters.toSortedMap()}"

    private fun cachePreview(key: String, value: JsonObject) {
        previewCache[key] = value.deepCopy()
        previewCacheOrder.remove(key)
        previewCacheOrder.addLast(key)
        while (previewCacheOrder.size > MAX_CACHED_PREVIEWS) previewCacheOrder.pollFirst()?.let(previewCache::remove)
    }

    private fun isAllowedCapability(capability: String): Boolean =
        capability in GuideNhProtocolSchema.capabilities && (supportedCapabilities.isEmpty() || capability in supportedCapabilities || capability in DYNAMIC_CAPABILITIES)

    private data class SemanticGuardState(val seenCursors: MutableSet<String> = HashSet(), var totalEntries: Int = 0)
    data class RuntimePage(val version: Long, val entries: List<JsonObject>, val nextCursor: String?)

    companion object {
        private val MAX_MESSAGE_BYTES = GuideNhProtocolSchema.maxMessageBytes
        private const val MAX_LOG_ENTRIES = 200
        private const val MAX_DIAGNOSTICS = 500
        private const val MAX_DIAGNOSTIC_TEXT = 2048
        private const val MAX_DIAGNOSTIC_RANGE = 1_000_000
        private const val MAX_TOOLTIP_LINES = 32
        private const val MAX_PREVIEW_DIMENSION = 512
        private const val MAX_ICON_BASE64_LENGTH = 2_000_000
        private const val MAX_CACHED_PREVIEWS = 256
        private const val MAX_WARMUP_ENTRIES = 2_000
        private val PREFERRED_WARMUP_CAPABILITIES = setOf("commands", "sounds", "keybinds", "recipes", "quests", "entities", "structurelib", "pages")
        private val DYNAMIC_CAPABILITIES = setOf("items", "ores", "categories", "mods")
        private val previewExecutor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "guidenh-runtime-preview-prefetch").apply { isDaemon = true } }
        fun get(): GuideNhRuntimeBridgeService = com.intellij.openapi.application.ApplicationManager.getApplication().getService(GuideNhRuntimeBridgeService::class.java)
    }
}

package com.hfstudio.guidenh

import com.google.gson.Gson
import com.google.gson.JsonObject

object GuideNhProtocolSchema {
    private val root: JsonObject by lazy {
        GuideNhProtocolSchema::class.java.getResourceAsStream("/guidenh/schema/protocol.json")?.use { Gson().fromJson(it.reader(), JsonObject::class.java) } ?: JsonObject()
    }
    val protocolVersion: Int get() = root.get("protocolVersion")?.asInt ?: 1
    val maxMessageBytes: Int get() = root.getAsJsonObject("limits")?.get("maxMessageBytes")?.asInt ?: 262144
    val maxPageSize: Int get() = root.getAsJsonObject("limits")?.get("maxPageSize")?.asInt ?: 200
    val maxTotalEntries: Int get() = root.getAsJsonObject("limits")?.get("maxTotalEntries")?.asInt ?: 50000
    val maxEntryTextLength: Int get() = root.getAsJsonObject("limits")?.get("maxEntryTextLength")?.asInt ?: 512
    val maxCursorLength: Int get() = root.getAsJsonObject("limits")?.get("maxCursorLength")?.asInt ?: 128
    val capabilities: Set<String> by lazy { root.getAsJsonArray("capabilities")?.map { it.asString }?.toSet().orEmpty() }
}

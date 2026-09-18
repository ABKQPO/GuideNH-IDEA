package com.hfstudio.guidenh

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class GuideNhPage(
    val file: VirtualFile,
    val pageId: String,
    val namespace: String?,
    val locale: String?,
    val relativePath: String,
    val itemIds: Set<String> = emptySet(),
    val oreIds: Set<String> = emptySet(),
    val semanticValues: Map<String, Set<String>> = emptyMap()
)
data class GuideNhResource(val file: VirtualFile, val resourceId: String, val namespace: String?, val relativePath: String)

@Service(Service.Level.PROJECT)
class GuideNhWorkspaceIndex(private val project: Project) {
    private val pages = ConcurrentHashMap<String, GuideNhPage>()
    private val resources = ConcurrentHashMap<String, GuideNhResource>()
    private val pagesByItem = ConcurrentHashMap<String, MutableSet<VirtualFile>>()
    private val pagesByOre = ConcurrentHashMap<String, MutableSet<VirtualFile>>()
    private val pagesByRelative = ConcurrentHashMap<String, MutableSet<VirtualFile>>()
    private val semanticValues = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var scanned = false

    init {
        project.messageBus.connect().subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                events.forEach { event -> event.file?.let { refresh(it) } }
            }
        })
        project.messageBus.connect().subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun fileContentLoaded(file: VirtualFile, document: com.intellij.openapi.editor.Document) { refresh(file) }
            override fun fileContentReloaded(file: VirtualFile, document: com.intellij.openapi.editor.Document) { refresh(file) }
            override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                FileDocumentManager.getInstance().getFile(document)?.let { refresh(it) }
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                FileDocumentManager.getInstance().getFile(event.document)?.let { file ->
                    if (GuideNhFileUtil.isGuideNhDocument(file)) refresh(file)
                }
            }
        }, project)
    }

    fun ensureScanned() {
        if (scanned) return
        synchronized(this) {
            if (scanned) return
            val root = project.baseDir ?: return
            VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                if (!file.isDirectory) {
                    when {
                        GuideNhFileUtil.isGuideNhDocument(file) -> index(file)
                        GuideNhFileUtil.isGuideNhResource(file) -> indexResource(file)
                    }
                }
                true
            }
            scanned = true
        }
    }

    fun refresh(file: VirtualFile) {
        remove(file)
        if (!file.isValid || file.isDirectory) return
        when {
            GuideNhFileUtil.isGuideNhDocument(file) -> index(file)
            GuideNhFileUtil.isGuideNhResource(file) -> indexResource(file)
        }
    }

    fun findPage(reference: String, from: VirtualFile): GuideNhPage? {
        ensureScanned()
        val normalized = normalizeReference(reference, from)
        val namespace = normalized.substringBefore(':', "").takeIf { normalized.contains(':') }
        val relative = normalized.substringAfter(':', normalized)
        return selectPage(
            pages.values.filter { page ->
                page.relativePath.equals(relative, true) && (namespace == null || page.namespace.equals(namespace, true))
            },
            from
        )
    }

    fun queryPages(prefix: String, from: VirtualFile): List<GuideNhPage> {
        ensureScanned()
        val normalized = normalizeReference(prefix, from)
        val namespace = normalized.substringBefore(':', "").takeIf { normalized.contains(':') }
        val relative = normalized.substringAfter(':', normalized)
        return pages.values.distinctBy { it.file }.filter { page ->
            (namespace == null || page.namespace.equals(namespace, true)) &&
                (page.relativePath.startsWith(relative, true) || page.pageId.startsWith(normalized, true))
        }
            .sortedWith(compareBy<GuideNhPage> { it.relativePath }.thenComparator { left, right -> localeRank(left, from).compareTo(localeRank(right, from)) }).take(200)
    }

    /**
     * The named arguments a template declares, read from its body. GuideNH resolves these from the template
     * page, so the editor reads the same file the mod indexes under `templates/`.
     */
    fun templateParameterNames(templateName: String, from: VirtualFile): List<String> {
        val file = findTemplate(templateName, from)?.file ?: return emptyList()
        val text = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (ignored: IOException) {
            return emptyList()
        }
        return extractTemplateParameterNames(text)
    }

    /** Finds a template using GuideNH's MediaWiki-compatible name rules, including nested templates. */
    fun findTemplate(templateName: String, from: VirtualFile): GuideNhPage? {
        ensureScanned()
        val normalizedName = normalizeGuideNhTemplateName(templateName)
        if (normalizedName.isEmpty()) return null
        val sourceNamespace = Regex("/assets/([^/]+)/guidenh/", RegexOption.IGNORE_CASE)
            .find(from.path.replace('\\', '/'))?.groupValues?.get(1)
        val candidates = pages.values.filter { page ->
            val pageTemplateName = guideNhTemplateName(page.relativePath)
            pageTemplateName != null &&
                normalizeGuideNhTemplateName(pageTemplateName) == normalizedName &&
                (sourceNamespace == null || page.namespace.equals(sourceNamespace, true))
        }
        return selectPage(candidates, from)
    }

    fun queryResources(prefix: String, from: VirtualFile): List<GuideNhResource> {        ensureScanned()
        val normalized = normalizeResourceReference(prefix, from)
        val namespace = normalized.substringBefore(':', "").takeIf { normalized.contains(':') }
        val relative = normalized.substringAfter(':', normalized)
        return resources.values.distinctBy { it.resourceId }
            .filter { resource ->
                (namespace == null || resource.namespace.equals(namespace, true)) &&
                    (resource.relativePath.startsWith(relative, true) || resource.resourceId.startsWith(normalized, true))
            }
            .sortedBy { it.relativePath }
            .take(200)
    }

    fun allPages(): List<GuideNhPage> { ensureScanned(); return pages.values.sortedBy { it.relativePath } }
    fun findItemPages(itemId: String): List<GuideNhPage> = findSemanticPages(pagesByItem, itemId)
    fun findOrePages(oreId: String): List<GuideNhPage> = findSemanticPages(pagesByOre, oreId)
    fun querySemanticValues(path: String, prefix: String): List<String> {
        ensureScanned()
        return semanticValues[path].orEmpty().filter { it.startsWith(prefix, true) }.sorted().take(200)
    }
    fun findResource(reference: String, from: VirtualFile): GuideNhResource? {
        ensureScanned()
        val normalized = normalizeResourceReference(reference, from)
        return resources[normalized] ?: resources.values.firstOrNull { it.resourceId.equals(normalized, true) || it.relativePath.equals(normalized, true) }
    }

    private fun index(file: VirtualFile) {
        val match = Regex("/assets/([^/]+)/guidenh/(?:guidenh/)?_([^/]+)/(.+)$", RegexOption.IGNORE_CASE).find(file.path.replace('\\', '/')) ?: return
        val namespace = match.groupValues[1]
        val locale = match.groupValues[2].lowercase()
        val relative = match.groupValues[3]
        // A document may be referenced before it is saved. Use its in-memory state so
        // completion, definitions and Find Usages agree with the active editor.
        val frontmatter = FileDocumentManager.getInstance().getDocument(file)?.text
            ?: runCatching { file.inputStream.use { it.reader().readText() } }.getOrDefault("")
        val itemIds = extractFrontmatterValues(frontmatter, "item_id", "item_ids")
        val oreIds = extractFrontmatterValues(frontmatter, "ore_ids")
        val indexedValues = mapOf(
            "item_id" to itemIds,
            "item_ids" to itemIds,
            "ore_ids" to oreIds,
            "quest_ids" to extractFrontmatterValues(frontmatter, "quest_ids"),
            "categories" to extractFrontmatterValues(frontmatter, "categories"),
            "navigation.required_mod" to extractFrontmatterValues(frontmatter, "required_mod"),
            "navigation.required_mods" to extractFrontmatterValues(frontmatter, "required_mods"),
            "navigation.excluded_mod" to extractFrontmatterValues(frontmatter, "excluded_mod"),
            "navigation.excluded_mods" to extractFrontmatterValues(frontmatter, "excluded_mods"),
            "navigation.icon" to extractFrontmatterValues(frontmatter, "icon"),
            "navigation.icons" to extractFrontmatterValues(frontmatter, "icons")
        ).filterValues { it.isNotEmpty() }
        val page = GuideNhPage(file, "$namespace:$relative", namespace, locale, relative, itemIds, oreIds, indexedValues)
        pages[file.url] = page
        pagesByRelative.computeIfAbsent(relative) { ConcurrentHashMap.newKeySet() }.add(file)
        itemIds.forEach { pagesByItem.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(file) }
        oreIds.forEach { pagesByOre.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(file) }
        indexedValues.forEach { (key, values) -> semanticValues.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.addAll(values) }
    }

    private fun remove(file: VirtualFile) {
        val removed = pages.values.filter { it.file == file }.distinctBy { it.file }
        pages.entries.removeIf { it.value.file == file }
        removed.forEach { page ->
            pagesByRelative[page.relativePath]?.also { it.remove(file); if (it.isEmpty()) pagesByRelative.remove(page.relativePath) }
            page.itemIds.forEach { id -> pagesByItem[id]?.also { it.remove(file); if (it.isEmpty()) pagesByItem.remove(id) } }
            page.oreIds.forEach { id -> pagesByOre[id]?.also { it.remove(file); if (it.isEmpty()) pagesByOre.remove(id) } }
            page.semanticValues.forEach { (key, values) ->
                semanticValues[key]?.also { candidates ->
                    values.filterNot { value -> pages.values.any { other -> value in other.semanticValues[key].orEmpty() } }
                        .forEach(candidates::remove)
                    if (candidates.isEmpty()) semanticValues.remove(key)
                }
            }
        }
        resources.entries.removeIf { it.value.file == file }
    }

    private fun findSemanticPages(index: Map<String, Set<VirtualFile>>, value: String): List<GuideNhPage> {
        ensureScanned()
        return index[value]?.mapNotNull { file -> pages.values.firstOrNull { it.file == file } }?.distinctBy { it.file }?.sortedBy { it.relativePath } ?: emptyList()
    }

    private fun selectPage(candidates: Collection<GuideNhPage>, from: VirtualFile): GuideNhPage? =
        candidates.minWithOrNull(compareBy<GuideNhPage> { localeRank(it, from) }.thenBy { it.locale ?: "zzzz" })

    private fun localeRank(page: GuideNhPage, from: VirtualFile): Int {
        val currentLocale = Regex("/guidenh/(?:guidenh/)?_([^/]+)/", RegexOption.IGNORE_CASE).find(from.path.replace('\\', '/'))?.groupValues?.get(1)?.lowercase()
        val configuredLocale = GuideNhSettings.get().state.locale.orEmpty().lowercase().takeIf(String::isNotBlank)
        val priority = listOfNotNull(currentLocale, configuredLocale, "en_us").distinct()
        val index = priority.indexOfFirst { it.equals(page.locale, true) }
        return if (index >= 0) index else priority.size + 1
    }

    private fun extractFrontmatterValues(text: String, vararg keys: String): Set<String> {
        val result = linkedSetOf<String>()
        var active = false
        val wanted = keys.toSet()
        for (line in text.substringBefore("\n---").lineSequence()) {
            val key = Regex("^\\s*([A-Za-z_][\\w.-]*)\\s*:\\s*(.*)$").find(line)
            if (key != null) {
                active = key.groupValues[1] in wanted
                val raw = key.groupValues[2].trim().removeSurrounding("[", "]")
                if (active) raw.split(',').map { normalizeFrontmatterScalar(it) }.filter { it.isNotEmpty() }.forEach(result::add)
                continue
            }
            if (active) {
                val item = Regex("^\\s*-\\s+(.+)$").find(line)?.groupValues?.get(1)
                if (item != null) normalizeFrontmatterScalar(item).takeIf { it.isNotEmpty() }?.let(result::add)
            }
        }
        return result
    }

    private fun normalizeFrontmatterScalar(value: String): String = value.trim().removeSurrounding("\"").removeSurrounding("'")
    private fun indexResource(file: VirtualFile) {
        val match = Regex("/assets/([^/]+)/guidenh/(?:guidenh/)?assets/(.+)$", RegexOption.IGNORE_CASE).find(file.path.replace('\\', '/')) ?: return
        val namespace = match.groupValues[1]
        val relative = "assets/${match.groupValues[2]}"
        val resource = GuideNhResource(file, "$namespace:$relative", namespace, relative)
        resources[resource.resourceId] = resource
        resources[relative] = resource
    }
    private fun normalizeReference(reference: String, from: VirtualFile): String {
        val value = unwrapLegacyMarkdownDestination(reference).substringBefore('#').trim()
        val explicit = Regex("^([A-Za-z0-9_.-]+):(.+)$").find(value)
        if (explicit != null) return "${explicit.groupValues[1]}:${normalizeGuidePath(explicit.groupValues[2])}"
        if (value.startsWith("/")) return normalizeGuidePath(value)
        val base = from.parent?.path?.replace('\\', '/') ?: ""
        val absolute = java.nio.file.Paths.get(base, value).normalize().toString().replace('\\', '/')
        return guideRelativePath(absolute) ?: value
    }

    private fun normalizeResourceReference(reference: String, from: VirtualFile): String {
        val value = unwrapLegacyMarkdownDestination(reference).substringBefore('#').trim()
        val explicit = Regex("^([A-Za-z0-9_.-]+):(.+)$").find(value)
        if (explicit != null) return "${explicit.groupValues[1]}:${normalizeGuidePath(explicit.groupValues[2])}"
        if (value.startsWith("/assets/")) return normalizeGuidePath(value.removePrefix("/"))
        if (value.startsWith("assets/")) return normalizeGuidePath(value)
        val base = from.parent?.path?.replace('\\', '/') ?: ""
        val absolute = java.nio.file.Paths.get(base, value).normalize().toString().replace('\\', '/')
        return guideRelativePath(absolute) ?: value
    }

    /** Converts a physical GuideNH path into its logical page/resource path. */
    private fun guideRelativePath(absolute: String): String? {
        val marker = absolute.indexOf("/guidenh/", ignoreCase = true)
        if (marker < 0) return null
        var relative = absolute.substring(marker + "/guidenh/".length).removePrefix("guidenh/")
        val locale = Regex("^_[^/]+(?:/(.*))?$").matchEntire(relative)
        if (locale != null) relative = locale.groupValues[1]
        return relative
    }

    private fun normalizeGuidePath(value: String): String {
        val segments = mutableListOf<String>()
        value.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    /**
     * Old GuideNH documents used `(*relative/path*)` as a Markdown destination.
     * Markdown exposes that destination as `*relative/path*`; accept it without
     * treating the asterisks as part of a file name.
     */
    private fun unwrapLegacyMarkdownDestination(reference: String): String {
        var value = reference.trim()
        if (value.startsWith("(*") && value.endsWith("*)") && value.length > 4) {
            value = value.substring(2, value.length - 2).trim()
        }
        if (value.startsWith('*') && value.endsWith('*') && value.length > 2) {
            value = value.substring(1, value.length - 1).trim()
        }
        return value
    }

    companion object { fun get(project: Project): GuideNhWorkspaceIndex = project.getService(GuideNhWorkspaceIndex::class.java) }
}

/** Returns the logical name of a page below the reserved templates/ prefix. */
private fun guideNhTemplateName(relativePath: String): String? {
    val normalized = relativePath.replace('\\', '/')
    val marker = normalized.lastIndexOf("templates/", ignoreCase = true)
    if (marker < 0 || !normalized.endsWith(".md", true)) return null
    val name = normalized.substring(marker + "templates/".length, normalized.length - ".md".length)
    return name.takeIf { it.isNotEmpty() }
}

/** Mirrors MediaWikiTemplateName.normalize in GuideNH-NH. */
private fun normalizeGuideNhTemplateName(value: String): String {
    val normalized = value.replace('_', ' ').trim().replace(Regex("\\s+"), " ")
    return normalized.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }.orEmpty()
}

/**
 * Every parameter name a template body declares. Both `<Param name="x" />` and the attribute-value form
 * `id={<Param name="x" />}` match, because the pattern only looks for the tag and its name attribute. A
 * positional `<Param pos="1" />` has no name, so it contributes nothing a name completion could offer.
 */
internal fun extractTemplateParameterNames(text: String): List<String> {
    val pattern = Regex("""<Param\b[^>]*?\bname\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    val names = linkedSetOf<String>()
    for (match in pattern.findAll(text)) {
        val name = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
        if (name.isNotEmpty()) names += name
    }
    return names.toList()
}

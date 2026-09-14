package com.hfstudio.guidenh

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.fs.EelFiles

@Service(Service.Level.PROJECT)
class GuideNhSchemaService(private val project: Project) {
    @Volatile private var tags: Map<String, GuideNhTagSchema> = loadTags()
    private val frontmatter: Map<String, GuideNhFrontmatterKey> by lazy { loadFrontmatter() }
    private val snippets: List<GuideNhSnippet> by lazy { loadSnippets() }
    private val fencedBlocks: Map<String, String> by lazy { loadFencedBlocks() }
    private val inlineMarkers: Map<String, Pair<String, String>> by lazy { loadInlineMarkers() }
    private val generatedOverlay: JsonObject by lazy { loadOverlay() }

    fun tag(name: String): GuideNhTagSchema? = tags[name.lowercase()]
    fun allTags(): Collection<GuideNhTagSchema> = tags.values
    fun attribute(tag: String, name: String): GuideNhAttributeSchema? = tag(tag)?.attributes?.entries
        ?.firstOrNull { it.key.equals(name, true) }?.value
    fun frontmatterKey(path: List<String>, name: String): GuideNhFrontmatterKey? {
        var current = frontmatter
        for (part in path) current = current.entries.firstOrNull { it.key.equals(part, true) }?.value?.children ?: return null
        return current.entries.firstOrNull { it.key.equals(name, true) }?.value
    }
    fun frontmatterKeys(path: List<String>): Collection<String> {
        var current = frontmatter
        for (part in path) current = current.entries.firstOrNull { it.key.equals(part, true) }?.value?.children ?: return emptyList()
        return current.keys
    }
    fun snippets(): List<GuideNhSnippet> = snippets
    fun fencedBlocks(): Map<String, String> = fencedBlocks
    fun inlineMarkers(): Map<String, Pair<String, String>> = inlineMarkers
    fun reload() { tags = loadTags() }

    private fun loadTags(): Map<String, GuideNhTagSchema> {
        val stream = javaClass.getResourceAsStream("/guidenh/schema/tags.json") ?: return emptyMap()
        stream.use {
            val root = Gson().fromJson(it.reader(), JsonObject::class.java)
            val result = linkedMapOf<String, GuideNhTagSchema>()
            root.getAsJsonObject("tags")?.entrySet()?.forEach { (key, value) ->
                val objectValue = value.asJsonObject
                val attributes = linkedMapOf<String, GuideNhAttributeSchema>()
                objectValue.getAsJsonObject("attributes")?.entrySet()?.forEach { (attributeName, attributeValue) ->
                    val a = attributeValue.asJsonObject
                    attributes[attributeName] = GuideNhAttributeSchema(
                        type = a.get("type")?.asString ?: "string",
                        description = a.get("description")?.asString,
                        valueStyle = a.get("valueStyle")?.asString,
                        values = (a.getAsJsonArray("values") ?: a.getAsJsonArray("enum"))?.map { it.asString } ?: emptyList(),
                        required = a.get("required")?.asBoolean ?: false,
                        requiredWhenMissing = a.getAsJsonArray("requiredWhenMissing")?.map { it.asString } ?: emptyList()
                    )
                }
                result[key.lowercase()] = GuideNhTagSchema(
                    name = objectValue.get("name")?.asString ?: key,
                    description = objectValue.get("description")?.asString,
                    attributes = attributes,
                    children = objectValue.getAsJsonArray("children")?.map { it.asString } ?: emptyList()
                )
            }
            loadGeneratedTags().forEach { (key, generated) ->
                val existing = result[key]
                result[key] = if (existing == null) generated else existing.copy(
                    attributes = (existing.attributes.filterKeys { it.lowercase() !in generated.removedAttributes } + generated.attributes.filterKeys { generatedName ->
                        existing.attributes.keys.none { it.equals(generatedName, true) }
                    }).filterKeys { it.lowercase() !in generated.removedAttributes },
                    children = (existing.children + generated.children).distinct().sorted(),
                    removedAttributes = existing.removedAttributes + generated.removedAttributes
                )
            }
            return result
        }
    }

    private fun loadGeneratedTags(): Map<String, GuideNhTagSchema> {
        val base = project.basePath ?: return emptyMap()
        val file = java.nio.file.Path.of(base, ".idea", "guidenh", "schema", "generated-tags.json")
        if (!java.nio.file.Files.isRegularFile(file)) return emptyMap()
        return runCatching {
            val root = Gson().fromJson(EelFiles.readString(file), JsonObject::class.java)
            root.getAsJsonObject("tags")?.entrySet()?.associate { (key, value) ->
                val tag = value.asJsonObject
                val removed = tag.getAsJsonArray("removeAttributes")?.map { it.asString.lowercase() }.orEmpty().toSet()
                val attributes = tag.getAsJsonObject("attributes")?.entrySet()?.associate { (attributeName, attributeValue) ->
                    val attribute = attributeValue.asJsonObject
                    attributeName to GuideNhAttributeSchema(
                        type = attribute.get("type")?.asString ?: "string",
                        description = attribute.get("description")?.asString,
                        valueStyle = attribute.get("valueStyle")?.asString ?: "string",
                        values = (attribute.getAsJsonArray("values") ?: attribute.getAsJsonArray("enum"))?.map { it.asString } ?: emptyList(),
                        required = attribute.get("required")?.asBoolean ?: false,
                        requiredWhenMissing = attribute.getAsJsonArray("requiredWhenMissing")?.map { it.asString } ?: emptyList()
                    )
                }.orEmpty()
                key.lowercase() to GuideNhTagSchema(
                    name = tag.get("name")?.asString ?: key,
                    description = tag.get("description")?.asString,
                    attributes = attributes.filterKeys { it.lowercase() !in removed },
                    children = tag.getAsJsonArray("children")?.map { it.asString } ?: emptyList(),
                    removedAttributes = removed
                )
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun loadFrontmatter(): Map<String, GuideNhFrontmatterKey> {
        val stream = javaClass.getResourceAsStream("/guidenh/schema/frontmatter.json") ?: return emptyMap()
        stream.use {
            val root = Gson().fromJson(it.reader(), JsonObject::class.java).getAsJsonObject("keys") ?: return emptyMap()
            fun parseObject(value: JsonObject): GuideNhFrontmatterKey {
                val children = value.getAsJsonObject("children")?.entrySet()?.associate { (key, child) -> key to parseObject(child.asJsonObject) } ?: emptyMap()
                return GuideNhFrontmatterKey(value.get("type")?.asString ?: "string", value.get("description")?.asString, children)
            }
            return root.entrySet().associate { (key, value) -> key to parseObject(value.asJsonObject) }
        }
    }

    private fun loadSnippets(): List<GuideNhSnippet> {
        val merged = LinkedHashMap(shippedSnippets())
        overlaySnippets().forEach { (key, snippet) -> merged.putIfAbsent(key, snippet) }
        return merged.values.toList()
    }

    private fun shippedSnippets(): Map<String, GuideNhSnippet> {
        val stream = javaClass.getResourceAsStream("/guidenh/schema/snippets.json") ?: return emptyMap()
        stream.use {
            val root = Gson().fromJson(it.reader(), JsonObject::class.java).getAsJsonObject("snippets") ?: return emptyMap()
            return root.entrySet().associate { (key, value) ->
                val item = value.asJsonObject
                key to GuideNhSnippet(
                    item.get("prefix")?.asString ?: "",
                    item.getAsJsonArray("body")?.map { body -> body.asString } ?: emptyList(),
                    item.get("description")?.asString
                )
            }
        }
    }

    /** The insert templates the workspace's Java sources declare, written next to the generated tags. */
    private fun overlaySnippets(): Map<String, GuideNhSnippet> =
        overlaySection("snippets").entrySet().associate { (key, value) ->
            val item = value.asJsonObject
            key to GuideNhSnippet(
                item.get("prefix")?.asString ?: "",
                item.getAsJsonArray("body")?.map { body -> body.asString } ?: emptyList(),
                item.get("description")?.asString
            )
        }

    private fun loadFencedBlocks(): Map<String, String> =
        loadMarkdownSection("fencedCodeBlocks") + overlayDescriptions("fencedCodeBlocks")

    private fun loadInlineMarkers(): Map<String, Pair<String, String>> {
        val merged = LinkedHashMap(shippedInlineMarkers())
        overlaySection("markdownExtensions").getAsJsonObject("inlineMarkers")?.entrySet()?.forEach { (key, value) ->
            val marker = value.asJsonObject
            merged[key] = marker.get("open")?.asString.orEmpty() to marker.get("close")?.asString.orEmpty()
        }
        return merged
    }

    private fun shippedInlineMarkers(): Map<String, Pair<String, String>> {
        val stream = javaClass.getResourceAsStream("/guidenh/schema/markdownExtensions.json") ?: return emptyMap()
        stream.use {
            val root = Gson().fromJson(it.reader(), JsonObject::class.java).getAsJsonObject("inlineMarkers") ?: return emptyMap()
            return root.entrySet().associate { (key, value) ->
                val obj = value.asJsonObject
                key to (obj.get("open")?.asString.orEmpty() to obj.get("close")?.asString.orEmpty())
            }
        }
    }

    /** The schema the workspace's Java sources declare, written next to the generated tags. */
    private fun loadOverlay(): JsonObject {
        val base = project.basePath ?: return JsonObject()
        val file = java.nio.file.Path.of(base, ".idea", "guidenh", "schema", "generated-tags.json")
        if (!java.nio.file.Files.isRegularFile(file)) return JsonObject()
        return runCatching { Gson().fromJson(EelFiles.readString(file), JsonObject::class.java) }
            .getOrDefault(JsonObject())
    }

    private fun overlaySection(section: String): JsonObject = generatedOverlay.getAsJsonObject(section) ?: JsonObject()

    /** Reads a section of the workspace overlay as name to description pairs. */
    private fun overlayDescriptions(section: String): Map<String, String> =
        overlaySection("markdownExtensions").getAsJsonObject(section)?.entrySet()
            ?.associate { (key, value) -> key to (value.asJsonObject.get("description")?.asString.orEmpty()) }
            .orEmpty()

    private fun loadMarkdownSection(section: String): Map<String, String> {
        val stream = javaClass.getResourceAsStream("/guidenh/schema/markdownExtensions.json") ?: return emptyMap()
        stream.use {
            val root = Gson().fromJson(it.reader(), JsonObject::class.java).getAsJsonObject(section) ?: return emptyMap()
            return root.entrySet().associate { (key, value) -> key to (value.asJsonObject.get("description")?.asString.orEmpty()) }
        }
    }

    companion object {
        fun get(project: Project): GuideNhSchemaService = project.getService(GuideNhSchemaService::class.java)
    }
}

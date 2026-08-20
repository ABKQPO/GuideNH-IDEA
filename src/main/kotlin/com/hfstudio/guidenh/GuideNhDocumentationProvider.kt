package com.hfstudio.guidenh

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.hfstudio.MyMessageBundle

class GuideNhDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val file = element.containingFile ?: return null
        if (!GuideNhFileUtil.isGuideNhDocument(file)) return null
        val offset = originalElement?.textRange?.startOffset ?: element.textRange.startOffset
        val model = GuideNhParser.parse(file.text)
        frontmatterDocumentation(file.text, offset, GuideNhSchemaService.get(file.project))?.let { return it }
        val tag = model.tags.firstOrNull { offset in it.range.startOffset..it.range.endOffset }
        if (tag != null) {
            val schema = GuideNhSchemaService.get(file.project).tag(tag.name) ?: return null
            val attribute = tag.attributes.firstOrNull { offset in it.valueRange }
            if (attribute != null) {
                val capability = resolveGuideNhRuntimeCapability(tag.name, attribute.name, schema.attributes.entries.firstOrNull { it.key.equals(attribute.name, true) }?.value)
                if (capability != null && GuideNhRuntimeBridgeService.get().isConnected) {
                    val runtime = GuideNhRuntimeBridgeService.get()
                    val value = attribute.value.orEmpty()
                    val filters = guideNhRuntimeFilters(file.text, offset, tag.name, attribute.name)
                    val entry = runtime.queryCached(capability, value, 20, filters).firstOrNull { it.get("id")?.asString.equals(value, true) }
                    if (entry != null) return "<h3>${escapeHtml(entry.get("label")?.asString ?: value)}</h3><code>${escapeHtml(value)}</code><p>${escapeHtml(entry.get("detail")?.asString ?: MyMessageBundle.message("documentation.runtime.entry"))}</p>"
                    runtime.prefetch(capability, value, 20, filters)
                }
            }
            return buildString {
                append("<h3>${schema.name}</h3>")
                schema.description?.let { append("<p>${it}</p>") }
                if (schema.attributes.isNotEmpty()) {
                    append("<p><b>${MyMessageBundle.message("documentation.attributes")}</b></p><ul>")
                    schema.attributes.forEach { (name, value) -> append("<li><code>$name</code>: ${value.description ?: value.type}</li>") }
                    append("</ul>")
                }
            }
        }
        val reference = model.references.firstOrNull { offset in it.range.startOffset..it.range.endOffset } ?: return null
        return when (reference.kind) {
            GuideNhReferenceKind.PAGE -> GuideNhWorkspaceIndex.get(file.project).findPage(reference.value, file.virtualFile)?.let { "<h3>${escapeHtml(it.relativePath)}</h3><p>${MyMessageBundle.message("documentation.page")}</p>" }
            GuideNhReferenceKind.RESOURCE -> GuideNhWorkspaceIndex.get(file.project).findResource(reference.value, file.virtualFile)?.let { "<h3>${escapeHtml(it.relativePath)}</h3><p>${MyMessageBundle.message("documentation.resource")}</p><code>${escapeHtml(it.file.url)}</code>" }
            GuideNhReferenceKind.ITEM, GuideNhReferenceKind.ORE -> {
                val capability = if (reference.kind == GuideNhReferenceKind.ITEM) "items" else "ores"
                val runtime = GuideNhRuntimeBridgeService.get()
                if (reference.kind == GuideNhReferenceKind.ITEM) {
                    val preview = runtime.previewCached("items", reference.value, count = 1, renderVariant = "inline", filters = mapOf("source" to "inline"))
                    if (preview != null) return formatItemPreview(reference.value, preview)
                    runtime.prefetchPreview("items", reference.value, count = 1, renderVariant = "inline", filters = mapOf("source" to "inline"))
                }
                val entry = if (runtime.isConnected) runtime.queryCached(capability, reference.value, 10)
                    .firstOrNull { it.get("id")?.asString.equals(reference.value, true) } else null
                if (entry != null) "<h3>${escapeHtml(entry.get("label")?.asString ?: reference.value)}</h3><code>${escapeHtml(reference.value)}</code><p>${escapeHtml(entry.get("detail")?.asString ?: MyMessageBundle.message("documentation.runtime.entry"))}</p>"
                else {
                    runtime.prefetch(capability, reference.value, 10)
                    val page = if (reference.kind == GuideNhReferenceKind.ITEM) GuideNhWorkspaceIndex.get(file.project).findItemPages(reference.value).firstOrNull() else GuideNhWorkspaceIndex.get(file.project).findOrePages(reference.value).firstOrNull()
                    "<code>${escapeHtml(reference.value)}</code><p>${if (page != null) MyMessageBundle.message("documentation.defined.in", escapeHtml(page.relativePath)) else MyMessageBundle.message("documentation.runtime.unavailable")}</p>"
                }
            }
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun formatItemPreview(id: String, preview: com.google.gson.JsonObject): String = buildString {
        append("<h3>").append(GuideNhMinecraftText.toHtml(preview.stringValue("displayName") ?: id)).append("</h3>")
        append("<code>").append(escapeHtml(id)).append("</code>")
        preview.stringValue("detail")?.let { append("<p>").append(GuideNhMinecraftText.toHtml(it)).append("</p>") }
        preview.get("tooltipLines")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { line ->
            line.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let {
                append("<div>").append(GuideNhMinecraftText.toHtml(it.asString)).append("</div>")
            }
        }
        preview.intValue("meta")?.let { append("<p>").append(MyMessageBundle.message("documentation.meta", it)).append("</p>") }
        preview.intValue("count")?.let { append("<p>").append(MyMessageBundle.message("documentation.count", it)).append("</p>") }
        preview.stringValue("nbt")?.let { append("<pre>").append(escapeHtml(it)).append("</pre>") }
    }

    private fun com.google.gson.JsonObject.stringValue(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun com.google.gson.JsonObject.intValue(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun frontmatterDocumentation(text: String, offset: Int, schema: GuideNhSchemaService): String? {
        if (!text.lineSequence().firstOrNull()?.trim().equals("---", true)) return null
        var cursor = 0
        val parents = ArrayDeque<Pair<Int, String>>()
        for (line in text.split('\n')) {
            val match = Regex("""^(\s*)([A-Za-z_][\w.-]*)\s*:\s*(.*)$""").find(line)
            if (match != null) {
                val start = cursor + match.groupValues[1].length
                val end = start + match.groupValues[2].length
                while (parents.isNotEmpty() && parents.last().first >= match.groupValues[1].length) parents.removeLast()
                val path = parents.map { it.second } + match.groupValues[2]
                if (offset in start..end) {
                    val key = schema.frontmatterKey(path.dropLast(1), path.last()) ?: return null
                    return "<h3>${escapeHtml(path.joinToString("."))}</h3><p>${escapeHtml(key.description ?: key.type)}</p><code>${escapeHtml(key.type)}</code>"
                }
                if (match.groupValues[3].isBlank()) parents.addLast(match.groupValues[1].length to match.groupValues[2])
            }
            cursor += line.length + 1
            if (line.trim() == "---" && cursor > 1) break
        }
        return null
    }
}

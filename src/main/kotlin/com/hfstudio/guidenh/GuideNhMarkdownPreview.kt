package com.hfstudio.guidenh

import com.intellij.openapi.Disposable
import org.intellij.markdown.ast.ASTNode
import org.intellij.plugins.markdown.extensions.CodeFenceGeneratingProvider
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import com.hfstudio.MyMessageBundle

/** Renders GuideNH fenced and inline Markdown extensions inside IDEA's built-in preview. */
class GuideNhFencePreviewProvider : CodeFenceGeneratingProvider {
    override fun isApplicable(language: String): Boolean = language.lowercase() in setOf("csv", "filetree", "funcgraph")

    override fun generateHtml(language: String, raw: String, node: ASTNode): String = when (language.lowercase()) {
        "csv" -> csv(raw)
        "filetree" -> "<pre class=\"guidenh-filetree\">${escape(raw)}</pre>"
        else -> functionGraph(raw)
    }

    private fun csv(text: String): String {
        val rows = text.lineSequence().filter { it.isNotBlank() }.map { it.split(',') }.toList()
        if (rows.isEmpty()) return "<div class=\"guidenh-empty\">${escape(MyMessageBundle.message("preview.csv.empty"))}</div>"
        return buildString {
            append("<table class=\"guidenh-csv\">")
            rows.forEachIndexed { index, row ->
                append("<tr>")
                row.forEach { cell -> append(if (index == 0) "<th>" else "<td>").append(escape(cell.trim())).append(if (index == 0) "</th>" else "</td>") }
                append("</tr>")
            }
            append("</table>")
        }
    }

    private fun functionGraph(text: String): String {
        // The browser extension turns this neutral container into the same local SVG
        // preview used for <FunctionGraph>. Keeping source text as content avoids an
        // additional serialization format and preserves special characters verbatim.
        return "<guidenh-funcgraph>${escape(text)}</guidenh-funcgraph>"
    }
}

class GuideNhMarkdownPreviewExtensionProvider : MarkdownBrowserPreviewExtension.Provider {
    override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension = GuideNhMarkdownPreviewExtension
}

private object GuideNhMarkdownPreviewExtension : MarkdownBrowserPreviewExtension, ResourceProvider, Disposable {
    private const val scriptUrl = "guidenh-markdown-preview.js"
    private const val styleUrl = "guidenh-markdown-preview.css"

    override val scripts: List<String> get() = listOf(scriptUrl)
    override val styles: List<String> get() = listOf(styleUrl)
    override val resourceProvider: ResourceProvider get() = this
    override fun canProvide(resourceName: String): Boolean = resourceName == scriptUrl || resourceName == styleUrl
    override fun loadResource(resourceName: String): ResourceProvider.Resource = when (resourceName) {
        scriptUrl -> resource("/guidenh/preview/$scriptUrl", "text/javascript")
        styleUrl -> resource("/guidenh/preview/$styleUrl", "text/css")
        else -> ResourceProvider.Resource(ByteArray(0), "text/plain")
    }
    override fun dispose() = Unit

    private fun resource(path: String, mime: String): ResourceProvider.Resource {
        val bytes = GuideNhMarkdownPreviewExtension::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: ByteArray(0)
        return ResourceProvider.Resource(bytes, mime)
    }
}

private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

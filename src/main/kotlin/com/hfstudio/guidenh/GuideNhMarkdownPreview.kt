package com.hfstudio.guidenh

import com.intellij.openapi.Disposable
import org.intellij.markdown.ast.ASTNode
import org.intellij.plugins.markdown.extensions.CodeFenceGeneratingProvider
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import com.hfstudio.MyMessageBundle
import java.util.Base64

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
    override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension = GuideNhMarkdownPreviewExtension(panel)
}

private class GuideNhMarkdownPreviewExtension(private val panel: MarkdownHtmlPanel) : MarkdownBrowserPreviewExtension, ResourceProvider, Disposable {
    private val imageResourcePrefix = "guidenh-image/"
    private val documentContextUrl = "guidenh-preview-context.js"
    private val scriptUrl = "guidenh-markdown-preview.js"
    private val styleUrl = "guidenh-markdown-preview.css"

    override val scripts: List<String> get() = listOf(documentContextUrl, scriptUrl)
    override val styles: List<String> get() = listOf(styleUrl)
    override val resourceProvider: ResourceProvider get() = this
    override fun canProvide(resourceName: String): Boolean = resourceName == documentContextUrl || resourceName == scriptUrl || resourceName == styleUrl || resourceName.startsWith(imageResourcePrefix)
    override fun loadResource(resourceName: String): ResourceProvider.Resource = when (resourceName) {
        documentContextUrl -> ResourceProvider.Resource(documentContextScript().toByteArray(Charsets.UTF_8), "text/javascript")
        scriptUrl -> resource("/guidenh/preview/$scriptUrl", "text/javascript")
        styleUrl -> resource("/guidenh/preview/$styleUrl", "text/css")
        else -> imageResource(resourceName) ?: ResourceProvider.Resource(ByteArray(0), "text/plain")
    }
    override fun dispose() = Unit

    private fun documentContextScript(): String {
        val documentUrl = panel.virtualFile?.url.orEmpty()
        val imageReferences = documentImageReferences().joinToString(",") { "\"${escapeJavaScript(it)}\"" }
        // Browser-extension resources are served by IDEA's panel-level aggregating provider.
        // Resolve from the preview page itself so its already registered provider hash is kept.
        return """
            (function () {
              window.__GUIDENH_DOCUMENT_URL__="${escapeJavaScript(documentUrl)}";
              window.__GUIDENH_IMAGE_URL__=new URL("$imageResourcePrefix", document.baseURI).toString();
              window.__GUIDENH_IMAGE_REFERENCES__=[$imageReferences];
            })();
        """.trimIndent()
    }

    /**
     * JCEF's Markdown renderer does not consistently retain the source destination as data-src.
     * Pass the source-order image references to the browser extension so it can still request the
     * GuideNH static resource endpoint after the built-in renderer has converted src.
     */
    private fun documentImageReferences(): List<String> {
        val document = panel.virtualFile ?: return emptyList()
        val text = runCatching { document.inputStream.use { it.reader().readText() } }.getOrDefault("")
        val references = mutableListOf<String>()
        Regex("""!\[[^\]]*]\(\s*(?:<([^>]+)>|([^\s)]+)(?:\s+[^)]*)?)\s*\)""").findAll(text).forEach { match ->
            (match.groupValues[1].ifBlank { match.groupValues[2] }).takeIf(String::isNotBlank)?.let(references::add)
        }
        Regex("""<FloatingImage\b[^>]*\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            (match.groupValues[1].ifBlank { match.groupValues[2] }).takeIf(String::isNotBlank)?.let(references::add)
        }
        return references
    }

    private fun imageResource(resourceName: String): ResourceProvider.Resource? {
        if (!resourceName.startsWith(imageResourcePrefix)) return null
        // Resource names are part of the static-server URL path. Do not pass a logical
        // GuideNH reference such as ../assets/... through that path: HTTP decoding can
        // normalize it before this provider receives the request.
        val reference = runCatching {
            String(Base64.getUrlDecoder().decode(resourceName.removePrefix(imageResourcePrefix)), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val target = resolveGuideNhImage(reference) ?: return null
        return ResourceProvider.loadExternalResource(target, imageMimeType(target.extension.orEmpty()))
    }

    private fun imageMimeType(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    /** Matches GuideNH's IdUtils.resolveLink followed by MutableGuide.loadAsset fallback. */
    private fun resolveGuideNhImage(reference: String): com.intellij.openapi.vfs.VirtualFile? {
        val document = panel.virtualFile ?: return null
        val path = document.path.replace('\\', '/')
        val location = Regex("^(.*?/assets/)([^/]+)(/guidenh/)(?:guidenh/)?_([a-z]{2}_[a-z]{2})/(.+\\.md)$", RegexOption.IGNORE_CASE)
            .find(path) ?: return document.parent?.findFileByRelativePath(reference)
        val recoveredLogicalPath = guideLogicalPathFromFileUri(reference)
        val logicalReference = recoveredLogicalPath ?: reference
        if (logicalReference.isBlank() || logicalReference.startsWith('#') ||
            (recoveredLogicalPath == null && Regex("^(?:[a-z][a-z0-9+.-]*:|//)", RegexOption.IGNORE_CASE).containsMatchIn(logicalReference))) {
            return null
        }

        val assetsRoot = location.groupValues[1]
        val documentNamespace = location.groupValues[2]
        val guideFolder = location.groupValues[3]
        val locale = location.groupValues[4]
        val pagePath = location.groupValues[5]
        val explicit = Regex("^([A-Za-z0-9_.-]+):/?(.+)$").find(logicalReference)
        val namespace = explicit?.groupValues?.get(1) ?: documentNamespace
        val resourcePath = explicit?.groupValues?.get(2) ?: logicalReference
        val logicalPath = if (recoveredLogicalPath != null || logicalReference.startsWith("/") || explicit != null) {
            normalizeGuidePath(resourcePath)
        } else {
            normalizeGuidePath("${pagePath.substringBeforeLast('/', "")}/$resourcePath")
        }
        if (logicalPath.isBlank()) return null

        val guideRoot = "$assetsRoot$namespace$guideFolder"
        return listOf("${guideRoot}_$locale/$logicalPath", "$guideRoot$logicalPath")
            .asSequence()
            .mapNotNull { candidate -> com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(candidate) }
            .firstOrNull()
    }

    private fun normalizeGuidePath(value: String): String {
        val result = mutableListOf<String>()
        value.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                else -> result += segment
            }
        }
        return result.joinToString("/")
    }

    /** Recovers the logical resource path if IntelliJ resolved Markdown relative to _<locale>. */
    private fun guideLogicalPathFromFileUri(reference: String): String? {
        if (!reference.startsWith("file:", ignoreCase = true)) return null
        val physicalPath = runCatching { java.nio.file.Paths.get(java.net.URI(reference)).toString().replace('\\', '/') }.getOrNull()
            ?: return null
        val marker = physicalPath.indexOf("/guidenh/", ignoreCase = true)
        if (marker < 0) return null
        var logicalPath = physicalPath.substring(marker + "/guidenh/".length).removePrefix("guidenh/")
        val localized = Regex("^_[^/]+/(.+)$").matchEntire(logicalPath)
        if (localized != null) logicalPath = localized.groupValues[1]
        return logicalPath
    }

    private fun resource(path: String, mime: String): ResourceProvider.Resource {
        val bytes = GuideNhMarkdownPreviewExtension::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: ByteArray(0)
        return ResourceProvider.Resource(bytes, mime)
    }
}

private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun escapeJavaScript(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")

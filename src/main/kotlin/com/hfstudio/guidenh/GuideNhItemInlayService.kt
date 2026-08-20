package com.hfstudio.guidenh

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.Image
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import javax.swing.ImageIcon

@Service(Service.Level.APP)
class GuideNhItemInlayService : Disposable {
    private val scanExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "guidenh-item-inlay-scan").apply { isDaemon = true } }
    private val previewExecutor = Executors.newFixedThreadPool(MAX_PREVIEW_CONCURRENCY) { runnable -> Thread(runnable, "guidenh-item-inlay-preview").apply { isDaemon = true } }
    private val editorListeners = mutableMapOf<Editor, DocumentListener>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : com.intellij.openapi.editor.event.EditorFactoryListener {
            override fun editorCreated(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                attach(event.editor)
            }
            override fun editorReleased(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                editorListeners.remove(event.editor)
                event.editor.inlayModel.getInlineElementsInRange(0, event.editor.document.textLength).forEach { inlay ->
                    if (inlay.renderer is GuideNhItemRenderer) inlay.dispose()
                }
            }
        }, this)
        EditorFactory.getInstance().allEditors.forEach(::attach)
    }

    private fun attach(editor: Editor) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!GuideNhFileUtil.isGuideNhDocument(file)) return
        if (editorListeners.containsKey(editor)) return
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { schedule(editor) }
        }
        editor.document.addDocumentListener(listener, this)
        editorListeners[editor] = listener
        schedule(editor)
    }

    private fun schedule(editor: Editor) {
        val documentStamp = editor.document.modificationStamp
        scanExecutor.execute {
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@execute
            val visible = editor.scrollingModel.visibleArea
            val firstLine = editor.xyToLogicalPosition(java.awt.Point(0, visible.y)).line.coerceAtLeast(0)
            val lastLine = editor.xyToLogicalPosition(java.awt.Point(0, visible.y + visible.height)).line.coerceAtMost(editor.document.lineCount - 1)
            val startOffset = editor.document.getLineStartOffset((firstLine - VISIBLE_LINE_PADDING).coerceAtLeast(0))
            val endOffset = editor.document.getLineEndOffset((lastLine + VISIBLE_LINE_PADDING).coerceAtMost(editor.document.lineCount - 1))
            val references = GuideNhParser.parse(editor.document.text).references
                .asSequence().filter { it.kind == GuideNhReferenceKind.ITEM && it.range.startOffset in startOffset..endOffset }
                .take(MAX_VISIBLE_CONTEXTS).toList()
            val images = references.map { it.value }.distinct().associateWith { itemId ->
                CompletableFuture.supplyAsync({ resolveImage(itemId) }, previewExecutor)
            }
            val resolved = references.mapNotNull { reference ->
                runCatching { images.getValue(reference.value).get() }.getOrNull()?.let { image -> reference.range.endOffset to image }
            }
            ApplicationManager.getApplication().invokeLater {
                if (editor.isDisposed || editor.document.modificationStamp != documentStamp) return@invokeLater
                editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength).forEach { inlay ->
                    if (inlay.renderer is GuideNhItemRenderer) inlay.dispose()
                }
                resolved.forEach { (offset, image) ->
                    if (offset <= editor.document.textLength) editor.inlayModel.addInlineElement(offset, GuideNhItemRenderer(image))
                }
            }
        }
    }

    private fun resolveImage(itemId: String): Image? {
        val runtime = GuideNhRuntimeBridgeService.get()
        val preview = runtime.previewCached("items", itemId, count = 1, renderVariant = "inline", filters = mapOf("source" to "inline"))
            ?: if (runtime.isConnected) runtime.previewResolve("items", itemId, count = 1, renderVariant = "inline", filters = mapOf("source" to "inline")) else null
        val base64 = preview?.get("iconPngBase64")?.asString ?: return null
        return runCatching { ImageIcon(Base64.getDecoder().decode(base64)).image }.getOrNull()
    }

    override fun dispose() {
        scanExecutor.shutdownNow()
        previewExecutor.shutdownNow()
        editorListeners.clear()
    }
}

private const val MAX_VISIBLE_CONTEXTS = 24
private const val MAX_PREVIEW_CONCURRENCY = 4
private const val VISIBLE_LINE_PADDING = 3

private class GuideNhItemRenderer(private val image: Image) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 18
    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        g.drawImage(image, targetRegion.x, targetRegion.y + 1, targetRegion.width, targetRegion.height - 2, null)
    }
}

package com.hfstudio.guidenh

import com.intellij.openapi.editor.CustomFileDropHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.command.WriteCommandAction
import java.awt.datatransfer.Transferable

/** Handles plain-text and GuideNH JSON item drops in the native editor. */
class GuideNhItemDropHandler : CustomFileDropHandler() {
    override fun canHandle(transferable: Transferable, editor: Editor?): Boolean {
        if (editor == null) return false
        val file = editor.virtualFile ?: return false
        return GuideNhFileUtil.isGuideNhDocument(file) && itemId(transferable) != null
    }

    override fun handleDrop(transferable: Transferable, editor: Editor?, project: Project): Boolean {
        if (editor == null) return false
        val id = itemId(transferable) ?: return false
        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            val offset = editor.caretModel.offset.coerceIn(0, document.textLength)
            val context = GuideNhItemStackContexts.findAt(document.charsSequence, offset)
            if (context == null) document.insertString(offset, id)
            else document.replaceString(context.valueStart, context.valueEndExclusive, id)
        }
        return true
    }

    private fun itemId(transferable: Transferable): String? {
        val flavors = transferable.transferDataFlavors
        val custom = flavors.firstOrNull { it.mimeType.startsWith("application/vnd.guidenh.itemstack+json") }
            ?.let { runCatching { transferable.getTransferData(it).toString() }.getOrNull() }
        val customId = custom?.let { Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        return customId ?: flavors.firstOrNull { it.isFlavorTextType }
            ?.let { runCatching { transferable.getTransferData(it).toString().trim() }.getOrNull() }
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+(?::\\d+)?")) }
    }
}

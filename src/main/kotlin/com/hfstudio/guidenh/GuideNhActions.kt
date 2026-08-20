package com.hfstudio.guidenh

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.command.WriteCommandAction
import com.hfstudio.MyMessageBundle

class ConnectRuntimeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val state = GuideNhSettings.get().state
        if (state.token.orEmpty().isBlank()) {
            Messages.showErrorDialog(e.project, MyMessageBundle.message("runtime.token.required"), MyMessageBundle.message("runtime.title"))
            return
        }
        runCatching { GuideNhRuntimeBridgeService.get().connect(state.host.orEmpty(), state.port, state.token.orEmpty(), state.allowRemote) }
            .onFailure { Messages.showErrorDialog(e.project, it.message, MyMessageBundle.message("runtime.title")) }
    }
}

class DisconnectRuntimeAction : AnAction() { override fun actionPerformed(e: AnActionEvent) { GuideNhRuntimeBridgeService.get().disconnect() } }

class PickItemStackAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val caret = editor.caretModel.offset
        val context = GuideNhItemStackContexts.findAt(editor.document.charsSequence, caret)
        val dialog = GuideNhItemPickerDialog(project, context?.value.orEmpty().ifBlank { "minecraft:" })
        if (!dialog.showAndGet()) return
        val selection = dialog.selection
        if (selection != null) {
            val tagName = context?.tagName
            val supported = tagName?.let { GuideNhSchemaService.get(project).tag(it)?.attributes.orEmpty() }.orEmpty()
            WriteCommandAction.runWriteCommandAction(project) {
                if (context != null) {
                    val hasMeta = supported.keys.any { it.equals("meta", true) }
                    val hasNbt = supported.keys.any { it.equals("nbt", true) }
                    var itemValue = selection.id
                    if (!hasMeta) selection.meta?.let { itemValue += ":$it" }
                    if (!hasNbt) selection.nbt?.let { itemValue += ":$it" }
                    var markup = editor.document.getText(com.intellij.openapi.util.TextRange(context.tagStart, context.tagEndExclusive))
                    markup = upsertGuideNhAttribute(markup, context.attributeName, itemValue)
                    if (hasMeta) selection.meta?.let { markup = upsertGuideNhAttribute(markup, "meta", it.toString()) }
                    if (supported.keys.any { it.equals("count", true) }) markup = upsertGuideNhAttribute(markup, "count", selection.count.toString(), expression = true)
                    if (hasNbt) selection.nbt?.let { markup = upsertGuideNhAttribute(markup, "nbt", it) }
                    editor.document.replaceString(context.tagStart, context.tagEndExclusive, markup)
                } else {
                    val value = buildString {
                        append(selection.id)
                        selection.meta?.let { append(':').append(it) }
                        selection.nbt?.let { append(':').append(it) }
                    }
                    editor.document.insertString(caret, value)
                }
            }
        }
    }

    private fun upsertGuideNhAttribute(source: String, name: String, value: String, expression: Boolean = false): String {
        val existing = Regex("\\b${Regex.escape(name)}\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|\\{([^}]*)\\})", RegexOption.IGNORE_CASE).find(source)
        val replacement = if (expression) "$name={$value}" else {
            val quote = if (value.contains('"')) '\'' else '"'
            "$name=$quote$value$quote"
        }
        return if (existing != null) source.replaceRange(existing.range, replacement)
        else source.substring(0, source.length - if (source.endsWith("/>") ) 2 else 1) + " $replacement" + source.takeLast(if (source.endsWith("/>") ) 2 else 1)
    }
}

class ValidateDocumentAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val file = e.project?.let { FileEditorManager.getInstance(it).selectedEditor?.file } ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        runCatching { GuideNhRuntimeBridgeService.get().validate(file.url, "markdown", document.text) }
            .onFailure { Messages.showErrorDialog(e.project, it.message, MyMessageBundle.message("runtime.title")) }
    }
}

package com.hfstudio.guidenh

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettingsManager

class GuideNhEnterHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?
    ): EnterHandlerDelegate.Result {
        if (!GuideNhFileUtil.isGuideNhDocument(file)) return EnterHandlerDelegate.Result.Continue
        val text = editor.document.text
        val lineStart = text.lastIndexOf('\n', (caretOffset.get() - 1).coerceAtLeast(0)) + 1
        val prefix = text.substring(lineStart, caretOffset.get())
        val indent = prefix.takeWhile { it == ' ' || it == '\t' }
        val tag = Regex("<(?:([A-Za-z][A-Za-z0-9]*))[^>]*>$").find(prefix.trim())?.groupValues?.getOrNull(1)
        if (tag != null && !prefix.trimEnd().endsWith("/>") && !tag.equals("br", true)) {
            val options = CodeStyleSettingsManager.getSettings(file.project).getIndentOptions(file.fileType)
            val indentUnit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
            editor.document.insertString(caretOffset.get(), "\n$indent$indentUnit")
            caretOffset.set(caretOffset.get() + indent.length + indentUnit.length + 1)
            return EnterHandlerDelegate.Result.Stop
        }
        // Markdown's normal Enter action only repeats the preceding whitespace. When
        // text immediately follows an opening GuideNH container it may therefore stay
        // outside that container's visual indentation. Preserve at least one indent
        // level beneath the deepest still-open tag, matching GuideVSC's on-type rule.
        val openTags = ArrayDeque<GuideNhTag>()
        GuideNhParser.parse(text.substring(0, caretOffset.get())).tags.forEach { parsed ->
            if (parsed.closing) {
                val match = openTags.indexOfLast { it.name.equals(parsed.name, true) }
                if (match >= 0) repeat(openTags.size - match) { openTags.removeLast() }
            } else if (!parsed.selfClosing) {
                openTags.addLast(parsed)
            }
        }
        val parent = openTags.lastOrNull() ?: return EnterHandlerDelegate.Result.Continue
        val options = CodeStyleSettingsManager.getSettings(file.project).getIndentOptions(file.fileType)
        val indentUnit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
        val parentLineStart = text.lastIndexOf('\n', (parent.range.startOffset - 1).coerceAtLeast(0)) + 1
        val parentIndent = text.substring(parentLineStart, parent.range.startOffset).takeWhile { it == ' ' || it == '\t' }
        val targetIndent = parentIndent + indentUnit
        if (prefix.trim().isNotEmpty() && indent.length < targetIndent.length) {
            editor.document.insertString(caretOffset.get(), "\n$targetIndent")
            caretOffset.set(caretOffset.get() + targetIndent.length + 1)
            return EnterHandlerDelegate.Result.Stop
        }
        return EnterHandlerDelegate.Result.Continue
    }
}

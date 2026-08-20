package com.hfstudio.guidenh

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class GuideNhTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!GuideNhFileUtil.isGuideNhDocument(file)) return Result.CONTINUE
        if (c == '<' || c == ' ' || c == '=' || c == '"' || c == '\'' || c == '/' || c == '(' || c == '`' || c == ':') {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        if (c != '>') return Result.CONTINUE
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val text = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
        val closing = Regex("^(\\s*)</([A-Za-z][A-Za-z0-9]*)>\\s*$").find(text) ?: return Result.CONTINUE
        val sourceBeforeClosing = document.text.substring(0, lineStart)
        val stack = ArrayDeque<GuideNhTag>()
        for (tag in GuideNhParser.parse(sourceBeforeClosing).tags) {
            if (tag.closing) {
                val match = stack.indexOfLast { it.name.equals(tag.name, true) }
                if (match >= 0) repeat(stack.size - match) { stack.removeLast() }
            } else if (!tag.selfClosing) {
                stack.addLast(tag)
            }
        }
        val open = stack.lastOrNull { it.name.equals(closing.groupValues[2], true) } ?: return Result.CONTINUE
        val openingLine = document.getText(com.intellij.openapi.util.TextRange(
            document.getLineStartOffset(document.getLineNumber(open.range.startOffset)),
            document.getLineEndOffset(document.getLineNumber(open.range.startOffset))
        ))
        val targetIndent = openingLine.takeWhile { it == ' ' || it == '\t' }
        if (closing.groupValues[1] != targetIndent) {
            document.replaceString(lineStart, lineStart + closing.groupValues[1].length, targetIndent)
        }
        return Result.CONTINUE
    }
}

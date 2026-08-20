package com.hfstudio.guidenh

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.psi.PsiFile
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange

data class GuideNhRuntimeAnnotationInput(val uri: String, val text: String)

class GuideNhRuntimeExternalAnnotator : ExternalAnnotator<GuideNhRuntimeAnnotationInput, List<GuideNhRuntimeDiagnostic>>() {
    override fun collectInformation(file: PsiFile): GuideNhRuntimeAnnotationInput? {
        if (!GuideNhFileUtil.isGuideNhDocument(file)) return null
        val virtualFile = file.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        return GuideNhRuntimeAnnotationInput(virtualFile.url, document.text)
    }

    override fun doAnnotate(collectedInfo: GuideNhRuntimeAnnotationInput): List<GuideNhRuntimeDiagnostic> =
        GuideNhRuntimeBridgeService.get().diagnostics(collectedInfo.uri)

    override fun apply(file: PsiFile, annotationResult: List<GuideNhRuntimeDiagnostic>?, holder: AnnotationHolder) {
        val virtualFile = file.virtualFile ?: return
        annotationResult.orEmpty().forEach { diagnostic ->
            val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@forEach
            val start = diagnostic.start.coerceIn(0, document.textLength)
            val end = diagnostic.end.coerceIn(start, document.textLength)
            val severity = if (diagnostic.severity.equals("warning", true)) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            holder.newAnnotation(severity, diagnostic.message).range(TextRange(start, end.coerceAtLeast(start + 1).coerceAtMost(document.textLength))).create()
        }
    }
}

package com.hfstudio.guidenh

import com.intellij.icons.AllIcons
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement

class GuideNhItemLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val file = element.containingFile ?: return null
        if (!GuideNhFileUtil.isGuideNhDocument(file) || element.textLength == 0) return null
        val start = element.textRange.startOffset
        val reference = GuideNhParser.parse(file.text).references.firstOrNull {
            it.kind == GuideNhReferenceKind.ITEM && it.range.startOffset in element.textRange.startOffset..element.textRange.endOffset
        } ?: return null
        if (reference.range.startOffset != start && element.parent?.textRange?.contains(reference.range.startOffset) == true) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Nodes.Tag,
            { "GuideNH item: ${reference.value}" },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { "GuideNH item reference ${reference.value}" }
        )
    }
}

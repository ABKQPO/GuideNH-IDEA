package com.hfstudio.guidenh

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag

/**
 * Lets the platform's HTML and spelling inspections coexist with GuideNH markup.
 * Only schema-known tags in GuideNH resource-pack Markdown are suppressed; normal
 * Markdown prose and ordinary HTML continue to use IDEA's built-in inspections.
 */
class GuideNhInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in SUPPRESSED_TOOLS || !isGuideNhContext(element)) return false
        if (toolId == "MarkdownUnresolvedFileReference") {
            return isLegacyGuideNhResourceLink(element)
        }
        var current: PsiElement? = element
        while (current != null) {
            val tag = current as? XmlTag
            if (tag != null && GuideNhSchemaService.get(element.project).tag(tag.name) != null) {
                return true
            }
            current = current.parent
        }
        return false
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = emptyArray()

    private fun isGuideNhContext(element: PsiElement): Boolean {
        val containingFile = element.containingFile ?: return false
        if (GuideNhFileUtil.isGuideNhDocument(containingFile)) return true
        val topLevelFile = InjectedLanguageManager.getInstance(element.project).getTopLevelFile(element)
        return GuideNhFileUtil.isGuideNhDocument(topLevelFile)
    }

    private fun isLegacyGuideNhResourceLink(element: PsiElement): Boolean {
        val topLevelFile = InjectedLanguageManager.getInstance(element.project).getTopLevelFile(element)
        val offset = element.textRange.startOffset
        return legacyMarkdownResourcePattern.findAll(topLevelFile.text).any { match -> offset in match.range }
    }

    private companion object {
        val SUPPRESSED_TOOLS = setOf(
            "HtmlUnknownTag",
            "HtmlUnknownAttribute",
            "HtmlUnknownBooleanAttribute",
            "HtmlMissingClosingTag",
            "HtmlExtraClosingTag",
            "CheckEmptyScriptTag",
            "CheckTagEmptyBody",
            "MarkdownUnresolvedFileReference",
            "SpellCheckingInspection"
        )
        val legacyMarkdownResourcePattern = Regex("!?\\[[^\\]\\r\\n]*\\]\\(\\*[^)\\r\\n]+\\*\\)")
    }
}

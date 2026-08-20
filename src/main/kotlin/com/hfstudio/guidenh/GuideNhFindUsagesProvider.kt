package com.hfstudio.guidenh

import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.hfstudio.MyMessageBundle

class GuideNhFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner? = null
    override fun canFindUsagesFor(element: PsiElement): Boolean = element is GuideNhRuntimeSemanticElement || element.containingFile?.let(GuideNhFileUtil::isGuideNhDocument) == true
    override fun getHelpId(element: PsiElement): String? = null
    override fun getType(element: PsiElement): String = (element as? GuideNhRuntimeSemanticElement)
        ?.let { MyMessageBundle.message("findusages.runtime", it.entry.capability) }
        ?: MyMessageBundle.message("findusages.reference")
    override fun getDescriptiveName(element: PsiElement): String = element.text
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = element.text
}

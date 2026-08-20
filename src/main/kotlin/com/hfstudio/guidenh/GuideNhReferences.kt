package com.hfstudio.guidenh

import com.intellij.psi.*
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

class GuideNhReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
                if (!GuideNhFileUtil.isGuideNhDocument(file)) return PsiReference.EMPTY_ARRAY
                return GuideNhPsiReferences.create(file, element)
            }
        })
    }
}

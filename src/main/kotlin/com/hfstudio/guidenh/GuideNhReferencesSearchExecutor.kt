package com.hfstudio.guidenh

import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

/** Makes Find Usages work from a page/resource definition as well as from a reference site. */
class GuideNhReferencesSearchExecutor : QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    override fun execute(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val runtimeTarget = queryParameters.elementToSearch as? GuideNhRuntimeSemanticElement
        if (runtimeTarget != null) return searchRuntimeSymbol(runtimeTarget, queryParameters, consumer)
        val target = queryParameters.elementToSearch.containingFile ?: return true
        if (!GuideNhFileUtil.isGuideNhDocument(target) && !GuideNhFileUtil.isGuideNhPath(target.virtualFile?.path.orEmpty())) {
            return true
        }
        val index = GuideNhWorkspaceIndex.get(target.project)
        val psiManager = PsiManager.getInstance(target.project)
        for (page in index.allPages()) {
            val source = psiManager.findFile(page.file) ?: continue
            for (reference in GuideNhPsiReferences.create(source, source, includeDeclarations = false)) {
                val resolved = reference.resolve()
                if (resolved?.containingFile?.virtualFile == target.virtualFile) {
                    if (!consumer.process(reference)) return false
                }
            }
        }
        return true
    }

    private fun searchRuntimeSymbol(
        target: GuideNhRuntimeSemanticElement,
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val project = queryParameters.elementToSearch.project
        val psiManager = PsiManager.getInstance(project)
        for (page in GuideNhWorkspaceIndex.get(project).allPages()) {
            val source = psiManager.findFile(page.file) ?: continue
            for (reference in GuideNhPsiReferences.create(source, source, includeDeclarations = false)) {
                if (reference.resolve() === target && !consumer.process(reference)) return false
            }
        }
        return true
    }
}

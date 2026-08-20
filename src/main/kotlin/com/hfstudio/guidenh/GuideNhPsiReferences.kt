package com.hfstudio.guidenh

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VirtualFile

/** Shared PSI reference construction used by navigation and IntelliJ's references-search EP. */
object GuideNhPsiReferences {
    fun create(file: PsiFile, element: PsiElement = file, includeDeclarations: Boolean = true): Array<PsiReference> {
        val absoluteStart = element.textRange.startOffset
        val elementEnd = element.textRange.endOffset
        return GuideNhParser.parse(file.text).references
            .filter { (includeDeclarations || !it.declaration) && it.range.startOffset >= absoluteStart && it.range.endOffset <= elementEnd }
            .map { reference ->
                object : PsiReferenceBase<PsiElement>(
                    element,
                    TextRange(reference.range.startOffset - absoluteStart, reference.range.endOffset - absoluteStart),
                    true
                ) {
                    override fun resolve(): PsiElement? = resolveFile(file, reference)?.let {
                        PsiManager.getInstance(file.project).findFile(it)
                    } ?: GuideNhRuntimeSemanticIndex.get(file.project).resolve(reference)

                    override fun getVariants(): Array<Any> = when (reference.kind) {
                        GuideNhReferenceKind.PAGE -> GuideNhWorkspaceIndex.get(file.project)
                            .queryPages(reference.value, file.virtualFile).map { it.relativePath }
                        GuideNhReferenceKind.RESOURCE -> GuideNhWorkspaceIndex.get(file.project)
                            .queryResources(reference.value, file.virtualFile).map { it.relativePath }
                        GuideNhReferenceKind.ITEM -> GuideNhWorkspaceIndex.get(file.project)
                            .findItemPages(reference.value).map { it.pageId }
                        GuideNhReferenceKind.ORE -> GuideNhWorkspaceIndex.get(file.project)
                            .findOrePages(reference.value).map { it.pageId }
                    }.toTypedArray()
                }
            }.toTypedArray()
    }

    fun resolveFile(source: PsiFile, reference: GuideNhReference): VirtualFile? {
        val index = GuideNhWorkspaceIndex.get(source.project)
        return when (reference.kind) {
            GuideNhReferenceKind.PAGE -> index.findPage(reference.value, source.virtualFile)?.file
            GuideNhReferenceKind.RESOURCE -> index.findResource(reference.value, source.virtualFile)?.file
            GuideNhReferenceKind.ITEM -> index.findItemPages(reference.value).firstOrNull()?.file
            GuideNhReferenceKind.ORE -> index.findOrePages(reference.value).firstOrNull()?.file
        }
    }
}

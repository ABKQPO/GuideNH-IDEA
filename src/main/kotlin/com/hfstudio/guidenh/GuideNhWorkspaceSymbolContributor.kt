package com.hfstudio.guidenh

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

class GuideNhWorkspaceSymbolContributor : ChooseByNameContributor {
    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> =
        GuideNhWorkspaceIndex.get(project).allPages()
            .flatMap { page -> listOf(page.pageId, page.relativePath, page.file.name) }
            .distinctBy { it.lowercase() }
            .toTypedArray()

    override fun getItemsByName(name: String, pattern: String, project: Project, includeNonProjectItems: Boolean): Array<NavigationItem> =
        GuideNhWorkspaceIndex.get(project).allPages().filter {
            it.pageId.equals(name, true) || it.relativePath.equals(name, true) || it.file.name.equals(name, true)
        }
            .mapNotNull { PsiManager.getInstance(project).findFile(it.file) }
            .toTypedArray()
}

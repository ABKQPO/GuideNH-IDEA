package com.hfstudio.guidenh

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewBuilderProvider
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor

class GuideNhStructureViewProvider : StructureViewBuilderProvider {
    override fun getStructureViewBuilder(fileType: FileType, virtualFile: VirtualFile, project: Project): StructureViewBuilder? {
        if (!GuideNhFileUtil.isGuideNhDocument(virtualFile)) return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel {
                return StructureViewModelBase(psiFile, editor, GuideNhRootElement(psiFile))
            }
        }
    }
}

private class GuideNhRootElement(private val file: PsiFile) : StructureViewTreeElement, SortableTreeElement {
    override fun getValue(): Any = file
    override fun getAlphaSortKey(): String = file.name
    override fun navigate(requestFocus: Boolean) = file.navigate(requestFocus)
    override fun canNavigate(): Boolean = file.canNavigate()
    override fun canNavigateToSource(): Boolean = file.canNavigateToSource()
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = file.name
        override fun getLocationString(): String? = file.virtualFile?.parent?.presentableUrl
        override fun getIcon(unused: Boolean) = file.getIcon(0)
    }
    override fun getChildren(): Array<TreeElement> = guideNhStructureNodes(file.text)
        .map { GuideNhTagElement(file, it) }
        .toTypedArray()
}

private data class GuideNhStructureNode(val tag: GuideNhTag, val children: MutableList<GuideNhStructureNode> = mutableListOf())

private fun guideNhStructureNodes(text: String): List<GuideNhStructureNode> {
    val roots = mutableListOf<GuideNhStructureNode>()
    val stack = ArrayDeque<GuideNhStructureNode>()
    for (tag in GuideNhParser.parse(text).tags) {
        if (tag.closing) {
            val match = stack.indexOfLast { it.tag.name.equals(tag.name, true) }
            if (match >= 0) repeat(stack.size - match) { stack.removeLast() }
            continue
        }
        val node = GuideNhStructureNode(tag)
        stack.lastOrNull()?.children?.add(node) ?: roots.add(node)
        if (!tag.selfClosing) stack.addLast(node)
    }
    return roots
}

private class GuideNhTagElement(private val file: PsiFile, private val node: GuideNhStructureNode) : StructureViewTreeElement, SortableTreeElement {
    private val tag: GuideNhTag get() = node.tag
    override fun getValue(): Any = tag
    override fun getAlphaSortKey(): String = "${tag.name}:${tag.range.startOffset}"
    override fun navigate(requestFocus: Boolean) {
        file.virtualFile?.let { OpenFileDescriptor(file.project, it, tag.range.startOffset).navigate(requestFocus) }
    }
    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = "<${tag.name}>"
        override fun getLocationString(): String? = "line ${file.text.substring(0, tag.range.startOffset).count { it == '\n' } + 1}"
        override fun getIcon(unused: Boolean) = file.getIcon(0)
    }
    override fun getChildren(): Array<TreeElement> = node.children.map { GuideNhTagElement(file, it) }.toTypedArray()
}

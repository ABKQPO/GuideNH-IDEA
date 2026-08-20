package com.hfstudio.guidenh

import com.intellij.psi.PsiFile
import com.intellij.openapi.vfs.VirtualFile

object GuideNhFileUtil {
    fun isGuideNhDocument(file: PsiFile): Boolean = file.virtualFile?.let(::isGuideNhDocument) == true

    fun isGuideNhDocument(file: VirtualFile): Boolean {
        val path = file.path.replace('\\', '/')
        return file.extension.equals("md", true) && Regex("/assets/[^/]+/guidenh/(?:guidenh/)?_[^/]+/.+\\.md$", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }

    /** A locale-independent asset loaded by GuideNH as `namespace:assets/...`. */
    fun isGuideNhResource(file: VirtualFile): Boolean {
        if (file.isDirectory || file.extension.equals("md", true)) return false
        val path = file.path.replace('\\', '/')
        return Regex("/assets/[^/]+/guidenh/(?:guidenh/)?assets/.+", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }

    fun isGuideNhPath(path: String): Boolean = Regex("/assets/[^/]+/guidenh/", RegexOption.IGNORE_CASE).containsMatchIn(path.replace('\\', '/'))
}

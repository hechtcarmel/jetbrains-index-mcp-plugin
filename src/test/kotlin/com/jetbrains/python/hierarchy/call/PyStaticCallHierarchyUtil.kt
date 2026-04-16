package com.jetbrains.python.hierarchy.call

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.PyElement

object PyStaticCallHierarchyUtil {
    @JvmStatic
    var callers: Map<PsiElement, Collection<PsiElement>> = emptyMap()

    @JvmStatic
    fun getCallers(element: PyElement): Map<PsiElement, Collection<PsiElement>> = callers
}

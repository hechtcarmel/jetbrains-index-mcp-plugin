package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod

/** Loaded only after the universal hierarchy helper has verified that Java PSI is available. */
internal fun haveSameHierarchyMethodSignature(first: PsiElement, second: PsiElement): Boolean {
    return hierarchyMethodSignatureKey(first) == hierarchyMethodSignatureKey(second)
}

internal fun hierarchyMethodSignatureKey(element: PsiElement): String {
    val method = element as PsiMethod
    return method.name + method.parameterList.parameters.joinToString(prefix = "(", postfix = ")") {
        it.type.canonicalText
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java.haveSameHierarchyMethodSignature
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java.hierarchyMethodSignatureKey
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.psi.PsiElement

// The Java plugin is optional. Keep its PSI types out of this universal helper's bytecode.
private val hierarchyMethodClass: Class<*>? by lazy {
    try {
        Class.forName("com.intellij.psi.PsiMethod")
    } catch (_: ClassNotFoundException) {
        null
    }
}

/** Compares restored declarations, not public handles or non-unique display signatures. */
internal fun sameHierarchyDeclaration(first: PsiElement, second: PsiElement): Boolean {
    if (hierarchyMethodClass?.isInstance(first) == true && hierarchyMethodClass?.isInstance(second) == true) {
        if (!haveSameHierarchyMethodSignature(first, second)) return false
    }
    val left = PsiUtils.resolveNavigationTarget(first)
    val right = PsiUtils.resolveNavigationTarget(second)
    if (left === right) return true

    val leftFile = left.containingFile?.virtualFile
    val rightFile = right.containingFile?.virtualFile
    if (leftFile != null && rightFile != null && left.isPhysical && right.isPhysical) {
        // Both offsets are read from current PSI restored through smart pointers. A line shift
        // updates both sides, while equally named local/anonymous declarations remain distinct.
        return leftFile == rightFile && left.textOffset == right.textOffset && left.javaClass == right.javaClass
    }
    return left.manager.areElementsEquivalent(left, right)
}

/** Stable per-page identity key used to avoid repeated linear scans of visited pointers. */
internal fun hierarchyDeclarationKey(element: PsiElement): String {
    val target = PsiUtils.resolveNavigationTarget(element)
    val file = target.containingFile?.virtualFile?.path
    val location = if (file != null && target.isPhysical) {
        "${file}|${target.textOffset}|${target.javaClass.name}"
    } else {
        "${target.javaClass.name}|${target.textOffset}|${target.text.hashCode()}"
    }
    return if (hierarchyMethodClass?.isInstance(element) == true) {
        "$location|${hierarchyMethodSignatureKey(element)}"
    } else location
}

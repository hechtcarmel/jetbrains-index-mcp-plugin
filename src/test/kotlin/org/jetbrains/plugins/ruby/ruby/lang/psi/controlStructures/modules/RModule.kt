package org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.modules

import com.intellij.psi.PsiElement

/**
 * Compilation stub for the Ruby plugin's RModule PSI interface.
 *
 * Used only in unit tests. See [RClass] stub for rationale.
 */
interface RModule : PsiElement {
    fun getName(): String?
    fun getFullyQualifiedName(): String?
    fun getIncludedModules(): List<PsiElement>
}

package org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.classes

import com.intellij.psi.PsiElement

/**
 * Compilation stub for the Ruby plugin's RClass PSI interface.
 *
 * Used only in unit tests. The real interface is loaded via reflection at runtime
 * when the Ruby plugin is present in the IDE. This stub provides the method
 * signatures that [BaseRubyHandler] accesses reflectively, so test code that
 * creates [FakeRClass] instances compiles and runs without the Ruby plugin JAR.
 */
interface RClass : PsiElement {
    fun getName(): String?
    fun getFullyQualifiedName(): String?
    fun getSuperClass(): PsiElement?
    fun getSuperClassName(): String?
    fun getIncludedModules(): List<PsiElement>
}

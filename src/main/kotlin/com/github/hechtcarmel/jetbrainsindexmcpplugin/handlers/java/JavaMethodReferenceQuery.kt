package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.util.Query

/**
 * Reference query for JVM methods, matching the IDE's own Find Usages.
 *
 * Plain `ReferencesSearch` is an exact-target text search, so for a method it misses
 * - Kotlin synthetic-property accesses of Java accessors (`bean.name` -> `getName()`): the Kotlin
 *   plugin contributes those only to [MethodReferencesSearch];
 * - calls that resolve to an override (`base.area()` where `base: Base` and `Base` overrides
 *   `Shape.area()`), which are usages of the overridden method too.
 *
 * Kept in its own class (same isolation pattern as [JavaHierarchyMethodComplement]) so Java-plugin
 * classes are only classloaded when the Java plugin is available — callers must gate on
 * `PluginDetectors.java.isAvailable` before touching this object.
 */
internal object JavaMethodReferenceQuery {
    fun forMethod(target: PsiElement, scope: SearchScope): Query<PsiReference>? =
        (target as? PsiMethod)?.let { MethodReferencesSearch.search(it, scope, true) }
}

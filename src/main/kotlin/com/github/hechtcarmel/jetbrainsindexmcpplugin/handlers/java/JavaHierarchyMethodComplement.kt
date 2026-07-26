package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.Processor
import com.intellij.util.indexing.IdFilter

/**
 * Looks up every non-constructor method with a given short name via the Java short-names stub
 * index.
 *
 * The Go to Symbol popup stack (`DefaultSymbolNavigationContributor`) deduplicates method
 * hierarchies for unqualified patterns: an override whose super method is in scope and matches
 * the same pattern is suppressed, so `ide_find_symbol` would silently drop implementations of
 * same-named interface methods. This complement re-queries the same index the contributor used
 * so those suppressed overrides can be added back.
 *
 * Kept in its own class (same isolation pattern as [JavaHandlers]) so Java-plugin classes are
 * only classloaded when the Java plugin is actually available — callers must gate on
 * `PluginDetectors.java.isAvailable` before touching this object.
 */
internal object JavaHierarchyMethodComplement {

    fun methodsNamed(project: Project, name: String, scope: GlobalSearchScope): List<NavigationItem> {
        val out = mutableListOf<NavigationItem>()
        PsiShortNamesCache.getInstance(project).processMethodsWithName(
            name,
            Processor { method ->
                if (!method.isConstructor) {
                    (method as? NavigationItem)?.let(out::add)
                }
                true
            },
            scope,
            IdFilter.getProjectIdFilter(project, scope.isSearchInLibraries)
        )
        return out
    }
}

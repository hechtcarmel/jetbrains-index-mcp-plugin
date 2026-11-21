package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.Processor
import com.intellij.util.indexing.IdFilter

/**
 * Kotlin file facade classes: the JVM class that holds a file's top-level declarations
 * (`UtilsKt`, or the name given by `@file:JvmName("Util")`). Java code imports and calls them like
 * any class, but the Kotlin Go-to-Class contributor lists only `KtClassOrObject` declarations, so
 * `ide_find_class` cannot see them through `ChooseByNameContributor.CLASS_EP_NAME`.
 *
 * The facades are found through the Java short-names cache, which the Kotlin plugin feeds with its
 * light classes. Kept in its own class (same isolation pattern as [JavaHierarchyMethodComplement]):
 * callers must gate on `PluginDetectors.kotlin.isAvailable` (Kotlin implies Java) first.
 */
internal object KotlinFileFacadeSearch {
    private const val MAX_NAMES = 1000

    data class Facade(val name: String, val qualifiedName: String?, val file: PsiFile, val lightClass: PsiClass)

    fun facades(project: Project, scope: GlobalSearchScope, nameFilter: (String) -> Boolean, limit: Int): List<Facade> {
        if (limit <= 0) return emptyList()
        val cache = PsiShortNamesCache.getInstance(project)
        val names = LinkedHashSet<String>()
        cache.processAllClassNames(
            Processor { name ->
                ProgressManager.checkCanceled()
                if (nameFilter(name)) names.add(name)
                names.size < MAX_NAMES
            },
            scope,
            IdFilter.getProjectIdFilter(project, scope.isSearchInLibraries)
        )
        val out = mutableListOf<Facade>()
        for (name in names) {
            for (psiClass in cache.getClassesByName(name, scope)) {
                if (out.size >= limit) return out
                val file = facadeFile(psiClass) ?: continue
                out += Facade(name, psiClass.qualifiedName, file, psiClass)
            }
        }
        return out
    }

    /** A facade light class navigates to its Kotlin file rather than to a class declaration. */
    private fun facadeFile(psiClass: PsiClass): PsiFile? {
        val navigation = psiClass.navigationElement as? PsiFile ?: return null
        return navigation.takeIf { it.language.id == "kotlin" }
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FindSymbolParameters

/**
 * Optimized symbol search using IntelliJ's built-in infrastructure.
 *
 * This class leverages the same APIs that power IntelliJ's "Go to Symbol" dialog (Ctrl+Alt+Shift+N),
 * providing optimized search with caching, word index, and prefix matching.
 *
 * ## Performance Characteristics
 *
 * - Uses [DefaultChooseByNameItemProvider] which has internal optimizations
 * - Leverages registered [ChooseByNameContributor]s for all languages
 * - Uses [MinusculeMatcher] for CamelCase, substring, and typo-tolerant matching
 * - Early termination when limit is reached
 *
 * ## Usage
 *
 * ```kotlin
 * val results = OptimizedSymbolSearch.search(project, "UserService", scope, limit = 25)
 * ```
 */
object OptimizedSymbolSearch {

    private val LOG = logger<OptimizedSymbolSearch>()

    /**
     * Search for symbols using the optimized platform infrastructure.
     *
     * @param project The project to search in
     * @param pattern The search pattern (supports CamelCase, substring, and typo-tolerant matching)
     * @param scope The search scope (project only or including libraries)
     * @param limit Maximum number of results to return
     * @param languageFilter Optional filter to restrict results to specific languages
     * @return List of matching symbols
     */
    fun search(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int,
        languageFilter: Set<String>? = null
    ): List<SymbolData> {
        if (pattern.isBlank()) return emptyList()

        LOG.debug("Searching for symbols matching '$pattern' (limit=$limit, filter=$languageFilter)")

        val results = mutableListOf<SymbolData>()
        val seen = mutableSetOf<String>() // Deduplication key: file:line:name
        val matcher = createMatcher(pattern)

        // Strategy 1: Use ChooseByNameContributor extension points (most reliable)
        try {
            searchUsingContributors(project, pattern, scope, limit, languageFilter, matcher, results, seen)
        } catch (e: Exception) {
            LOG.debug("Contributor-based search failed: ${e.message}")
        }

        // Sort by match quality
        val sortedResults = results.sortedWith(compareBy(
            { !it.name.equals(pattern, ignoreCase = true) }, // Exact matches first
            { -matcher.matchingDegree(it.name) } // Then by match quality
        ))

        LOG.debug("Found ${sortedResults.size} symbols")
        return sortedResults.take(limit)
    }

    /**
     * Search using platform ChooseByNameContributor extension points.
     *
     * This is the same infrastructure used by IntelliJ's "Go to Symbol" dialog.
     */
    private fun searchUsingContributors(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int,
        languageFilter: Set<String>?,
        matcher: MinusculeMatcher,
        results: MutableList<SymbolData>,
        seen: MutableSet<String>
    ) {
        val contributors = ChooseByNameContributor.SYMBOL_EP_NAME.extensionList

        for (contributor in contributors) {
            if (results.size >= limit) break

            try {
                processContributor(contributor, project, pattern, scope, limit, languageFilter, matcher, results, seen)
            } catch (e: Exception) {
                LOG.debug("Error processing contributor ${contributor.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun processContributor(
        contributor: ChooseByNameContributor,
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int,
        languageFilter: Set<String>?,
        matcher: MinusculeMatcher,
        results: MutableList<SymbolData>,
        seen: MutableSet<String>
    ) {
        if (contributor is ChooseByNameContributorEx) {
            // Modern API with Processor pattern - streaming, memory efficient
            val matchingNames = mutableListOf<String>()

            contributor.processNames(
                { name ->
                    if (matcher.matches(name)) {
                        matchingNames.add(name)
                    }
                    matchingNames.size < limit * 3 // Collect extra for filtering
                },
                scope,
                null
            )

            for (name in matchingNames) {
                if (results.size >= limit) break

                val params = FindSymbolParameters.wrap(pattern, scope)
                contributor.processElementsWithName(
                    name,
                    { item ->
                        if (results.size >= limit) return@processElementsWithName false

                        val symbolData = convertToSymbolData(item, project, languageFilter)
                        if (symbolData != null) {
                            val key = "${symbolData.file}:${symbolData.line}:${symbolData.name}"
                            if (key !in seen) {
                                seen.add(key)
                                results.add(symbolData)
                            }
                        }
                        true
                    },
                    params
                )
            }
        } else {
            // Legacy API - load all names then filter
            val names = contributor.getNames(project, true)
            val matchingNames = names.filter { matcher.matches(it) }

            for (name in matchingNames) {
                if (results.size >= limit) break

                val items = contributor.getItemsByName(name, pattern, project, true)
                for (item in items) {
                    if (results.size >= limit) break

                    val symbolData = convertToSymbolData(item, project, languageFilter)
                    if (symbolData != null) {
                        val key = "${symbolData.file}:${symbolData.line}:${symbolData.name}"
                        if (key !in seen) {
                            seen.add(key)
                            results.add(symbolData)
                        }
                    }
                }
            }
        }
    }

    /**
     * Convert a NavigationItem or PsiElement to SymbolData.
     */
    private fun convertToSymbolData(
        item: NavigationItem,
        project: Project,
        languageFilter: Set<String>?
    ): SymbolData? {
        val element = when (item) {
            is PsiElement -> item
            else -> {
                // Try to extract PsiElement from NavigationItem
                try {
                    val method = item.javaClass.getMethod("getElement")
                    method.invoke(item) as? PsiElement
                } catch (e: Exception) {
                    null
                }
            }
        } ?: return null

        val language = getLanguageName(element)

        // Apply language filter if specified
        if (languageFilter != null && language !in languageFilter) {
            return null
        }

        val file = element.containingFile?.virtualFile ?: return null
        val basePath = project.basePath ?: ""
        val relativePath = file.path.removePrefix(basePath).removePrefix("/")

        val name = when (element) {
            is PsiNamedElement -> element.name
            else -> {
                try {
                    val method = element.javaClass.getMethod("getName")
                    method.invoke(element) as? String
                } catch (e: Exception) {
                    null
                }
            }
        } ?: return null

        val qualifiedName = try {
            val method = element.javaClass.getMethod("getQualifiedName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            null
        }

        val line = getLineNumber(project, element) ?: 1
        val kind = determineKind(element)
        val containerName = getContainerName(element)

        return SymbolData(
            name = name,
            qualifiedName = qualifiedName,
            kind = kind,
            file = relativePath,
            line = line,
            containerName = containerName,
            language = language
        )
    }

    private fun getLanguageName(element: PsiElement): String {
        return when (element.language.id) {
            "JAVA" -> "Java"
            "kotlin" -> "Kotlin"
            "Python" -> "Python"
            "JavaScript", "ECMAScript 6", "JSX Harmony" -> "JavaScript"
            "TypeScript", "TypeScript JSX" -> "TypeScript"
            "go" -> "Go"
            "PHP" -> "PHP"
            "Rust" -> "Rust"
            else -> element.language.displayName
        }
    }

    private fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    private fun determineKind(element: PsiElement): String {
        val className = element.javaClass.simpleName.lowercase()
        return when {
            // Rust types
            className.contains("structitem") -> "STRUCT"
            className.contains("traititem") -> "TRAIT"
            className.contains("enumitem") -> "ENUM"
            className.contains("implitem") -> "IMPL"
            className.contains("moditem") -> "MODULE"
            // Common types
            className.contains("class") -> "CLASS"
            className.contains("interface") -> "INTERFACE"
            className.contains("enum") -> "ENUM"
            className.contains("struct") -> "STRUCT"
            className.contains("trait") -> "TRAIT"
            className.contains("method") -> "METHOD"
            className.contains("function") -> "FUNCTION"
            className.contains("field") -> "FIELD"
            className.contains("variable") -> "VARIABLE"
            className.contains("property") -> "PROPERTY"
            className.contains("constant") -> "CONSTANT"
            else -> "SYMBOL"
        }
    }

    private fun getContainerName(element: PsiElement): String? {
        return try {
            // Try to find containing class/type
            var parent = element.parent
            while (parent != null) {
                val parentClassName = parent.javaClass.simpleName.lowercase()
                if (parentClassName.contains("class") || parentClassName.contains("type")) {
                    val nameMethod = parent.javaClass.getMethod("getName")
                    return nameMethod.invoke(parent) as? String
                }
                parent = parent.parent
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun createMatcher(pattern: String): MinusculeMatcher {
        return NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    }
}

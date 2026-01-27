package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for searching classes and interfaces by name.
 *
 * Uses CLASS_EP_NAME index for class-only lookups.
 *
 * Equivalent to IntelliJ's "Go to Class" (Ctrl+N / Cmd+O).
 */
@Suppress("unused")
class FindClassTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<FindClassTool>()
        private const val DEFAULT_LIMIT = 25
        private const val MAX_LIMIT = 100
    }

    override val name = ToolNames.FIND_CLASS

    override val description = """
        Search for classes and interfaces by name. Faster than ide_find_symbol when you only need classes.

        Matching: camelCase ("USvc" → "UserService"), substring ("Service" → "UserService"), and wildcard ("User*Impl" → "UserServiceImpl").

        Returns: matching classes with qualified names, file paths, line numbers, and kind (class/interface/enum).

        Parameters: query (required), includeLibraries (optional, default: false), limit (optional, default: 25, max: 100).

        Example: {"query": "UserService"} or {"query": "U*Impl"} or {"query": "USvc", "includeLibraries": true}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject(ParamNames.QUERY) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Search pattern. Supports substring and camelCase matching.")
            }
            putJsonObject(ParamNames.INCLUDE_LIBRARIES) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_BOOLEAN)
                put(SchemaConstants.DESCRIPTION, "Include classes from library dependencies. Default: false.")
            }
            putJsonObject(ParamNames.LIMIT) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum results to return. Default: 25, Max: 100.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.QUERY))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val query = arguments[ParamNames.QUERY]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.QUERY}")
        val includeLibraries = arguments[ParamNames.INCLUDE_LIBRARIES]?.jsonPrimitive?.boolean ?: false
        val limit = (arguments[ParamNames.LIMIT]?.jsonPrimitive?.int ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            val scope = if (includeLibraries) {
                GlobalSearchScope.allScope(project)
            } else {
                GlobalSearchScope.projectScope(project)
            }

            val matcher = createMatcher(query)
            val classes = searchClasses(project, query, scope, limit, matcher)

            val sortedClasses = classes
                .distinctBy { "${it.file}:${it.line}:${it.name}" }
                .sortedByDescending { matcher.matchingDegree(it.name) }
                .take(limit)

            createJsonResult(FindClassResult(
                classes = sortedClasses,
                totalCount = sortedClasses.size,
                query = query
            ))
        }
    }

    /**
     * Search for classes using CLASS_EP_NAME index.
     */
    private fun searchClasses(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int,
        matcher: MinusculeMatcher
    ): List<SymbolMatch> {
        val results = mutableListOf<SymbolMatch>()
        val seen = mutableSetOf<String>()

        // Use CLASS_EP_NAME for class-only search
        val contributors = ChooseByNameContributor.CLASS_EP_NAME.extensionList

        for (contributor in contributors) {
            if (results.size >= limit) break

            try {
                processContributor(contributor, project, pattern, scope, limit, matcher, results, seen)
            } catch (e: Exception) {
                LOG.debug("Contributor ${contributor.javaClass.simpleName} failed for pattern '$pattern'", e)
            }
        }

        return results
    }

    private fun processContributor(
        contributor: ChooseByNameContributor,
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int,
        matcher: MinusculeMatcher,
        results: MutableList<SymbolMatch>,
        seen: MutableSet<String>
    ) {
        if (contributor is ChooseByNameContributorEx) {
            // Modern API with Processor pattern
            val matchingNames = mutableListOf<String>()

            contributor.processNames(
                { name ->
                    if (matcher.matches(name)) {
                        matchingNames.add(name)
                    }
                    matchingNames.size < limit * 3
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

                        val symbolMatch = convertToSymbolMatch(item, project)
                        if (symbolMatch != null) {
                            val key = "${symbolMatch.file}:${symbolMatch.line}:${symbolMatch.name}"
                            if (key !in seen) {
                                seen.add(key)
                                results.add(symbolMatch)
                            }
                        }
                        true
                    },
                    params
                )
            }
        } else {
            // Legacy API
            val names = contributor.getNames(project, true)
            val matchingNames = names.filter { matcher.matches(it) }

            for (name in matchingNames) {
                if (results.size >= limit) break

                val items = contributor.getItemsByName(name, pattern, project, true)
                for (item in items) {
                    if (results.size >= limit) break

                    val symbolMatch = convertToSymbolMatch(item, project)
                    if (symbolMatch != null) {
                        val key = "${symbolMatch.file}:${symbolMatch.line}:${symbolMatch.name}"
                        if (key !in seen) {
                            seen.add(key)
                            results.add(symbolMatch)
                        }
                    }
                }
            }
        }
    }

    private fun convertToSymbolMatch(item: NavigationItem, project: Project): SymbolMatch? {
        val element = when (item) {
            is PsiElement -> item
            else -> {
                try {
                    val method = item.javaClass.getMethod("getElement")
                    method.invoke(item) as? PsiElement
                } catch (_: Exception) {
                    null
                }
            }
        } ?: return null

        val file = element.containingFile?.virtualFile ?: return null
        val basePath = project.basePath ?: ""
        val relativePath = file.path.removePrefix(basePath).removePrefix("/")

        val name = when (element) {
            is PsiNamedElement -> element.name
            else -> {
                try {
                    val method = element.javaClass.getMethod("getName")
                    method.invoke(element) as? String
                } catch (_: Exception) {
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
        val language = getLanguageName(element)

        return SymbolMatch(
            name = name,
            qualifiedName = qualifiedName,
            kind = kind,
            file = relativePath,
            line = line,
            containerName = null,
            language = language
        )
    }

    private fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    private fun determineKind(element: PsiElement): String {
        val className = element.javaClass.simpleName.lowercase()
        return when {
            className.contains("interface") -> "INTERFACE"
            className.contains("enum") -> "ENUM"
            className.contains("class") -> "CLASS"
            className.contains("struct") -> "STRUCT"
            className.contains("trait") -> "TRAIT"
            else -> "CLASS"
        }
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

    private fun createMatcher(pattern: String): MinusculeMatcher {
        return NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    }
}

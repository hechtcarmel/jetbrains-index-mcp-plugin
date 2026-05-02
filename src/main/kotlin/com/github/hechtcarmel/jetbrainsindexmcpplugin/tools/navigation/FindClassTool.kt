package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createMatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createNameFilter
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FindSymbolParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
        private const val DEFAULT_PAGE_SIZE = 25
        private const val MAX_PAGE_SIZE = PaginationService.MAX_PAGE_SIZE
        // processNames may emit names from broader scope (including libraries/JDK) even when
        // we search in project scope. Short/common patterns like "Tool" would fill a small buffer
        // with library class names (e.g., "Toolkit", "ToolProvider") before reaching project classes.
        // Use a generous limit so project classes are always collected.
        private const val MAX_NAME_COLLECTION_LIMIT = 5000
    }

    override val name = ToolNames.FIND_CLASS

    override val description = """
        Search for classes and interfaces by name. Faster than ide_find_symbol when you only need classes.

        Matching: camelCase ("USvc" → "UserService"), substring ("Service" → "UserService"), and wildcard ("User*Impl" → "UserServiceImpl").

        Returns: matching classes with qualified names, file paths, line numbers, and kind (class/interface/enum).

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.
        Parameters: query (required for fresh search), scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files), pageSize (optional, default: 25, max: 500), cursor (for pagination, replaces search params; project_path may still be required).

        Example: {"query": "UserService"} or {"query": "U*Impl"} or {"query": "USvc", "scope": "project_and_libraries"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.QUERY, "Search pattern. Supports substring and camelCase matching. Required for fresh search, ignored when cursor is provided.")
        .scopeProperty("Search scope. Default: project_files.")
        .stringProperty(ParamNames.LANGUAGE, "Filter results by language (e.g., \"Kotlin\", \"Java\", \"Python\"). Case-insensitive. Optional.")
        .enumProperty(ParamNames.MATCH_MODE, "How to match the query. Default: \"substring\".", listOf("substring", "prefix", "exact"))
        .intProperty(ParamNames.LIMIT, "Maximum results per page (deprecated, use pageSize). Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val cursor = arguments["cursor"]?.jsonPrimitive?.content
        if (cursor != null) {
            val pageSize = resolveExplicitPageSize(arguments, aliases = arrayOf("limit"))
            return buildPaginatedResult<SymbolMatch, FindClassResult>(getPageFromCache(cursor, pageSize, project)) { items, page ->
                FindClassResult(
                    classes = items,
                    totalCount = page.totalCollected,
                    query = page.metadata["query"] ?: "",
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                    totalCollected = page.totalCollected,
                    offset = page.offset,
                    pageSize = page.pageSize,
                    stale = page.stale
                )
            }
        }

        val query = arguments[ParamNames.QUERY]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.QUERY}")
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        val languageFilter = arguments[ParamNames.LANGUAGE]?.jsonPrimitive?.content
        val matchMode = arguments[ParamNames.MATCH_MODE]?.jsonPrimitive?.content ?: "substring"
        val pageSize = resolvePageSize(arguments, DEFAULT_PAGE_SIZE, aliases = arrayOf("limit"))
        val collectLimit = maxOf(PaginationService.DEFAULT_OVERCOLLECT, pageSize)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        requireSmartMode(project)

        val cursorToken = suspendingReadAction {
            val searchScope = resolveSearchScope(project, scope)

            val matcher = createMatcher(query, matchMode)
            val nameFilter = createNameFilter(query, matchMode, matcher)
            val classes = searchClasses(project, query, searchScope, scope, collectLimit, nameFilter, matcher, languageFilter)

            val sortedClasses = classes
                .distinctBy { "${it.file}:${it.line}:${it.column}:${it.name}" }
                .sortedByDescending { matcher.matchingDegree(it.name) }

            val searchExtender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { seenKeys, limit ->
                suspendingReadAction {
                    extendSearchClasses(project, query, scope, matchMode, languageFilter, seenKeys, limit)
                }
            }

            val serializedResults = sortedClasses.map { cls ->
                PaginationService.SerializedResult(
                    key = "${cls.file}:${cls.line}:${cls.column}:${cls.name}",
                    data = json.encodeToJsonElement(cls)
                )
            }

            val paginationService = ApplicationManager.getApplication().getService(PaginationService::class.java)
            paginationService.createCursor(
                toolName = name,
                results = serializedResults,
                seenKeys = serializedResults.map { it.key }.toSet(),
                searchExtender = searchExtender,
                psiModCount = PsiModificationTracker.getInstance(project).modificationCount,
                projectBasePath = ProjectResolver.normalizePath(project.basePath ?: ""),
                metadata = mapOf("query" to query)
            )
        }

        return buildPaginatedResult<SymbolMatch, FindClassResult>(getPageFromCache(cursorToken, pageSize, project)) { items, page ->
            FindClassResult(
                classes = items,
                totalCount = page.totalCollected,
                query = page.metadata["query"] ?: "",
                nextCursor = page.nextCursor,
                hasMore = page.hasMore,
                totalCollected = page.totalCollected,
                offset = page.offset,
                pageSize = page.pageSize,
                stale = page.stale
            )
        }
    }

    /**
     * Re-executes the search to collect more results beyond the initial cache.
     * This re-scans from the beginning, skipping already-seen keys — O(total_results) per extension.
     * This is unavoidable: IntelliJ's search APIs (ReferencesSearch, PsiSearchHelper, etc.)
     * don't support offset-based iteration or resumption.
     */
    private fun extendSearchClasses(
        project: Project,
        query: String,
        scope: BuiltInSearchScope,
        matchMode: String,
        languageFilter: String?,
        seenKeys: Set<String>,
        limit: Int
    ): List<PaginationService.SerializedResult> {
        val searchScope = resolveSearchScope(project, scope)
        val matcher = createMatcher(query, matchMode)
        val nameFilter = createNameFilter(query, matchMode, matcher)
        val classes = searchClasses(project, query, searchScope, scope, limit + seenKeys.size, nameFilter, matcher, languageFilter)

        return classes
            .distinctBy { "${it.file}:${it.line}:${it.column}:${it.name}" }
            .sortedByDescending { matcher.matchingDegree(it.name) }
            .filter { cls -> "${cls.file}:${cls.line}:${cls.column}:${cls.name}" !in seenKeys }
            .take(limit)
            .map { cls ->
                PaginationService.SerializedResult(
                    key = "${cls.file}:${cls.line}:${cls.column}:${cls.name}",
                    data = json.encodeToJsonElement(cls)
                )
            }
    }

    /**
     * Search for classes using CLASS_EP_NAME index.
     */
    private fun searchClasses(
        project: Project,
        pattern: String,
        searchScope: GlobalSearchScope,
        scope: BuiltInSearchScope,
        limit: Int,
        nameFilter: (String) -> Boolean,
        matcher: MinusculeMatcher,
        languageFilter: String? = null
    ): List<SymbolMatch> {
        val results = mutableListOf<SymbolMatch>()
        val seen = mutableSetOf<String>()

        // Use CLASS_EP_NAME for class-only search
        val contributors = ChooseByNameContributor.CLASS_EP_NAME.extensionList

        for (contributor in contributors) {
            if (results.size >= limit) break

            try {
                processContributor(contributor, project, pattern, searchScope, scope, limit, nameFilter, matcher, results, seen, languageFilter)
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
        searchScope: GlobalSearchScope,
        scope: BuiltInSearchScope,
        limit: Int,
        nameFilter: (String) -> Boolean,
        matcher: MinusculeMatcher,
        results: MutableList<SymbolMatch>,
        seen: MutableSet<String>,
        languageFilter: String? = null
    ) {
        if (contributor is ChooseByNameContributorEx) {
            // Modern API with Processor pattern
            val matchingNames = mutableListOf<String>()

            contributor.processNames(
                { name ->
                    if (nameFilter(name)) {
                        matchingNames.add(name)
                    }
                    matchingNames.size < MAX_NAME_COLLECTION_LIMIT
                },
                searchScope,
                null
            )

            for (name in matchingNames) {
                if (results.size >= limit) break

                val params = FindSymbolParameters.wrap(pattern, searchScope)
                contributor.processElementsWithName(
                    name,
                    { item ->
                        if (results.size >= limit) return@processElementsWithName false

                        val symbolMatch = convertToSymbolMatch(item, project, searchScope)
                        if (symbolMatch != null &&
                            (languageFilter == null || symbolMatch.language.equals(languageFilter, ignoreCase = true))) {
                            val key = "${symbolMatch.file}:${symbolMatch.line}:${symbolMatch.column}:${symbolMatch.name}"
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
            val names = contributor.getNames(project, scope == BuiltInSearchScope.PROJECT_AND_LIBRARIES)
            val matchingNames = names.filter { nameFilter(it) }

            for (name in matchingNames) {
                if (results.size >= limit) break

                val items = contributor.getItemsByName(name, pattern, project, scope == BuiltInSearchScope.PROJECT_AND_LIBRARIES)
                for (item in items) {
                    if (results.size >= limit) break

                    val symbolMatch = convertToSymbolMatch(item, project, searchScope)
                    if (symbolMatch != null &&
                        (languageFilter == null || symbolMatch.language.equals(languageFilter, ignoreCase = true))) {
                        val key = "${symbolMatch.file}:${symbolMatch.line}:${symbolMatch.column}:${symbolMatch.name}"
                        if (key !in seen) {
                            seen.add(key)
                            results.add(symbolMatch)
                        }
                    }
                }
            }
        }
    }

    private fun resolveSearchScope(project: Project, scope: BuiltInSearchScope): GlobalSearchScope {
        return BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
    }

    private fun rawScopeValue(scopeElement: JsonElement?): String = when (scopeElement) {
        null -> ""
        is JsonPrimitive -> scopeElement.content
        else -> scopeElement.toString()
    }

    private fun createInvalidScopeError(provided: String): ToolCallResult =
        createStructuredErrorResult(buildJsonObject {
            put("error", JsonPrimitive("invalid_scope"))
            put("parameter", JsonPrimitive(ParamNames.SCOPE))
            put("provided", JsonPrimitive(provided))
            put("supportedValues", buildJsonArray {
                BuiltInSearchScope.supportedWireValues().forEach { add(JsonPrimitive(it)) }
            })
        })

    private fun convertToSymbolMatch(item: NavigationItem, project: Project, scope: GlobalSearchScope): SymbolMatch? {
        val element = extractPsiElement(item) ?: return null
        val targetElement = element.navigationElement ?: element

        val file = targetElement.containingFile?.virtualFile ?: return null
        if (!scope.contains(file)) return null
        val relativePath = ProjectUtils.getToolFilePath(project, file)

        val name = when (targetElement) {
            is PsiNamedElement -> targetElement.name
            else -> {
                try {
                    val method = targetElement.javaClass.getMethod("getName")
                    method.invoke(targetElement) as? String
                } catch (_: Exception) {
                    null
                }
            }
        } ?: return null

        val qualifiedName = try {
            val method = targetElement.javaClass.getMethod("getQualifiedName")
            method.invoke(targetElement) as? String
        } catch (e: Exception) {
            null
        }

        val line = getLineNumber(project, targetElement) ?: 1
        val kind = determineKind(targetElement)
        val language = getLanguageName(targetElement)

        return SymbolMatch(
            name = name,
            qualifiedName = qualifiedName,
            kind = kind,
            file = relativePath,
            line = line,
            column = getColumnNumber(project, targetElement) ?: 1,
            containerName = null,
            language = language
        )
    }

    private fun extractPsiElement(item: NavigationItem): PsiElement? {
        return when (item) {
            is PsiElement -> item
            else -> {
                try {
                    val method = item.javaClass.getMethod("getElement")
                    method.invoke(item) as? PsiElement
                } catch (_: Exception) {
                    null
                }
            }
        }
    }


    private fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    private fun getColumnNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val lineNumber = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(lineNumber) + 1
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

    // Provided by SearchMatchUtils — this local alias preserves call-site compatibility
    private fun createMatcher(pattern: String, matchMode: String = "substring"): MinusculeMatcher =
        com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createMatcher(pattern, matchMode)

    // Provided by SearchMatchUtils
    private fun createNameFilter(pattern: String, matchMode: String, matcher: MinusculeMatcher): (String) -> Boolean =
        com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createNameFilter(pattern, matchMode, matcher)
}

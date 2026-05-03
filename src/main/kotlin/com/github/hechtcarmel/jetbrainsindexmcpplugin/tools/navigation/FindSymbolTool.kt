package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SymbolData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool for searching code symbols by name.
 *
 * Works in any supported JetBrains IDE. Delegates to the headless Go to Symbol popup stack via
 * [OptimizedSymbolSearch], so matching and ranking follow IntelliJ's own Go to Symbol popup.
 */
class FindSymbolTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_PAGE_SIZE = 25
        private const val MAX_PAGE_SIZE = PaginationService.MAX_PAGE_SIZE
    }

    override val name = ToolNames.FIND_SYMBOL

    override val description = """
        Search for symbols by name across the codebase. Use when you know a symbol name but not its location—finds classes, methods, fields, and functions. Faster and more accurate than grep for code navigation.

        Works in any supported JetBrains IDE. Result quality is best for Java, Kotlin, Python, JavaScript, TypeScript, Go, PHP, and Rust; other IDE-supplied languages (Ruby, C/C++, SQL, …) are also returned with their IDE-provided metadata.

        Matching and ranking follow IntelliJ's Go to Symbol popup, including qualified queries like "BasicSolver.run".

        Returns: matching symbols with qualified names, file paths, line/column numbers, and kind.

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.
        Parameters: query (required for fresh search), scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files), language (optional case-insensitive filter, e.g. "Kotlin"), pageSize (optional, default: 25, max: 500), cursor (for pagination, replaces search params; project_path may still be required).

        Example: {"query": "UserService"} or {"query": "find_user", "scope": "project_and_libraries"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.QUERY, "Search pattern. Matching follows IntelliJ's Go to Symbol popup, including qualified queries. Required for fresh search, ignored when cursor is provided.")
        .scopeProperty("Search scope. Default: project_files.")
        .stringProperty(ParamNames.LANGUAGE, "Filter results by language (e.g., \"Kotlin\", \"Java\", \"Python\"). Case-insensitive. Optional.")
        .intProperty(ParamNames.LIMIT, "Maximum results per page (deprecated, use pageSize). Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val cursor = optionalStringArg(arguments, ParamNames.CURSOR)
        if (cursor != null) {
            val pageSize = resolveExplicitPageSize(arguments, aliases = arrayOf("limit"))
            return buildPaginatedResult<SymbolMatch, FindSymbolResult>(getPageFromCache(cursor, pageSize, project)) { items, page ->
                FindSymbolResult(
                    symbols = items,
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
        val pageSize = resolvePageSize(arguments, DEFAULT_PAGE_SIZE, aliases = arrayOf("limit"))
        val collectLimit = maxOf(PaginationService.DEFAULT_OVERCOLLECT, pageSize)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        requireSmartMode(project)

        val token = suspendingReadAction {
            val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
            val nativeLanguageFilter = languageFilter?.takeIf { it.isNotBlank() }?.let { setOf(it) }
            val symbols = OptimizedSymbolSearch.search(
                project = project,
                pattern = query,
                scope = searchScope,
                limit = collectLimit,
                languageFilter = nativeLanguageFilter
            )

            val matches = symbols.map { it.toSymbolMatch() }

            val searchExtender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { seenKeys, limit ->
                suspendingReadAction {
                    extendSearchSymbols(project, query, scope, languageFilter, seenKeys, limit)
                }
            }

            val serializedResults = matches.map { sym ->
                PaginationService.SerializedResult(
                    key = sym.paginationKey(),
                    data = json.encodeToJsonElement(sym)
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

        return buildPaginatedResult<SymbolMatch, FindSymbolResult>(getPageFromCache(token, pageSize, project)) { items, page ->
            FindSymbolResult(
                symbols = items,
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
     * Re-executes the popup-backed search to collect more results beyond the initial cache.
     * Skips already-seen keys in the caller's cache — O(total_results) per extension because
     * the popup APIs don't support offset-based iteration.
     */
    private fun extendSearchSymbols(
        project: Project,
        query: String,
        scope: BuiltInSearchScope,
        languageFilter: String?,
        seenKeys: Set<String>,
        limit: Int
    ): List<PaginationService.SerializedResult> {
        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val nativeLanguageFilter = languageFilter?.takeIf { it.isNotBlank() }?.let { setOf(it) }
        val symbols = OptimizedSymbolSearch.search(
            project = project,
            pattern = query,
            scope = searchScope,
            limit = limit + seenKeys.size,
            languageFilter = nativeLanguageFilter
        )

        return symbols.asSequence()
            .map { it.toSymbolMatch() }
            .filter { sym -> sym.paginationKey() !in seenKeys }
            .take(limit)
            .map { sym ->
                PaginationService.SerializedResult(
                    key = sym.paginationKey(),
                    data = json.encodeToJsonElement(sym)
                )
            }
            .toList()
    }

    private fun SymbolData.toSymbolMatch(): SymbolMatch = SymbolMatch(
        name = name,
        qualifiedName = qualifiedName,
        kind = kind,
        file = file,
        line = line,
        column = column,
        containerName = containerName,
        language = language
    )

    private fun SymbolMatch.paginationKey(): String = "$file:$line:$column:$name"


}

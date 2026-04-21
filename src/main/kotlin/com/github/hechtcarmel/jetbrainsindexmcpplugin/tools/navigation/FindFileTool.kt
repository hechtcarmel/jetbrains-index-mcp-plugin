package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
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
 * Tool for searching files by name.
 *
 * Uses FILE_EP_NAME index for file lookups.
 *
 * Equivalent to IntelliJ's "Go to File" (Ctrl+Shift+N / Cmd+Shift+O).
 */
@Suppress("unused")
class FindFileTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<FindFileTool>()
        private const val DEFAULT_PAGE_SIZE = 25
        private const val MAX_PAGE_SIZE = PaginationService.MAX_PAGE_SIZE
    }

    override val name = ToolNames.FIND_FILE

    override val description = """
        Search for files by name. Very fast file lookup using IDE's file index.

        Matching: camelCase ("USJ" → "UserService.java"), substring ("User" → "UserService.java"), and wildcard ("*Test.kt").

        Returns: matching files with name, path, and containing directory.

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.
        Parameters: query (required for fresh search), scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files), pageSize (optional, default: 25, max: 500), cursor (for pagination, replaces search params; project_path may still be required).

        Example: {"query": "UserService.java"} or {"query": "build.gradle"} or {"query": "BG"} (matches build.gradle)
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.QUERY, "File name pattern. Supports substring and fuzzy matching. Required for fresh search, ignored when cursor is provided.")
        .scopeProperty("Search scope. Default: project_files.")
        .intProperty(ParamNames.LIMIT, "Maximum results per page (deprecated, use pageSize). Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val cursor = arguments["cursor"]?.jsonPrimitive?.content
        if (cursor != null) {
            val pageSize = resolveExplicitPageSize(arguments, aliases = arrayOf("limit"))
            return buildPaginatedResult<FileMatch, FindFileResult>(getPageFromCache(cursor, pageSize, project)) { items, page ->
                FindFileResult(
                    files = items,
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
        val pageSize = resolvePageSize(arguments, DEFAULT_PAGE_SIZE, aliases = arrayOf("limit"))
        val collectLimit = maxOf(PaginationService.DEFAULT_OVERCOLLECT, pageSize)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        requireSmartMode(project)

        val cursorToken = suspendingReadAction {
            val searchScope = resolveSearchScope(project, scope)
            val matcher = createMatcher(query)
            val files = searchFiles(project, query, searchScope, scope, collectLimit, matcher)

            val sortedFiles = files
                .distinctBy { it.path }
                .sortedByDescending { matcher.matchingDegree(it.name) }

            val searchExtender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { seenKeys, limit ->
                suspendingReadAction {
                    extendSearchFiles(project, query, scope, seenKeys, limit)
                }
            }

            val serializedResults = sortedFiles.map { file ->
                PaginationService.SerializedResult(
                    key = file.path,
                    data = json.encodeToJsonElement(file)
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

        return buildPaginatedResult<FileMatch, FindFileResult>(getPageFromCache(cursorToken, pageSize, project)) { items, page ->
            FindFileResult(
                files = items,
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
    private fun extendSearchFiles(
        project: Project,
        query: String,
        scope: BuiltInSearchScope,
        seenKeys: Set<String>,
        limit: Int
    ): List<PaginationService.SerializedResult> {
        val searchScope = resolveSearchScope(project, scope)
        val matcher = createMatcher(query)
        val files = searchFiles(project, query, searchScope, scope, limit + seenKeys.size, matcher)

        return files
            .distinctBy { it.path }
            .sortedByDescending { matcher.matchingDegree(it.name) }
            .filter { it.path !in seenKeys }
            .take(limit)
            .map { file ->
                PaginationService.SerializedResult(
                    key = file.path,
                    data = json.encodeToJsonElement(file)
                )
            }
    }

    /**
     * Search for files using FILE_EP_NAME index (optimized for file lookups).
     */
    private fun searchFiles(
        project: Project,
        pattern: String,
        searchScope: GlobalSearchScope,
        scope: BuiltInSearchScope,
        limit: Int,
        matcher: MinusculeMatcher
    ): List<FileMatch> {
        val results = mutableListOf<FileMatch>()
        val seen = mutableSetOf<String>()

        // Use FILE_EP_NAME for file search
        val contributors = ChooseByNameContributor.FILE_EP_NAME.extensionList

        for (contributor in contributors) {
            if (results.size >= limit) break

            try {
                processContributor(contributor, project, pattern, searchScope, scope, limit, matcher, results, seen)
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
        matcher: MinusculeMatcher,
        results: MutableList<FileMatch>,
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

                        val fileMatch = convertToFileMatch(item, project, searchScope)
                        if (fileMatch != null) {
                            val key = fileMatch.path
                            if (key !in seen) {
                                seen.add(key)
                                results.add(fileMatch)
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
            val matchingNames = names.filter { matcher.matches(it) }

            for (name in matchingNames) {
                if (results.size >= limit) break

                val items = contributor.getItemsByName(name, pattern, project, scope == BuiltInSearchScope.PROJECT_AND_LIBRARIES)
                for (item in items) {
                    if (results.size >= limit) break

                    val fileMatch = convertToFileMatch(item, project, searchScope)
                    if (fileMatch != null) {
                        val key = fileMatch.path
                        if (key !in seen) {
                            seen.add(key)
                            results.add(fileMatch)
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

    private fun convertToFileMatch(item: NavigationItem, project: Project, scope: GlobalSearchScope): FileMatch? {
        val virtualFile = extractVirtualFile(item) ?: return null
        if (!scope.contains(virtualFile)) return null

        val relativePath = ProjectUtils.getToolFilePath(project, virtualFile)
        val directory = virtualFile.parent?.let { ProjectUtils.getToolFilePath(project, it) } ?: ""

        return FileMatch(
            name = virtualFile.name,
            path = relativePath,
            directory = directory
        )
    }

    private fun extractVirtualFile(item: NavigationItem): VirtualFile? {
        return when (item) {
            is PsiFile -> item.virtualFile
            else -> {
                try {
                    val method = item.javaClass.getMethod("getElement")
                    val element = method.invoke(item)
                    if (element is PsiFile) {
                        element.virtualFile
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun createMatcher(pattern: String): MinusculeMatcher {
        return NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    }
}

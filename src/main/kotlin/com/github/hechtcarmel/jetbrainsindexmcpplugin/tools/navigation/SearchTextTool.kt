package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createFilteredScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TextMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Text search using IDE's word index.
 *
 * Uses a pre-built word index for exact word matches.
 *
 * Supports context filtering: search only in code, comments, or string literals.
 */
@Suppress("unused")
class SearchTextTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_PAGE_SIZE = 100
        private const val MAX_PAGE_SIZE = PaginationService.MAX_PAGE_SIZE
    }

    override val name = ToolNames.SEARCH_TEXT

    override val description = """
        Search for text using IDE's word index. Significantly faster than file scanning for exact word matches.

        Uses a pre-built word index for O(1) lookups instead of scanning all files.

        Context filtering: search only in code, comments, or string literals.

        Returns: matching locations with file, line, column, context snippet, and context type.

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.
        Parameters: query (required for fresh search), context (optional: "code", "comments", "strings", "all"), caseSensitive (optional, default: true), pageSize (optional, default: 100, max: 500), cursor (for pagination, replaces search params; project_path may still be required).

        Example: {"query": "ConfigManager"} or {"query": "TODO", "context": "comments"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.QUERY, "Exact word to search for (not a pattern/regex). Required for fresh search, ignored when cursor is provided.")
        .enumProperty(ParamNames.CONTEXT, "Where to search: \"code\", \"comments\", \"strings\", \"all\". Default: \"all\".", listOf("code", "comments", "strings", "all"))
        .booleanProperty(ParamNames.CASE_SENSITIVE, "Case sensitive search. Default: true.")
        .intProperty(ParamNames.LIMIT, "Maximum results per page (deprecated, use pageSize). Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val cursor = arguments["cursor"]?.jsonPrimitive?.content
        if (cursor != null) {
            val pageSize = resolveExplicitPageSize(arguments, aliases = arrayOf("limit"))
            return buildPaginatedResult<TextMatch, SearchTextResult>(getPageFromCache(cursor, pageSize, project)) { items, page ->
                SearchTextResult(
                    matches = items,
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
        val contextStr = arguments[ParamNames.CONTEXT]?.jsonPrimitive?.content ?: "all"
        val caseSensitive = arguments[ParamNames.CASE_SENSITIVE]?.jsonPrimitive?.boolean ?: true
        val pageSize = resolvePageSize(arguments, DEFAULT_PAGE_SIZE, aliases = arrayOf("limit"))
        val collectLimit = maxOf(PaginationService.DEFAULT_OVERCOLLECT, pageSize)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        val searchContext = parseSearchContext(contextStr)

        requireSmartMode(project)

        val cursorToken = suspendingReadAction {
            val scope = createFilteredScope(project)
            val matches = searchText(project, query, scope, searchContext, caseSensitive, collectLimit)

            val searchExtender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = extender@{ seenKeys, limit ->
                suspendingReadAction {
                    extendSearchText(project, query, searchContext, caseSensitive, seenKeys, limit)
                }
            }

            val serializedResults = matches.map { match ->
                PaginationService.SerializedResult(
                    key = "${match.file}:${match.line}",
                    data = json.encodeToJsonElement(match)
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

        return buildPaginatedResult<TextMatch, SearchTextResult>(getPageFromCache(cursorToken, pageSize, project)) { items, page ->
            SearchTextResult(
                matches = items,
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

    private fun parseSearchContext(contextStr: String): Short {
        return when (contextStr.lowercase()) {
            "code" -> UsageSearchContext.IN_CODE
            "comments" -> UsageSearchContext.IN_COMMENTS
            "strings" -> UsageSearchContext.IN_STRINGS
            "all" -> UsageSearchContext.ANY
            else -> UsageSearchContext.ANY
        }
    }

    private fun searchText(
        project: Project,
        word: String,
        scope: com.intellij.psi.search.GlobalSearchScope,
        searchContext: Short,
        caseSensitive: Boolean,
        limit: Int
    ): List<TextMatch> {
        val results = ConcurrentLinkedQueue<TextMatch>()
        val seenLines = ConcurrentHashMap.newKeySet<String>()
        val count = AtomicInteger(0)
        val helper = PsiSearchHelper.getInstance(project)

        val processor = TextOccurenceProcessor { element, _ ->
            if (count.get() >= limit) {
                return@TextOccurenceProcessor false
            }

            val match = convertToTextMatch(project, element, searchContext)
            if (match != null) {
                val lineContainsWord = if (caseSensitive) {
                    match.context.contains(word)
                } else {
                    match.context.contains(word, ignoreCase = true)
                }
                if (!lineContainsWord) {
                    return@TextOccurenceProcessor true
                }

                val lineKey = "${match.file}:${match.line}"
                if (seenLines.add(lineKey)) {
                    val slot = count.incrementAndGet()
                    if (slot <= limit) {
                        results.add(match)
                    }
                    slot < limit
                } else {
                    true
                }
            } else {
                true
            }
        }

        helper.processElementsWithWord(
            processor,
            scope,
            word,
            searchContext,
            caseSensitive
        )

        return results.toList()
    }

    /**
     * Re-executes the search to collect more results beyond the initial cache.
     * This re-scans from the beginning, skipping already-seen keys — O(total_results) per extension.
     * This is unavoidable: IntelliJ's search APIs (ReferencesSearch, PsiSearchHelper, etc.)
     * don't support offset-based iteration or resumption.
     */
    private fun extendSearchText(
        project: Project,
        word: String,
        searchContext: Short,
        caseSensitive: Boolean,
        seenKeys: Set<String>,
        limit: Int
    ): List<PaginationService.SerializedResult> {
        val scope = createFilteredScope(project)
        val newResults = ConcurrentLinkedQueue<PaginationService.SerializedResult>()
        val seenLines = ConcurrentHashMap.newKeySet<String>()
        seenLines.addAll(seenKeys)
        val count = AtomicInteger(0)
        val helper = PsiSearchHelper.getInstance(project)

        val processor = TextOccurenceProcessor { element, _ ->
            if (count.get() >= limit) {
                return@TextOccurenceProcessor false
            }

            val match = convertToTextMatch(project, element, searchContext)
            if (match != null) {
                val lineContainsWord = if (caseSensitive) {
                    match.context.contains(word)
                } else {
                    match.context.contains(word, ignoreCase = true)
                }
                if (!lineContainsWord) {
                    return@TextOccurenceProcessor true
                }

                val lineKey = "${match.file}:${match.line}"
                if (seenLines.add(lineKey)) {
                    val slot = count.incrementAndGet()
                    if (slot <= limit) {
                        newResults.add(PaginationService.SerializedResult(
                            key = lineKey,
                            data = json.encodeToJsonElement(match)
                        ))
                    }
                    slot < limit
                } else {
                    true
                }
            } else {
                true
            }
        }

        helper.processElementsWithWord(
            processor,
            scope,
            word,
            searchContext,
            caseSensitive
        )

        return newResults.toList()
    }

    private fun convertToTextMatch(
        project: Project,
        element: PsiElement,
        searchContext: Short
    ): TextMatch? {
        val containingFile = element.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val relativePath = getRelativePath(project, virtualFile)

        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
        val offset = element.textOffset
        val lineNumber = document.getLineNumber(offset)
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val columnNumber = offset - lineStartOffset

        val lineEndOffset = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))

        val contextType = determineContextType(element, searchContext)

        return TextMatch(
            file = relativePath,
            line = lineNumber + 1,
            column = columnNumber + 1,
            context = lineText.trim(),
            contextType = contextType
        )
    }

    private fun determineContextType(element: PsiElement, searchContext: Short): String {
        if (searchContext == UsageSearchContext.IN_COMMENTS) {
            return "COMMENT"
        }
        if (searchContext == UsageSearchContext.IN_STRINGS) {
            return "STRING_LITERAL"
        }
        if (searchContext == UsageSearchContext.IN_CODE) {
            return "CODE"
        }

        val elementType = element.node?.elementType?.toString() ?: ""
        return when {
            elementType.contains("COMMENT", ignoreCase = true) -> "COMMENT"
            elementType.contains("STRING", ignoreCase = true) -> "STRING_LITERAL"
            elementType.contains("LITERAL", ignoreCase = true) -> "STRING_LITERAL"
            else -> "CODE"
        }
    }
}

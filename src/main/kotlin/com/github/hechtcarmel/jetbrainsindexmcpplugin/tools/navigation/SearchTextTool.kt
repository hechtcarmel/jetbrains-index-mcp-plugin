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
import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException

/**
 * Text search using IntelliJ Find in Files.
 *
 * All searches (plain-text and regex) use FindInProjectUtil, matching the IDE's own Find in Files
 * behaviour. Plain-text queries do substring matching so a query like "a_word" correctly
 * finds "a_word_and_another_word" — unlike the word-index API which only matches complete tokens.
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
        Search for text using IntelliJ Find in Files.

        Searches use IntelliJ's Find in Files engine, matching the IDE's own search behaviour. Plain-text queries do substring matching so a query like "a_word" correctly finds "a_word_and_another_word". Regex searches use the same engine with regular expression matching.

        Context filtering: search only in code, comments, or string literals.
        File filtering: pass filePattern with an IntelliJ file mask such as "*.kt" or "*.java,!*Test.java".

        Returns: matching locations with file, line, column, context snippet, and context type.

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.
        Parameters: query (required for fresh search), regex (optional, default: false), context (optional: "code", "comments", "strings", "all"), filePattern (optional IntelliJ file mask), caseSensitive (optional, default: true), pageSize (optional, default: 100, max: 500), cursor (for pagination, replaces search params; project_path may still be required).

        Example: {"query": "ConfigManager"} or {"query": "TODO", "context": "comments", "filePattern": "*.kt"} or {"query": "Runtime\\.getRuntime\\(\\)\\.exec\\(", "regex": true, "filePattern": "*.java"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.QUERY, "Text to search for. Treated as a substring (plain text) or pattern (when regex is true). Required for fresh search, ignored when cursor is provided.")
        .booleanProperty(ParamNames.REGEX, "Treat query as a regular expression. Default: false.")
        .enumProperty(ParamNames.CONTEXT, "Where to search: \"code\", \"comments\", \"strings\", \"all\". Default: \"all\".", listOf("code", "comments", "strings", "all"))
        .booleanProperty(ParamNames.CASE_SENSITIVE, "Case sensitive search. Default: true.")
        .stringProperty(ParamNames.FILE_PATTERN, "IntelliJ file mask to filter files by name, e.g. \"*.kt\", \"*.gradle.kts\", \"*.java,!*Test.java\".")
        .intProperty(ParamNames.LIMIT, "Maximum results per page (deprecated, use pageSize). Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val cursor = optionalStringArg(arguments, ParamNames.CURSOR)
        if (cursor != null) {
            val pageSize = resolveExplicitPageSize(arguments, aliases = arrayOf("limit"))
            return buildPaginatedResult<TextMatch, SearchTextResult>(
                getPageFromCache(
                    cursor,
                    pageSize,
                    project
                )
            ) { items, page ->
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
        val regex = arguments[ParamNames.REGEX]?.jsonPrimitive?.boolean ?: false
        val filePattern = optionalStringArg(arguments, ParamNames.FILE_PATTERN)
        val pageSize = resolvePageSize(arguments, DEFAULT_PAGE_SIZE, aliases = arrayOf("limit"))
        val collectLimit = maxOf(PaginationService.DEFAULT_OVERCOLLECT, pageSize)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        val usageSearchContext = parseUsageSearchContext(contextStr)
        val findSearchContext = parseFindSearchContext(contextStr)

        // Always use FindInProjectUtil (mirrors IDE's Find in Files) so that plain-text queries
        // do substring matching rather than whole-word token matching. The word-index API
        // (PsiSearchHelper.processElementsWithWord) only finds complete tokens: a query like
        // "a_word" yields zero results when the file contains "a_word_and_another_word",
        // because underscores are word characters and the whole identifier is one token.
        val findModel = if (regex) {
            try {
                createFindModel(project, query, caseSensitive, filePattern, findSearchContext, isRegex = true)
            } catch (e: PatternSyntaxException) {
                return createErrorResult("Invalid regex query: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return createErrorResult("Invalid regex query: ${e.message}")
            }
        } else {
            createFindModel(project, query, caseSensitive, filePattern, findSearchContext, isRegex = false)
        }

        requireSmartMode(project)

        val cursorToken = run {
            val matches = searchRegex(project, findModel, usageSearchContext, collectLimit)
            createCursor(
                project = project,
                query = query,
                regex = regex,
                filePattern = filePattern,
                matches = matches,
                searchExtender = { seenKeys, limit ->
                    extendSearch(project, findModel, usageSearchContext, seenKeys, limit)
                }
            )
        }

        return buildPaginatedResult<TextMatch, SearchTextResult>(
            getPageFromCache(
                cursorToken,
                pageSize,
                project
            )
        ) { items, page ->
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

    private fun createCursor(
        project: Project,
        query: String,
        regex: Boolean,
        filePattern: String?,
        matches: List<TextMatch>,
        searchExtender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult>
    ): String {
        val serializedResults = matches.map { match ->
            PaginationService.SerializedResult(
                key = "${match.file}:${match.line}",
                data = json.encodeToJsonElement(match)
            )
        }

        val paginationService = ApplicationManager.getApplication().getService(PaginationService::class.java)
        return paginationService.createCursor(
            toolName = name,
            results = serializedResults,
            seenKeys = serializedResults.map { it.key }.toSet(),
            searchExtender = searchExtender,
            psiModCount = PsiModificationTracker.getInstance(project).modificationCount,
            projectBasePath = ProjectResolver.normalizePath(project.basePath ?: ""),
            metadata = buildMap {
                put("query", query)
                put("regex", regex.toString())
                if (filePattern != null) put("filePattern", filePattern)
            }
        )
    }

    private fun parseUsageSearchContext(contextStr: String): Short {
        return when (contextStr.lowercase()) {
            "code" -> UsageSearchContext.IN_CODE
            "comments" -> UsageSearchContext.IN_COMMENTS
            "strings" -> UsageSearchContext.IN_STRINGS
            "all" -> UsageSearchContext.ANY
            else -> UsageSearchContext.ANY
        }
    }

    private fun parseFindSearchContext(contextStr: String): FindModel.SearchContext {
        return when (contextStr.lowercase()) {
            "code" -> FindModel.SearchContext.EXCEPT_COMMENTS_AND_STRING_LITERALS
            "comments" -> FindModel.SearchContext.IN_COMMENTS
            "strings" -> FindModel.SearchContext.IN_STRING_LITERALS
            else -> FindModel.SearchContext.ANY
        }
    }

    private fun createFindModel(
        project: Project,
        query: String,
        caseSensitive: Boolean,
        filePattern: String?,
        searchContext: FindModel.SearchContext,
        isRegex: Boolean
    ): FindModel {
        return FindModel().apply {
            stringToFind = query
            isRegularExpressions = isRegex
            isCaseSensitive = caseSensitive
            isMultipleFiles = true
            isProjectScope = true
            isFindAll = true
            isMultiline = true
            this.searchContext = searchContext
            customScope = createFilteredScope(project)
            isCustomScope = true
            fileFilter = filePattern?.trim().orEmpty()
            if (isRegex) compileRegExp()
        }
    }

    /**
     * Re-executes the search to collect more results beyond the initial cache.
     * This re-scans from the beginning, skipping already-seen keys — O(total_results) per extension.
     * This is unavoidable: FindInProjectUtil does not support offset-based resumption.
     */
    private fun extendSearch(
        project: Project,
        findModel: FindModel,
        searchContext: Short,
        seenKeys: Set<String>,
        limit: Int
    ): List<PaginationService.SerializedResult> {
        return searchRegex(project, findModel, searchContext, limit, seenKeys)
            .map { match ->
                PaginationService.SerializedResult(
                    key = "${match.file}:${match.line}",
                    data = json.encodeToJsonElement(match)
                )
            }
    }

    private fun searchRegex(
        project: Project,
        findModel: FindModel,
        searchContext: Short,
        limit: Int,
        seenKeys: Set<String> = emptySet()
    ): List<TextMatch> {
        val results = ConcurrentLinkedQueue<TextMatch>()
        val seenLines = ConcurrentHashMap.newKeySet<String>().apply { addAll(seenKeys) }
        val count = AtomicInteger(0)
        val presentation =
            FindInProjectUtil.setupProcessPresentation(FindInProjectUtil.setupViewPresentation(findModel))

        FindInProjectUtil.findUsages(
            findModel,
            project,
            presentation,
            emptySet<VirtualFile>()
        ) { usageInfo: UsageInfo ->
            if (count.get() >= limit) return@findUsages false

            val match = convertToTextMatch(project, usageInfo, searchContext) ?: return@findUsages true
            val lineKey = "${match.file}:${match.line}"
            if (seenLines.add(lineKey)) {
                if (count.incrementAndGet() <= limit) results.add(match)
            }

            count.get() < limit
        }

        return results.toList()
    }

    private fun convertToTextMatch(
        project: Project,
        usageInfo: UsageInfo,
        searchContext: Short
    ): TextMatch? {
        val psiFile = usageInfo.file ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val offset = usageInfo.segment?.startOffset ?: usageInfo.navigationOffset
        return convertToTextMatch(project, psiFile, document, offset, searchContext)
    }

    private fun convertToTextMatch(
        project: Project,
        psiFile: PsiFile,
        document: com.intellij.openapi.editor.Document,
        offset: Int,
        searchContext: Short
    ): TextMatch? {
        val virtualFile = psiFile.virtualFile ?: return null
        val relativePath = getRelativePath(project, virtualFile)
        val element = findElementAtOffset(psiFile, offset)
        return convertToTextMatch(project, psiFile, document, offset, searchContext, element, relativePath)
    }

    private fun convertToTextMatch(
        project: Project,
        psiFile: PsiFile,
        document: com.intellij.openapi.editor.Document,
        offset: Int,
        searchContext: Short,
        element: PsiElement?,
        relativePath: String
    ): TextMatch? {
        if (document.textLength == 0) return null
        val safeOffset = offset.coerceIn(0, document.textLength - 1)
        val lineNumber = document.getLineNumber(safeOffset)
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val columnNumber = safeOffset - lineStartOffset

        val lineEndOffset = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(TextRange(lineStartOffset, lineEndOffset))

        val contextType = element?.let { resolveActualContextType(it) } ?: "CODE"

        if (searchContext != UsageSearchContext.ANY && !matchesRequestedContext(contextType, searchContext)) {
            return null
        }

        return TextMatch(
            file = relativePath,
            line = lineNumber + 1,
            column = columnNumber + 1,
            context = lineText.trim(),
            contextType = contextType
        )
    }

    private fun findElementAtOffset(psiFile: PsiFile, offset: Int): PsiElement? {
        val textLength = psiFile.textLength
        if (textLength <= 0) return null
        return psiFile.findElementAt(offset.coerceIn(0, textLength - 1))
    }

    private fun resolveActualContextType(element: PsiElement): String {
        // Check if element is inside a comment (PsiComment or comment-type node)
        if (PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) {
            return "COMMENT"
        }
        // Walk ancestors checking node element types for languages where PsiComment
        // may not cover all comment variants (e.g., doc comments, template comments)
        var current: PsiElement? = element
        while (current != null && current !is com.intellij.psi.PsiFile) {
            val typeName = current.node?.elementType?.toString() ?: ""
            when {
                typeName.contains("COMMENT", ignoreCase = true) -> return "COMMENT"
                typeName.contains("STRING_LITERAL", ignoreCase = true) ||
                typeName.contains("TEMPLATE_EXPRESSION", ignoreCase = true) -> return "STRING_LITERAL"
            }
            current = current.parent
        }
        // Check the element itself for string-like types not caught by ancestor walk
        val elementType = element.node?.elementType?.toString() ?: ""
        return when {
            elementType.contains("STRING", ignoreCase = true) -> "STRING_LITERAL"
            elementType.contains("LITERAL", ignoreCase = true) -> "STRING_LITERAL"
            else -> "CODE"
        }
    }

    private fun matchesRequestedContext(actualType: String, searchContext: Short): Boolean {
        return when (searchContext) {
            UsageSearchContext.IN_COMMENTS -> actualType == "COMMENT"
            UsageSearchContext.IN_STRINGS -> actualType == "STRING_LITERAL"
            UsageSearchContext.IN_CODE -> actualType == "CODE"
            else -> true
        }
    }
}

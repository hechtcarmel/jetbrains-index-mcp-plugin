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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiModificationTracker
import java.util.regex.PatternSyntaxException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

internal class SearchTextFilePatternMatcher private constructor(
    private val regex: Regex,
    private val matchPath: Boolean
) {
    fun matches(relativePath: String, fileName: String): Boolean {
        val normalizedPath = relativePath.replace('\\', '/')
        val normalizedFileName = fileName.replace('\\', '/')
        return if (matchPath) {
            regex.matches(normalizedPath)
        } else {
            regex.matches(normalizedFileName)
        }
    }

    companion object {
        fun fromGlob(pattern: String?): SearchTextFilePatternMatcher? {
            if (pattern == null) return null
            val normalized = pattern.trim().replace('\\', '/')
            if (normalized.isEmpty()) return null
            return SearchTextFilePatternMatcher(
                regex = globToRegex(normalized).toRegex(),
                matchPath = normalized.contains('/')
            )
        }

        private fun globToRegex(glob: String): String {
            val builder = StringBuilder("^")
            var index = 0
            while (index < glob.length) {
                val char = glob[index]
                when (char) {
                    '*' -> {
                        if (index + 1 < glob.length && glob[index + 1] == '*') {
                            builder.append(".*")
                            index++
                        } else {
                            builder.append("[^/]*")
                        }
                    }
                    '?' -> builder.append("[^/]")
                    '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']' -> {
                        builder.append('\\').append(char)
                    }
                    else -> builder.append(char)
                }
                index++
            }
            builder.append('$')
            return builder.toString()
        }
    }
}

/**
 * Text search using IDE's word index.
 *
 * Uses a pre-built word index for exact word matches and scans project files for regex matches.
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
        Search for text using IDE's word index or regex scanning.

        Exact searches use a pre-built word index for O(1) lookups instead of scanning all files. Regex searches scan project files.

        Context filtering: search only in code, comments, or string literals.
        File filtering: pass filePattern with a glob such as "*.kt" or "src/**/*.java".

        Returns: matching locations with file, line, column, context snippet, and context type.

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.
        Parameters: query (required for fresh search), regex (optional, default: false), context (optional: "code", "comments", "strings", "all"), filePattern (optional glob), caseSensitive (optional, default: true), pageSize (optional, default: 100, max: 500), cursor (for pagination, replaces search params; project_path may still be required).

        Example: {"query": "ConfigManager"} or {"query": "TODO", "context": "comments", "filePattern": "*.kt"} or {"query": "Runtime\\.getRuntime\\(\\)\\.exec\\(", "regex": true, "filePattern": "*.java"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.QUERY, "Text to search for. Treated as an exact word unless regex is true. Required for fresh search, ignored when cursor is provided.")
        .booleanProperty(ParamNames.REGEX, "Treat query as a regular expression. Default: false.")
        .enumProperty(ParamNames.CONTEXT, "Where to search: \"code\", \"comments\", \"strings\", \"all\". Default: \"all\".", listOf("code", "comments", "strings", "all"))
        .booleanProperty(ParamNames.CASE_SENSITIVE, "Case sensitive search. Default: true.")
        .stringProperty(ParamNames.FILE_PATTERN, "Glob pattern to filter files by name or project-relative path, e.g. \"*.kt\", \"*.gradle.kts\", \"src/**/*.java\".")
        .intProperty(ParamNames.LIMIT, "Maximum results per page (deprecated, use pageSize). Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val cursor = optionalStringArg(arguments, ParamNames.CURSOR)
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
        val regex = arguments[ParamNames.REGEX]?.jsonPrimitive?.boolean ?: false
        val filePattern = optionalStringArg(arguments, ParamNames.FILE_PATTERN)
        val pageSize = resolvePageSize(arguments, DEFAULT_PAGE_SIZE, aliases = arrayOf("limit"))
        val collectLimit = maxOf(PaginationService.DEFAULT_OVERCOLLECT, pageSize)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        val searchContext = parseSearchContext(contextStr)
        val filePatternMatcher = try {
            SearchTextFilePatternMatcher.fromGlob(filePattern)
        } catch (e: PatternSyntaxException) {
            return createErrorResult("Invalid filePattern glob: ${e.message}")
        }
        val regexPattern = if (regex) {
            try {
                query.toRegex(if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (e: PatternSyntaxException) {
                return createErrorResult("Invalid regex query: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return createErrorResult("Invalid regex query: ${e.message}")
            }
        } else {
            null
        }

        requireSmartMode(project)

        val cursorToken = suspendingReadAction {
            val scope = createSearchScope(project, filePatternMatcher)
            val matches = if (regexPattern != null) {
                searchRegex(project, regexPattern, scope, searchContext, collectLimit)
            } else {
                searchText(project, query, scope, searchContext, caseSensitive, collectLimit)
            }

            val searchExtender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = extender@{ seenKeys, limit ->
                suspendingReadAction {
                    extendSearchText(project, query, regexPattern, searchContext, caseSensitive, filePatternMatcher, seenKeys, limit)
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
                metadata = buildMap {
                    put("query", query)
                    put("regex", regex.toString())
                    if (filePattern != null) put("filePattern", filePattern)
                }
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
        scope: GlobalSearchScope,
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
        regex: Regex?,
        searchContext: Short,
        caseSensitive: Boolean,
        filePatternMatcher: SearchTextFilePatternMatcher?,
        seenKeys: Set<String>,
        limit: Int
    ): List<PaginationService.SerializedResult> {
        val scope = createSearchScope(project, filePatternMatcher)
        if (regex != null) {
            return searchRegex(project, regex, scope, searchContext, limit, seenKeys)
                .asSequence()
                .map { match ->
                    PaginationService.SerializedResult(
                        key = "${match.file}:${match.line}",
                        data = json.encodeToJsonElement(match)
                    )
                }
                .toList()
        }

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

    private fun searchRegex(
        project: Project,
        regex: Regex,
        scope: GlobalSearchScope,
        searchContext: Short,
        limit: Int,
        seenKeys: Set<String> = emptySet()
    ): List<TextMatch> {
        val results = mutableListOf<TextMatch>()
        val seenLines = HashSet<String>()
        seenLines.addAll(seenKeys)
        val fileIndex = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val fileDocumentManager = FileDocumentManager.getInstance()

        fileIndex.iterateContent { virtualFile ->
            if (results.size >= limit) {
                return@iterateContent false
            }
            if (virtualFile.isDirectory || !scope.contains(virtualFile)) {
                return@iterateContent true
            }

            val document = fileDocumentManager.getDocument(virtualFile) ?: return@iterateContent true
            val psiFile = psiManager.findFile(virtualFile) ?: return@iterateContent true
            val text = document.text

            for (matchResult in regex.findAll(text)) {
                if (results.size >= limit) {
                    return@iterateContent false
                }
                if (matchResult.value.isEmpty()) {
                    continue
                }

                val match = convertToTextMatch(project, psiFile, document, matchResult.range.first, searchContext)
                    ?: continue
                val lineKey = "${match.file}:${match.line}"
                if (seenLines.add(lineKey)) {
                    results.add(match)
                }
            }

            true
        }

        return results
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
        return convertToTextMatch(project, containingFile, document, element.textOffset, searchContext, element, relativePath)
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

        // When a specific context filter is active, skip elements that don't match.
        // processElementsWithWord may return false positives (e.g., code occurrences
        // from files that also have the word in a comment).
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

    private fun createSearchScope(
        project: Project,
        filePatternMatcher: SearchTextFilePatternMatcher?
    ): GlobalSearchScope {
        val baseScope = createFilteredScope(project)
        if (filePatternMatcher == null) {
            return baseScope
        }

        return object : DelegatingGlobalSearchScope(baseScope) {
            override fun contains(file: VirtualFile): Boolean {
                if (!super.contains(file)) return false
                val relativePath = getRelativePath(project, file)
                return filePatternMatcher.matches(relativePath, file.name)
            }
        }
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

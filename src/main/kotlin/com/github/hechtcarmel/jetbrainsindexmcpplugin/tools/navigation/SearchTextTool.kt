package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TextMatch
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
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
 * Text search using IDE's word index.
 *
 * Uses a pre-built word index for exact word matches.
 *
 * Supports context filtering: search only in code, comments, or string literals.
 */
@Suppress("unused")
class SearchTextTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_LIMIT = 100
        private const val MAX_LIMIT = 500
    }

    override val name = ToolNames.SEARCH_TEXT

    override val description = """
        Search for text using IDE's word index. Significantly faster than file scanning for exact word matches.

        Uses a pre-built word index for O(1) lookups instead of scanning all files.

        Context filtering: search only in code, comments, or string literals.

        Returns: matching locations with file, line, column, context snippet, and context type.

        Parameters: query (required), context (optional: "code", "comments", "strings", "all"), caseSensitive (optional, default: true), limit (optional, default: 100).

        Example: {"query": "ConfigManager"} or {"query": "TODO", "context": "comments"}
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
                put(SchemaConstants.DESCRIPTION, "Exact word to search for (not a pattern/regex).")
            }
            putJsonObject(ParamNames.CONTEXT) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(
                    SchemaConstants.DESCRIPTION,
                    "Where to search: \"code\", \"comments\", \"strings\", \"all\". Default: \"all\"."
                )
                putJsonArray("enum") {
                    add(JsonPrimitive("code"))
                    add(JsonPrimitive("comments"))
                    add(JsonPrimitive("strings"))
                    add(JsonPrimitive("all"))
                }
            }
            putJsonObject(ParamNames.CASE_SENSITIVE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_BOOLEAN)
                put(SchemaConstants.DESCRIPTION, "Case sensitive search. Default: true.")
            }
            putJsonObject(ParamNames.LIMIT) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum results to return. Default: 100, Max: 500.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.QUERY))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val query = arguments[ParamNames.QUERY]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.QUERY}")
        val contextStr = arguments[ParamNames.CONTEXT]?.jsonPrimitive?.content ?: "all"
        val caseSensitive = arguments[ParamNames.CASE_SENSITIVE]?.jsonPrimitive?.boolean ?: true
        val limit = (arguments[ParamNames.LIMIT]?.jsonPrimitive?.int ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        val searchContext = parseSearchContext(contextStr)

        requireSmartMode(project)

        return suspendingReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val matches = searchText(project, query, scope, searchContext, caseSensitive, limit)

            createJsonResult(
                SearchTextResult(
                    matches = matches,
                    totalCount = matches.size,
                    query = query
                )
            )
        }
    }

    /**
     * Parse context string to UsageSearchContext flags.
     */
    private fun parseSearchContext(contextStr: String): Short {
        return when (contextStr.lowercase()) {
            "code" -> UsageSearchContext.IN_CODE
            "comments" -> UsageSearchContext.IN_COMMENTS
            "strings" -> UsageSearchContext.IN_STRINGS
            "all" -> UsageSearchContext.ANY
            else -> UsageSearchContext.ANY
        }
    }

    /**
     * Search for text using PsiSearchHelper's word index.
     *
     * Uses lock-free CAS pattern for thread-safety since processElementsWithWord
     * invokes the processor concurrently from multiple threads.
     */
    private fun searchText(
        project: Project,
        word: String,
        scope: GlobalSearchScope,
        searchContext: Short,
        caseSensitive: Boolean,
        limit: Int
    ): List<TextMatch> {
        // Lock-free concurrent collection - processElementsWithWord calls processor from multiple threads
        val results = ConcurrentLinkedQueue<TextMatch>()
        val count = AtomicInteger(0)
        val helper = PsiSearchHelper.getInstance(project)

        val processor = TextOccurenceProcessor { element, _ ->
            // Fast path: already at limit
            if (count.get() >= limit) {
                return@TextOccurenceProcessor false
            }

            val match = convertToTextMatch(project, element, searchContext)
            if (match != null) {
                // Optimistically claim a slot via CAS increment
                val slot = count.incrementAndGet()
                if (slot <= limit) {
                    results.add(match)
                }
                // Continue only if under limit
                slot < limit
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
     * Convert PsiElement to TextMatch with context information.
     */
    private fun convertToTextMatch(
        project: Project,
        element: PsiElement,
        searchContext: Short
    ): TextMatch? {
        val containingFile = element.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val basePath = project.basePath ?: ""
        val relativePath = virtualFile.path.removePrefix(basePath).removePrefix("/")

        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
        val offset = element.textOffset
        val lineNumber = document.getLineNumber(offset)
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val columnNumber = offset - lineStartOffset

        // Get line content for context
        val lineEndOffset = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))

        // Determine context type
        val contextType = determineContextType(element, searchContext)

        return TextMatch(
            file = relativePath,
            line = lineNumber + 1, // 1-based
            column = columnNumber + 1, // 1-based
            context = lineText.trim(),
            contextType = contextType
        )
    }

    /**
     * Determine the type of context where the match was found.
     */
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

        // For "all" context, try to determine the actual type
        val elementType = element.node?.elementType?.toString() ?: ""
        return when {
            elementType.contains("COMMENT", ignoreCase = true) -> "COMMENT"
            elementType.contains("STRING", ignoreCase = true) -> "STRING_LITERAL"
            elementType.contains("LITERAL", ignoreCase = true) -> "STRING_LITERAL"
            else -> "CODE"
        }
    }
}

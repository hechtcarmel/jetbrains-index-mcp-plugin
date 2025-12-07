package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for searching code symbols across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class FindSymbolTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_LIMIT = 25
        private const val MAX_LIMIT = 100
    }

    override val name = ToolNames.FIND_SYMBOL

    override val description = """
        Searches for code symbols (classes, interfaces, methods, fields, functions) by name using the IDE's semantic index.

        SUPPORTED LANGUAGES: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust

        Use this tool when you need to:
        - Find a class or interface by name (e.g., find "UserService")
        - Locate methods/functions across the codebase (e.g., find all "findById" methods)
        - Discover fields or constants by name
        - Navigate to code when you know the symbol name but not the file location

        Supports fuzzy matching:
        - Substring: "Service" matches "UserService", "OrderService"
        - CamelCase: "USvc" matches "UserService", "US" matches "UserService"

        EXAMPLE Java/Kotlin: {"query": "UserService"}
        EXAMPLE Python: {"query": "find_user"}
        EXAMPLE TypeScript: {"query": "fetchData", "includeLibraries": true}
        EXAMPLE Rust: {"query": "UserService"}
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
                put(SchemaConstants.DESCRIPTION, "Include symbols from library dependencies. Default: false.")
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

        return readAction {
            // Aggregate results from ALL available language handlers
            val handlers = LanguageHandlerRegistry.getAllSymbolSearchHandlers()
            if (handlers.isEmpty()) {
                return@readAction createErrorResult(
                    "No symbol search handlers available. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForSymbolSearch()}"
                )
            }

            val allMatches = mutableListOf<SymbolMatch>()

            for (handler in handlers) {
                val handlerResults = handler.searchSymbols(project, query, includeLibraries, limit)
                allMatches.addAll(handlerResults.map { symbolData ->
                    SymbolMatch(
                        name = symbolData.name,
                        qualifiedName = symbolData.qualifiedName,
                        kind = symbolData.kind,
                        file = symbolData.file,
                        line = symbolData.line,
                        containerName = symbolData.containerName,
                        language = symbolData.language
                    )
                })
            }

            // Sort by relevance and deduplicate
            val sortedMatches = allMatches
                .distinctBy { "${it.file}:${it.line}:${it.name}" }
                .sortedWith(compareBy(
                    { !it.name.equals(query, ignoreCase = true) },
                    { levenshteinDistance(it.name.lowercase(), query.lowercase()) }
                ))
                .take(limit)

            createJsonResult(FindSymbolResult(
                symbols = sortedMatches,
                totalCount = sortedMatches.size,
                query = query
            ))
        }
    }

    internal fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (s1[i - 1] == s2[j - 1]) 0 else 1
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * Checks if a symbol name matches a camelCase query pattern.
     * Used for fuzzy matching in symbol search.
     */
    internal fun matchesCamelCase(name: String, query: String): Boolean {
        var queryIndex = 0
        for (char in name) {
            if (queryIndex >= query.length) return true
            if (char.equals(query[queryIndex], ignoreCase = true)) queryIndex++
        }
        return queryIndex >= query.length
    }
}

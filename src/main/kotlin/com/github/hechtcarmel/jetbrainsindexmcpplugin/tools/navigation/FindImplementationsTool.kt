package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for finding implementations of interfaces, abstract classes, or methods across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class FindImplementationsTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_PAGE_SIZE = 100
        private const val MAX_PAGE_SIZE = PaginationService.MAX_PAGE_SIZE
    }

    override val name = "ide_find_implementations"

    override val description = """
        Find all implementations of an interface, abstract class, or abstract method. Use to discover concrete implementations when working with abstractions.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust.

        Returns: list of implementing classes/methods with file paths, line/column numbers, and kind (class/method).

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.

        Target (mutually exclusive):
        - file + line + column: position-based lookup (necessary for fresh search, ignored when cursor is provided)
        - language + symbol: fully qualified symbol reference (necessary for fresh search, ignored when cursor is provided)
        - cursor: pagination cursor from a previous response

        Parameters: pageSize (optional, default: 100, max: 500).

        Example: {"file": "src/Repository.java", "line": 8, "column": 18}
        Example: {"language": "Java", "symbol": "com.example.Repository"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(required = false)
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val cursor = arguments["cursor"]?.jsonPrimitive?.content
        if (cursor != null) {
            val pageSize = resolveExplicitPageSize(arguments)
            return buildPaginatedResult<ImplementationLocation, ImplementationResult>(getPageFromCache(cursor, pageSize, project)) { items, page ->
                ImplementationResult(
                    implementations = items,
                    totalCount = page.totalCollected,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                    totalCollected = page.totalCollected,
                    offset = page.offset,
                    pageSize = page.pageSize,
                    stale = page.stale
                )
            }
        }

        val pageSize = resolvePageSize(arguments, DEFAULT_PAGE_SIZE)

        requireSmartMode(project)

        val cursorToken = suspendingReadAction {
            val element = resolveElementFromArguments(project, arguments).getOrElse {
                return@suspendingReadAction null to createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            val handler = LanguageHandlerRegistry.getImplementationsHandler(element)
            if (handler == null) {
                return@suspendingReadAction null to createErrorResult(
                    "No implementations handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForImplementations()}"
                )
            }

            val implementations = handler.findImplementations(element, project)
            if (implementations == null) {
                val isSymbolMode = arguments[ParamNames.LANGUAGE] != null
                return@suspendingReadAction null to createErrorResult(
                    if (isSymbolMode) "No method or class found for the specified symbol"
                    else "No method or class found at position"
                )
            }

            val implementationLocations = implementations.map { impl ->
                ImplementationLocation(
                    name = impl.name,
                    file = impl.file,
                    line = impl.line,
                    column = impl.column,
                    kind = impl.kind,
                    language = impl.language
                )
            }

            val serializedResults = implementationLocations.map { impl ->
                PaginationService.SerializedResult(
                    key = "${impl.file}:${impl.line}:${impl.column}:${impl.name}",
                    data = json.encodeToJsonElement(impl)
                )
            }

            val paginationService = ApplicationManager.getApplication().getService(PaginationService::class.java)
            val token = paginationService.createCursor(
                toolName = name,
                results = serializedResults,
                seenKeys = serializedResults.map { it.key }.toSet(),
                searchExtender = null,
                psiModCount = PsiModificationTracker.getInstance(project).modificationCount,
                projectBasePath = ProjectResolver.normalizePath(project.basePath ?: "")
            )

            token to null
        }

        val (token, errorResult) = cursorToken
        if (errorResult != null) return errorResult

        return buildPaginatedResult<ImplementationLocation, ImplementationResult>(getPageFromCache(token!!, pageSize, project)) { items, page ->
            ImplementationResult(
                implementations = items,
                totalCount = page.totalCollected,
                nextCursor = page.nextCursor,
                hasMore = page.hasMore,
                totalCollected = page.totalCollected,
                offset = page.offset,
                pageSize = page.pageSize,
                stale = page.stale
            )
        }
    }
}

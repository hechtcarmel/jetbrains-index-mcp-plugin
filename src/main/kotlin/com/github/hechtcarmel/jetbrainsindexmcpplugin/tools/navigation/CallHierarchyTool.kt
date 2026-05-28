package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RIDER_CALL_HIERARCHY_SYMBOL_MODE_UNSUPPORTED
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderSymbolParser
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendTimeoutException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.normalizeAcceptedRiderLanguageAlias
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for analyzing method call relationships across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, C#, F#
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class CallHierarchyTool : AbstractMcpTool() {

    override val name = "ide_call_hierarchy"

    override val description = """
        Build a call hierarchy tree for a method/function. Use to trace execution flow—find what calls this method (callers) or what this method calls (callees).

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, C#, F#.

        Rust note: "callers" direction works well; "callees" direction may have limited results due to Rust plugin PSI resolution constraints.
        Rider note: C#/F# results use Rider's frontend navigation bridge to the ReSharper backend. Caller/callee scope separation between project_files and project_and_libraries is only guaranteed where backend APIs can enforce that distinction, and framework-routed endpoints can legitimately have empty static callers. For routed/reflection-driven entry points, an empty callers result is a static-analysis limitation and does not imply backend failure.

        Returns: recursive tree with method signatures, file locations (line/column), and nested call relationships.

        Target selection:
        - Complete file + positive line + positive column: position-based lookup, preferred when present because it is more precise
        - Complete language + symbol: fully qualified symbol reference used when no complete position target is present (supported when the requested language has a SymbolReferenceHandler, including Rider C#/F#). Blank strings and non-positive line/column values count as absent.

        Parameters: direction (required): "callers" or "callees". depth (optional, default: 3, max: 5). scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files).

        Example: {"file": "src/Service.java", "line": 42, "column": 10, "direction": "callers"}
        Example: {"language": "Java", "symbol": "com.example.Service#processRequest(String)", "direction": "callers", "scope": "project_and_libraries"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(required = false, description = "Project-relative file path, or a dependency/library absolute path or jar:// URL previously returned by the plugin. Required for position-based lookup.")
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .enumProperty("direction", "Direction: 'callers' (methods that call this method) or 'callees' (methods this method calls)", listOf("callers", "callees"), required = true)
        .intProperty("depth", "How many levels deep to traverse the call hierarchy (default: 3, max: 5)")
        .scopeProperty("Search scope. Default: project_files.")
        .build()

    companion object {
        private const val DEFAULT_DEPTH = 3
        private const val MAX_DEPTH = 5

        internal fun riderTimeoutMessage(timeout: RiderBackendTimeoutException): String =
            timeout.message ?: "Rider backend timed out while resolving call hierarchy"

        internal fun noCallableMessage(isSymbolMode: Boolean): String =
            if (isSymbolMode) "No method/function found for the specified symbol"
            else "No method/function found at position"

        internal fun riderSymbolValidationMessage(language: String?, symbol: String?): String? {
            if (language == null || symbol == null) return null
            return RiderSymbolParser.callHierarchyCallableGuidance(language, symbol)
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val requestedLanguage = optionalStringArg(arguments, ParamNames.LANGUAGE)
        val normalizedRequestedLanguage = normalizeAcceptedRiderLanguageAlias(requestedLanguage)
        val requestedSymbol = optionalStringArg(arguments, ParamNames.SYMBOL)
        val isRiderSymbolMode = resolveLookupMode(arguments) == LookupModeState.SYMBOL &&
            normalizedRequestedLanguage in setOf("C#", "F#") &&
            requestedSymbol != null
        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: direction")
        val depth = (arguments["depth"]?.jsonPrimitive?.int ?: DEFAULT_DEPTH).coerceIn(1, MAX_DEPTH)
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        if (direction !in listOf("callers", "callees")) {
            return createErrorResult("direction must be 'callers' or 'callees'")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            ProgressManager.checkCanceled() // Allow cancellation

            if (isRiderSymbolMode) {
                riderSymbolValidationMessage(normalizedRequestedLanguage, requestedSymbol)?.let {
                    return@suspendingReadAction createErrorResult(it)
                }
                val riderHierarchy = RiderBackendSemanticService.getCallHierarchy(
                    project = project,
                    file = optionalStringArg(arguments, ParamNames.FILE),
                    line = optionalPositionIntArg(arguments, ParamNames.LINE),
                    column = optionalPositionIntArg(arguments, ParamNames.COLUMN),
                    language = normalizedRequestedLanguage,
                    symbol = requestedSymbol,
                    direction = direction,
                    depth = depth,
                    scope = scope
                )
                if (riderHierarchy.handled) {
                    riderHierarchy.errorMessage?.let { return@suspendingReadAction createErrorResult(it) }
                    riderHierarchy.value?.let {
                        return@suspendingReadAction createJsonResult(
                            CallHierarchyResult(
                                element = convertToCallElement(it.element),
                                calls = it.calls.map(::convertToCallElement),
                                message = riderHierarchy.message
                            )
                        )
                    }
                    return@suspendingReadAction createErrorResult(noCallableMessage(isSymbolMode = true))
                }

                return@suspendingReadAction createErrorResult(RIDER_CALL_HIERARCHY_SYMBOL_MODE_UNSUPPORTED)
            }

            val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true).getOrElse {
                return@suspendingReadAction createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getCallHierarchyHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No call hierarchy handler registered for detected PSI language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForCallHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            val hierarchyData = try {
                handler.getCallHierarchy(element, project, direction, depth, scope)
            } catch (timeout: RiderBackendTimeoutException) {
                return@suspendingReadAction createErrorResult(riderTimeoutMessage(timeout))
            }
            if (hierarchyData == null) {
                val isSymbolMode = optionalStringArg(arguments, ParamNames.LANGUAGE) != null
                return@suspendingReadAction createErrorResult(noCallableMessage(isSymbolMode))
            }

            // Convert handler result to tool result
            createJsonResult(CallHierarchyResult(
                element = convertToCallElement(hierarchyData.element),
                calls = hierarchyData.calls.map { convertToCallElement(it) },
                message = null
            ))
        }
    }

    /**
     * Converts handler CallElementData to tool CallElement.
     */
    private fun convertToCallElement(data: CallElementData): CallElement {
        return CallElement(
            name = data.name,
            file = data.file,
            line = data.line,
            column = data.column,
            language = data.language,
            children = data.children?.map { convertToCallElement(it) }
        )
    }
}

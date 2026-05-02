package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
        Rider note: C#/F# results use Rider's frontend navigation bridge to the ReSharper backend.

        Returns: recursive tree with method signatures, file locations (line/column), and nested call relationships.

        Target (mutually exclusive):
        - file + line + column: position-based lookup
        - language + symbol: fully qualified symbol reference (currently supported for Java only)

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
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
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

            // For C#/F# symbol-form requests, the universal SymbolReferenceHandler
            // registry only knows about Java. Resolve the symbol to a position via
            // the Rider backend first, then fall through to the position-based
            // handler so depth/scope/seen-set semantics are identical.
            val effectiveArguments = rewriteSymbolArgumentsForRider(project, arguments) ?: arguments

            val element = resolveElementFromArguments(project, effectiveArguments, allowLibraryFilesForPosition = true).getOrElse {
                return@suspendingReadAction createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getCallHierarchyHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No call hierarchy handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForCallHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            val hierarchyData = handler.getCallHierarchy(element, project, direction, depth, scope)
            if (hierarchyData == null) {
                val isSymbolMode = arguments[ParamNames.LANGUAGE] != null
                return@suspendingReadAction createErrorResult(
                    if (isSymbolMode) "No method/function found for the specified symbol"
                    else "No method/function found at position"
                )
            }

            // Convert handler result to tool result
            createJsonResult(CallHierarchyResult(
                element = convertToCallElement(hierarchyData.element),
                calls = hierarchyData.calls.map { convertToCallElement(it) }
            ))
        }
    }

    private fun rawScopeValue(scopeElement: JsonElement?): String = when (scopeElement) {
        null -> ""
        is JsonPrimitive -> scopeElement.content
        else -> scopeElement.toString()
    }

    /**
     * If [arguments] carries a (`language`, `symbol`) pair for a Rider-supported
     * language but no position, ask the Rider backend to resolve the symbol to a
     * (file, line, column) and return a new JsonObject augmented with those keys.
     * Returns null when no rewrite is needed (already has position, language not
     * supported, or symbol not resolvable). The caller then falls through to
     * `resolveElementFromArguments` and the standard call-hierarchy handler so
     * depth/seen-set semantics stay identical to the position-form path.
     */
    private fun rewriteSymbolArgumentsForRider(project: Project, arguments: JsonObject): JsonObject? {
        val hasPosition = arguments[ParamNames.FILE] != null &&
                          arguments[ParamNames.LINE] != null &&
                          arguments[ParamNames.COLUMN] != null
        if (hasPosition) return null
        val language = arguments[ParamNames.LANGUAGE]?.jsonPrimitive?.content ?: return null
        val symbol = arguments[ParamNames.SYMBOL]?.jsonPrimitive?.content ?: return null
        val (file, line, column) = RiderBackendSemanticService.resolveSymbolToPosition(project, language, symbol) ?: return null
        return JsonObject(
            arguments.toMutableMap().apply {
                put(ParamNames.FILE, JsonPrimitive(file))
                put(ParamNames.LINE, JsonPrimitive(line))
                put(ParamNames.COLUMN, JsonPrimitive(column))
            }
        )
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

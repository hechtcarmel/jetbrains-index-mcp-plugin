package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for analyzing method call relationships across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class CallHierarchyTool : AbstractMcpTool() {

    override val name = "ide_call_hierarchy"

    override val description = """
        Analyzes method/function call relationships recursively to build a call hierarchy tree.

        SUPPORTED LANGUAGES: Java, Kotlin, Python, JavaScript, TypeScript

        REQUIRED: file + line + column to identify the method/function, plus direction.

        PARAMETERS:
        - direction: 'callers' = methods that call this method, 'callees' = methods this method calls
        - depth: how many levels deep to traverse (default: 3, max: 5)

        RETURNS: Tree structure with method signatures, file locations, and nested children.

        EXAMPLE Java: {"file": "src/main/java/com/example/Service.java", "line": 42, "column": 10, "direction": "callers"}
        EXAMPLE Python: {"file": "src/services/user_service.py", "line": 15, "column": 5, "direction": "callees"}
        EXAMPLE TypeScript: {"file": "src/services/api.ts", "line": 20, "column": 10, "direction": "callers", "depth": 2}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to the project root. Required when multiple projects are open.")
            }
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to the file relative to project root")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number")
            }
            putJsonObject("direction") {
                put("type", "string")
                put("description", "Direction: 'callers' (methods that call this method) or 'callees' (methods this method calls)")
                putJsonArray("enum") {
                    add(JsonPrimitive("callers"))
                    add(JsonPrimitive("callees"))
                }
            }
            putJsonObject("depth") {
                put("type", "integer")
                put("description", "How many levels deep to traverse the call hierarchy (default: 3, max: 5)")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("line"))
            add(JsonPrimitive("column"))
            add(JsonPrimitive("direction"))
        }
    }

    companion object {
        private const val DEFAULT_DEPTH = 3
        private const val MAX_DEPTH = 5
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")
        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: direction")
        val depth = (arguments["depth"]?.jsonPrimitive?.int ?: DEFAULT_DEPTH).coerceIn(1, MAX_DEPTH)

        if (direction !in listOf("callers", "callees")) {
            return createErrorResult("direction must be 'callers' or 'callees'")
        }

        requireSmartMode(project)

        return readAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult("No element found at position $file:$line:$column")

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getCallHierarchyHandler(element)
            if (handler == null) {
                return@readAction createErrorResult(
                    "No call hierarchy handler available for language: ${element.language.displayName}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForCallHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            val hierarchyData = handler.getCallHierarchy(element, project, direction, depth)
            if (hierarchyData == null) {
                return@readAction createErrorResult("No method/function found at position")
            }

            // Convert handler result to tool result
            createJsonResult(CallHierarchyResult(
                element = convertToCallElement(hierarchyData.element),
                calls = hierarchyData.calls.map { convertToCallElement(it) }
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
            language = data.language,
            children = data.children?.map { convertToCallElement(it) }
        )
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
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
 * Tool for finding implementations of interfaces, abstract classes, or methods across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class FindImplementationsTool : AbstractMcpTool() {

    override val name = "ide_find_implementations"

    override val description = """
        Find all implementations of an interface, abstract class, or abstract method. Use to discover concrete implementations when working with abstractions.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust.

        Returns: list of implementing classes/methods with file paths, line numbers, and kind (class/method).

        Parameters: file + line + column (required).

        Example: {"file": "src/Repository.java", "line": 8, "column": 18}
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
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("line"))
            add(JsonPrimitive("column"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")

        requireSmartMode(project)

        return readAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult("No element found at position $file:$line:$column")

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getImplementationsHandler(element)
            if (handler == null) {
                return@readAction createErrorResult(
                    "No implementations handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForImplementations()}"
                )
            }

            val implementations = handler.findImplementations(element, project)
            if (implementations == null) {
                return@readAction createErrorResult("No method or class found at position")
            }

            // Convert handler results to tool results
            val implementationLocations = implementations.map { impl ->
                ImplementationLocation(
                    name = impl.name,
                    file = impl.file,
                    line = impl.line,
                    kind = impl.kind,
                    language = impl.language
                )
            }

            createJsonResult(ImplementationResult(
                implementations = implementationLocations,
                totalCount = implementationLocations.size
            ))
        }
    }
}

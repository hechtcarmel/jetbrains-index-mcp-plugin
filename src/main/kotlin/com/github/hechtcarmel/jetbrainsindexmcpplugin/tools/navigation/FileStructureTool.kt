package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TreeFormatter
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for analyzing the hierarchical structure of source files.
 *
 * Provides a tree-formatted view of file structure similar to IDE's Structure view,
 * showing classes, methods, fields, and their nesting relationships.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Go, Rust
 */
class FileStructureTool : AbstractMcpTool() {

    override val name = "ide_file_structure"

    override val description = """
        Get the hierarchical structure of a source file (similar to IDE's Structure view).

        Shows classes, methods, fields, functions, and their nesting relationships in a tree format.

        Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Go, Rust

        Returns: Formatted tree string with element types, modifiers, signatures, and line numbers.

        Parameters: file (required) - Path relative to project root

        Example: {"file": "src/main/java/com/example/MyClass.java"}
    """.trimIndent()

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to project root. Only needed when multiple projects are open.")
            }
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
            }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("file"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")

        return readAction {
            val psiFile = getPsiFile(project, file)
                ?: return@readAction createErrorResult("File not found: $file")

            // Get structure handler for this file's language
            val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)
                ?: return@readAction createErrorResult(
                    "Language not supported for file structure. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForStructure().joinToString(", ")}"
                )

            // Extract structure
            val nodes = handler.getFileStructure(psiFile, project)

            if (nodes.isEmpty()) {
                return@readAction createSuccessResult(
                    "File is empty or has no parseable structure.\n\n" +
                    "File: ${psiFile.name}\n" +
                    "Language: ${psiFile.language.id}"
                )
            }

            // Format as tree
            val treeString = TreeFormatter.format(nodes, psiFile.name)

            createJsonResult(FileStructureResult(
                file = file,
                language = psiFile.language.id,
                structure = treeString
            ))
        }
    }
}

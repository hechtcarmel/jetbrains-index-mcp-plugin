package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.OpenFileResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class OpenFileTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.OPEN_FILE

    override val description = """
        Open a file in the IDE editor, optionally navigating to a specific line and column.

        Parameters: file (required, relative to project root or absolute), line (optional, 1-based), column (optional, 1-based, requires line), project_path (optional).

        Example: {"file": "src/Main.kt"} or {"file": "src/Main.kt", "line": 42, "column": 10}
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
                put("description", "File path relative to project root, or absolute path.")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number to navigate to.")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number to navigate to. Requires line to be specified.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")

        val line = arguments["line"]?.jsonPrimitive?.int
        val column = arguments["column"]?.jsonPrimitive?.int

        if (column != null && line == null) {
            return createErrorResult("Parameter 'column' requires 'line' to be specified.")
        }

        if (line != null && line < 1) {
            return createErrorResult("Parameter 'line' must be >= 1, got $line.")
        }

        if (column != null && column < 1) {
            return createErrorResult("Parameter 'column' must be >= 1, got $column.")
        }

        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")

        val relativePath = getRelativePath(project, virtualFile)

        withContext(Dispatchers.EDT) {
            if (line != null) {
                val lineIndex = line - 1
                val columnIndex = if (column != null) column - 1 else 0
                val descriptor = OpenFileDescriptor(project, virtualFile, lineIndex, columnIndex)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            } else {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }

        val message = if (line != null && column != null) {
            "Opened $relativePath at line $line, column $column."
        } else if (line != null) {
            "Opened $relativePath at line $line."
        } else {
            "Opened $relativePath."
        }

        return createJsonResult(OpenFileResult(
            file = relativePath,
            opened = true,
            message = message
        ))
    }
}

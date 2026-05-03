package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.OpenFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class OpenFileTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.OPEN_FILE

    override val description = """
        Open a file in the IDE editor, optionally navigating to a specific line and column.

        Parameters: file (required, relative to project root or absolute), line (optional, 1-based), column (optional, 1-based, requires line), project_path (optional).

        Example: {"file": "src/Main.kt"} or {"file": "src/Main.kt", "line": 42, "column": 10}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "File path relative to project root, or absolute path.")
        .intProperty("line", "1-based line number to navigate to.")
        .intProperty("column", "1-based column number to navigate to. Requires line to be specified.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = requiredStringArg(arguments, "file").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }

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

        edtAction {
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

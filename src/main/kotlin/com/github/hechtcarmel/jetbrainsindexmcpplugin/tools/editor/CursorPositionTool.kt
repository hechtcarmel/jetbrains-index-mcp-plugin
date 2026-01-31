package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CursorPositionResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool that returns the current cursor position in the IDE editor.
 *
 * Returns:
 * - `line`: 1-based line number
 * - `column`: 1-based column number
 * - `offset`: Character offset from start of file
 * - `file`: Path to the file where cursor is located
 */
class CursorPositionTool : AbstractMcpTool() {

    override val name = ToolNames.GET_CURSOR_POSITION

    override val description = """
        Get the current cursor position in the IDE editor. Use to understand where the user's cursor is located.

        Returns: line and column (1-based), character offset, and file path. Returns error if no editor is open.

        Parameters: project_path (optional, only needed with multiple projects open).

        Example: {}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to project root. Only needed when multiple projects are open.")
            }
        }
        putJsonArray("required") { }
    }

    override val requiresPsiSync: Boolean = false

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        return withContext(Dispatchers.EDT) {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editor = fileEditorManager.selectedTextEditor

            if (editor == null) {
                return@withContext createErrorResult("No editor is currently open")
            }

            val caretModel = editor.caretModel
            val caret = caretModel.currentCaret
            val document = editor.document

            val offset = caret.offset
            val logicalPosition = caret.logicalPosition

            // Convert to 1-based line/column
            val line = logicalPosition.line + 1
            val column = logicalPosition.column + 1

            createJsonResult(CursorPositionResult(
                line = line,
                column = column,
                offset = offset,
                file = editor.virtualFile?.path
            ))
        }
    }
}

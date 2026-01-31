package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SelectionResult
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
 * Tool that returns the current text selection in the IDE editor.
 *
 * Returns:
 * - `hasSelection`: Whether any text is selected
 * - `text`: The selected text (null if no selection)
 * - `startLine/startColumn`: Start position (1-based)
 * - `endLine/endColumn`: End position (1-based)
 * - `file`: Path to the file containing the selection
 */
class SelectionTool : AbstractMcpTool() {

    override val name = ToolNames.GET_SELECTION

    override val description = """
        Get the currently selected text in the IDE editor. Use to understand what code the user has highlighted.

        Returns: selected text, start/end positions (1-based line/column), and file path. Returns hasSelection=false if nothing is selected.

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
                return@withContext createJsonResult(SelectionResult(
                    hasSelection = false,
                    text = null,
                    startLine = null,
                    startColumn = null,
                    endLine = null,
                    endColumn = null,
                    file = null
                ))
            }

            val selectionModel = editor.selectionModel
            val selectedText = selectionModel.selectedText
            val hasSelection = !selectedText.isNullOrEmpty()

            if (!hasSelection) {
                return@withContext createJsonResult(SelectionResult(
                    hasSelection = false,
                    text = null,
                    startLine = null,
                    startColumn = null,
                    endLine = null,
                    endColumn = null,
                    file = editor.virtualFile?.path
                ))
            }

            val document = editor.document

            // Get selection start position
            val startOffset = selectionModel.selectionStart
            val startLine = document.getLineNumber(startOffset) + 1
            val startColumn = startOffset - document.getLineStartOffset(startLine - 1) + 1

            // Get selection end position
            val endOffset = selectionModel.selectionEnd
            val endLine = document.getLineNumber(endOffset) + 1
            val endColumn = endOffset - document.getLineStartOffset(endLine - 1) + 1

            createJsonResult(SelectionResult(
                hasSelection = true,
                text = selectedText,
                startLine = startLine,
                startColumn = startColumn,
                endLine = endLine,
                endColumn = endColumn,
                file = editor.virtualFile?.path
            ))
        }
    }
}

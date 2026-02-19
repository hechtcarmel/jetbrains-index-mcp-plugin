package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ActiveFileInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.GetActiveFileResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class GetActiveFileTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.GET_ACTIVE_FILE

    override val description = """
        Get the currently active file(s) open in the IDE editor. Returns information about all visible editors including split panes.

        Returns: list of active files with path, cursor position (line, column), selected text (if any), and language. Empty list when no editors are open.

        Parameters: project_path (optional, only needed with multiple projects open).

        Example: {}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to the project root. Required when multiple projects are open.")
            }
        }
        put("required", buildJsonArray { })
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val activeFiles = withContext(Dispatchers.EDT) {
            val editorManager = FileEditorManager.getInstance(project)
            val selectedEditors = editorManager.selectedEditors

            selectedEditors.mapNotNull { fileEditor ->
                val virtualFile = fileEditor.file ?: return@mapNotNull null
                val relativePath = getRelativePath(project, virtualFile)

                val textEditor = fileEditor as? TextEditor
                val editor = textEditor?.editor
                val caret = editor?.caretModel?.primaryCaret

                val line = caret?.let { it.logicalPosition.line + 1 }
                val column = caret?.let { it.logicalPosition.column + 1 }

                val selectionModel = editor?.selectionModel
                val hasSelection = selectionModel?.hasSelection() ?: false
                val selectedText = if (hasSelection) selectionModel?.selectedText else null

                val language = virtualFile.fileType.name

                ActiveFileInfo(
                    file = relativePath,
                    line = line,
                    column = column,
                    selectedText = selectedText,
                    hasSelection = hasSelection,
                    language = language
                )
            }
        }

        return createJsonResult(GetActiveFileResult(
            activeFiles = activeFiles
        ))
    }
}

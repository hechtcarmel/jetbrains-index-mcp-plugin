package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CurrentFileResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
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
 * Tool that returns information about the currently focused file in the IDE.
 *
 * This tool provides real-time editor context to MCP clients, enabling them
 * to understand what file the user is currently working on.
 *
 * Returns:
 * - `file`: Absolute path to the current file (null if no file is open)
 * - `relativePath`: Path relative to project root
 * - `language`: Language ID (e.g., "JAVA", "PHP", "Kotlin")
 * - `isModified`: Whether the file has unsaved changes
 */
class CurrentFileTool : AbstractMcpTool() {

    override val name = ToolNames.GET_CURRENT_FILE

    override val description = """
        Get the currently focused file in the IDE editor. Use to understand what file the user is actively working on.

        Returns: file path (absolute and relative), language, and modification status. Returns null values if no file is open.

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

    // This tool doesn't need PSI synchronization - it only reads editor state
    override val requiresPsiSync: Boolean = false

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        // FileEditorManager must be accessed from EDT
        return withContext(Dispatchers.EDT) {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editor = fileEditorManager.selectedTextEditor

            if (editor == null) {
                // No editor is open
                return@withContext createJsonResult(CurrentFileResult(
                    file = null,
                    relativePath = null,
                    language = null,
                    isModified = false
                ))
            }

            val virtualFile = editor.virtualFile
            if (virtualFile == null) {
                return@withContext createJsonResult(CurrentFileResult(
                    file = null,
                    relativePath = null,
                    language = null,
                    isModified = false
                ))
            }

            val absolutePath = virtualFile.path
            val relativePath = getRelativePath(project, virtualFile)

            // Get language from the file type
            val language = virtualFile.fileType.name

            // Check if document has unsaved changes
            val document = editor.document
            val isModified = FileDocumentManager.getInstance().isDocumentUnsaved(document)

            createJsonResult(CurrentFileResult(
                file = absolutePath,
                relativePath = relativePath,
                language = language,
                isModified = isModified
            ))
        }
    }
}

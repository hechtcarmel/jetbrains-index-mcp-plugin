package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class RestartIdeTool : AbstractMcpTool() {

    override val requiresPsiSync = false

    override val name = "ide_restart"

    override val description = """
        Restart the IDE.

        WARNING: this terminates the MCP server. The connection to this IDE will
        drop immediately and no response will be received after the restart is
        initiated. Call this as a final step — do not expect to execute further
        tool calls in the same session.

        Typical use: call ide_install_plugin, then ide_restart.

        Parameters:
        - project_path (optional): only needed when multiple projects are open.
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val app = ApplicationManager.getApplication()
        app.invokeLater {
            // Flush unsaved documents up front; the platform's exit sequence persists
            // application and project settings on its own (SAVE flag in ApplicationImpl.exit).
            FileDocumentManager.getInstance().saveAllDocuments()
            if (app is ApplicationEx) app.restart(true) else app.restart()
        }
        return createSuccessResult("Restarting IDE.")
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationEx
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

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val app = ApplicationManager.getApplication()
        app.invokeLater {
            app.saveAll()
            if (app is ApplicationEx) app.restart(true) else app.restart()
        }
        return createSuccessResult("Restarting IDE.")
    }
}

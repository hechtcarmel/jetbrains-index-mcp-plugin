package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import kotlinx.serialization.json.JsonObject

class CloseProjectTool : AbstractMcpTool() {

    override val requiresPsiSync = false

    override val name = "ide_close_project"

    override val description = """
        Close an open project window, freeing its memory and background processes.

        The project can be reopened via the IDE's Recent Projects list or ide_open_project.
        This is a non-blocking operation — the tool returns as soon as the close is scheduled.

        Parameters:
        - project_path (optional): required when multiple projects are open

        Example: { "project_path": "/Users/dev/myproject" }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val name = project.name
        // Skip actual close in test mode: invokeLater fires during super.tearDown() EDT drain,
        // which tries to dispose the test project and causes TestLoggerAssertionError.
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    ProjectManagerEx.getInstanceEx().closeAndDispose(project)
                }
            }
        }
        return createSuccessResult("Project '$name' is closing.")
    }
}

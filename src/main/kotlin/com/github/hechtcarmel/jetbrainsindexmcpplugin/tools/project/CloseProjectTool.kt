package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.ide.RecentProjectsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.wm.WindowManager
import kotlinx.serialization.json.JsonObject

class CloseProjectTool : AbstractMcpTool() {

    override val requiresPsiSync = false

    override val name = "ide_close_project"

    override val description = """
        Close an open project window, freeing its memory and background processes.

        The project can be reopened via the IDE's Recent Projects list or ide_open_project.
        This is a non-blocking operation — the tool returns as soon as the close is scheduled.

        Refuses to close the last open project: the MCP server needs at least one open
        project to serve requests (including ide_open_project), so open another project
        before closing this one.

        Parameters:
        - project_path (optional): required when multiple projects are open

        Example: { "project_path": "/Users/dev/myproject" }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDefault }
        if (openProjects.size <= 1) {
            return createErrorResult(
                "Refusing to close '${project.name}' — it is the last open project. " +
                    "The MCP server needs at least one open project to serve requests " +
                    "(including ide_open_project). Open another project first, then close this one."
            )
        }

        val projectName = project.name
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                // Mirror the platform's CloseProjectAction: persist the frame state before
                // closing and refresh the recent-projects path afterwards.
                WindowManager.getInstance().updateDefaultFrameInfoOnProjectClose(project)
                ProjectManagerEx.getInstanceEx().closeAndDispose(project)
                RecentProjectsManager.getInstance().updateLastProjectPath()
            }
        }
        return createSuccessResult("Project '$projectName' is closing.")
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class ReleaseProjectTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false
    override val participatesInLifecycle: Boolean = false

    override val name = "ide_release_project"

    override val description = """
        Release a project from MCP lifecycle management, returning full control to the user.

        Accepts an optional path parameter to release a project that is currently closed by
        the lifecycle manager — the project does not need to be open.

        After release:
        - Power Save Mode is disabled
        - All lifecycle timers are cancelled
        - The project is no longer auto-slept or auto-closed by MCP
        - Open projects remain open with full IntelliJ capabilities

        Use ide_release_all_projects to release every managed project at once.

        Parameters:
        - path (optional): Filesystem path of a closed managed project to release by path.
          If omitted, releases the project resolved by project_path (must be open).
        - project_path (optional): Routing hint when multiple projects are open.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .stringProperty("path", "Filesystem path of a closed managed project to release. Omit to release the routed project.")
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val modeService = ProjectModeService.getInstance()
        val explicitPath = optionalStringArg(arguments, "path")

        if (explicitPath != null) {
            if (!modeService.isManaged(explicitPath)) {
                return createSuccessResult("'$explicitPath' is not managed by MCP lifecycle — nothing to release.")
            }
            modeService.release(explicitPath)
            return createSuccessResult("'${explicitPath.substringAfterLast("/")}' released from MCP lifecycle management.")
        }

        if (!modeService.isManaged(project)) {
            return createSuccessResult("Project '${project.name}' is not managed by MCP lifecycle — nothing to release.")
        }
        modeService.release(project)
        return createSuccessResult(
            "Project '${project.name}' released from MCP lifecycle management. " +
            "Power Save Mode disabled. Full IntelliJ capabilities restored."
        )
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectMode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SetProjectModeTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = "ide_set_project_mode"

    override val description = """
        Set the lifecycle mode for a specific managed project.

        Modes:
        - active: Full IntelliJ capabilities. Power Save OFF. Use before code review or active editing.
        - background: Power Save ON, index and MCP fully functional. Inspections and highlighting off.
          Default mode when MCP is working. Transitions here automatically after focus is lost.
        - dormant: Power Save ON, editors closed, PSI caches dropped. Index stays loaded.
          MCP calls auto-wake to background. Transitions here after 2 min of MCP inactivity.
        - closed: Project fully closed. All memory freed. Auto-reopens on next MCP call (5-15s delay).
          Transitions here after 10 min of MCP inactivity.

        Use ide_set_all_project_modes to apply a mode to every managed project at once.

        Parameters:
        - mode: Target mode (active, background, dormant, closed)
        - project_path: Required when multiple projects are open.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .enumProperty(
            "mode",
            "Target mode: active, background, dormant, or closed.",
            listOf("active", "background", "dormant", "closed"),
            required = true
        )
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val modeStr = arguments["mode"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: mode")

        val mode = when (modeStr.lowercase()) {
            "active" -> ProjectMode.ACTIVE
            "background" -> ProjectMode.BACKGROUND
            "dormant" -> ProjectMode.DORMANT
            "closed" -> ProjectMode.CLOSED
            else -> return createErrorResult(
                "Invalid mode '$modeStr'. Valid values: active, background, dormant, closed"
            )
        }

        val modeService = ProjectModeService.getInstance()
        if (!modeService.isManaged(project)) modeService.enroll(project)
        modeService.transition(project, mode)

        return createSuccessResult("Project '${project.name}' mode set to ${mode.name.lowercase()}")
    }
}

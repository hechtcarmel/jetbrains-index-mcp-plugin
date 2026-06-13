package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectMode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SetAllProjectModesTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false
    override val participatesInLifecycle = false

    override val name = "ide_set_all_project_modes"

    override val description = """
        Set the lifecycle mode for every currently managed open project at once.

        CLOSED projects are skipped — they have no open Project object and cannot
        be transitioned directly. Use ide_open_project or ide_set_project_mode on
        a specific project to bring a closed project back first.

        When multiple projects are open, supply any one of them as project_path to
        satisfy the JSON-RPC resolver. The mode is applied to all managed open
        projects regardless of which project is specified.

        Parameters:
        - mode: Target mode for all managed open projects (active, background, dormant)
        - project_path (optional): Routing hint required when multiple projects are open;
          does not limit which projects are affected.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .enumProperty(
            "mode",
            "Target mode: active, background, or dormant. (closed is not supported — use ide_set_project_mode per project.)",
            listOf("active", "background", "dormant"),
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
            else -> return createErrorResult(
                "Invalid mode '$modeStr'. Valid values: active, background, dormant"
            )
        }

        val modeService = ProjectModeService.getInstance()
        val managedPaths = modeService.getAllManagedModes().keys
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault && managedPaths.contains(it.basePath) }

        if (openProjects.isEmpty()) {
            return createSuccessResult("No managed open projects found — nothing changed.")
        }

        openProjects.forEach { modeService.transition(it, mode) }

        return createSuccessResult(
            "Set ${openProjects.size} project(s) to ${mode.name.lowercase()}: " +
                openProjects.joinToString(", ") { it.name }
        )
    }
}

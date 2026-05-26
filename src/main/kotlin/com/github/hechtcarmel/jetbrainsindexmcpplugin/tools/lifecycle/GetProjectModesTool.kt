package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetProjectModesTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false
    override val participatesInLifecycle: Boolean = false

    override val name = "ide_get_project_modes"

    override val description = """
        List all MCP-managed projects and their current lifecycle mode.

        Returns each managed project's path, name, and current mode
        (active, background, dormant, or closed). Use this to decide
        which projects need waking before starting work, or to audit
        current memory usage patterns.

        Parameters:
        - project_path (optional): only needed when multiple projects are open; does not
          affect which projects are listed — all managed projects are always returned.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool().projectPath().build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val modeService = ProjectModeService.getInstance()
        val allModes = modeService.getAllManagedModes()

        if (allModes.isEmpty()) {
            return createSuccessResult("No projects are currently managed by MCP lifecycle. " +
                "Projects are enrolled automatically on first MCP tool use.")
        }

        val result = buildJsonObject {
            put("managed_projects", buildJsonArray {
                allModes.forEach { (path, mode) ->
                    add(buildJsonObject {
                        put("path", path)
                        put("name", path.substringAfterLast("/"))
                        put("mode", mode.name.lowercase())
                    })
                }
            })
            put("total", allModes.size)
        }

        return createJsonResult(result)
    }
}

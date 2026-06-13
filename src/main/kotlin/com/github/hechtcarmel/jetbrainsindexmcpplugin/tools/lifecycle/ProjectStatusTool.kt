package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProjectStatusTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_project_status"

    override val description = """
        Report the status of all known projects in one table.

        Combines two sources:
        - Open projects: every project currently loaded in the IDE
        - Managed projects: projects enrolled in MCP lifecycle management

        Each row includes whether the project is open, whether it is managed, and
        its current lifecycle mode (active, background, dormant, closed) when managed.

        Use this to answer questions like:
        - Which open projects are not yet enrolled in lifecycle management?
        - Which managed projects have been closed by the lifecycle manager?
        - What is the current state of every project at a glance?

        Parameters:
        - project_path (optional): routing hint, required when multiple projects are open.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool().projectPath().build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val lifecycleEnabled = McpSettings.getInstance().lifecycleEnabled
        val managedModes = if (lifecycleEnabled)
            ProjectModeService.getInstance().getAllManagedModes()
        else
            emptyMap()

        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault }
            .associate { (it.basePath ?: "") to it.name }

        val allPaths = (openProjects.keys + managedModes.keys).filter { it.isNotEmpty() }.toSortedSet()

        val projects = allPaths.map { path ->
            val isOpen = path in openProjects
            val mode = managedModes[path]
            buildJsonObject {
                put("name", openProjects[path] ?: path.substringAfterLast("/"))
                put("path", path)
                put("open", isOpen)
                put("managed", mode != null)
                if (mode != null) put("mode", mode.name.lowercase())
            }
        }.sortedWith(
            compareByDescending<JsonObject> { it["open"].toString() == "true" }
                .thenBy { it["name"].toString().trim('"') }
        )

        val openCount = projects.count { it["open"].toString() == "true" }
        val managedCount = projects.count { it["managed"].toString() == "true" }

        val result = buildJsonObject {
            put("projects", buildJsonArray { projects.forEach { add(it) } })
            put("summary", buildJsonObject {
                put("total", projects.size)
                put("open", openCount)
                put("managed", managedCount)
                put("open_not_managed", projects.count {
                    it["open"].toString() == "true" && it["managed"].toString() == "false"
                })
                put("managed_closed", projects.count {
                    it["managed"].toString() == "true" && it["open"].toString() == "false"
                })
            })
        }

        return createJsonResult(result)
    }
}

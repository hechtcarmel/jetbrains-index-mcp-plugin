package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

class ReleaseAllProjectsTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_release_all_projects"

    override val description = """
        Release every managed project from MCP lifecycle management at once.

        Works on all managed projects including those currently closed by the lifecycle
        manager. Power Save Mode is disabled after all releases complete.

        Parameters:
        - project_path (optional): Routing hint when multiple projects are open.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool().projectPath().build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val modeService = ProjectModeService.getInstance()
        val count = modeService.getAllManagedModes().size
        modeService.releaseAll()
        return createSuccessResult("Released $count project${if (count == 1) "" else "s"} from MCP lifecycle management.")
    }
}

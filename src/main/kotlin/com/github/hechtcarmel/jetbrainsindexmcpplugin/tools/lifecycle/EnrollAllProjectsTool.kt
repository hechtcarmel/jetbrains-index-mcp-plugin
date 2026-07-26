package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.json.JsonObject

class EnrollAllProjectsTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_enroll_all_projects"

    override val description = """
        Enroll all currently open projects in MCP lifecycle management.

        Projects already managed are skipped. Only open projects can be enrolled —
        closed projects must be opened first (via ide_open_project or auto-open).

        Parameters:
        - project_path (optional): Routing hint when multiple projects are open.
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool().projectPath().build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val modeService = ProjectModeService.getInstance()
        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDefault }
        val before = openProjects.count { modeService.isManaged(it) }
        modeService.enrollAll(openProjects)
        val enrolled = openProjects.count { modeService.isManaged(it) } - before
        val already = openProjects.size - enrolled
        return createSuccessResult(
            "Enrolled $enrolled project${if (enrolled == 1) "" else "s"}" +
            if (already > 0) " ($already already managed)." else "."
        )
    }
}

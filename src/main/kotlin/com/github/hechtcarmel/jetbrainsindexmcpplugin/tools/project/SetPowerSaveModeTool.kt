package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class SetPowerSaveModeTool : AbstractMcpTool() {

    override val requiresPsiSync = false

    override val name = "ide_set_power_save_mode"

    override val description = """
        Enable or disable Power Save Mode for the IDE.

        Power Save Mode suspends background inspections, on-the-fly code analysis, and
        auto-import suggestions, reducing CPU and memory usage. The index and all code
        intelligence operations (find usages, refactoring, navigation) remain fully functional.

        Useful when a project is open but not being actively edited — for example, when
        running searches or refactoring across multiple open projects.

        Parameters:
        - enabled: true to enable Power Save Mode, false to disable it
        - project_path (optional): only needed when multiple projects are open

        Example: { "enabled": true }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .booleanProperty("enabled", "true to enable Power Save Mode, false to disable it.", required = true)
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val enabled = arguments["enabled"]?.jsonPrimitive?.boolean
            ?: return createErrorResult("Missing required parameter: enabled")

        edtAction { PowerSaveMode.setEnabled(enabled) }

        return createSuccessResult(
            "Power Save Mode ${if (enabled) "enabled" else "disabled"} for '${project.name}'"
        )
    }
}

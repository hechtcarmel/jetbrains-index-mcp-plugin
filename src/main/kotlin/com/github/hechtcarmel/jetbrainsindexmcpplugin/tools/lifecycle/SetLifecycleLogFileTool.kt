package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class SetLifecycleLogFileTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_set_lifecycle_log_file"

    override val description = """
        Enable or disable writing lifecycle events to the log file on disk.

        The in-memory ring buffer (queryable via ide_lifecycle_log) is always active
        regardless of this setting. This controls whether events are also appended to
        the persistent log file alongside idea.log, allowing tail -f monitoring and
        post-mortem analysis even when no MCP connection is available.

        File output is also enabled automatically when IntelliJ's debug logger is active
        for the lifecycle category (Help → Diagnostic Tools → Debug Log Settings,
        add #com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle).

        Parameters:
        - enabled (required): true to start writing to the log file, false to stop
        - project_path (optional): routing hint when multiple projects are open

        Example: { "enabled": true }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .booleanProperty("enabled", "true to write events to the log file, false to stop.", required = true)
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val enabled = arguments["enabled"]?.jsonPrimitive?.boolean
            ?: return createErrorResult("Missing required parameter: enabled")

        McpSettings.getInstance().lifecycleLogToFile = enabled

        val logFile = PathManager.getLogPath() + "/mcp-lifecycle.log"
        return createSuccessResult(
            if (enabled) "Lifecycle log file enabled. Events are being written to: $logFile"
            else "Lifecycle log file disabled. Events remain in the in-memory ring buffer (ide_lifecycle_log still works)."
        )
    }
}

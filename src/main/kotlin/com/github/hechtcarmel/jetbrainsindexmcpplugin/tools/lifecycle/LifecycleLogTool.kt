package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.LifecycleEventLog
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LifecycleLogTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_lifecycle_log"

    override val description = """
        Return recent lifecycle events for all projects.

        Records every state transition, open/close, focus change, timer firing, and MCP-triggered
        wake in a ring buffer (last 500 events). Events cover all IntelliJ projects, not just
        managed ones.

        The same log is written to a file (see log_file in the response) that can be read directly
        even when no project is open: `cat <log_file>` or `tail -f <log_file>`.

        Use this to diagnose lifecycle health: understand why a project closed, whether auto-open
        worked, or which projects are cycling unexpectedly.

        Event types: open, closed, transition, enroll, release, wake
        Trigger values: focus_gained, focus_lost, timer:focus, timer:inactivity, timer:close,
                        mcp_call, auto_open, user

        Parameters:
        - limit: Number of recent events to return, newest first (default 50, max 500)
        - project: Optional path filter — only events for projects whose path contains this string
        - project_path: Routing hint when multiple projects are open
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .intProperty("limit", "Number of recent events to return, newest first (default 50, max 500).")
        .stringProperty("project", "Optional path filter (substring match against project path).")
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 50
        val pathFilter = optionalStringArg(arguments, "project")

        val log = LifecycleEventLog.getInstance()
        val events = log.recent(limit, pathFilter)

        val result = buildJsonObject {
            put("events", buildJsonArray { events.forEach { add(it.toJson()) } })
            put("log_file", log.logFilePath.toString())
            put("buffered", log.size)
        }

        return createJsonResult(result)
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.coroutines.resume

class OpenProjectTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    // Opening a project is infrastructure — it should not auto-enroll it in lifecycle
    // management. Enrollment happens on the first real semantic tool call (find references,
    // diagnostics, etc.) after the project is open, which signals genuine intent to work on it.
    override val participatesInLifecycle = false

    override val name = "ide_open_project"

    override val description = """
        Open a project by filesystem path and wait until indexing is complete.

        Blocks until the IDE is ready for code intelligence on the opened project,
        so subsequent MCP tool calls against the new project will succeed immediately.

        This tool does not enroll the project in lifecycle management. Lifecycle enrollment
        happens automatically on the first real semantic tool call (find references,
        diagnostics, refactoring, etc.) after the project is open — not on open/close itself.

        Requires at least one project to already be open (needed as the JSON-RPC context).

        Parameters:
        - path: filesystem path of the project to open (required)
        - project_path (optional): selects the JSON-RPC context project when multiple are open

        Example: { "path": "/Users/dev/myproject" }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .stringProperty("path", "Filesystem path of the project directory to open.", required = true)
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val path = arguments["path"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: path")

        val opened = try {
            ProjectManagerEx.getInstanceEx().openProjectAsync(
                Path.of(path),
                OpenProjectTask()
            )
        } catch (e: Throwable) {
            return createErrorResult("Failed to open project at: $path (${e.message})")
        } ?: return createErrorResult("Failed to open project at: $path")

        suspendCancellableCoroutine { continuation ->
            DumbService.getInstance(opened).runWhenSmart { continuation.resume(Unit) }
        }

        return createSuccessResult("Project '${opened.name}' is open and ready.")
    }
}

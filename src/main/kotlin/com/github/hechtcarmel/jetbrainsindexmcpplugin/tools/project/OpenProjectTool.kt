package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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

    override val name = "ide_open_project"

    override val description = """
        Open a project by filesystem path and wait until indexing is complete.

        Blocks until the IDE is ready for code intelligence on the opened project,
        so subsequent MCP tool calls against the new project will succeed immediately.

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

        // Validate before calling openProjectAsync — the platform call hangs indefinitely
        // in headless/CI environments when the path does not exist.
        val dir = java.io.File(path)
        if (!dir.exists()) return createErrorResult("Path does not exist: $path")
        if (!dir.isDirectory) return createErrorResult("Path is not a directory: $path")

        val opened = ProjectManagerEx.getInstanceEx().openProjectAsync(
            Path.of(path),
            openTask()
        ) ?: return createErrorResult("Failed to open project at: $path")

        // DumbService.runWhenSmart must be called from a non-modal EDT context.
        suspendCancellableCoroutine { continuation ->
            ApplicationManager.getApplication().invokeLater({
                if (!opened.isDisposed) {
                    DumbService.getInstance(opened).runWhenSmart {
                        if (!continuation.isCompleted) continuation.resume(Unit)
                    }
                } else {
                    continuation.cancel()
                }
            }, ModalityState.nonModal())
        }

        return createSuccessResult("Project '${opened.name}' is open and ready.")
    }

    private fun openTask(): OpenProjectTask =
        OpenProjectTask.build().withForceOpenInNewFrame(true)
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path

class OpenProjectTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_open_project"

    override val description = """
        Open a project by filesystem path and wait until indexing is complete.

        Blocks until the IDE is ready for code intelligence on the opened project,
        so subsequent MCP tool calls against the new project will succeed immediately.
        If the project is already open, returns successfully right away.

        This tool does not enroll the project in lifecycle management. Lifecycle enrollment
        happens automatically on the first real semantic tool call (find references,
        diagnostics, refactoring, etc.) after the project is open — not on open/close itself.

        Requires at least one project to already be open (needed as the JSON-RPC context).

        Opening a project the IDE has not seen before may show the modal "Trust project?"
        dialog, which only a human can answer; the call fails after timeoutSeconds if the
        project has not opened by then.

        Parameters:
        - path: absolute filesystem path of the project directory to open (required)
        - timeoutSeconds (optional): maximum seconds to wait for opening + indexing. Default: $DEFAULT_TIMEOUT_SECONDS.
        - project_path (optional): selects the JSON-RPC context project when multiple are open

        Example: { "path": "/Users/dev/myproject" }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .stringProperty("path", "Absolute filesystem path of the project directory to open.", required = true)
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds to wait for the project to open and finish indexing. " +
                "Must be a positive integer. Default: $DEFAULT_TIMEOUT_SECONDS."
        )
        .projectPath()
        .build()

    private enum class OpenOutcome { OPEN_FAILED, CLOSED_WHILE_WAITING, READY }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val path = requiredStringArg(arguments, "path").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: path")
        }
        if (!File(path).isAbsolute) {
            return createErrorResult("path must be an absolute path, got: $path")
        }

        val timeoutSeconds = arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_TIMEOUT_SECONDS
        if (timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer.")
        }

        ProjectUtils.findOpenProjectByPath(path)?.let {
            return createSuccessResult("Project '${it.name}' is already open.")
        }

        val dir = File(path)
        if (!dir.exists()) return createErrorResult("Path does not exist: $path")
        if (!dir.isDirectory) return createErrorResult("Path is not a directory: $path")

        var openedProject: Project? = null
        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            val opened = ProjectManagerEx.getInstanceEx().openProjectAsync(Path.of(path), ProjectUtils.openTask())
                ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
            openedProject = opened
            if (ProjectUtils.awaitSmartMode(opened)) OpenOutcome.READY else OpenOutcome.CLOSED_WHILE_WAITING
        }

        return when (outcome) {
            OpenOutcome.READY ->
                createSuccessResult("Project '${openedProject!!.name}' is open and ready.")

            OpenOutcome.OPEN_FAILED ->
                createErrorResult("Failed to open project at: $path")

            OpenOutcome.CLOSED_WHILE_WAITING ->
                createErrorResult("Project at $path was closed while waiting for indexing to finish.")

            null -> {
                val opened = openedProject
                if (opened != null && !opened.isDisposed) {
                    createSuccessResult(
                        "Project '${opened.name}' is open but still indexing after ${timeoutSeconds}s. " +
                            "Index-dependent tools may fail until indexing completes — check ide_index_status."
                    )
                } else {
                    createErrorResult(
                        "Timed out after ${timeoutSeconds}s waiting for the project at $path to open. " +
                            "If the IDE is showing a 'Trust project?' dialog, a human must answer it; " +
                            "otherwise retry with a larger timeoutSeconds."
                    )
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 600
    }
}

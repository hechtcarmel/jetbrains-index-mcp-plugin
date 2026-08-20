package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildSystemLinker
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.LinkResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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

        When invoked, this tool marks the target directory as trusted before opening,
        so the trust dialog does not appear. Build scripts (Maven, Gradle) may execute
        on import.

        Parameters:
        - path: absolute filesystem path of the project directory to open (required)
        - autoLink (optional): when true, automatically link an unlinked Maven/Gradle build system after opening. Default: false.
        - timeoutSeconds (optional): maximum seconds to wait for opening + indexing. Default: $DEFAULT_TIMEOUT_SECONDS.
        - project_path (optional): selects the JSON-RPC context project when multiple are open

        Example: { "path": "/Users/dev/myproject", "autoLink": true }
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .stringProperty("path", "Absolute filesystem path of the project directory to open.", required = true)
        .booleanProperty("autoLink", "Automatically link an unlinked Maven/Gradle build system after opening. Default: false.")
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds to wait for the project to open and finish indexing. " +
                "Must be a positive integer. Default: $DEFAULT_TIMEOUT_SECONDS."
        )
        .projectPath()
        .build()

    private enum class OpenOutcome { OPEN_FAILED, CLOSED_WHILE_WAITING, READY }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val path = requiredStringArg(arguments, "path").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: path")
        }
        if (!File(path).isAbsolute) {
            return createErrorResult("path must be an absolute path, got: $path")
        }

        val autoLink = arguments["autoLink"]?.jsonPrimitive?.booleanOrNull ?: false
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

        TrustedProjects.setProjectTrusted(Path.of(path), true)

        var openedProject: Project? = null
        var linkMsg: String? = null
        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            val opened = ProjectManagerEx.getInstanceEx().openProjectAsync(Path.of(path), ProjectUtils.openTask())
                ?: return@withTimeoutOrNull OpenOutcome.OPEN_FAILED
            openedProject = opened
            if (!ProjectUtils.awaitSmartMode(opened)) return@withTimeoutOrNull OpenOutcome.CLOSED_WHILE_WAITING
            if (autoLink) {
                linkMsg = tryAutoLink(opened, path)
                ProjectUtils.awaitSmartMode(opened)
            }
            OpenOutcome.READY
        }

        return when (outcome) {
            OpenOutcome.READY -> {
                val msg = "Project '${openedProject!!.name}' is open and ready."
                if (linkMsg != null) createSuccessResult("$msg $linkMsg") else createSuccessResult(msg)
            }

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

    private suspend fun tryAutoLink(project: Project, path: String): String? {
        return try {
            when (val result = BuildSystemLinker.linkBuildSystem(project, path)) {
                is LinkResult.Linked -> "${result.systemName} project linked."
                is LinkResult.AlreadyLinked -> null
                is LinkResult.NoBuildFile -> null
                is LinkResult.PluginUnavailable -> "Auto-link skipped: ${result.systemName} plugin not available."
                is LinkResult.Failed -> "Auto-link failed: ${result.error}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            "Auto-link failed: ${e.message}"
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 600
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.IndexNotReadyException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.error
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Everything that happens between "a client asked for tool X" and "the tool ran".
 *
 * The MCP protocol itself — JSON-RPC framing, `initialize`, version negotiation, `tools/list`
 * dispatch — belongs to the SDK. What is left is IDE-specific and lives here:
 *
 *  1. the enabled/disabled gate from Settings → Index MCP Server → Available Tools
 *  2. resolving `project_path` to an open [Project], reopening it if the lifecycle manager
 *     closed it
 *  3. recording the call in the per-project command history shown in the tool window
 *  4. running the tool inside an IDE modality context
 *
 * ### Why the modality context is here and not in the HTTP layer
 *
 * Ktor worker threads carry no IntelliJ modality state, and
 * [com.intellij.psi.PsiDocumentManager.commitAllDocuments] requires a write-safe context.
 * Before the SDK migration this wrapper sat around the whole HTTP handler; the SDK owns that
 * now, so it wraps the narrower and more correct thing: the tool invocation itself.
 */
class McpToolDispatcher @JvmOverloads constructor(
    private val toolRegistry: ToolRegistry,
    private val recordHistory: (Project, CommandEntry) -> Unit = { project, entry ->
        CommandHistoryService.getInstance(project).recordCommand(entry)
    },
    private val updateHistory: (Project, String, CommandStatus, String?, Long?) -> Unit = { project, id, status, result, duration ->
        CommandHistoryService.getInstance(project).updateCommandStatus(id, status, result, duration)
    }
) {

    private companion object {
        val LOG = logger<McpToolDispatcher>()

        /**
         * History is for display, not replay. `ide_find_references` on a popular class can return
         * 100 KB+, and every entry would otherwise sit in the per-project deque.
         */
        const val HISTORY_RESULT_LIMIT = 4096
    }

    /**
     * Runs [toolName] with [arguments].
     *
     * Never throws for tool-level problems: per the MCP spec, tool failures are reported in the
     * result with `isError = true` so the model can read and react to them, rather than as
     * JSON-RPC errors which clients treat as hard transport failures. Cancellation still
     * propagates.
     */
    suspend fun call(toolName: String, arguments: JsonObject): CallToolResult {
        val tool = toolRegistry.getTool(toolName)
            ?: return CallToolResult.error(ErrorMessages.toolNotFound(toolName))

        if (!McpSettings.getInstance().isToolEnabled(toolName)) {
            return CallToolResult.error(
                "Tool '$toolName' is disabled. Enable it in Settings → Index MCP Server → Available Tools."
            )
        }

        val projectPathElement = arguments[ParamNames.PROJECT_PATH]
        if (projectPathElement != null &&
            projectPathElement !is JsonNull &&
            (projectPathElement as? JsonPrimitive)?.isString != true
        ) {
            return CallToolResult.error("${ParamNames.PROJECT_PATH} must be a string when present")
        }
        val projectPath = (projectPathElement as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        val projectResult = ProjectResolver.resolveOrOpen(projectPath)
        if (projectResult.isError) return projectResult.errorResult!!
        val project = projectResult.project!!

        val commandEntry = CommandEntry(toolName = toolName, parameters = arguments)
        recordHistorySafely(project, commandEntry)

        val startTime = System.currentTimeMillis()
        return try {
            val result = withIdeModality { tool.execute(project, arguments) }
            updateHistorySafely(
                project = project,
                commandEntry = commandEntry,
                status = if (result.isError == true) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result = result.summarizeForHistory(),
                duration = System.currentTimeMillis() - startTime
            )
            result
        } catch (e: ProcessCanceledException) {
            // IntelliJ control-flow exception (e.g. project disposed mid-call). Must not be
            // logged as an error and must not become a tool result.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndexNotReadyException) {
            // Dumb mode is expected during indexing — debug, not error.
            LOG.debug("Tool $toolName called while IDE is indexing: ${e.message}")
            failed(project, commandEntry, startTime, e.message ?: "IDE index is not ready")
        } catch (e: Exception) {
            LOG.error("Tool execution failed: $toolName", e)
            failed(project, commandEntry, startTime, e.message ?: ErrorMessages.UNKNOWN_ERROR)
        }
    }

    private fun failed(
        project: Project,
        commandEntry: CommandEntry,
        startTime: Long,
        message: String
    ): CallToolResult {
        updateHistorySafely(
            project = project,
            commandEntry = commandEntry,
            status = CommandStatus.ERROR,
            result = message,
            duration = System.currentTimeMillis() - startTime
        )
        return CallToolResult.error(message)
    }

    private fun CallToolResult.summarizeForHistory(): String? =
        when (val block = content.firstOrNull()) {
            is TextContent -> block.text.take(HISTORY_RESULT_LIMIT)
            is ImageContent -> "[Image]"
            null -> null
            else -> "[${block::class.simpleName}]"
        }

    private suspend fun <T> withIdeModality(block: suspend () -> T): T {
        // Null in plain unit tests that never boot the platform.
        ApplicationManager.getApplication() ?: return block()
        return withContext(ModalityState.any().asContextElement()) { block() }
    }

    private fun recordHistorySafely(project: Project, commandEntry: CommandEntry) {
        try {
            recordHistory(project, commandEntry)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to record command history for ${commandEntry.toolName}", e)
        }
    }

    private fun updateHistorySafely(
        project: Project,
        commandEntry: CommandEntry,
        status: CommandStatus,
        result: String?,
        duration: Long
    ) {
        try {
            updateHistory(project, commandEntry.id, status, result, duration)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to update command history for ${commandEntry.toolName}", e)
        }
    }
}

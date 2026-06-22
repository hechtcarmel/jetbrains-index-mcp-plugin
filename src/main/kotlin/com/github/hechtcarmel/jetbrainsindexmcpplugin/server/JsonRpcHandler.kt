package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.JsonRpcMethods
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.IndexNotReadyException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class JsonRpcHandler @JvmOverloads constructor(
    private val toolRegistry: ToolRegistry,
    private val recordHistory: (Project, CommandEntry) -> Unit = { project, entry ->
        CommandHistoryService.getInstance(project).recordCommand(entry)
    },
    private val updateHistory: (Project, String, CommandStatus, String?, Long?) -> Unit = { project, id, status, result, duration ->
        CommandHistoryService.getInstance(project).updateCommandStatus(id, status, result, duration)
    }
) {
    private val projectResolver = ProjectResolver
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    companion object {
        private val LOG = logger<JsonRpcHandler>()
    }

    suspend fun handleRequest(jsonString: String): String? =
        handleRequest(jsonString, McpConstants.MCP_PROTOCOL_VERSION)

    suspend fun handleRequest(
        jsonString: String,
        protocolVersion: String
    ): String? {
        val request = try {
            json.decodeFromString<JsonRpcRequest>(jsonString)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to parse JSON-RPC request", e)
            return json.encodeToString(createErrorResponse(code = JsonRpcErrorCodes.PARSE_ERROR, message = ErrorMessages.PARSE_ERROR))
        }

        if (request.jsonrpc != "2.0") {
            return json.encodeToString(createErrorResponse(
                id = request.id,
                code = JsonRpcErrorCodes.INVALID_REQUEST,
                message = "Invalid JSON-RPC version: ${request.jsonrpc}. Expected \"2.0\"."
            ))
        }

        val response = try {
            routeRequest(request, protocolVersion)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.error("Error processing request: ${request.method}", e)
            createErrorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Unknown error")
        }

        return response?.let { json.encodeToString(response) }
    }

    private suspend fun routeRequest(request: JsonRpcRequest, protocolVersion: String): JsonRpcResponse? {
        return when (request.method) {
            JsonRpcMethods.INITIALIZE -> processInitialize(request, protocolVersion)
            JsonRpcMethods.NOTIFICATIONS_INITIALIZED -> null
            JsonRpcMethods.TOOLS_LIST -> processToolsList(request)
            JsonRpcMethods.TOOLS_CALL -> processToolCall(request)
            JsonRpcMethods.PING -> processPing(request)
            else -> createErrorResponse(request.id, JsonRpcErrorCodes.METHOD_NOT_FOUND, ErrorMessages.methodNotFound(request.method))
        }
    }

    private fun processInitialize(request: JsonRpcRequest, protocolVersion: String): JsonRpcResponse {
        val result = InitializeResult(
            protocolVersion = protocolVersion,
            serverInfo = ServerInfo(
                name = McpConstants.SERVER_NAME,
                version = McpConstants.SERVER_VERSION,
                description = McpConstants.SERVER_DESCRIPTION
            ),
            capabilities = ServerCapabilities(
                tools = ToolCapability(listChanged = false)
            )
        )

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private fun processToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val tools = toolRegistry.getToolDefinitions()
        val result = ToolsListResult(tools = tools)

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private suspend fun processToolCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, ErrorMessages.MISSING_PARAMS)

        val toolName = params[ParamNames.NAME]?.jsonPrimitive?.contentOrNull
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, ErrorMessages.MISSING_TOOL_NAME)

        val arguments = params[ParamNames.ARGUMENTS]?.jsonObject ?: JsonObject(emptyMap())

        val tool = toolRegistry.getTool(toolName)
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.METHOD_NOT_FOUND, ErrorMessages.toolNotFound(toolName))

        // Extract optional project_path from arguments
        val projectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.contentOrNull

        val projectResult = projectResolver.resolveOrOpen(projectPath)
        if (projectResult.isError) {
            return JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(projectResult.errorResult!!)
            )
        }

        val project = projectResult.project!!

        // Record command in history
        val commandEntry = CommandEntry(
            toolName = toolName,
            parameters = arguments
        )

        recordHistorySafely(project, commandEntry)

        val startTime = System.currentTimeMillis()

        return try {
            val result = tool.execute(project, arguments)
            val duration = System.currentTimeMillis() - startTime

            // Update history
            updateHistorySafely(
                project = project,
                commandEntry = commandEntry,
                status = if (result.isError) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result = result.content.firstOrNull()?.let {
                    when (it) {
                        // Truncate to 4 KB — history is for display, not replay.
                        // Large tool responses (find_references on popular classes can be 100 KB+)
                        // would otherwise accumulate in the per-project history deque and waste heap.
                        is ContentBlock.Text -> it.text.take(4096)
                        is ContentBlock.Image -> "[Image]"
                    }
                },
                duration = duration
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: IndexNotReadyException) {
            // Dumb mode is expected during indexing — log at debug, not error.
            val duration = System.currentTimeMillis() - startTime
            LOG.debug("Tool $toolName called while IDE is indexing: ${e.message}")
            updateHistorySafely(project = project, commandEntry = commandEntry,
                status = CommandStatus.ERROR, result = e.message, duration = duration)
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = -32603, message = e.message ?: ErrorMessages.INDEX_NOT_READY)
            )
        } catch (e: ProcessCanceledException) {
            // IntelliJ control-flow exception (e.g. project disposed mid-call) — must not be
            // logged with LOG.error; rethrow so the coroutine machinery handles it cleanly.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            LOG.error("Tool execution failed: $toolName", e)

            updateHistorySafely(
                project = project,
                commandEntry = commandEntry,
                status = CommandStatus.ERROR,
                result = e.message,
                duration = duration
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(
                    ToolCallResult(
                        content = listOf(ContentBlock.Text(text = e.message ?: ErrorMessages.UNKNOWN_ERROR)),
                        isError = true
                    )
                )
            )
        }
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

    private fun processPing(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    private fun createErrorResponse(
        id: JsonElement? = null,
        code: Int,
        message: String
    ) = JsonRpcResponse(
        id = id,
        error = JsonRpcError(code = code, message = message)
    )
}

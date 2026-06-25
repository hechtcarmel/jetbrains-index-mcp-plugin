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
        val requestElement = try {
            json.parseToJsonElement(jsonString)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to parse JSON-RPC request", e)
            return json.encodeToString(createErrorResponse(code = JsonRpcErrorCodes.PARSE_ERROR, message = ErrorMessages.PARSE_ERROR))
        }

        val rawRequest = requestElement as? JsonObject
            ?: return json.encodeToString(createErrorResponse(
                code = JsonRpcErrorCodes.INVALID_REQUEST,
                message = "JSON-RPC request must be a JSON object"
            ))

        validateRequestShape(rawRequest)?.let { errorResponse ->
            return json.encodeToString(errorResponse)
        }

        validateToolsCallRequestShape(rawRequest)?.let { errorResponse ->
            return json.encodeToString(errorResponse)
        }

        val request = try {
            json.decodeFromJsonElement<JsonRpcRequest>(rawRequest)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to decode JSON-RPC request", e)
            return json.encodeToString(createErrorResponse(code = JsonRpcErrorCodes.INVALID_REQUEST, message = "Malformed JSON-RPC request structure"))
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

        val toolNameElement = params[ParamNames.NAME]
        val toolName = toolNameElement?.stringContentOrNull()
        if (toolNameElement != null && toolName == null) {
            return createErrorResponse(
                request.id,
                JsonRpcErrorCodes.INVALID_PARAMS,
                "tools/call params.name must be a string"
            )
        }
        if (toolName == null) {
            return createErrorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, ErrorMessages.MISSING_TOOL_NAME)
        }

        val argumentsElement = params[ParamNames.ARGUMENTS]
        if (argumentsElement != null && argumentsElement !is JsonObject && argumentsElement !is JsonNull) {
            return createErrorResponse(
                request.id,
                JsonRpcErrorCodes.INVALID_PARAMS,
                "tools/call params.arguments must be a JSON object when present"
            )
        }

        val arguments = when (argumentsElement) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> argumentsElement
            else -> JsonObject(emptyMap())
        }

        // Extract optional project_path from arguments
        val projectPathElement = arguments[ParamNames.PROJECT_PATH]
        val projectPath = projectPathElement?.stringContentOrNull(allowNullLiteral = true)
        if (projectPathElement != null && projectPathElement !is JsonNull && projectPath == null) {
            return createErrorResponse(
                request.id,
                JsonRpcErrorCodes.INVALID_PARAMS,
                "tools/call params.arguments.project_path must be a string when present"
            )
        }

        val tool = toolRegistry.getTool(toolName)
            ?: return createErrorResponse(request.id, JsonRpcErrorCodes.METHOD_NOT_FOUND, ErrorMessages.toolNotFound(toolName))

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
                error = JsonRpcError(code = e.errorCode, message = e.message ?: "IDE index is not ready")
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

    private fun validateRequestShape(rawRequest: JsonObject): JsonRpcResponse? {
        val methodElement = rawRequest["method"] ?: return null
        val method = methodElement.stringContentOrNull()
            ?: return createErrorResponse(
                id = rawRequest["id"],
                code = JsonRpcErrorCodes.INVALID_REQUEST,
                message = "JSON-RPC method must be a string"
            )

        if (method == JsonRpcMethods.TOOLS_CALL) {
            return null
        }

        val paramsElement = rawRequest["params"]
        if (paramsElement != null && paramsElement !is JsonObject && paramsElement !is JsonNull) {
            return createErrorResponse(
                id = rawRequest["id"],
                code = JsonRpcErrorCodes.INVALID_REQUEST,
                message = "JSON-RPC params must be a JSON object when present"
            )
        }

        return null
    }

    private fun validateToolsCallRequestShape(rawRequest: JsonObject): JsonRpcResponse? {
        val method = rawRequest["method"]?.stringContentOrNull() ?: return null
        if (method != JsonRpcMethods.TOOLS_CALL) return null

        val id = rawRequest["id"]
        val paramsElement = rawRequest["params"]
        if (paramsElement != null && paramsElement !is JsonObject && paramsElement !is JsonNull) {
            return createErrorResponse(
                id = id,
                code = JsonRpcErrorCodes.INVALID_PARAMS,
                message = "tools/call params must be a JSON object"
            )
        }

        val paramsObject = paramsElement as? JsonObject ?: return null
        val argumentsElement = paramsObject[ParamNames.ARGUMENTS]
        if (argumentsElement != null && argumentsElement !is JsonObject && argumentsElement !is JsonNull) {
            return createErrorResponse(
                id = id,
                code = JsonRpcErrorCodes.INVALID_PARAMS,
                message = "tools/call params.arguments must be a JSON object when present"
            )
        }

        return null
    }

    private fun JsonElement.stringContentOrNull(allowNullLiteral: Boolean = false): String? {
        if (allowNullLiteral && this is JsonNull) return null
        val primitive = this as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.contentOrNull
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

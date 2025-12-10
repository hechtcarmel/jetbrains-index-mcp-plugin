package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.JsonRpcMethods
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class JsonRpcHandler(
    private val toolRegistry: ToolRegistry
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    companion object {
        private val LOG = logger<JsonRpcHandler>()
    }

    suspend fun handleRequest(jsonString: String): String {
        val request = try {
            json.decodeFromString<JsonRpcRequest>(jsonString)
        } catch (e: Exception) {
            LOG.warn("Failed to parse JSON-RPC request", e)
            return json.encodeToString(createParseErrorResponse())
        }

        val response = try {
            routeRequest(request)
        } catch (e: Exception) {
            LOG.error("Error processing request: ${request.method}", e)
            createInternalErrorResponse(request.id, e.message ?: "Unknown error")
        }

        return json.encodeToString(response)
    }

    private suspend fun routeRequest(request: JsonRpcRequest): JsonRpcResponse {
        return when (request.method) {
            JsonRpcMethods.INITIALIZE -> processInitialize(request)
            JsonRpcMethods.INITIALIZED -> processInitialized(request)
            JsonRpcMethods.TOOLS_LIST -> processToolsList(request)
            JsonRpcMethods.TOOLS_CALL -> processToolCall(request)
            JsonRpcMethods.PING -> processPing(request)
            else -> createMethodNotFoundResponse(request.id, request.method)
        }
    }

    private fun processInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result = InitializeResult(
            protocolVersion = McpConstants.MCP_PROTOCOL_VERSION,
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

    private fun processInitialized(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
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
            ?: return createInvalidParamsResponse(request.id, ErrorMessages.MISSING_PARAMS)

        val toolName = params[ParamNames.NAME]?.jsonPrimitive?.contentOrNull
            ?: return createInvalidParamsResponse(request.id, ErrorMessages.MISSING_TOOL_NAME)

        val arguments = params[ParamNames.ARGUMENTS]?.jsonObject ?: JsonObject(emptyMap())

        val tool = toolRegistry.getTool(toolName)
            ?: return createMethodNotFoundResponse(request.id, ErrorMessages.toolNotFound(toolName))

        // Extract optional project_path from arguments
        val projectPath = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.contentOrNull

        val projectResult = resolveProject(projectPath)
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

        val historyService = try {
            CommandHistoryService.getInstance(project)
        } catch (e: Exception) {
            null
        }
        historyService?.recordCommand(commandEntry)

        val startTime = System.currentTimeMillis()

        return try {
            val result = tool.execute(project, arguments)
            val duration = System.currentTimeMillis() - startTime

            // Update history
            historyService?.updateCommandStatus(
                commandEntry.id,
                if (result.isError) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result.content.firstOrNull()?.let {
                    when (it) {
                        is ContentBlock.Text -> it.text
                        is ContentBlock.Image -> "[Image]"
                    }
                },
                duration
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            LOG.error("Tool execution failed: $toolName", e)

            historyService?.updateCommandStatus(
                commandEntry.id,
                CommandStatus.ERROR,
                e.message,
                duration
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

    private fun processPing(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    private data class ProjectResolutionResult(
        val project: Project? = null,
        val errorResult: ToolCallResult? = null,
        val isError: Boolean = false
    )

    private fun resolveProject(projectPath: String?): ProjectResolutionResult {
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault }

        // No projects open
        if (openProjects.isEmpty()) {
            return ProjectResolutionResult(
                isError = true,
                errorResult = ToolCallResult(
                    content = listOf(ContentBlock.Text(
                        text = json.encodeToString(buildJsonObject {
                            put("error", ErrorMessages.ERROR_NO_PROJECT_OPEN)
                            put("message", ErrorMessages.MSG_NO_PROJECT_OPEN)
                        })
                    )),
                    isError = true
                )
            )
        }

        // If project_path is provided, find matching project
        if (projectPath != null) {
            val matchingProject = openProjects.find { it.basePath == projectPath }
            return if (matchingProject != null) {
                ProjectResolutionResult(project = matchingProject)
            } else {
                ProjectResolutionResult(
                    isError = true,
                    errorResult = ToolCallResult(
                        content = listOf(ContentBlock.Text(
                            text = json.encodeToString(buildJsonObject {
                                put("error", ErrorMessages.ERROR_PROJECT_NOT_FOUND)
                                put("message", ErrorMessages.msgProjectNotFound(projectPath))
                                put("available_projects", buildJsonArray {
                                    openProjects.forEach { proj ->
                                        add(buildJsonObject {
                                            put("name", proj.name)
                                            put("path", proj.basePath ?: "")
                                        })
                                    }
                                })
                            })
                        )),
                        isError = true
                    )
                )
            }
        }

        // Only one project open - use it
        if (openProjects.size == 1) {
            return ProjectResolutionResult(project = openProjects.first())
        }

        // Multiple projects open, no path specified - return error with list
        return ProjectResolutionResult(
            isError = true,
            errorResult = ToolCallResult(
                content = listOf(ContentBlock.Text(
                    text = json.encodeToString(buildJsonObject {
                        put("error", ErrorMessages.ERROR_MULTIPLE_PROJECTS)
                        put("message", ErrorMessages.MSG_MULTIPLE_PROJECTS)
                        put("available_projects", buildJsonArray {
                            openProjects.forEach { proj ->
                                add(buildJsonObject {
                                    put("name", proj.name)
                                    put("path", proj.basePath ?: "")
                                })
                            }
                        })
                    })
                )),
                isError = true
            )
        )
    }

    private fun getCurrentProject(): Project? {
        val openProjects = ProjectManager.getInstance().openProjects
        return openProjects.firstOrNull { !it.isDefault }
    }

    private fun createParseErrorResponse(): JsonRpcResponse {
        return JsonRpcResponse(
            error = JsonRpcError(
                code = JsonRpcErrorCodes.PARSE_ERROR,
                message = ErrorMessages.PARSE_ERROR
            )
        )
    }

    private fun createInvalidParamsResponse(id: JsonElement?, message: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(
                code = JsonRpcErrorCodes.INVALID_PARAMS,
                message = message
            )
        )
    }

    private fun createMethodNotFoundResponse(id: JsonElement?, method: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(
                code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
                message = ErrorMessages.methodNotFound(method)
            )
        )
    }

    private fun createInternalErrorResponse(id: JsonElement?, message: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(
                code = JsonRpcErrorCodes.INTERNAL_ERROR,
                message = message
            )
        )
    }
}

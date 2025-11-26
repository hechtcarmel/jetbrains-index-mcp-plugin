package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.resources.FileContentResource
import com.github.hechtcarmel.jetbrainsindexmcpplugin.resources.ResourceRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.resources.SymbolInfoResource
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class JsonRpcHandler(
    private val toolRegistry: ToolRegistry,
    private val resourceRegistry: ResourceRegistry
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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
            "initialize" -> processInitialize(request)
            "initialized" -> processInitialized(request)
            "tools/list" -> processToolsList(request)
            "tools/call" -> processToolCall(request)
            "resources/list" -> processResourcesList(request)
            "resources/read" -> processResourceRead(request)
            "ping" -> processPing(request)
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
                tools = ToolCapability(listChanged = false),
                resources = ResourceCapability(subscribe = false, listChanged = false)
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
            ?: return createInvalidParamsResponse(request.id, "Missing params")

        val toolName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return createInvalidParamsResponse(request.id, "Missing tool name")

        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        val tool = toolRegistry.getTool(toolName)
            ?: return createMethodNotFoundResponse(request.id, "Tool not found: $toolName")

        // Extract optional project_path from arguments
        val projectPath = arguments["project_path"]?.jsonPrimitive?.contentOrNull

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
                        content = listOf(ContentBlock.Text(text = e.message ?: "Unknown error")),
                        isError = true
                    )
                )
            )
        }
    }

    private suspend fun processResourcesList(request: JsonRpcRequest): JsonRpcResponse {
        val resources = resourceRegistry.getResourceDefinitions()
        val result = ResourcesListResult(resources = resources)

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private suspend fun processResourceRead(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return createInvalidParamsResponse(request.id, "Missing params")

        val uri = params["uri"]?.jsonPrimitive?.contentOrNull
            ?: return createInvalidParamsResponse(request.id, "Missing resource URI")

        val resource = resourceRegistry.getResource(uri)
            ?: return createMethodNotFoundResponse(request.id, "Resource not found: $uri")

        // Extract optional project_path from params
        val projectPath = params["project_path"]?.jsonPrimitive?.contentOrNull

        val projectResult = resolveProject(projectPath)
        if (projectResult.isError) {
            return JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(projectResult.errorResult!!)
            )
        }

        val project = projectResult.project!!

        return try {
            // Handle parameterized resources
            val content = when (resource) {
                is FileContentResource -> {
                    // Extract path from URI: file://content/{path}
                    val path = extractPathFromUri(uri, "file://content/")
                    if (path != null) {
                        resource.readWithPath(project, path)
                    } else {
                        resource.read(project)
                    }
                }
                is SymbolInfoResource -> {
                    // Extract FQN from URI: symbol://info/{fqn}
                    val fqn = extractPathFromUri(uri, "symbol://info/")
                    if (fqn != null) {
                        resource.readWithFqn(project, fqn)
                    } else {
                        resource.read(project)
                    }
                }
                else -> resource.read(project)
            }
            val result = ResourceReadResult(contents = listOf(content))

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            LOG.error("Resource read failed: $uri", e)
            createInternalErrorResponse(request.id, e.message ?: "Unknown error")
        }
    }

    private fun extractPathFromUri(uri: String, prefix: String): String? {
        return if (uri.startsWith(prefix) && uri.length > prefix.length) {
            uri.substring(prefix.length)
        } else {
            null
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
                            put("error", "no_project_open")
                            put("message", "No project is currently open in the IDE.")
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
                                put("error", "project_not_found")
                                put("message", "No open project matches the specified path: $projectPath")
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
                        put("error", "multiple_projects_open")
                        put("message", "Multiple projects are open. Please specify 'project_path' parameter with one of the available project paths.")
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
                message = "Failed to parse JSON-RPC request"
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
                message = "Method not found: $method"
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

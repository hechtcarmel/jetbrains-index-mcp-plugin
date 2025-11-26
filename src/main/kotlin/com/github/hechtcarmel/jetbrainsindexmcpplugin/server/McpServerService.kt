package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.resources.ResourceRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.jetbrains.ide.BuiltInServerManager

@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val toolRegistry: ToolRegistry = ToolRegistry()
    private val resourceRegistry: ResourceRegistry = ResourceRegistry()
    private val jsonRpcHandler: JsonRpcHandler

    companion object {
        private val LOG = logger<McpServerService>()

        fun getInstance(): McpServerService = service()
    }

    init {
        LOG.info("Initializing MCP Server Service")
        jsonRpcHandler = JsonRpcHandler(toolRegistry, resourceRegistry)

        // Register built-in tools
        toolRegistry.registerBuiltInTools()

        // Register built-in resources
        resourceRegistry.registerBuiltInResources()

        LOG.info("MCP Server Service initialized")
    }

    fun getToolRegistry(): ToolRegistry = toolRegistry

    fun getResourceRegistry(): ResourceRegistry = resourceRegistry

    fun getJsonRpcHandler(): JsonRpcHandler = jsonRpcHandler

    fun getServerUrl(): String {
        val port = BuiltInServerManager.getInstance().port
        return "http://localhost:$port${McpRequestHandler.MCP_PATH}"
    }

    fun getServerPort(): Int {
        return BuiltInServerManager.getInstance().port
    }

    override fun dispose() {
        LOG.info("Disposing MCP Server Service")
    }
}

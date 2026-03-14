package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.mcpserver.impl.McpServerService as NativeMcpServerService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger

/**
 * Application-level facade for plugin-managed MCP integration.
 *
 * JetBrains' built-in MCP server owns transport, authentication, and session lifecycle.
 * This service only manages the plugin's tool registry and exposes native server state
 * to the rest of the plugin UI.
 */
@Service(Service.Level.APP)
class McpServerService {

    private val toolRegistry = ToolRegistry()

    @Volatile
    var isInitialized: Boolean = false
        private set

    companion object {
        private val LOG = logger<McpServerService>()

        fun getInstance(): McpServerService = service()
    }

    init {
        initialize()
    }

    @Synchronized
    fun initialize() {
        if (isInitialized) {
            return
        }

        toolRegistry.registerBuiltInTools()
        isInitialized = true
        notifyStatusChanged()
        LOG.info("Initialized native MCP integration with ${toolRegistry.getAllTools().size} tools")
    }

    fun getToolRegistry(): ToolRegistry {
        initialize()
        return toolRegistry
    }

    fun getEnabledTools(): List<McpTool> {
        val settings = McpSettings.getInstance()
        return getToolRegistry().getAllTools().filter { settings.isToolEnabled(it.name) }
    }

    fun isServerRunning(): Boolean = nativeServerService()?.isRunning ?: false

    fun getServerUrl(): String? {
        val nativeService = nativeServerService() ?: return null
        return nativeService.serverSseUrl.takeIf { nativeService.isRunning }
    }

    fun isNativeServerAvailable(): Boolean = nativeServerService() != null

    fun getServerInfo(): ServerStatusInfo {
        val nativeService = nativeServerService()
        val serverUrl = nativeService?.serverSseUrl
        val isRunning = nativeService?.isRunning ?: false
        val statusMessage = when {
            nativeService == null -> "JetBrains MCP Server dependency is unavailable."
            isRunning && !serverUrl.isNullOrBlank() -> "JetBrains built-in MCP Server is running."
            else -> "JetBrains built-in MCP Server is available but currently not running."
        }

        return ServerStatusInfo(
            version = McpConstants.SERVER_VERSION,
            serverUrl = serverUrl,
            registeredTools = toolRegistry.getAllTools().size,
            enabledTools = getEnabledTools().size,
            isRunning = isRunning,
            statusMessage = statusMessage
        )
    }

    fun notifyStatusChanged() {
        ApplicationManager.getApplication().invokeLater({
            ApplicationManager.getApplication().messageBus
                .syncPublisher(McpConstants.SERVER_STATUS_TOPIC)
                .serverStatusChanged()
        }, ModalityState.any())
    }

    private fun nativeServerService(): NativeMcpServerService? {
        return runCatching { NativeMcpServerService.Companion.getInstance() }
            .onFailure { LOG.debug("Failed to access JetBrains MCP server service", it) }
            .getOrNull()
    }
}

data class ServerStatusInfo(
    val version: String,
    val serverUrl: String?,
    val registeredTools: Int,
    val enabledTools: Int,
    val isRunning: Boolean,
    val statusMessage: String
)

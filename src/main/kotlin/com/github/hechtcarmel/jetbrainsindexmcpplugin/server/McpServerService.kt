package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.ServerStatusListener
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorSseSessionManager
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettingsConfigurable
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Application-level service managing the MCP server infrastructure.
 *
 * This service manages:
 * - Embedded Ktor CIO server with configurable port
 * - Tool registry for MCP tools
 * - JSON-RPC handler for message processing
 * - SSE session management for client connections
 * - Coroutine scope for non-blocking tool execution
 *
 * Uses HTTP+SSE transport for compatibility with MCP clients.
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val toolRegistry: ToolRegistry = ToolRegistry()
    private val jsonRpcHandler: JsonRpcHandler
    private val sseSessionManager: KtorSseSessionManager = KtorSseSessionManager()
    private var ktorServer: KtorMcpServer? = null
    private var serverError: ServerError? = null

    /**
     * Coroutine scope for non-blocking tool execution.
     * Uses SupervisorJob so failures in one tool don't cancel others.
     * Uses Default dispatcher for CPU-bound PSI operations.
     * Uses ModalityState.any() so EDT-bound work executes even when modal dialogs are open,
     * preventing MCP tool calls from hanging indefinitely (see issue #68).
     */
    val coroutineScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + ModalityState.any().asContextElement()
    )

    /**
     * Represents a server error state.
     */
    data class ServerError(
        val message: String,
        val port: Int? = null
    )

    @Volatile
    var isInitialized: Boolean = false
        private set

    companion object {
        private val LOG = logger<McpServerService>()

        fun getInstance(): McpServerService = service()
    }

    init {
        LOG.info("Initializing MCP Server Service (Protocol: ${McpConstants.MCP_PROTOCOL_VERSION})")
        jsonRpcHandler = JsonRpcHandler(toolRegistry)
        // Self-initialize asynchronously so the server starts even if postStartupActivity
        // doesn't fire (see issue #73). initialize() is idempotent (@Synchronized + isInitialized
        // guard), so the redundant call from McpServerStartupActivity is a safe no-op.
        coroutineScope.launch { initialize() }
    }

    @Synchronized
    fun initialize() {
        if (isInitialized) return

        LOG.info("Performing deferred MCP Server initialization")

        toolRegistry.registerBuiltInTools()

        val settings = McpSettings.getInstance()
        val port = settings.serverPort
        val host = settings.serverHost
        isInitialized = true
        startServer(host, port)

        LOG.info("MCP Server Service initialized with Ktor CIO server")
    }

    /**
     * Starts the MCP server on the specified port.
     *
     * @param host The host to bind to
     * @param port The port to listen on
     * @return The result of the start operation
     */
    fun startServer(host: String, port: Int): KtorMcpServer.StartResult {
        // Stop existing server if running
        stopServer()

        LOG.info("Starting MCP Server on $host:$port")

        val server = KtorMcpServer(
            port = port,
            host = host,
            jsonRpcHandler = jsonRpcHandler,
            sseSessionManager = sseSessionManager,
            coroutineScope = coroutineScope
        )

        val result = when (val startResult = server.start()) {
            is KtorMcpServer.StartResult.Success -> {
                ktorServer = server
                serverError = null
                LOG.info("MCP Server started successfully on $host:$port")
                startResult
            }
            is KtorMcpServer.StartResult.PortInUse -> {
                serverError = ServerError("Port $port is already in use", port)
                showErrorNotification(
                    McpBundle.message("notification.serverPortInUse.title"),
                    McpBundle.message("notification.serverPortInUse.content", port, host)
                )
                startResult
            }
            is KtorMcpServer.StartResult.Error -> {
                serverError = ServerError(startResult.message)
                LOG.warn("Failed to start MCP Server: ${startResult.message}", startResult.cause)
                showErrorNotification(
                    McpBundle.message("notification.serverStartFailed.title"),
                    McpBundle.message("notification.serverStartFailed.content", startResult.message)
                )
                startResult
            }
        }

        // Notify listeners that server status changed
        notifyStatusChanged()

        return result
    }

    /**
     * Notifies all listeners that the server status has changed.
     */
    private fun notifyStatusChanged() {
        ApplicationManager.getApplication().invokeLater({
            ApplicationManager.getApplication().messageBus
                .syncPublisher(McpConstants.SERVER_STATUS_TOPIC)
                .serverStatusChanged()
        }, ModalityState.any())
    }

    /**
     * Stops the MCP server.
     */
    fun stopServer() {
        ktorServer?.stop()
        ktorServer = null
    }

    /**
     * Restarts the MCP server on a new host/port.
     *
     * @param newHost The new host to bind to
     * @param newPort The new port to listen on
     * @return The result of the restart operation
     */
    fun restartServer(newHost: String, newPort: Int): KtorMcpServer.StartResult {
        LOG.info("Restarting MCP Server on $newHost:$newPort")
        return startServer(newHost, newPort)
    }

    /**
     * Returns whether the server is currently running.
     */
    fun isServerRunning(): Boolean = ktorServer?.isRunning() == true

    /**
     * Returns the current server error, if any.
     */
    fun getServerError(): ServerError? = serverError

    fun getToolRegistry(): ToolRegistry = toolRegistry

    fun getJsonRpcHandler(): JsonRpcHandler = jsonRpcHandler

    fun getSseSessionManager(): KtorSseSessionManager = sseSessionManager

    /**
     * Returns the SSE endpoint URL for MCP connections.
     * Clients should connect to this URL to establish SSE stream.
     *
     * @return The server URL, or null if server is not running
     */
    fun getServerUrl(): String? {
        if (ktorServer == null || serverError != null) return null
        val settings = McpSettings.getInstance()
        val port = settings.serverPort
        val host = settings.serverHost
        return "http://$host:$port${McpConstants.SSE_ENDPOINT_PATH}"
    }

    /**
     * Returns the configured server port.
     */
    fun getServerPort(): Int = McpSettings.getInstance().serverPort

    /**
     * Returns information about the server status.
     */
    fun getServerInfo(): ServerStatusInfo {
        val settings = McpSettings.getInstance()
        val port = settings.serverPort
        val host = settings.serverHost
        val isRunning = isServerRunning()
        return ServerStatusInfo(
            name = McpConstants.SERVER_NAME,
            version = McpConstants.SERVER_VERSION,
            protocolVersion = McpConstants.MCP_PROTOCOL_VERSION,
            sseUrl = if (isRunning) "http://$host:$port${McpConstants.SSE_ENDPOINT_PATH}" else "Server not running",
            postUrl = "http://$host:$port${McpConstants.MCP_ENDPOINT_PATH}",
            port = port,
            registeredTools = toolRegistry.getAllTools().size,
            error = serverError?.message,
            isRunning = isRunning
        )
    }

    /**
     * Shows an error notification with an action to open settings.
     */
    private fun showErrorNotification(title: String, content: String) {
        ApplicationManager.getApplication().invokeLater({
            NotificationGroupManager.getInstance()
                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                    title,
                    content,
                    NotificationType.ERROR
                )
                .addAction(object : NotificationAction(McpBundle.message("notification.action.openSettings")) {
                    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(null, McpSettingsConfigurable::class.java)
                        notification.expire()
                    }
                })
                .notify(null)
        }, ModalityState.any())
    }

    override fun dispose() {
        LOG.info("Disposing MCP Server Service")
        stopServer()
        sseSessionManager.closeAllSessions()
        coroutineScope.cancel("McpServerService disposed")
    }
}

/**
 * Data class containing server status information.
 */
data class ServerStatusInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
    val sseUrl: String,
    val postUrl: String,
    val port: Int,
    val registeredTools: Int,
    val error: String? = null,
    val isRunning: Boolean = true
)

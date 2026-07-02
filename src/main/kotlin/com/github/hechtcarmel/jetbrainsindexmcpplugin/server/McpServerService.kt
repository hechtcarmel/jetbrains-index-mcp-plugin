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
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.util.Alarm
import kotlinx.coroutines.CoroutineScope
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
class McpServerService(
    private val coroutineScope: CoroutineScope
) : Disposable {

    private val toolRegistry: ToolRegistry = ToolRegistry()
    private val jsonRpcHandler: JsonRpcHandler
    private val sseSessionManager: KtorSseSessionManager = KtorSseSessionManager()
    private var ktorServer: KtorMcpServer? = null
    private var serverError: ServerError? = null

    // Watchdog: restarts the server if it stops unexpectedly.
    // The reactive path (ApplicationStopped event) fires immediately; the safety-net
    // alarm is a backup in case the reactive signal is missed.
    @Volatile private var isShuttingDown = false
    @Volatile private var restartAttempts = 0
    private val watchdogAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    companion object {
        private val LOG = logger<McpServerService>()
        private const val WATCHDOG_INTERVAL_MS = 30_000
        private val RESTART_BACKOFF_MS = listOf(5_000, 15_000, 30_000)

        fun getInstance(): McpServerService = service()
    }

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

    init {
        LOG.info("Initializing MCP Server Service (Protocol: ${McpConstants.MCP_PROTOCOL_VERSION})")
        jsonRpcHandler = JsonRpcHandler(toolRegistry)
        if (shouldStartServer()) {
            coroutineScope.launch { initialize() }
        } else {
            LOG.info("Skipping MCP Server auto-start in unit/headless environment")
        }
    }

    @Synchronized
    fun initialize() {
        if (isInitialized) return

        LOG.info("Performing deferred MCP Server initialization")

        toolRegistry.registerBuiltInTools()

        isInitialized = true
        val startServer = shouldStartServer()
        if (startServer) {
            val settings = McpSettings.getInstance()
            val port = settings.serverPort
            val host = settings.serverHost
            startServer(host, port)
            LOG.info("MCP Server Service initialized with Ktor CIO server")
        } else {
            LOG.info("Initialized MCP tool metadata without starting server in unit/headless environment")
        }
    }

    private fun shouldStartServer(): Boolean {
        val application = ApplicationManager.getApplication()
        return !application.isUnitTestMode && !application.isHeadlessEnvironment
    }

    /**
     * Starts the MCP server on the specified port.
     *
     * @param host The host to bind to
     * @param port The port to listen on
     * @return The result of the start operation
     */
    fun startServer(host: String, port: Int): KtorMcpServer.StartResult {
        watchdogAlarm.cancelAllRequests()
        stopServer()

        LOG.info("Starting MCP Server on $host:$port")

        val server = KtorMcpServer(
            port = port,
            host = host,
            jsonRpcHandler = jsonRpcHandler,
            sseSessionManager = sseSessionManager,
            coroutineScope = coroutineScope,
            onUnexpectedStop = { scheduleRestart() }
        )

        val result = when (val startResult = server.start()) {
            is KtorMcpServer.StartResult.Success -> {
                ktorServer = server
                serverError = null
                LOG.info("MCP Server started successfully on $host:$port")
                scheduleWatchdog()
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
     * Returns the Streamable HTTP endpoint URL for MCP connections (primary transport).
     * Clients should use this URL for the MCP 2025-03-26 Streamable HTTP transport.
     *
     * @return The server URL, or null if server is not running
     */
    fun getServerUrl(): String? {
        if (ktorServer == null || serverError != null) return null
        val settings = McpSettings.getInstance()
        val port = settings.serverPort
        val host = settings.serverHost
        return "http://$host:$port${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}"
    }

    /**
     * Returns the legacy SSE endpoint URL for older MCP clients (2024-11-05 transport).
     *
     * @return The SSE URL, or null if server is not running
     */
    fun getLegacySseUrl(): String? {
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
            streamableHttpUrl = if (isRunning) "http://$host:$port${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}" else "Server not running",
            legacySseUrl = if (isRunning) "http://$host:$port${McpConstants.SSE_ENDPOINT_PATH}" else "Server not running",
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
        val application = ApplicationManager.getApplication()
        if (application.isHeadlessEnvironment) return
        application.invokeLater({
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

    private fun scheduleRestart() {
        if (isShuttingDown) return
        val delayMs = RESTART_BACKOFF_MS.getOrElse(restartAttempts) { RESTART_BACKOFF_MS.last() }.toLong()
        restartAttempts++
        LOG.warn("Scheduling MCP Server restart in ${delayMs}ms (attempt $restartAttempts)")
        watchdogAlarm.addRequest({
            if (!isShuttingDown) {
                val settings = McpSettings.getInstance()
                val result = startServer(settings.serverHost, settings.serverPort)
                if (result is KtorMcpServer.StartResult.Success) {
                    restartAttempts = 0
                    LOG.info("watchdog: MCP Server restarted successfully")
                    scheduleWatchdog()
                } else {
                    LOG.warn("watchdog: MCP Server restart failed ($result) — will retry")
                    scheduleRestart()
                }
            }
        }, delayMs)
    }

    private fun scheduleWatchdog() {
        if (isShuttingDown) return
        watchdogAlarm.addRequest({
            if (!isShuttingDown && isInitialized) {
                if (!isServerRunning()) {
                    LOG.warn("watchdog: MCP Server not running — triggering restart")
                    scheduleRestart()
                } else {
                    scheduleWatchdog()
                }
            }
        }, WATCHDOG_INTERVAL_MS.toLong())
    }

    override fun dispose() {
        LOG.info("Disposing MCP Server Service")
        isShuttingDown = true
        watchdogAlarm.cancelAllRequests()
        stopServer()
        sseSessionManager.closeAllSessions()
    }
}

/**
 * Data class containing server status information.
 */
data class ServerStatusInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
    val streamableHttpUrl: String,
    val legacySseUrl: String,
    val postUrl: String,
    val port: Int,
    val registeredTools: Int,
    val error: String? = null,
    val isRunning: Boolean = true
)

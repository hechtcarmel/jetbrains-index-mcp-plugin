package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.BindException

/**
 * Embedded Ktor CIO server for MCP protocol.
 *
 * Provides HTTP+SSE transport for MCP clients with configurable port.
 *
 * Supports two transport modes:
 * 1. SSE Transport (2024-11-05 spec):
 *    - GET /index-mcp/sse → Opens SSE stream, sends `endpoint` event with POST URL
 *    - POST /index-mcp?sessionId=xxx → JSON-RPC messages, response sent via SSE
 *
 * 2. Streamable HTTP Transport (for clients like Claude Code):
 *    - POST /index-mcp (no sessionId) → JSON-RPC messages, immediate JSON response
 */
class KtorMcpServer(
    private val port: Int,
    private val host: String = McpConstants.DEFAULT_SERVER_HOST,
    private val jsonRpcHandler: JsonRpcHandler,
    private val sseSessionManager: KtorSseSessionManager,
    private val coroutineScope: CoroutineScope
) : Disposable {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    companion object {
        private val LOG = logger<KtorMcpServer>()
    }

    /**
     * Result of attempting to start the server.
     */
    sealed class StartResult {
        data object Success : StartResult()
        data class PortInUse(val port: Int) : StartResult()
        data class Error(val message: String, val cause: Throwable? = null) : StartResult()
    }

    /**
     * Starts the server.
     *
     * @return StartResult indicating success or failure with details
     */
    fun start(): StartResult {
        return try {
            server = embeddedServer(CIO, port = port, host = host) {
                configureCors()
                configureRouting()
            }
            server?.start(wait = false)

            LOG.info("MCP Server started on http://$host:$port")
            StartResult.Success
        } catch (e: BindException) {
            LOG.warn("Port $port is already in use", e)
            StartResult.PortInUse(port)
        } catch (e: Exception) {
            LOG.error("Failed to start MCP server", e)
            StartResult.Error(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Stops the server gracefully.
     */
    fun stop() {
        try {
            server?.stop(1000, 2000)
            server = null
            LOG.info("MCP Server stopped")
        } catch (e: Exception) {
            LOG.warn("Error stopping MCP server", e)
        }
    }

    /**
     * Returns whether the server is currently running.
     */
    fun isRunning(): Boolean = server != null

    override fun dispose() = stop()

    private fun Application.configureCors() {
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Accept)
        }
    }

    private fun Application.configureRouting() {
        routing {
            // GET /index-mcp/sse → Open SSE stream
            get(McpConstants.SSE_ENDPOINT_PATH) {
                handleSseRequest(call)
            }

            // POST /index-mcp → JSON-RPC (Streamable HTTP or with sessionId for SSE)
            post(McpConstants.MCP_ENDPOINT_PATH) {
                handlePostRequest(call)
            }

            // POST /index-mcp/sse → JSON-RPC (some clients POST to SSE endpoint)
            post(McpConstants.SSE_ENDPOINT_PATH) {
                handlePostRequest(call)
            }
        }
    }

    /**
     * Handles GET /index-mcp/sse - Opens SSE stream.
     *
     * Creates a new SSE session and sends the `endpoint` event with the POST URL.
     * Keeps the connection open for sending responses to JSON-RPC requests.
     */
    private suspend fun handleSseRequest(call: ApplicationCall) {
        val sessionId = sseSessionManager.createSession()
        LOG.info("Opening SSE connection, session: $sessionId")

        // Create a channel for sending SSE events
        val eventChannel = Channel<String>(Channel.UNLIMITED)
        sseSessionManager.registerChannel(sessionId, eventChannel)

        try {
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                // Send the endpoint event with POST URL
                val endpointPath = "${McpConstants.MCP_ENDPOINT_PATH}?${McpConstants.SESSION_ID_PARAM}=$sessionId"
                write("event: endpoint\n")
                write("data: $endpointPath\n\n")
                flush()
                LOG.info("SSE session established: $sessionId, endpoint: $endpointPath")

                // Keep connection open and send events from channel
                try {
                    for (event in eventChannel) {
                        write(event)
                        flush()
                    }
                } catch (e: Exception) {
                    LOG.debug("SSE connection closed for session $sessionId: ${e.message}")
                }
            }
        } finally {
            sseSessionManager.removeSession(sessionId)
            LOG.info("SSE connection closed, session: $sessionId")
        }
    }

    /**
     * Handles POST /index-mcp - JSON-RPC requests.
     *
     * Supports two modes:
     * - With sessionId: SSE transport - sends response via SSE, returns 202 Accepted
     * - Without sessionId: Streamable HTTP - returns immediate JSON response
     */
    private suspend fun handlePostRequest(call: ApplicationCall) {
        val body = call.receiveText()
        val sessionId = call.request.queryParameters[McpConstants.SESSION_ID_PARAM]

        if (sessionId.isNullOrBlank()) {
            // Streamable HTTP mode - immediate JSON response
            handleStreamableHttpRequest(call, body)
        } else {
            // SSE transport mode - response via SSE stream
            handleSsePostRequest(call, sessionId, body)
        }
    }

    /**
     * Handles POST in Streamable HTTP mode (no sessionId).
     * Returns immediate JSON response.
     */
    private suspend fun handleStreamableHttpRequest(call: ApplicationCall, body: String) {
        if (body.isBlank()) {
            call.respondText(
                createJsonRpcError(null, -32700, "Empty request body"),
                ContentType.Application.Json
            )
            return
        }

        try {
            val response = withContext(ModalityState.any().asContextElement()) {
                jsonRpcHandler.handleRequest(body)
            }
            if (response != null){
                call.respondText(response, ContentType.Application.Json)
            }
        } catch (e: Exception) {
            LOG.error("Error processing MCP request (Streamable HTTP)", e)
            call.respondText(
                createJsonRpcError(null, -32603, e.message ?: "Internal error"),
                ContentType.Application.Json
            )
        }
    }

    /**
     * Handles POST in SSE transport mode (with sessionId).
     * Sends response via SSE `message` event, returns 202 Accepted immediately.
     */
    private suspend fun handleSsePostRequest(call: ApplicationCall, sessionId: String, body: String) {
        val session = sseSessionManager.getSession(sessionId)
        if (session == null) {
            LOG.warn("POST request with invalid sessionId: $sessionId")
            call.respond(HttpStatusCode.NotFound, "Session not found: $sessionId")
            return
        }

        if (body.isBlank()) {
            sseSessionManager.sendEvent(
                sessionId,
                "message",
                createJsonRpcError(null, -32700, "Empty request body")
            )
            call.respond(HttpStatusCode.Accepted)
            return
        }

        // Return 202 Accepted immediately
        call.respond(HttpStatusCode.Accepted)

        // Process request asynchronously and send response via SSE
        coroutineScope.launch {
            try {
                val response = jsonRpcHandler.handleRequest(body)
                if (response != null) {
                    val sent = sseSessionManager.sendEvent(sessionId, "message", response)
                    if (!sent) {
                        LOG.warn("Failed to send response to session $sessionId - session may have closed")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error processing MCP request (SSE)", e)
                sseSessionManager.sendEvent(
                    sessionId,
                    "message",
                    createJsonRpcError(null, -32603, e.message ?: "Internal error")
                )
            }
        }
    }

    /**
     * Creates a JSON-RPC error response.
     */
    private fun createJsonRpcError(id: Any?, code: Int, message: String): String {
        val idStr = when (id) {
            null -> "null"
            is String -> "\"$id\""
            else -> id.toString()
        }
        return """{"jsonrpc":"2.0","id":$idStr,"error":{"code":$code,"message":"$message"}}"""
    }
}

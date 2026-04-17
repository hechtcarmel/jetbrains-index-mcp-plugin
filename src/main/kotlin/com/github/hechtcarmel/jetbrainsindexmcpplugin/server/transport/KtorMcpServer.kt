package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcError
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.delete
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.BindException
import java.net.URI

/**
 * Embedded Ktor CIO server for MCP protocol.
 *
 * Provides multiple transport modes for MCP clients with configurable port.
 *
 * Supports three transport modes:
 * 1. Streamable HTTP Transport (2025-03-26 spec) — PRIMARY:
 *    - POST /index-mcp/streamable-http → Stateless JSON-RPC over HTTP
 *    - GET  /index-mcp/streamable-http → 405 Method Not Allowed
 *    - DELETE /index-mcp/streamable-http → 405 Method Not Allowed
 *
 * 2. Legacy SSE Transport (2024-11-05 spec):
 *    - GET /index-mcp/sse → Opens SSE stream, sends `endpoint` event with POST URL
 *    - POST /index-mcp?sessionId=xxx → JSON-RPC messages, response sent via SSE
 *
 * 3. Stateless HTTP (convenience):
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

    private enum class StreamableBatchKind {
        REQUESTS_OR_NOTIFICATIONS,
        RESPONSES
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
                configureRouting()
            }
            server?.start(wait = false)

            LOG.info("MCP Server started on http://$host:$port")
            StartResult.Success
        } catch (e: BindException) {
            LOG.warn("Port $port is already in use", e)
            StartResult.PortInUse(port)
        } catch (e: Exception) {
            if (e is CancellationException) {
                val cause = e.cause
                if (cause is BindException) {
                    LOG.warn("Failed to start server on $host:$port: ${cause.message}", cause)
                    return StartResult.Error("Failed to bind to $host:$port. ${cause.message}", cause)
                }
                throw e
            }
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
            sseSessionManager.closeAllSessions()
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

    private fun Application.configureRouting() {
        routing {
            options(McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH) {
                handleCorsPreflight(call)
            }

            options(McpConstants.MCP_ENDPOINT_PATH) {
                handleCorsPreflight(call)
            }

            options(McpConstants.SSE_ENDPOINT_PATH) {
                handleCorsPreflight(call)
            }

            // === Streamable HTTP Transport (2025-03-26 spec) ===

            post(McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH) {
                handleStreamableHttpPostRequest(call)
            }

            get(McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH) {
                if (!validateOrigin(call)) return@get
                call.response.header(HttpHeaders.Allow, "POST")
                call.respond(HttpStatusCode.MethodNotAllowed)
            }

            delete(McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH) {
                handleStreamableHttpDeleteRequest(call)
            }

            // === Legacy SSE Transport (2024-11-05 spec) ===

            get(McpConstants.SSE_ENDPOINT_PATH) {
                handleSseRequest(call)
            }

            post(McpConstants.MCP_ENDPOINT_PATH) {
                handlePostRequest(call)
            }

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
        if (!validateOrigin(call)) return

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
        if (!validateOrigin(call)) return

        val body = call.receiveText()
        val sessionId = call.request.queryParameters[McpConstants.SESSION_ID_PARAM]

        if (sessionId.isNullOrBlank()) {
            // Legacy stateless mode on the pre-2025 endpoint.
            handleStatelessHttpRequest(call, body, McpConstants.LEGACY_MCP_PROTOCOL_VERSION)
        } else {
            // SSE transport mode - response via SSE stream
            handleSsePostRequest(call, sessionId, body)
        }
    }

    /**
     * Handles POST /index-mcp/streamable-http — Streamable HTTP transport (MCP 2025-03-26).
     *
     * - initialize request: returns JSON-RPC initialize result
     * - notifications (no id): returns 202 Accepted
     * - requests (has id): returns JSON response
     */
    private suspend fun handleStreamableHttpPostRequest(call: ApplicationCall) {
        if (!validateOrigin(call)) return

        val body = call.receiveText()

        if (body.isBlank()) {
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32700, "Empty request body"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        // Parse once to determine message type
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32700, "Parse error: ${e.message}"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        if (element is JsonArray) {
            handleStreamableHttpBatchRequest(call, element)
            return
        }

        val parsed = element as? JsonObject
        if (parsed == null || !isSupportedJsonRpcMessage(parsed)) {
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32600, "Invalid JSON-RPC message"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        val method = parsed["method"]?.jsonPrimitive?.contentOrNull
        val hasId = hasJsonRpcRequestId(parsed)

        // Initialize: process and create session
        if (method == "initialize") {
            handleStreamableHttpInitialize(call, body)
            return
        }

        val requestId = parsed["id"]

        if (isJsonRpcResponseMessage(parsed)) {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        // Notifications (no id): fire-and-forget, return 202
        if (!hasId) {
            try {
                runWithIdeModality {
                    jsonRpcHandler.handleRequest(
                        body,
                        protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
                    )
                }
            } catch (e: Exception) {
                LOG.debug("Error processing notification: ${e.message}")
            }
            call.respond(HttpStatusCode.Accepted)
            return
        }

        // Regular request: process and return JSON response
        try {
            val response = runWithIdeModality {
                jsonRpcHandler.handleRequest(
                    body,
                    protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
                )
            }
            if (response != null) {
                call.respondText(response, ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.Accepted)
            }
        } catch (e: Exception) {
            LOG.error("Error processing MCP request (Streamable HTTP)", e)
            call.respondText(
                createJsonRpcError(requestId, -32603, e.message ?: "Internal error"),
                ContentType.Application.Json
            )
        }
    }

    private suspend fun handleStreamableHttpBatchRequest(call: ApplicationCall, batch: JsonArray) {
        if (batch.isEmpty()) {
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32600, "JSON-RPC batch requests must not be empty"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        if (batch.any { (it as? JsonObject)?.get("method")?.jsonPrimitive?.contentOrNull == "initialize" }) {
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32600, "Initialize requests must not be batched"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        val batchKind = classifyStreamableBatch(batch)
        if (batchKind == null) {
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32600, "Invalid JSON-RPC batch"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        if (batchKind == StreamableBatchKind.RESPONSES) {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        val responses = mutableListOf<JsonElement>()
        for (message in batch) {
            val parsed = message as JsonObject
            val hasId = hasJsonRpcRequestId(parsed)

            val response = try {
                runWithIdeModality {
                    jsonRpcHandler.handleRequest(
                        message.toString(),
                        protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
                    )
                }
            } catch (e: Exception) {
                LOG.error("Error processing MCP batch message (Streamable HTTP)", e)
                createJsonRpcError(parsed?.get("id"), -32603, e.message ?: "Internal error")
            }

            if (hasId && response != null) {
                responses += json.parseToJsonElement(response)
            }
        }

        if (responses.isEmpty()) {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        call.respondText(
            json.encodeToString(JsonArray(responses)),
            ContentType.Application.Json
        )
    }

    /**
     * Handles initialize request for Streamable HTTP transport.
     */
    private suspend fun handleStreamableHttpInitialize(call: ApplicationCall, body: String) {
        try {
            val response = runWithIdeModality {
                jsonRpcHandler.handleRequest(
                    body,
                    protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
                )
            }
            if (response != null) {
                call.respondText(response, ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.InternalServerError)
            }
        } catch (e: Exception) {
            LOG.error("Error processing initialize (Streamable HTTP)", e)
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32603, e.message ?: "Internal error"),
                ContentType.Application.Json
            )
        }
    }

    /**
     * Handles DELETE /index-mcp/streamable-http.
     * Stateless Streamable HTTP does not support session termination.
     */
    private suspend fun handleStreamableHttpDeleteRequest(call: ApplicationCall) {
        if (!validateOrigin(call)) return
        call.response.header(HttpHeaders.Allow, "POST")
        call.respond(HttpStatusCode.MethodNotAllowed)
    }

    /**
     * Handles POST in Streamable HTTP mode (no sessionId).
     * Returns immediate JSON response.
     */
    private suspend fun handleStatelessHttpRequest(
        call: ApplicationCall,
        body: String,
        protocolVersion: String
    ) {
        if (body.isBlank()) {
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32700, "Empty request body"),
                ContentType.Application.Json
            )
            return
        }

        try {
            val response = runWithIdeModality {
                jsonRpcHandler.handleRequest(body, protocolVersion = protocolVersion)
            }
            if (response != null) {
                call.respondText(response, ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.Accepted)
            }
        } catch (e: Exception) {
            LOG.error("Error processing MCP request (Streamable HTTP)", e)
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32603, e.message ?: "Internal error"),
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
                createJsonRpcError(null as JsonElement?, -32700, "Empty request body")
            )
            call.respond(HttpStatusCode.Accepted)
            return
        }

        // Return 202 Accepted immediately
        call.respond(HttpStatusCode.Accepted)

        // Process request asynchronously and send response via SSE
        coroutineScope.launch {
            try {
                val response = runWithIdeModality {
                    jsonRpcHandler.handleRequest(
                        body,
                        protocolVersion = McpConstants.LEGACY_MCP_PROTOCOL_VERSION
                    )
                }
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
                    createJsonRpcError(null as JsonElement?, -32603, e.message ?: "Internal error")
                )
            }
        }
    }

    private val json = Json { encodeDefaults = true; prettyPrint = false }

    private suspend fun <T> runWithIdeModality(block: suspend () -> T): T {
        val application = ApplicationManager.getApplication()
        return if (application == null) {
            block()
        } else {
            withContext(ModalityState.any().asContextElement()) {
                block()
            }
        }
    }

    private suspend fun handleCorsPreflight(call: ApplicationCall) {
        val origin = call.request.headers[HttpHeaders.Origin]
        if (origin.isNullOrBlank() || !isAllowedOrigin(origin)) {
            call.respondText("Origin not allowed", status = HttpStatusCode.Forbidden)
            return
        }

        setCorsResponseHeaders(call, origin)
        call.response.header(
            HttpHeaders.AccessControlAllowMethods,
            listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Delete, HttpMethod.Options).joinToString(", ") { it.value }
        )
        call.response.header(HttpHeaders.AccessControlAllowHeaders, listOf(HttpHeaders.ContentType, HttpHeaders.Accept).joinToString(", "))
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun validateOrigin(call: ApplicationCall): Boolean {
        val origin = call.request.headers[HttpHeaders.Origin] ?: return true
        if (isAllowedOrigin(origin)) {
            setCorsResponseHeaders(call, origin)
            return true
        }

        LOG.warn("Rejected MCP request with invalid Origin header: $origin")

        if (call.request.httpMethod == HttpMethod.Get) {
            call.respondText("Origin not allowed", status = HttpStatusCode.Forbidden)
        } else {
            call.respondText(
                createJsonRpcError(null as JsonElement?, -32600, "Origin not allowed"),
                ContentType.Application.Json,
                HttpStatusCode.Forbidden
            )
        }
        return false
    }

    private fun setCorsResponseHeaders(call: ApplicationCall, origin: String) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
        call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)
    }

    private fun isAllowedOrigin(origin: String): Boolean {
        val uri = try {
            URI(origin)
        } catch (_: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase() ?: return false
        val host = normalizeOriginHost(uri.host?.lowercase() ?: return false)
        return scheme in setOf("http", "https") && host in setOf("127.0.0.1", "localhost", "::1")
    }

    private fun isJsonRpcResponseMessage(parsed: JsonObject): Boolean {
        return !parsed.containsKey("method") && (parsed.containsKey("result") || parsed.containsKey("error"))
    }

    private fun isJsonRpcRequestMessage(parsed: JsonObject): Boolean = parsed.containsKey("method")

    private fun isSupportedJsonRpcMessage(parsed: JsonObject): Boolean {
        return isJsonRpcRequestMessage(parsed) || isJsonRpcResponseMessage(parsed)
    }

    private fun hasJsonRpcRequestId(parsed: JsonObject): Boolean {
        return parsed.containsKey("id") && parsed["id"] != JsonNull
    }

    private fun classifyStreamableBatch(batch: JsonArray): StreamableBatchKind? {
        var sawRequestsOrNotifications = false
        var sawResponses = false

        for (message in batch) {
            val parsed = message as? JsonObject ?: return null
            when {
                isJsonRpcRequestMessage(parsed) -> sawRequestsOrNotifications = true
                isJsonRpcResponseMessage(parsed) -> sawResponses = true
                else -> return null
            }
        }

        return when {
            sawRequestsOrNotifications && sawResponses -> null
            sawRequestsOrNotifications -> StreamableBatchKind.REQUESTS_OR_NOTIFICATIONS
            sawResponses -> StreamableBatchKind.RESPONSES
            else -> null
        }
    }

    private fun normalizeOriginHost(host: String): String = host.removePrefix("[").removeSuffix("]")

    private fun createJsonRpcError(id: JsonElement?, code: Int, message: String): String {
        val response = JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
        return json.encodeToString(response)
    }
}

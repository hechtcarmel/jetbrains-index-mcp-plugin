package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcError
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import java.nio.charset.StandardCharsets

/**
 * MCP Request Handler implementing the HTTP+SSE transport.
 *
 * This transport uses two endpoints:
 * - GET /index-mcp/sse → Opens SSE stream, sends `endpoint` event with POST URL
 * - POST /index-mcp → JSON-RPC messages, immediate JSON response
 *
 * This is the standard transport used by MCP clients like Cursor, Claude Desktop, etc.
 *
 * @see <a href="https://modelcontextprotocol.io/docs/concepts/transports#http-with-sse">MCP HTTP+SSE Transport</a>
 */
class McpRequestHandler : HttpRequestHandler() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    companion object {
        private val LOG = logger<McpRequestHandler>()
        const val MCP_PATH = McpConstants.MCP_ENDPOINT_PATH
        const val SSE_PATH = "${MCP_PATH}/sse"
    }

    override fun isSupported(request: FullHttpRequest): Boolean {
        val uri = request.uri()
        return uri.startsWith(MCP_PATH)
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        val path = urlDecoder.path()

        if (!path.startsWith(MCP_PATH)) {
            return false
        }

        return when {
            // GET /index-mcp/sse → SSE stream
            request.method() == HttpMethod.GET && path == SSE_PATH -> {
                handleSseRequest(context)
                true
            }
            // POST /index-mcp OR /index-mcp/sse → JSON-RPC
            // (Some clients POST to /sse endpoint for streamable HTTP fallback)
            request.method() == HttpMethod.POST && (path == MCP_PATH || path == SSE_PATH) -> {
                handlePostRequest(request, context)
                true
            }
            // OPTIONS for CORS
            request.method() == HttpMethod.OPTIONS -> {
                handleOptionsRequest(context)
                true
            }
            else -> false
        }
    }

    /**
     * GET /index-mcp/sse → Opens SSE stream.
     *
     * Sends an `endpoint` event with the URL for POST requests,
     * then keeps the connection open for server-initiated messages.
     */
    private fun handleSseRequest(context: ChannelHandlerContext) {
        LOG.info("Opening SSE connection")

        val response = DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
        )

        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
            set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
            set(HttpHeaderNames.CONNECTION, "keep-alive")
        }
        addCorsHeaders(response)

        context.write(response)

        // Send the endpoint event - this tells the client where to POST
        val port = BuiltInServerManager.getInstance().port
        val endpointUrl = "http://localhost:$port$MCP_PATH"

        val endpointEvent = "event: endpoint\ndata: $endpointUrl\n\n"
        val buffer = Unpooled.copiedBuffer(endpointEvent, StandardCharsets.UTF_8)
        context.writeAndFlush(DefaultHttpContent(buffer))

        LOG.info("SSE connection established, endpoint: $endpointUrl")

        // Connection stays open for server-initiated messages
        // The channel will be closed by the client or on error
    }

    /**
     * POST /index-mcp → Handles JSON-RPC requests.
     *
     * Parses the JSON-RPC request, routes to the appropriate handler,
     * and returns the response as JSON.
     *
     * Uses non-blocking coroutine execution to avoid freezing the UI.
     * Tool execution happens on background threads, responses are sent
     * back on the Netty event loop thread.
     */
    private fun handlePostRequest(request: FullHttpRequest, context: ChannelHandlerContext) {
        val body = request.content().toString(StandardCharsets.UTF_8)

        if (body.isBlank()) {
            sendJsonRpcError(context, null, JsonRpcErrorCodes.PARSE_ERROR, "Empty request body")
            return
        }

        val mcpService = ApplicationManager.getApplication().service<McpServerService>()

        // Launch tool execution on background thread (non-blocking)
        mcpService.coroutineScope.launch {
            try {
                val response = mcpService.getJsonRpcHandler().handleRequest(body)

                // Send response back on Netty event loop thread
                context.channel().eventLoop().execute {
                    if (context.channel().isActive) {
                        sendJsonResponse(context, HttpResponseStatus.OK, response)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error processing MCP request", e)

                // Send error response back on Netty event loop thread
                context.channel().eventLoop().execute {
                    if (context.channel().isActive) {
                        sendJsonRpcError(
                            context,
                            null,
                            JsonRpcErrorCodes.INTERNAL_ERROR,
                            e.message ?: "Internal error"
                        )
                    }
                }
            }
        }
    }

    /**
     * OPTIONS → CORS preflight.
     */
    private fun handleOptionsRequest(context: ChannelHandlerContext) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
        )
        addCorsHeaders(response)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        context.writeAndFlush(response)
    }

    /**
     * Sends a JSON response.
     */
    private fun sendJsonResponse(
        context: ChannelHandlerContext,
        status: HttpResponseStatus,
        jsonContent: String
    ) {
        val content = Unpooled.copiedBuffer(jsonContent, StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            content
        )

        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
            set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        }
        addCorsHeaders(response)

        context.writeAndFlush(response)
    }

    /**
     * Sends a JSON-RPC error response.
     */
    private fun sendJsonRpcError(
        context: ChannelHandlerContext,
        id: kotlinx.serialization.json.JsonElement?,
        code: Int,
        message: String
    ) {
        val errorResponse = JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
        sendJsonResponse(context, HttpResponseStatus.OK, json.encodeToString(errorResponse))
    }

    private fun addCorsHeaders(response: HttpResponse) {
        response.headers().apply {
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS")
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Accept")
        }
    }
}

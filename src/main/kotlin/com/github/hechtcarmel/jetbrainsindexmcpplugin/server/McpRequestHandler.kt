package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcError
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.ide.HttpRequestHandler
import java.nio.charset.StandardCharsets

class McpRequestHandler : HttpRequestHandler() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    companion object {
        private val LOG = logger<McpRequestHandler>()
        const val MCP_PATH = "/index-mcp"
    }

    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.uri().startsWith(MCP_PATH)
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

        return when (request.method()) {
            HttpMethod.GET -> {
                handleGetRequest(context)
                true
            }
            HttpMethod.POST -> {
                handlePostRequest(request, context)
                true
            }
            HttpMethod.OPTIONS -> {
                handleOptionsRequest(context)
                true
            }
            else -> false
        }
    }

    private fun handleGetRequest(context: ChannelHandlerContext) {
        val serverInfo = buildJsonObject {
            put("name", "intellij-index-mcp")
            put("version", "1.0.0")
            put("status", "running")
            put("protocolVersion", "2024-11-05")
        }
        sendJsonResponse(context, HttpResponseStatus.OK, json.encodeToString(serverInfo))
    }

    private fun handlePostRequest(request: FullHttpRequest, context: ChannelHandlerContext) {
        val body = request.content().toString(StandardCharsets.UTF_8)

        if (body.isBlank()) {
            val errorResponse = JsonRpcResponse(
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.PARSE_ERROR,
                    message = "Empty request body"
                )
            )
            sendJsonResponse(context, HttpResponseStatus.OK, json.encodeToString(errorResponse))
            return
        }

        try {
            val mcpService = ApplicationManager.getApplication().service<McpServerService>()

            val response = runBlocking {
                mcpService.getJsonRpcHandler().handleRequest(body)
            }

            sendJsonResponse(context, HttpResponseStatus.OK, response)
        } catch (e: Exception) {
            LOG.error("Error processing MCP request", e)
            val errorResponse = JsonRpcResponse(
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.INTERNAL_ERROR,
                    message = e.message ?: "Internal error"
                )
            )
            sendJsonResponse(context, HttpResponseStatus.OK, json.encodeToString(errorResponse))
        }
    }

    private fun handleOptionsRequest(context: ChannelHandlerContext) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
        )
        addCorsHeaders(response)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        context.writeAndFlush(response)
    }

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

    private fun addCorsHeaders(response: HttpResponse) {
        response.headers().apply {
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS")
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Accept")
        }
    }
}

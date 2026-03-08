package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import io.ktor.http.HttpStatusCode
import junit.framework.TestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class KtorMcpServerUnitTest : TestCase() {

    private val httpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var sseSessionManager: KtorSseSessionManager
    private lateinit var streamableHttpSessionManager: StreamableHttpSessionManager
    private lateinit var server: KtorMcpServer
    private var port: Int = 0

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry().also { it.registerBuiltInTools() }
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sseSessionManager = KtorSseSessionManager()
        streamableHttpSessionManager = StreamableHttpSessionManager()
        port = findFreePort()
        server = createServer(port, streamableHttpSessionManager)
        assertEquals(KtorMcpServer.StartResult.Success, server.start())
    }

    override fun tearDown() {
        server.stop()
        coroutineScope.cancel()
        super.tearDown()
    }

    fun testStreamableInitializeReturnsSessionHeaderAnd2025ProtocolVersion() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = initializeRequestBody("2025-03-26")
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())
        assertTrue(
            "Expected Mcp-Session-Id response header",
            response.headers().firstValue(McpConstants.MCP_SESSION_ID_HEADER).isPresent
        )

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals(
            "2025-03-26",
            responseBody["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testLegacyPostInitializeReturns2024ProtocolVersion() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.MCP_ENDPOINT_PATH,
            body = initializeRequestBody("2024-11-05")
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals(
            "2024-11-05",
            responseBody["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testStreamableBatchRequestsReturnJsonArray() {
        val sessionId = initializeStreamableSession()

        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """
                [
                  {"jsonrpc":"2.0","id":1,"method":"ping"},
                  {"jsonrpc":"2.0","id":2,"method":"ping"}
                ]
            """.trimIndent(),
            headers = mapOf(McpConstants.MCP_SESSION_ID_HEADER to sessionId)
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())

        val responseArray = json.parseToJsonElement(response.body()).jsonArray
        assertEquals(2, responseArray.size)
        assertEquals("1", responseArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("2", responseArray[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    fun testStreamableScalarJsonReturnsInvalidRequestError() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = "1"
        )

        assertEquals(HttpStatusCode.BadRequest.value, response.statusCode())

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("-32600", responseBody["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    fun testStreamableInvalidNotificationReturnsInvalidRequestError() {
        val sessionId = initializeStreamableSession()

        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """{"jsonrpc":"2.0"}""",
            headers = mapOf(McpConstants.MCP_SESSION_ID_HEADER to sessionId)
        )

        assertEquals(HttpStatusCode.BadRequest.value, response.statusCode())

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("-32600", responseBody["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    fun testStreamableMixedBatchReturnsInvalidRequestError() {
        val sessionId = initializeStreamableSession()

        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """
                [
                  {"jsonrpc":"2.0","id":1,"method":"ping"},
                  {"jsonrpc":"2.0","id":1,"result":{}}
                ]
            """.trimIndent(),
            headers = mapOf(McpConstants.MCP_SESSION_ID_HEADER to sessionId)
        )

        assertEquals(HttpStatusCode.BadRequest.value, response.statusCode())

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("-32600", responseBody["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    fun testStreamableNotificationBatchReturnsAcceptedWithoutResponseBody() {
        val sessionId = initializeStreamableSession()

        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """
                [
                  {"jsonrpc":"2.0","method":"ping"},
                  {"jsonrpc":"2.0","method":"notifications/initialized"}
                ]
            """.trimIndent(),
            headers = mapOf(McpConstants.MCP_SESSION_ID_HEADER to sessionId)
        )

        assertEquals(HttpStatusCode.Accepted.value, response.statusCode())
        assertTrue("Expected empty body for notification batch", response.body().isEmpty())
    }

    fun testDeleteWithoutSessionHeaderReturnsJsonRpcError() {
        val response = sendRequest(
            method = "DELETE",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH
        )

        assertEquals(HttpStatusCode.BadRequest.value, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("content-type").orElse("").substringBefore(";"))

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("-32600", responseBody["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    fun testRejectsNonLocalOrigin() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = initializeRequestBody("2025-03-26"),
            headers = mapOf("Origin" to "https://evil.example")
        )

        assertEquals(HttpStatusCode.Forbidden.value, response.statusCode())
    }

    fun testAcceptsIpv6LoopbackOrigin() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = initializeRequestBody("2025-03-26"),
            headers = mapOf("Origin" to "http://[::1]:3000")
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())
    }

    fun testLegacySseHandshakeAdvertisesEndpointAndStreamsResponses() {
        val response = openSseStream(McpConstants.SSE_ENDPOINT_PATH)
        response.body().use {
            assertEquals(HttpStatusCode.OK.value, response.statusCode())
            assertTrue(
                response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream")
            )

            val reader = it.bufferedReader()
            val endpointEvent = readSseEvent(reader)
            assertEquals("endpoint", endpointEvent.eventType())

            val endpointPath = endpointEvent.data()
            assertTrue(endpointPath.startsWith("${McpConstants.MCP_ENDPOINT_PATH}?${McpConstants.SESSION_ID_PARAM}="))

            val postResponse = sendRequest(
                method = "POST",
                path = endpointPath,
                body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
            )

            assertEquals(HttpStatusCode.Accepted.value, postResponse.statusCode())

            val messageEvent = readSseEvent(reader)
            assertEquals("message", messageEvent.eventType())

            val messageBody = json.parseToJsonElement(messageEvent.data()).jsonObject
            assertEquals("1", messageBody["id"]!!.jsonPrimitive.content)
            assertNotNull(messageBody["result"])
        }
    }

    fun testStopClearsStreamableSessionsBeforeRestart() {
        val sessionId = initializeStreamableSession()

        server.stop()

        port = findFreePort()
        server = createServer(port, streamableHttpSessionManager)
        assertEquals(KtorMcpServer.StartResult.Success, server.start())

        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            headers = mapOf(McpConstants.MCP_SESSION_ID_HEADER to sessionId)
        )

        assertEquals(HttpStatusCode.NotFound.value, response.statusCode())
    }

    private fun initializeStreamableSession(): String {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = initializeRequestBody("2025-03-26")
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())
        return response.headers().firstValue(McpConstants.MCP_SESSION_ID_HEADER).orElseThrow()
    }

    private fun createServer(
        port: Int,
        streamableSessionManager: StreamableHttpSessionManager
    ): KtorMcpServer {
        return KtorMcpServer(
            port = port,
            jsonRpcHandler = JsonRpcHandler(toolRegistry),
            sseSessionManager = sseSessionManager,
            streamableHttpSessionManager = streamableSessionManager,
            coroutineScope = coroutineScope
        )
    }

    private fun sendRequest(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Accept", "application/json, text/event-stream")

        headers.forEach { (name, value) -> builder.header(name, value) }

        if (body != null) {
            builder.header("Content-Type", "application/json")
        }

        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
            "DELETE" -> builder.DELETE()
            else -> error("Unsupported method: $method")
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun openSseStream(
        path: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<InputStream> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Accept", "text/event-stream")
            .GET()

        headers.forEach { (name, value) -> builder.header(name, value) }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun readSseEvent(reader: BufferedReader, timeoutMillis: Long = 5_000): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!reader.ready() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertTrue("Timed out waiting for SSE event", reader.ready())

        val lines = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                if (lines.isNotEmpty()) {
                    break
                }
                continue
            }
            lines += line
        }

        return lines
    }

    private fun List<String>.eventType(): String = first { it.startsWith("event: ") }.substringAfter("event: ")

    private fun List<String>.data(): String = filter { it.startsWith("data: ") }
        .joinToString("\n") { it.substringAfter("data: ") }

    private fun initializeRequestBody(protocolVersion: String) = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "$protocolVersion",
            "clientInfo": {
              "name": "test-client",
              "version": "1.0.0"
            }
          }
        }
    """.trimIndent()

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

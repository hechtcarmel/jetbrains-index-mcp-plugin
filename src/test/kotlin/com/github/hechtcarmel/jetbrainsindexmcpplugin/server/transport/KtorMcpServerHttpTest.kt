package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import io.ktor.http.HttpStatusCode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Raw-HTTP assertions on the transport: status codes, headers, origin rejection and the legacy
 * SSE handshake. Protocol semantics are covered by [McpProtocolConformanceTest], which drives the
 * same server with the SDK's own client.
 *
 * Platform tier, not unit: the server builds its tool list per connection from [McpSettings], so
 * it needs a real Application. The pre-migration version ran headless only because the code path
 * it exercised happened never to touch an IDE service.
 */
class KtorMcpServerHttpTest : BasePlatformTestCase() {

    private val httpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val mcpSessionIdHeader = "Mcp-Session-Id"

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var server: KtorMcpServer
    private var port: Int = 0

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry().also { it.registerBuiltInTools() }
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        port = findFreePort()
        server = createServer(port)
        assertEquals(KtorMcpServer.StartResult.Success, server.start())
    }

    override fun tearDown() {
        server.stop()
        coroutineScope.cancel()
        super.tearDown()
    }

    fun testStreamableInitializeOmitsSessionHeaderAndReturns2025ProtocolVersion() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = initializeRequestBody("2025-03-26")
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())
        assertFalse(
            "Streamable HTTP should not return an Mcp-Session-Id header in stateless mode",
            response.headers().firstValue(mcpSessionIdHeader).isPresent
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
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """
                [
                  {"jsonrpc":"2.0","id":1,"method":"ping"},
                  {"jsonrpc":"2.0","id":2,"method":"ping"}
                ]
            """.trimIndent()
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

    /**
     * The hand-written server classified every batch member and rejected anything that was
     * neither a request nor a response. The SDK parses what it can and ignores the rest, which is
     * what the spec asks for — a server must not fail a batch because one notification is odd.
     */
    fun testStreamableInvalidNotificationIsAccepted() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """{"jsonrpc":"2.0"}"""
        )

        assertEquals(HttpStatusCode.Accepted.value, response.statusCode())
    }

    /** Mixed request/response batches are processed per message rather than rejected wholesale. */
    fun testStreamableMixedBatchIsProcessedPerMessage() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """
                [
                  {"jsonrpc":"2.0","id":1,"method":"ping"},
                  {"jsonrpc":"2.0","id":1,"result":{}}
                ]
            """.trimIndent()
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())

        // The one real request in the batch still gets its answer.
        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("1", responseBody["id"]!!.jsonPrimitive.content)
        assertNotNull(responseBody["result"])
    }

    fun testStreamableNotificationBatchReturnsAcceptedWithoutResponseBody() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """
                [
                  {"jsonrpc":"2.0","method":"ping"},
                  {"jsonrpc":"2.0","method":"notifications/initialized"}
                ]
            """.trimIndent()
        )

        assertEquals(HttpStatusCode.Accepted.value, response.statusCode())
        // The SDK answers notification-only batches with `respondNullable(null)`, which the JSON
        // converter renders as the literal `null` rather than an empty body. Clients do not read
        // a 202 body, so this is pinned rather than worked around.
        assertTrue(
            "202 body should carry no JSON-RPC payload, was: ${response.body()}",
            response.body().isEmpty() || response.body().trim() == "null"
        )
    }

    fun testStreamableDeleteReturnsMethodNotAllowedInStatelessMode() {
        val response = sendRequest(
            method = "DELETE",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH
        )

        assertEquals(HttpStatusCode.MethodNotAllowed.value, response.statusCode())
    }

    fun testStreamableRequestSucceedsWithoutInitialize() {
        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())

        val responseBody = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("1", responseBody["id"]!!.jsonPrimitive.content)
        assertNotNull(responseBody["result"])
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

    fun testStreamableRequestStillWorksAfterRestart() {
        server.stop()

        port = findFreePort()
        server = createServer(port)
        assertEquals(KtorMcpServer.StartResult.Success, server.start())

        val response = sendRequest(
            method = "POST",
            path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH,
            body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        )

        assertEquals(HttpStatusCode.OK.value, response.statusCode())
    }

    /**
     * A loopback-bound server is exactly the DNS-rebinding target the Host check exists for: a
     * browser lured to an attacker-controlled name that resolves to 127.0.0.1.
     */
    fun testLoopbackBindRejectsForeignHostHeader() {
        val status = sendRawPost(port = port, hostHeader = "evil.example")

        assertEquals(HttpStatusCode.Forbidden.value, status)
    }

    /**
     * The mirror image, and a regression guard: binding to 0.0.0.0 is a supported, warned-about
     * setting, and such a server is reached under whatever address routes to it. Enforcing the
     * loopback Host allow-list there answers every LAN request with 403 — the server becomes
     * unreachable for the exact configuration the user chose.
     */
    fun testNonLoopbackBindAcceptsForeignHostHeader() {
        val wildcardPort = findFreePort()
        val wildcardServer = createServer(wildcardPort, host = "0.0.0.0")
        assertEquals(KtorMcpServer.StartResult.Success, wildcardServer.start())
        try {
            val status = sendRawPost(port = wildcardPort, hostHeader = "10.1.2.3")

            assertEquals(HttpStatusCode.OK.value, status)
        } finally {
            wildcardServer.stop()
        }
    }

    private fun createServer(port: Int, host: String = McpConstants.DEFAULT_SERVER_HOST): KtorMcpServer {
        return KtorMcpServer(
            port = port,
            host = host,
            serverFactory = McpServerFactory(toolRegistry, McpToolDispatcher(toolRegistry)),
            legacySseTransports = LegacySseTransports(),
            coroutineScope = coroutineScope
        )
    }

    /**
     * Raw socket rather than [HttpClient]: `Host` is on the JDK client's restricted-header list and
     * is silently overwritten from the URI, so it cannot express this test at all.
     */
    private fun sendRawPost(port: Int, hostHeader: String): Int {
        val body = initializeRequestBody("2025-03-26")
        val request = buildString {
            append("POST ${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH} HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            append("Accept: application/json, text/event-stream\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }

        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val statusLine = socket.getInputStream().bufferedReader().readLine()
                ?: error("No response from server")
            return statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: error("Unparseable status line: $statusLine")
        }
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

    private fun initializeRequestBody(protocolVersion: String) = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "$protocolVersion",
            "capabilities": {},
            "clientInfo": {
              "name": "test-client",
              "version": "1.0.0"
            }
          }
        }
    """.trimIndent()

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

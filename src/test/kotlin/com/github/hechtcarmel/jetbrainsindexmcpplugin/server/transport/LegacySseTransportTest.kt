package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/**
 * The 2024-11-05 HTTP+SSE transport, which older MCP clients are configured against.
 *
 * The exact `endpoint` event payload is the contract here: a client that received
 * `?sessionId=…` (the SDK's own default, relative to the SSE path) instead of
 * `/index-mcp?sessionId=…` would POST to `/index-mcp/sse` and every existing configuration
 * pointing at `/index-mcp` would silently stop working. That is why the plugin wires
 * `SseServerTransport` by hand instead of calling the SDK's `Route.mcp(path)` helper.
 */
class LegacySseTransportTest : BasePlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private lateinit var scope: CoroutineScope
    private lateinit var server: KtorMcpServer
    private var port: Int = 0

    override fun setUp() {
        super.setUp()
        val registry = ToolRegistry().apply { registerBuiltInTools() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        port = ServerSocket(0).use { it.localPort }
        server = KtorMcpServer(
            port = port,
            serverFactory = McpServerFactory(registry, McpToolDispatcher(registry)),
            legacySseTransports = LegacySseTransports(),
            coroutineScope = scope
        )
        assertEquals(KtorMcpServer.StartResult.Success, server.start())
    }

    override fun tearDown() {
        try {
            server.stop()
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testHandshakeAdvertisesTheLegacyPostEndpointAndStreamsTheResponse() {
        val stream = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${McpConstants.SSE_ENDPOINT_PATH}"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream()
        )
        assertEquals(200, stream.statusCode())

        val reader = stream.body().bufferedReader()
        try {
            val endpoint = reader.readEvent("endpoint")
            assertTrue(
                "endpoint event must point at ${McpConstants.MCP_ENDPOINT_PATH}?sessionId=…, was: $endpoint",
                endpoint.startsWith("${McpConstants.MCP_ENDPOINT_PATH}?${McpConstants.SESSION_ID_PARAM}=")
            )

            val postResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$endpoint"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals("Legacy POST is accepted and answered on the stream", 202, postResponse.statusCode())

            val message = json.parseToJsonElement(reader.readEvent("message")).jsonObject
            assertEquals("7", message["id"]!!.jsonPrimitive.content)
            val toolNames = message["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue(
                "tools/list over legacy SSE should return the same surface as Streamable HTTP",
                ToolNames.INDEX_STATUS in toolNames
            )
        } finally {
            reader.close()
        }
    }

    fun testStatelessPostWithoutSessionIdStillWorks() {
        // Pre-Streamable-HTTP convenience mode. Some client configurations still point here.
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${McpConstants.MCP_ENDPOINT_PATH}"))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, response.statusCode())
        val body = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("1", body["id"]!!.jsonPrimitive.content)
        assertNotNull("ping should return a result", body["result"])
    }

    fun testPostWithUnknownSessionIdIsRejected() {
        val response = httpClient.send(
            HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:$port${McpConstants.MCP_ENDPOINT_PATH}" +
                        "?${McpConstants.SESSION_ID_PARAM}=00000000-0000-0000-0000-000000000000"
                )
            )
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(404, response.statusCode())
    }

    /** Reads SSE frames until one with the given event name arrives, returning its data. */
    private fun BufferedReader.readEvent(name: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var event: String? = null
        val data = StringBuilder()

        while (System.nanoTime() < deadline) {
            val line = readLine() ?: break
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                line.isEmpty() -> {
                    if (event == name && data.isNotEmpty()) return data.toString()
                    event = null
                    data.setLength(0)
                }
            }
        }
        fail("Timed out waiting for SSE '$name' event")
        error("unreachable")
    }
}

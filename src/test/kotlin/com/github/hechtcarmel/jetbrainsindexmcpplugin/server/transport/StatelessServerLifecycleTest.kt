package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * Stateless Streamable HTTP creates a transport, an SDK [Server] and a session **per POST**, and
 * the SDK never closes any of them — that is the caller's job. Get it wrong and every tool call
 * an agent makes leaks a session plus its notification scope for the lifetime of the IDE, which
 * stays invisible until someone's IDE has been up for a day.
 *
 * This drives real traffic through the real transport and asserts the servers it created were
 * actually closed.
 */
class StatelessServerLifecycleTest : BasePlatformTestCase() {

    private val created = ConcurrentHashMap.newKeySet<Server>()

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
            serverFactory = RecordingFactory(registry, McpToolDispatcher(registry), created),
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

    fun testEveryStatelessRequestClosesItsServer() {
        val http = HttpClient.newHttpClient()
        val calls = 25

        repeat(calls) { i ->
            val response = http.send(
                HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:$port${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}")
                )
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"jsonrpc":"2.0","id":$i,"method":"tools/call","params":""" +
                                """{"name":"${ToolNames.INDEX_STATUS}","arguments":{}}}"""
                        )
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals("Request $i should succeed", 200, response.statusCode())
        }

        assertEquals("Stateless mode builds one Server per POST", calls, created.size)

        // close() is launched on the service scope from invokeOnCompletion, so it is not
        // guaranteed to have run the instant the HTTP response lands.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && created.any { it.sessions.isNotEmpty() }) {
            Thread.sleep(50)
        }

        val leaked = created.count { it.sessions.isNotEmpty() }
        assertEquals(
            "Every per-request Server must be closed once the HTTP call completes; " +
                "$leaked of $calls still hold live sessions",
            0,
            leaked
        )
    }

    private class RecordingFactory(
        registry: ToolRegistry,
        dispatcher: McpToolDispatcher,
        private val created: MutableSet<Server>
    ) : McpServerFactory(registry, dispatcher) {
        override fun newServer(): Server = super.newServer().also { created.add(it) }
    }
}

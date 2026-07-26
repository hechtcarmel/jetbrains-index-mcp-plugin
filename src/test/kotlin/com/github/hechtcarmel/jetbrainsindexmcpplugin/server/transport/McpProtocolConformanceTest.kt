package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket

/**
 * Drives the running server with the MCP Kotlin SDK's **client** over real HTTP.
 *
 * This is the strongest available statement that the wire contract is intact: an independent MCP
 * implementation performs the full handshake, lists the tools and calls one, with no shared code
 * path other than the protocol itself. It replaces the old `McpServerIntegrationTest`, which
 * asserted on hand-built JSON-RPC envelopes fed straight into the plugin's own handler — a test
 * that could not have caught a transport-level regression.
 */
class McpProtocolConformanceTest : BasePlatformTestCase() {

    private lateinit var scope: CoroutineScope
    private lateinit var server: KtorMcpServer
    private var port: Int = 0

    override fun setUp() {
        super.setUp()
        val registry = ToolRegistry().apply { registerBuiltInTools() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        port = freePort()
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

    fun testHandshakeReportsServerIdentityAndToolCapability() = withMcpClient { client ->
        val serverInfo = requireNotNull(client.serverVersion) { "initialize did not report serverInfo" }
        assertEquals(McpConstants.getServerName(), serverInfo.name)
        assertTrue("serverInfo.version must be the real plugin version", serverInfo.version.isNotBlank())
        assertNotSame("serverInfo.version must not be the old hardcoded constant", "4.10.4", serverInfo.version)

        assertNotNull("Server must advertise the tools capability", client.serverCapabilities?.tools)
    }

    fun testInstructionsCarryTheServerDescription() = withMcpClient { client ->
        // The pre-migration server put this text in the non-standard `serverInfo.description`.
        // MCP's slot for it is `instructions`, which clients feed to the model.
        val instructions = requireNotNull(client.serverInstructions) { "Server did not send instructions" }
        assertTrue(
            "instructions should describe the refactoring capability, was: $instructions",
            instructions.contains("refactoring")
        )
    }

    fun testListToolsReturnsTheEnabledToolSurface() = withMcpClient { client ->
        val names = client.listTools().tools.map { it.name }.toSet()

        // ide_find_symbol and ide_file_structure are disabled by default, so are absent here.
        val expected = listOf(
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
            ToolNames.TYPE_HIERARCHY,
            ToolNames.CALL_HIERARCHY,
            ToolNames.FIND_IMPLEMENTATIONS,
            ToolNames.FIND_SUPER_METHODS,
            ToolNames.FIND_CLASS,
            ToolNames.FIND_FILE,
            ToolNames.SEARCH_TEXT,
            ToolNames.DIAGNOSTICS,
            ToolNames.INDEX_STATUS
        )
        expected.forEach { assertTrue("tools/list should contain $it", it in names) }
    }

    fun testEveryAdvertisedToolCarriesAnObjectInputSchema() = withMcpClient { client ->
        val tools = client.listTools().tools
        assertTrue("tools/list must not be empty", tools.isNotEmpty())

        tools.forEach { tool ->
            assertEquals("${tool.name} inputSchema must be an object schema", "object", tool.inputSchema.type)
            assertFalse("${tool.name} must have a description", tool.description.isNullOrBlank())
        }
    }

    fun testCallToolRoundTripsThroughTheProtocol() = withMcpClient { client ->
        val result = client.callTool(ToolNames.INDEX_STATUS, emptyMap())

        assertFalse("${ToolNames.INDEX_STATUS} should succeed: ${result.text}", result.isFailure)
        assertTrue("Result should carry content", result.content.isNotEmpty())
    }

    fun testUnknownToolIsReportedAsAToolErrorNotAProtocolError() = withMcpClient { client ->
        // Per the MCP spec, tool-level failures belong in the result so the model can read them.
        // A JSON-RPC error would surface to the user as a hard transport failure instead.
        val result = client.callTool("ide_does_not_exist", emptyMap())

        assertTrue("Unknown tool must come back as isError", result.isFailure)
        assertTrue(
            "Error text should name the tool, was: ${result.text}",
            result.text.contains("ide_does_not_exist")
        )
    }

    fun testPingSucceeds() = withMcpClient { client ->
        client.ping()
    }

    private fun withMcpClient(block: suspend (io.modelcontextprotocol.kotlin.sdk.client.Client) -> Unit) =
        runBlocking {
            val http = HttpClient(CIO)
            try {
                val client = http.mcpStreamableHttp(
                    "http://127.0.0.1:$port${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}"
                )
                try {
                    block(client)
                } finally {
                    client.close()
                }
            } finally {
                http.close()
            }
        }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

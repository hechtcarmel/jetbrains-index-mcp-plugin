package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.project.Project
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * When the EDT heartbeat is stale, tool calls must fail fast with an actionable error
 * instead of hanging until the client timeout.
 */
class McpToolDispatcherEdtGateTest : McpPlatformTestCase() {

    fun testToolCallFailsFastWhenEdtIsUnresponsive() = runBlocking {
        val dispatcher = McpToolDispatcher(
            toolRegistry = ToolRegistry().apply { register(EchoTool()) },
            edtUnresponsiveDurationMs = { 120_000L }
        )

        val result = dispatcher.call(EchoTool.NAME, buildJsonObject { })

        assertTrue("Should be an error when EDT is unresponsive", result.isFailure)
        assertTrue(
            "Error should mention unresponsive duration, was: ${result.text}",
            result.text.contains("120s")
        )
        assertTrue(
            "Error should suggest restarting, was: ${result.text}",
            result.text.contains("restart")
        )
    }

    fun testToolCallProceedsWhenEdtIsResponsive() = runBlocking {
        val dispatcher = McpToolDispatcher(
            toolRegistry = ToolRegistry().apply { register(EchoTool()) },
            edtUnresponsiveDurationMs = { null }
        )

        val result = dispatcher.call(EchoTool.NAME, buildJsonObject { })

        assertFalse("Should succeed when EDT is responsive", result.isFailure)
        assertEquals("echo", (result.content.single() as TextContent).text)
    }

    private class EchoTool : McpTool {
        override val name: String = NAME
        override val description: String = "Test tool for EDT gate tests"
        override val inputSchema: ToolSchema = ToolSchema()

        override suspend fun execute(project: Project, arguments: JsonObject): CallToolResult =
            CallToolResult(content = listOf(TextContent("echo")))

        companion object {
            const val NAME = "ide_test_edt_gate"
        }
    }
}

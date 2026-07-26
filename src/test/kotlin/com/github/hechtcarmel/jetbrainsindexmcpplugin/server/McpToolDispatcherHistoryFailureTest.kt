package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Command history is a UI convenience. A failure writing it must never turn a working tool call
 * into a failed one.
 */
class McpToolDispatcherHistoryFailureTest : BasePlatformTestCase() {

    fun testToolCallStillSucceedsWhenHistoryRecordingFails() = runBlocking {
        val dispatcher = McpToolDispatcher(
            toolRegistry = ToolRegistry().apply { register(TestTool()) },
            recordHistory = { _, _ -> error("history record failure") }
        )

        val result = dispatcher.call(TestTool.NAME, buildJsonObject { })

        assertFalse("Tool result should still succeed", result.isFailure)
        assertEquals("ok", (result.content.single() as TextContent).text)
    }

    fun testToolCallStillSucceedsWhenHistoryUpdateFails() = runBlocking {
        val dispatcher = McpToolDispatcher(
            toolRegistry = ToolRegistry().apply { register(TestTool()) },
            recordHistory = { _, _ -> },
            updateHistory = { _, _, _, _, _ -> error("history update failure") }
        )

        val result = dispatcher.call(TestTool.NAME, buildJsonObject { })

        assertFalse("Tool result should still succeed", result.isFailure)
        assertEquals("ok", (result.content.single() as TextContent).text)
    }

    private class TestTool : McpTool {
        override val name: String = NAME
        override val description: String = "Test tool for history failure regression coverage"
        override val inputSchema: ToolSchema = ToolSchema()

        override suspend fun execute(project: Project, arguments: JsonObject): CallToolResult =
            CallToolResult(content = listOf(TextContent("ok")))

        companion object {
            const val NAME = "ide_test_history_failure"
        }
    }
}

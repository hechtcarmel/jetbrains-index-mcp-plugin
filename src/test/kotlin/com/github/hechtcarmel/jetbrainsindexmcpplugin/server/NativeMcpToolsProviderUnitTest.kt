package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.intellij.mcpserver.McpTool as NativeMcpTool
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NativeMcpToolsProviderUnitTest : TestCase() {

    fun testProviderWrapsEnabledToolsOnly() {
        val enabledTool = FakeTool("ide_enabled_tool")
        val service = mockk<McpServerService>()
        mockkObject(McpServerService.Companion)
        every { McpServerService.getInstance() } returns service
        every { service.getEnabledTools() } returns listOf(enabledTool)

        try {
            val provider = NativeMcpToolsProvider()

            val nativeTools = provider.getTools()
            val nativeTool = nativeTools.single() as NativeMcpTool

            assertEquals(1, nativeTools.size)
            assertEquals("ide_enabled_tool", nativeTool.descriptor.name)
        } finally {
            unmockkObject(McpServerService.Companion)
        }
    }

    private class FakeTool(
        override val name: String
    ) : McpTool {
        override val description = "Fake tool used by native MCP provider tests."
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("project_path", buildJsonObject {
                    put("type", "string")
                })
            })
            put("required", JsonArray(emptyList()))
        }

        override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
            return ToolCallResult(content = listOf(ContentBlock.Text(name)), isError = false)
        }
    }
}

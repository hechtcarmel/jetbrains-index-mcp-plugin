package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class NativeMcpToolAdapterUnitTest : TestCase() {

    fun testDescriptorPreservesProjectPathSchema() {
        val adapter = NativeMcpToolAdapter(FakeTool())

        val schema = adapter.descriptor.inputSchema
        assertTrue(schema.propertiesSchema.containsKey("project_path"))
        assertTrue(schema.propertiesSchema.containsKey("query"))
        assertTrue(schema.requiredProperties.contains("query"))
        assertFalse(schema.requiredProperties.contains("project_path"))
    }

    fun testCallUsesFallbackProjectWhenProjectPathIsMissing() = runBlocking {
        val project = mockProject("fallback-project")
        val adapter = NativeMcpToolAdapter(FakeTool())

        val result = adapter.call(
            buildJsonObject {
                put("query", "hello")
            },
            project
        )

        assertFalse(result.isError)
        val content = result.content.single() as McpToolCallResultContent.Text
        assertEquals("Executed hello for fallback-project", content.text)
    }

    fun testExplicitProjectPathTakesPrecedenceOverFallbackProject() = runBlocking {
        val project = mockProject("fallback-project")
        val adapter = NativeMcpToolAdapter(FakeTool())
        mockkObject(ProjectResolver)
        every { ProjectResolver.resolve("/definitely/missing/project") } returns ProjectResolver.Result(
            isError = true,
            errorResult = ToolCallResult(
                content = listOf(ContentBlock.Text("{\"error\":\"project_not_found\"}")),
                isError = true
            )
        )

        try {
            val result = adapter.call(
                buildJsonObject {
                    put("project_path", "/definitely/missing/project")
                    put("query", "hello")
                },
                project
            )

            assertTrue(result.isError)
            val content = result.content.single() as McpToolCallResultContent.Text
            assertTrue(content.text.contains("project_not_found"))
        } finally {
            unmockkObject(ProjectResolver)
        }
    }

    fun testCallRecordsCommandHistory() = runBlocking {
        val project = mockProject("fallback-project")
        val recorder = RecordingHistoryRecorder()
        val adapter = NativeMcpToolAdapter(FakeTool()) { recorder }

        adapter.call(
            buildJsonObject {
                put("query", "hello")
            },
            project
        )

        assertEquals(1, recorder.recordedEntries.size)
        assertEquals("ide_fake_tool", recorder.recordedEntries.single().toolName)
        assertEquals("hello", recorder.recordedEntries.single().parameters["query"]?.jsonPrimitive?.content)
        assertEquals(1, recorder.statusUpdates.size)
        assertEquals(CommandStatus.SUCCESS, recorder.statusUpdates.single().status)
        assertEquals("Executed hello for fallback-project", recorder.statusUpdates.single().result)
    }

    private fun mockProject(name: String): Project {
        return mockk {
            every { this@mockk.name } returns name
            every { this@mockk.isDisposed } returns false
        }
    }

    private class FakeTool : McpTool {
        override val name = "ide_fake_tool"
        override val description = "Fake tool used by native MCP adapter tests."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("project_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional project path.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Query text.")
                })
            })
            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
        }

        override suspend fun execute(project: Project, arguments: kotlinx.serialization.json.JsonObject): ToolCallResult {
            val query = arguments["query"]?.jsonPrimitive?.content ?: "missing"
            return ToolCallResult(
                content = listOf(ContentBlock.Text("Executed $query for ${project.name}")),
                isError = false
            )
        }
    }

    private class RecordingHistoryRecorder : HistoryRecorder {
        val recordedEntries = mutableListOf<CommandEntry>()
        val statusUpdates = mutableListOf<StatusUpdate>()

        override fun recordCommand(entry: CommandEntry) {
            recordedEntries += entry
        }

        override fun updateCommandStatus(id: String, status: CommandStatus, result: String?, durationMs: Long?) {
            statusUpdates += StatusUpdate(id, status, result, durationMs)
        }
    }

    private data class StatusUpdate(
        val id: String,
        val status: CommandStatus,
        val result: String?,
        val durationMs: Long?
    )
}

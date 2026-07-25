package com.github.hechtcarmel.jetbrainsindexmcpplugin.models

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.*
import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpModelsUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ContentBlock serialization tests

    fun testTextContentBlockSerialization() {
        val textBlock = ContentBlock.Text("Hello, World!")

        val serialized = json.encodeToString<ContentBlock>(textBlock)
        val deserialized = json.decodeFromString<ContentBlock>(serialized)

        assertEquals("Hello, World!", (deserialized as ContentBlock.Text).text)
        assertTrue("Serialized should contain type discriminator", serialized.contains("\"type\":\"text\""))
    }

    fun testImageContentBlockSerialization() {
        val imageBlock = ContentBlock.Image("base64data==", "image/png")

        val serialized = json.encodeToString<ContentBlock>(imageBlock)
        val image = json.decodeFromString<ContentBlock>(serialized) as ContentBlock.Image

        assertEquals("base64data==", image.data)
        assertEquals("image/png", image.mimeType)
        assertTrue("Serialized should contain type discriminator", serialized.contains("\"type\":\"image\""))
    }

    fun testContentBlockPolymorphicDeserialization() {
        val textJson = """{"type":"text","text":"test content"}"""
        val imageJson = """{"type":"image","data":"abc123","mimeType":"image/jpeg"}"""

        val textBlock = json.decodeFromString<ContentBlock>(textJson)
        val imageBlock = json.decodeFromString<ContentBlock>(imageJson)

        assertTrue("Text JSON should deserialize to Text", textBlock is ContentBlock.Text)
        assertTrue("Image JSON should deserialize to Image", imageBlock is ContentBlock.Image)
    }

    // ToolCallResult tests

    fun testToolCallResultUsesMcpWireKeys() {
        val serialized = json.encodeToString(
            ToolCallResult(content = listOf(ContentBlock.Text("Error message")), isError = true)
        )

        assertTrue("MCP clients read the 'content' array", serialized.contains("\"content\":["))
        assertTrue("MCP clients read the 'isError' flag", serialized.contains("\"isError\":true"))
    }

    // ToolDefinition tests

    fun testToolDefinitionUsesMcpWireKeys() {
        val definition = ToolDefinition(
            name = "test_tool",
            description = "A test tool",
            inputSchema = buildJsonObject { put("type", "object") }
        )

        val serialized = json.encodeToString(definition)

        assertTrue("tools/list entries expose 'name'", serialized.contains("\"name\":\"test_tool\""))
        assertTrue("tools/list entries expose 'description'", serialized.contains("\"description\":\"A test tool\""))
        assertTrue("tools/list entries expose 'inputSchema'", serialized.contains("\"inputSchema\":{"))
    }

    // ServerCapabilities tests

    fun testServerCapabilitiesAdvertiseToolsByDefault() {
        val capabilities = ServerCapabilities()

        assertNotNull("initialize must advertise the tools capability", capabilities.tools)
        assertFalse("server does not send tools/list_changed notifications", capabilities.tools!!.listChanged)
    }
}

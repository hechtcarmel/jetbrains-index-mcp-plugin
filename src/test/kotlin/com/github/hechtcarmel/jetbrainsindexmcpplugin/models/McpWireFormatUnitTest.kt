package com.github.hechtcarmel.jetbrainsindexmcpplugin.models

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import junit.framework.TestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pins the bytes the MCP Kotlin SDK puts on the wire for the envelope shapes this plugin emits.
 *
 * These were hand-written models before the SDK migration, and their serialized form is a
 * contract with every configured MCP client. This test is what turns red if an SDK upgrade
 * starts emitting a `$schema` key, adds a class discriminator to content blocks, drops
 * `isError`, or renames anything — none of which the golden tool manifest would notice, because
 * that snapshot is generated from the same code it is checking.
 *
 * Everything asserted here matches what the plugin shipped before the migration.
 */
class McpWireFormatUnitTest : TestCase() {

    private val json = McpJson

    fun testTextContentSerializesAsTypeTextPlusText() {
        val serialized = json.encodeToString<ContentBlock>(TextContent("Hello, World!")).asJsonObject()

        assertEquals(setOf("type", "text"), serialized.keys)
        assertEquals("text", serialized["type"]!!.jsonPrimitive.content)
        assertEquals("Hello, World!", serialized["text"]!!.jsonPrimitive.content)
    }

    fun testImageContentSerializesAsTypeImagePlusDataAndMimeType() {
        val serialized = json.encodeToString<ContentBlock>(ImageContent("base64data==", "image/png")).asJsonObject()

        assertEquals(setOf("type", "data", "mimeType"), serialized.keys)
        assertEquals("image", serialized["type"]!!.jsonPrimitive.content)
        assertEquals("base64data==", serialized["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", serialized["mimeType"]!!.jsonPrimitive.content)
    }

    fun testContentBlockRoundTripsPolymorphically() {
        val text = json.decodeFromString<ContentBlock>("""{"type":"text","text":"Hello"}""")
        val image = json.decodeFromString<ContentBlock>("""{"type":"image","data":"abc","mimeType":"image/png"}""")

        assertTrue("Text JSON should decode to TextContent", text is TextContent)
        assertTrue("Image JSON should decode to ImageContent", image is ImageContent)
        assertEquals("Hello", (text as TextContent).text)
    }

    fun testCallToolResultEmitsContentAndIsErrorAndNothingElse() {
        val result = CallToolResult(content = listOf(TextContent("Error message")), isError = true)

        val serialized = json.encodeToString(result).asJsonObject()

        // structuredContent and _meta must stay off the wire when unset — McpJson's
        // explicitNulls = false is what guarantees it.
        assertEquals(setOf("content", "isError"), serialized.keys)
        assertTrue(serialized["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    fun testToolEmitsNameDescriptionAndAnObjectInputSchema() {
        val tool = Tool(
            name = "ide_test_tool",
            description = "Test tool",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("file", buildJsonObject { put("type", "string") })
                },
                required = listOf("file")
            )
        )

        val serialized = json.encodeToString(tool).asJsonObject()

        assertEquals(setOf("name", "inputSchema", "description"), serialized.keys)

        val schema = serialized["inputSchema"]!!.jsonObject
        // No "$defs": the plugin never sets it and clients have never seen it, but ToolSchema
        // would happily emit it.
        assertEquals(setOf("properties", "required", "type"), schema.keys)
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
    }

    fun testToolSchemaOmitsRequiredWhenThereAreNoRequiredParameters() {
        val schema = json.encodeToString(ToolSchema(properties = buildJsonObject { })).asJsonObject()

        assertEquals(
            "A tool with no required parameters must not emit an empty required array",
            setOf("properties", "type"),
            schema.keys
        )
    }

    fun testServerCapabilitiesAdvertiseToolsWithListChangedFalse() {
        val capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))

        val serialized = json.encodeToString(capabilities).asJsonObject()

        assertEquals(setOf("tools"), serialized.keys)
        assertFalse(
            serialized["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.content.toBoolean()
        )
    }

    private fun String.asJsonObject() = Json.parseToJsonElement(this).jsonObject
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.contract

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import junit.framework.TestCase
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Guards the one thing the golden manifest cannot see.
 *
 * Tool schemas used to be free-form `JsonObject`s. They are now [ToolSchema], which can only
 * carry `$schema`, `properties`, `required`, `$defs` and a fixed `type: "object"`. A tool that
 * needs any other JSON Schema keyword — `additionalProperties`, `oneOf`, `patternProperties` —
 * would have it **silently dropped** on the way to the client, and the golden manifest would
 * happily record the truncated version because it is generated from the same objects.
 *
 * This asserts the schema a tool declares survives the trip to JSON with nothing lost.
 */
class ToolSchemaFidelityUnitTest : TestCase() {

    private companion object {
        /** Keys [ToolSchema] is able to represent. Anything else cannot reach the wire. */
        val REPRESENTABLE_KEYS = setOf("\$schema", "properties", "required", "\$defs", "type")

        val PINNED_LANGUAGES = listOf("Java", "Kotlin", "JavaScript", "TypeScript")
    }

    override fun setUp() {
        super.setUp()
        mockkObject(LanguageHandlerRegistry)
        every { LanguageHandlerRegistry.getSupportedLanguageNamesForSymbolReference() } returns PINNED_LANGUAGES
    }

    override fun tearDown() {
        try {
            unmockkObject(LanguageHandlerRegistry)
        } finally {
            super.tearDown()
        }
    }

    fun testEveryToolSchemaSerializesToOnlyRepresentableKeys() {
        val tools = ToolRegistry().apply { registerBuiltInTools() }.getAllTools()
        assertTrue("Registry should expose built-in tools", tools.isNotEmpty())

        tools.forEach { tool ->
            val serialized = McpJson.encodeToJsonElement(tool.inputSchema).jsonObject
            val unrepresentable = serialized.keys - REPRESENTABLE_KEYS
            assertEquals(
                "${tool.name} emits schema keys ToolSchema cannot round-trip. Either express " +
                    "them through SchemaBuilder's supported shapes, or stop using ToolSchema.",
                emptySet<String>(),
                unrepresentable
            )
        }
    }

    fun testEveryToolSchemaIsAnObjectSchemaWithProperties() {
        val tools = ToolRegistry().apply { registerBuiltInTools() }.getAllTools()

        tools.forEach { tool ->
            assertEquals("${tool.name} must declare an object schema", "object", tool.inputSchema.type)
            assertNotNull(
                "${tool.name} must declare properties, even if empty — clients treat a missing " +
                    "properties map as 'no arguments accepted'",
                tool.inputSchema.properties
            )
        }
    }

    fun testRequiredNamesAlwaysReferToDeclaredProperties() {
        val tools = ToolRegistry().apply { registerBuiltInTools() }.getAllTools()

        tools.forEach { tool ->
            val declared = tool.inputSchema.properties?.keys.orEmpty()
            val required = tool.inputSchema.required.orEmpty()
            val dangling = required - declared
            assertEquals(
                "${tool.name} marks parameters required that it never declares",
                emptyList<String>(),
                dangling.sorted()
            )
        }
    }
}

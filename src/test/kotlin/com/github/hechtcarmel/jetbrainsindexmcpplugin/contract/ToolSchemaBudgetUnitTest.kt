package com.github.hechtcarmel.jetbrainsindexmcpplugin.contract

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import junit.framework.TestCase
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class ToolSchemaBudgetUnitTest : TestCase() {
    fun testPublishedToolDescriptionsAndSchemasFitTheContextBudget() {
        val tools = ToolRegistry().apply { registerBuiltInTools() }.getAllTools()
        assertTrue("The budget must cover the actual registry", tools.size >= 52)
        val payload = buildJsonArray {
            tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", McpJson.encodeToJsonElement(tool.inputSchema))
                })
            }
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8).size
        assertTrue("Tool discovery uses $bytes bytes; keep shared target descriptions concise (budget 116000)", bytes <= 116_000)
    }
}

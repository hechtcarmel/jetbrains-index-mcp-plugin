package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class McpPluginTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testJsonRpcRequestSerialization() {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "tools/list"
        )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<JsonRpcRequest>(serialized)

        assertEquals("2.0", deserialized.jsonrpc)
        assertEquals("tools/list", deserialized.method)
    }

    fun testJsonRpcResponseSerialization() {
        val response = JsonRpcResponse(
            id = JsonPrimitive(1),
            result = JsonPrimitive("test")
        )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<JsonRpcResponse>(serialized)

        assertEquals("2.0", deserialized.jsonrpc)
        assertNull(deserialized.error)
    }

    fun testToolRegistry() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val tools = registry.getAllTools()
        assertTrue("Should have registered tools", tools.isNotEmpty())

        // Check that find_usages tool exists
        val findUsagesTool = registry.getTool("find_usages")
        assertNotNull("find_usages tool should be registered", findUsagesTool)

        // Check that go_to_definition tool exists
        val goToDefTool = registry.getTool("go_to_definition")
        assertNotNull("go_to_definition tool should be registered", goToDefTool)

        // Check that get_index_status tool exists
        val indexStatusTool = registry.getTool("get_index_status")
        assertNotNull("get_index_status tool should be registered", indexStatusTool)
    }

    fun testJsonRpcErrorCodes() {
        assertEquals(-32700, JsonRpcErrorCodes.PARSE_ERROR)
        assertEquals(-32600, JsonRpcErrorCodes.INVALID_REQUEST)
        assertEquals(-32601, JsonRpcErrorCodes.METHOD_NOT_FOUND)
        assertEquals(-32602, JsonRpcErrorCodes.INVALID_PARAMS)
        assertEquals(-32603, JsonRpcErrorCodes.INTERNAL_ERROR)
    }
}

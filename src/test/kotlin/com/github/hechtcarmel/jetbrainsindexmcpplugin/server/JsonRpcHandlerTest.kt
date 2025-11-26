package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.resources.ResourceRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Platform-dependent tests that require IntelliJ Platform indexing.
 * For JSON-RPC protocol tests that don't need the platform, see JsonRpcHandlerUnitTest.
 */
class JsonRpcHandlerTest : BasePlatformTestCase() {

    private lateinit var handler: JsonRpcHandler
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var resourceRegistry: ResourceRegistry

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        resourceRegistry = ResourceRegistry()
        resourceRegistry.registerBuiltInResources()
        handler = JsonRpcHandler(toolRegistry, resourceRegistry)
    }

    fun testToolCallWithValidTool() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(9),
            method = "tools/call",
            params = buildJsonObject {
                put("name", "get_index_status")
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("get_index_status should not return JSON-RPC error", response.error)
        assertNotNull("get_index_status should return result", response.result)
    }
}

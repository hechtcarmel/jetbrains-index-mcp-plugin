package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.resources.ResourceRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JsonRpcHandlerUnitTest : TestCase() {

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

    fun testInitializeRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("Initialize should not return error", response.error)
        assertNotNull("Initialize should return result", response.result)

        val result = response.result!!.jsonObject
        assertNotNull("Result should contain serverInfo", result["serverInfo"])
        assertNotNull("Result should contain capabilities", result["capabilities"])

        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals("intellij-index-mcp", serverInfo["name"]?.jsonPrimitive?.content)
        assertNotNull("serverInfo should contain description", serverInfo["description"])
        assertTrue(
            "description should mention code intelligence",
            serverInfo["description"]?.jsonPrimitive?.content?.contains("code intelligence") == true
        )
    }

    fun testToolsListRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(2),
            method = "tools/list"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("tools/list should not return error", response.error)
        assertNotNull("tools/list should return result", response.result)

        val result = response.result!!.jsonObject
        assertNotNull("Result should contain tools array", result["tools"])
    }

    fun testResourcesListRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(3),
            method = "resources/list"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("resources/list should not return error", response.error)
        assertNotNull("resources/list should return result", response.result)

        val result = response.result!!.jsonObject
        assertNotNull("Result should contain resources array", result["resources"])
    }

    fun testPingRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(4),
            method = "ping"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("ping should not return error", response.error)
        assertNotNull("ping should return result", response.result)
    }

    fun testMethodNotFound() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(5),
            method = "unknown/method"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("Unknown method should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    fun testToolCallMissingParams() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(6),
            method = "tools/call"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("tools/call without params should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testToolCallMissingToolName() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(7),
            method = "tools/call",
            params = buildJsonObject {
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("tools/call without tool name should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testToolCallUnknownTool() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(8),
            method = "tools/call",
            params = buildJsonObject {
                put("name", "unknown_tool")
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("tools/call with unknown tool should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    fun testParseError() = runBlocking {
        val responseJson = handler.handleRequest("not valid json")
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("Invalid JSON should return error", response.error)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, response.error?.code)
    }

    fun testResourceReadMissingParams() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(10),
            method = "resources/read"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("resources/read without params should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testResourceReadMissingUri() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(11),
            method = "resources/read",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("resources/read without uri should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testResourceReadUnknownResource() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(12),
            method = "resources/read",
            params = buildJsonObject {
                put("uri", "unknown://resource")
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("resources/read with unknown uri should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }
}

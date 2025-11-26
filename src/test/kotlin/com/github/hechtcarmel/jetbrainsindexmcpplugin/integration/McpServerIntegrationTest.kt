package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.resources.ResourceRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.JsonRpcHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Integration tests for the MCP server endpoints.
 * Tests full server functionality including tools/list, tools/call, resources/list, and resources/read.
 */
class McpServerIntegrationTest : BasePlatformTestCase() {

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

    // Server Initialization Tests

    fun testInitializeEndpoint() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "initialize",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("Initialize should not return error", response.error)
        assertNotNull("Initialize should return result", response.result)

        val result = response.result!!.jsonObject
        assertNotNull("Result should have serverInfo", result["serverInfo"])
        assertNotNull("Result should have capabilities", result["capabilities"])

        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals("intellij-index-mcp", serverInfo["name"]?.jsonPrimitive?.content)
    }

    fun testInitializedEndpoint() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(2),
            method = "initialized",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("Initialized should not return error", response.error)
        assertNotNull("Initialized should return result", response.result)
    }

    fun testPingEndpoint() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(3),
            method = "ping",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("Ping should not return error", response.error)
        assertNotNull("Ping should return result", response.result)
    }

    // Tools List Tests

    fun testToolsListEndpoint() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(10),
            method = "tools/list",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("tools/list should not return error", response.error)
        assertNotNull("tools/list should return result", response.result)

        val result = response.result!!.jsonObject
        val tools = result["tools"]?.jsonArray
        assertNotNull("Result should have tools array", tools)
        assertTrue("Should have at least 14 tools", tools!!.size >= 14)
    }

    fun testToolsListContainsNavigationTools() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(11),
            method = "tools/list",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        val tools = response.result!!.jsonObject["tools"]?.jsonArray
        val toolNames = tools?.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        val expectedNavigationTools = listOf(
            "find_usages",
            "find_definition",
            "type_hierarchy",
            "call_hierarchy",
            "find_implementations"
        )

        expectedNavigationTools.forEach { toolName ->
            assertTrue("Should contain $toolName tool", toolNames?.contains(toolName) == true)
        }
    }

    fun testToolsListContainsIntelligenceTools() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(12),
            method = "tools/list",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        val tools = response.result!!.jsonObject["tools"]?.jsonArray
        val toolNames = tools?.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        val expectedIntelligenceTools = listOf(
            "get_symbol_info",
            "get_completions",
            "get_inspections",
            "get_quick_fixes",
            "apply_quick_fix"
        )

        expectedIntelligenceTools.forEach { toolName ->
            assertTrue("Should contain $toolName tool", toolNames?.contains(toolName) == true)
        }
    }

    fun testToolsListContainsProjectTools() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(13),
            method = "tools/list",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        val tools = response.result!!.jsonObject["tools"]?.jsonArray
        val toolNames = tools?.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        val expectedProjectTools = listOf(
            "get_index_status",
            "get_file_structure",
            "get_project_structure",
            "get_dependencies"
        )

        expectedProjectTools.forEach { toolName ->
            assertTrue("Should contain $toolName tool", toolNames?.contains(toolName) == true)
        }
    }

    // Tools Call Tests

    fun testToolsCallGetIndexStatus() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(20),
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

    fun testToolsCallGetProjectStructure() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(21),
            method = "tools/call",
            params = buildJsonObject {
                put("name", "get_project_structure")
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("get_project_structure should not return JSON-RPC error", response.error)
        assertNotNull("get_project_structure should return result", response.result)
    }

    fun testToolsCallGetDependencies() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(22),
            method = "tools/call",
            params = buildJsonObject {
                put("name", "get_dependencies")
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("get_dependencies should not return JSON-RPC error", response.error)
        assertNotNull("get_dependencies should return result", response.result)
    }

    fun testToolsCallNonExistentTool() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(23),
            method = "tools/call",
            params = buildJsonObject {
                put("name", "non_existent_tool")
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("Non-existent tool should return error", response.error)
        assertEquals(-32601, response.error?.code)
    }

    fun testToolsCallMissingToolName() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(24),
            method = "tools/call",
            params = buildJsonObject {
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("Missing tool name should return error", response.error)
        assertEquals(-32602, response.error?.code)
    }

    // Resources List Tests

    fun testResourcesListEndpoint() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(30),
            method = "resources/list",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("resources/list should not return error", response.error)
        assertNotNull("resources/list should return result", response.result)

        val result = response.result!!.jsonObject
        val resources = result["resources"]?.jsonArray
        assertNotNull("Result should have resources array", resources)
        assertTrue("Should have at least 4 resources", resources!!.size >= 4)
    }

    fun testResourcesListContainsExpectedResources() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(31),
            method = "resources/list",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        val resources = response.result!!.jsonObject["resources"]?.jsonArray
        val resourceUris = resources?.map { it.jsonObject["uri"]?.jsonPrimitive?.content }

        assertTrue("Should contain index status resource",
            resourceUris?.any { it?.contains("index://status") == true } == true)
        assertTrue("Should contain project structure resource",
            resourceUris?.any { it?.contains("project://structure") == true } == true)
        assertTrue("Should contain file content resource",
            resourceUris?.any { it?.contains("file://content") == true } == true)
        assertTrue("Should contain symbol info resource",
            resourceUris?.any { it?.contains("symbol://info") == true } == true)
    }

    // Resources Read Tests

    fun testResourcesReadIndexStatus() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(40),
            method = "resources/read",
            params = buildJsonObject {
                put("uri", "index://status")
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("resources/read index://status should not return error", response.error)
        assertNotNull("resources/read should return result", response.result)
    }

    fun testResourcesReadProjectStructure() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(41),
            method = "resources/read",
            params = buildJsonObject {
                put("uri", "project://structure")
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("resources/read project://structure should not return error", response.error)
        assertNotNull("resources/read should return result", response.result)
    }

    fun testResourcesReadNonExistent() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(42),
            method = "resources/read",
            params = buildJsonObject {
                put("uri", "nonexistent://resource")
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("Non-existent resource should return error", response.error)
    }

    // Error Handling Tests

    fun testInvalidMethodReturnsError() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(50),
            method = "invalid/method",
            params = buildJsonObject { }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("Invalid method should return error", response.error)
        assertEquals(-32601, response.error?.code)
    }

    fun testMalformedJsonReturnsError() = runBlocking {
        val responseJson = handler.handleRequest("{invalid json}")
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNotNull("Malformed JSON should return error", response.error)
        assertEquals(-32700, response.error?.code)
    }
}

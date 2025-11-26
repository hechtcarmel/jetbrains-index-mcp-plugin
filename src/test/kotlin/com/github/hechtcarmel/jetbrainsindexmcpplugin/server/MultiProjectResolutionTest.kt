package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.resources.ResourceRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
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

class MultiProjectResolutionTest : BasePlatformTestCase() {

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

    fun testToolCallWithSingleProject() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = "tools/call",
            params = buildJsonObject {
                put("name", "get_index_status")
                put("arguments", buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("Single project should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with single project", result.isError)
    }

    fun testToolCallWithExplicitProjectPath() = runBlocking {
        val projectPath = project.basePath

        val request = JsonRpcRequest(
            id = JsonPrimitive(2),
            method = "tools/call",
            params = buildJsonObject {
                put("name", "get_index_status")
                put("arguments", buildJsonObject {
                    put("project_path", projectPath ?: "")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("Explicit project_path should not return JSON-RPC error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertFalse("Tool should succeed with explicit project_path", result.isError)
    }

    fun testToolCallWithInvalidProjectPath() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(3),
            method = "tools/call",
            params = buildJsonObject {
                put("name", "get_index_status")
                put("arguments", buildJsonObject {
                    put("project_path", "/non/existent/project/path")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("Should not return JSON-RPC level error", response.error)
        assertNotNull("Should return result", response.result)

        val result = json.decodeFromJsonElement(ToolCallResult.serializer(), response.result!!)
        assertTrue("Tool should return error for invalid project_path", result.isError)

        val content = result.content.firstOrNull()
        assertNotNull("Should have error content", content)

        val errorJson = json.parseToJsonElement(
            (content as? com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock.Text)?.text ?: ""
        ).jsonObject

        assertEquals("project_not_found", errorJson["error"]?.jsonPrimitive?.content)
        assertNotNull("Should include available_projects", errorJson["available_projects"])
    }

    fun testToolInputSchemaIncludesProjectPath() {
        val tool = toolRegistry.getTool("find_usages")
        assertNotNull("find_usages tool should exist", tool)

        val schema = tool!!.inputSchema
        val properties = schema["properties"]?.jsonObject
        assertNotNull("Schema should have properties", properties)

        val projectPathProp = properties?.get("project_path")?.jsonObject
        assertNotNull("Schema should include project_path property", projectPathProp)
        assertEquals("string", projectPathProp?.get("type")?.jsonPrimitive?.content)
    }

    fun testAllToolsHaveProjectPathInSchema() {
        val tools = toolRegistry.getAllTools()

        for (tool in tools) {
            val schema = tool.inputSchema
            val properties = schema["properties"]?.jsonObject

            assertNotNull("${tool.name} schema should have properties", properties)

            val projectPathProp = properties?.get("project_path")?.jsonObject
            assertNotNull("${tool.name} schema should include project_path property", projectPathProp)
        }
    }

    fun testResourceReadWithExplicitProjectPath() = runBlocking {
        val projectPath = project.basePath

        val request = JsonRpcRequest(
            id = JsonPrimitive(4),
            method = "resources/read",
            params = buildJsonObject {
                put("uri", "index://status")
                put("project_path", projectPath ?: "")
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson)

        assertNull("Resource read with explicit project_path should not error", response.error)
        assertNotNull("Should return result", response.result)
    }
}

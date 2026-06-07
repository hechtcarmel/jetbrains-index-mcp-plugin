package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.JsonRpcMethods
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.project.Project
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JsonRpcHandlerUnitTest : TestCase() {

    private lateinit var handler: JsonRpcHandler
    private lateinit var toolRegistry: ToolRegistry
    private var includeStructuredOutput = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        includeStructuredOutput = false
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        handler = JsonRpcHandler(
            toolRegistry = toolRegistry,
            includeStructuredOutput = { includeStructuredOutput },
            resolveProject = { structuredProjectResolutionError() }
        )
    }

    fun testInitializeRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", McpConstants.MCP_PROTOCOL_VERSION)
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Initialize should not return error", response.error)
        assertNotNull("Initialize should return result", response.result)

        val result = response.result!!.jsonObject
        assertNotNull("Result should contain serverInfo", result["serverInfo"])
        assertNotNull("Result should contain capabilities", result["capabilities"])

        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals(McpConstants.SERVER_NAME, serverInfo["name"]?.jsonPrimitive?.content)
        assertNotNull("serverInfo should contain description", serverInfo["description"])
        assertTrue(
            "description should mention code intelligence",
            serverInfo["description"]?.jsonPrimitive?.content?.contains("code intelligence", ignoreCase = true) == true
        )
    }

    fun testInitializeRequestCanOverrideProtocolVersion() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = "2024-11-05"
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("Initialize should not return error", response.error)
        assertEquals(
            "2024-11-05",
            response.result!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testInitializeRequestNegotiates20250618() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", McpConstants.MCP_PROTOCOL_VERSION_2025_06_18)
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertEquals(
            McpConstants.MCP_PROTOCOL_VERSION_2025_06_18,
            response.result!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testInitializeRequestNegotiates20251125() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", McpConstants.MCP_PROTOCOL_VERSION_2025_11_25)
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertEquals(
            McpConstants.MCP_PROTOCOL_VERSION_2025_11_25,
            response.result!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testInitializeRequestFallsBackToLatestStreamableVersionForUnsupportedClientVersion() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(1),
            method = JsonRpcMethods.INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", "2099-01-01")
                put("clientInfo", buildJsonObject {
                    put("name", "test-client")
                    put("version", "1.0.0")
                })
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertEquals(
            McpConstants.LATEST_STREAMABLE_HTTP_MCP_PROTOCOL_VERSION,
            response.result!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        )
    }

    fun testToolsListOmitsOutputSchemaBeforeStructuredOutputProtocol() = runBlocking {
        toolRegistry.register(outputSchemaTool())
        val request = JsonRpcRequest(
            id = JsonPrimitive(2),
            method = JsonRpcMethods.TOOLS_LIST
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        val tool = response.result!!.jsonObject["tools"]!!.jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == "test_output_schema" }
            .jsonObject
        assertNull(tool["outputSchema"])
    }

    fun testToolsListOmitsOutputSchemaForStructuredOutputProtocolByDefault() = runBlocking {
        toolRegistry.register(outputSchemaTool())
        val request = JsonRpcRequest(
            id = JsonPrimitive(2),
            method = JsonRpcMethods.TOOLS_LIST
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_06_18
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        val tool = response.result!!.jsonObject["tools"]!!.jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == "test_output_schema" }
            .jsonObject
        assertNull(tool["outputSchema"])
    }

    fun testToolsListIncludesOutputSchemaForStructuredOutputProtocolWhenEnabled() = runBlocking {
        includeStructuredOutput = true
        toolRegistry.register(outputSchemaTool())
        val request = JsonRpcRequest(
            id = JsonPrimitive(2),
            method = JsonRpcMethods.TOOLS_LIST
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_06_18
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        val tool = response.result!!.jsonObject["tools"]!!.jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == "test_output_schema" }
            .jsonObject
        assertNotNull(tool["outputSchema"])
    }

    fun testToolCallOmitsStructuredContentBeforeStructuredOutputProtocol() = runBlocking {
        toolRegistry.register(outputSchemaTool())
        val request = JsonRpcRequest(
            id = JsonPrimitive(3),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.NAME, "test_output_schema")
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.STREAMABLE_HTTP_MCP_PROTOCOL_VERSION
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull(response.result!!.jsonObject["structuredContent"])
    }

    fun testToolCallOmitsStructuredContentForStructuredOutputProtocolByDefault() = runBlocking {
        toolRegistry.register(outputSchemaTool())
        val request = JsonRpcRequest(
            id = JsonPrimitive(3),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.NAME, "test_output_schema")
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_06_18
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull(response.result!!.jsonObject["structuredContent"])
    }

    fun testToolCallIncludesStructuredContentForStructuredOutputProtocolWhenEnabled() = runBlocking {
        includeStructuredOutput = true
        toolRegistry.register(outputSchemaTool())
        val request = JsonRpcRequest(
            id = JsonPrimitive(3),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.NAME, "test_output_schema")
            }
        )

        val responseJson = handler.handleRequest(
            json.encodeToString(JsonRpcRequest.serializer(), request),
            protocolVersion = McpConstants.MCP_PROTOCOL_VERSION_2025_06_18
        )
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull(response.result!!.jsonObject["structuredContent"])
    }

    fun testPingRequest() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(4),
            method = JsonRpcMethods.PING
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNull("${JsonRpcMethods.PING} should not return error", response.error)
        assertNotNull("${JsonRpcMethods.PING} should return result", response.result)
    }

    private fun outputSchemaTool(): McpTool = object : McpTool {
        override val name: String = "test_output_schema"
        override val description: String = "Test tool with output schema"
        override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
        override val outputSchema: JsonObject = buildJsonObject { put("type", "object") }

        override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
            error("Tool execution is not needed for tools/list tests")
        }
    }

    private fun structuredProjectResolutionError(): ProjectResolver.Result =
        ProjectResolver.Result(
            errorResult = ToolCallResult(
                content = listOf(ContentBlock.Text("Missing project")),
                structuredContent = buildJsonObject {
                    put("error", "missing_project")
                },
                isError = true
            ),
            isError = true
        )

    fun testMethodNotFound() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(5),
            method = "unknown/method"
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Unknown method should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    fun testToolCallMissingParams() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(6),
            method = JsonRpcMethods.TOOLS_CALL
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} without params should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testToolCallMissingToolName() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(7),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.ARGUMENTS, buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} without tool name should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, response.error?.code)
    }

    fun testToolCallUnknownTool() = runBlocking {
        val request = JsonRpcRequest(
            id = JsonPrimitive(8),
            method = JsonRpcMethods.TOOLS_CALL,
            params = buildJsonObject {
                put(ParamNames.NAME, "unknown_tool")
                put(ParamNames.ARGUMENTS, buildJsonObject { })
            }
        )

        val responseJson = handler.handleRequest(json.encodeToString(JsonRpcRequest.serializer(), request))
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("${JsonRpcMethods.TOOLS_CALL} with unknown tool should return error", response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    fun testParseError() = runBlocking {
        val responseJson = handler.handleRequest("not valid json")
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Invalid JSON should return error", response.error)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, response.error?.code)
    }

    fun testInvalidJsonRpcVersion() = runBlocking {
        val requestJson = """{"jsonrpc":"1.0","id":1,"method":"ping"}"""

        val responseJson = handler.handleRequest(requestJson)
        val response = json.decodeFromString<JsonRpcResponse>(responseJson!!)

        assertNotNull("Invalid jsonrpc version should return error", response.error)
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, response.error?.code)
        assertTrue(
            "Error message should mention version",
            response.error?.message?.contains("2.0") == true
        )
    }

    fun testNotificationReturnsNull() = runBlocking {
        val requestJson = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        val responseJson = handler.handleRequest(requestJson)

        assertNull("Notification should return null (no response)", responseJson)
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.McpErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import junit.framework.TestCase

/**
 * The JSON-RPC request/response serialization tests that used to live here were deleted with the
 * hand-written envelope models they covered. The MCP Kotlin SDK owns the envelope now; what this
 * plugin still puts on the wire is pinned by
 * [com.github.hechtcarmel.jetbrainsindexmcpplugin.models.McpWireFormatUnitTest].
 */
class McpPluginUnitTest : TestCase() {

    fun testToolRegistry() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val tools = registry.getAllTools()
        assertTrue("Should have registered tools", tools.isNotEmpty())

        val findReferencesTool = registry.getTool(ToolNames.FIND_REFERENCES)
        assertNotNull("${ToolNames.FIND_REFERENCES} tool should be registered", findReferencesTool)

        val findDefTool = registry.getTool(ToolNames.FIND_DEFINITION)
        assertNotNull("${ToolNames.FIND_DEFINITION} tool should be registered", findDefTool)

        val indexStatusTool = registry.getTool(ToolNames.INDEX_STATUS)
        assertNotNull("${ToolNames.INDEX_STATUS} tool should be registered", indexStatusTool)
    }

    fun testMcpErrorCodesMatchTheJsonRpcSpec() {
        assertEquals(-32700, McpErrorCodes.PARSE_ERROR)
        assertEquals(-32600, McpErrorCodes.INVALID_REQUEST)
        assertEquals(-32601, McpErrorCodes.METHOD_NOT_FOUND)
        assertEquals(-32602, McpErrorCodes.INVALID_PARAMS)
        assertEquals(-32603, McpErrorCodes.INTERNAL_ERROR)
    }
}

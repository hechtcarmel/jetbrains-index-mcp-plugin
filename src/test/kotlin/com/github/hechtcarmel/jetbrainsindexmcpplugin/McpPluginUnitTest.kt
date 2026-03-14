package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.JsonRpcErrorCodes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import junit.framework.TestCase

class McpPluginUnitTest : TestCase() {

    fun testServerVersionTracksPluginVersion() {
        assertEquals("5.0.0", McpConstants.SERVER_VERSION)
    }

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

    fun testJsonRpcErrorCodes() {
        assertEquals(-32700, JsonRpcErrorCodes.PARSE_ERROR)
        assertEquals(-32600, JsonRpcErrorCodes.INVALID_REQUEST)
        assertEquals(-32601, JsonRpcErrorCodes.METHOD_NOT_FOUND)
        assertEquals(-32602, JsonRpcErrorCodes.INVALID_PARAMS)
        assertEquals(-32603, JsonRpcErrorCodes.INTERNAL_ERROR)
    }
}

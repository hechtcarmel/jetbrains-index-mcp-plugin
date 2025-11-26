package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetSymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.GoToDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import junit.framework.TestCase
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolsUnitTest : TestCase() {

    fun testGetIndexStatusToolSchema() {
        val tool = GetIndexStatusTool()

        assertEquals("get_index_status", tool.name)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    fun testFindUsagesToolSchema() {
        val tool = FindUsagesTool()

        assertEquals("find_usages", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get("project_path"))
        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have line property", properties?.get("line"))
        assertNotNull("Should have column property", properties?.get("column"))

        val required = schema["required"]
        assertNotNull("Should have required array", required)
    }

    fun testGoToDefinitionToolSchema() {
        val tool = GoToDefinitionTool()

        assertEquals("go_to_definition", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get("project_path"))
        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have line property", properties?.get("line"))
        assertNotNull("Should have column property", properties?.get("column"))
    }

    fun testGetSymbolInfoToolSchema() {
        val tool = GetSymbolInfoTool()

        assertEquals("get_symbol_info", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get("project_path"))
        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have line property", properties?.get("line"))
        assertNotNull("Should have column property", properties?.get("column"))
    }

    fun testToolRegistryRegistersAllBuiltInTools() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val expectedTools = listOf(
            "find_usages",
            "go_to_definition",
            "get_symbol_info",
            "get_index_status"
        )

        for (toolName in expectedTools) {
            val tool = registry.getTool(toolName)
            assertNotNull("$toolName should be registered", tool)
        }
    }

    fun testToolDefinitionsHaveRequiredFields() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val definitions = registry.getToolDefinitions()

        for (definition in definitions) {
            assertNotNull("Definition should have name", definition.name)
            assertTrue("Name should not be empty", definition.name.isNotEmpty())

            assertNotNull("Definition should have description", definition.description)
            assertTrue("Description should not be empty", definition.description.isNotEmpty())

            assertNotNull("Definition should have inputSchema", definition.inputSchema)
            assertEquals("object", definition.inputSchema["type"]?.jsonPrimitive?.content)
        }
    }

    fun testAllToolsHaveProjectPathInSchema() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val tools = registry.getAllTools()

        for (tool in tools) {
            val schema = tool.inputSchema
            val properties = schema["properties"]?.jsonObject

            assertNotNull("${tool.name} schema should have properties", properties)

            val projectPathProp = properties?.get("project_path")?.jsonObject
            assertNotNull("${tool.name} schema should include project_path property", projectPathProp)
        }
    }
}

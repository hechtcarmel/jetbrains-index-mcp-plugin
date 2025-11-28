package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteTool
import junit.framework.TestCase
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolsUnitTest : TestCase() {

    fun testGetIndexStatusToolSchema() {
        val tool = GetIndexStatusTool()

        assertEquals(ToolNames.INDEX_STATUS, tool.name)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)
    }

    fun testFindUsagesToolSchema() {
        val tool = FindUsagesTool()

        assertEquals(ToolNames.FIND_REFERENCES, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))

        val required = schema[SchemaConstants.REQUIRED]
        assertNotNull("Should have required array", required)
    }

    fun testFindDefinitionToolSchema() {
        val tool = FindDefinitionTool()

        assertEquals(ToolNames.FIND_DEFINITION, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))
    }

    fun testTypeHierarchyToolSchema() {
        val tool = TypeHierarchyTool()

        assertEquals(ToolNames.TYPE_HIERARCHY, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))
        assertNotNull("Should have className property", properties?.get(ParamNames.CLASS_NAME))
    }

    fun testCallHierarchyToolSchema() {
        val tool = CallHierarchyTool()

        assertEquals(ToolNames.CALL_HIERARCHY, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have direction property", properties?.get(ParamNames.DIRECTION))
    }

    fun testFindImplementationsToolSchema() {
        val tool = FindImplementationsTool()

        assertEquals(ToolNames.FIND_IMPLEMENTATIONS, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))
    }

    fun testGetDiagnosticsToolSchema() {
        val tool = GetDiagnosticsTool()

        assertEquals(ToolNames.DIAGNOSTICS, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have startLine property", properties?.get(ParamNames.START_LINE))
        assertNotNull("Should have endLine property", properties?.get(ParamNames.END_LINE))
    }

    fun testToolRegistryRegistersAllBuiltInTools() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val expectedTools = listOf(
            // Navigation tools
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
            ToolNames.TYPE_HIERARCHY,
            ToolNames.CALL_HIERARCHY,
            ToolNames.FIND_IMPLEMENTATIONS,
            // Intelligence tools
            ToolNames.DIAGNOSTICS,
            // Project tools
            ToolNames.INDEX_STATUS,
            // Refactoring tools
            ToolNames.REFACTOR_RENAME,
            ToolNames.REFACTOR_SAFE_DELETE
        )

        assertEquals("Should have correct number of tools", expectedTools.size, registry.getAllTools().size)

        for (toolName in expectedTools) {
            val tool = registry.getTool(toolName)
            assertNotNull("$toolName should be registered", tool)
        }
    }

    // Phase 3: Refactoring Tools Schema Tests

    fun testRenameSymbolToolSchema() {
        val tool = RenameSymbolTool()

        assertEquals(ToolNames.REFACTOR_RENAME, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))
        assertNotNull("Should have newName property", properties?.get(ParamNames.NEW_NAME))
    }

    fun testSafeDeleteToolSchema() {
        val tool = SafeDeleteTool()

        assertEquals(ToolNames.REFACTOR_SAFE_DELETE, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))
        assertNotNull("Should have force property", properties?.get(ParamNames.FORCE))
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
            assertEquals(SchemaConstants.TYPE_OBJECT, definition.inputSchema[SchemaConstants.TYPE]?.jsonPrimitive?.content)
        }
    }

    fun testAllToolsHaveProjectPathInSchema() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val tools = registry.getAllTools()

        for (tool in tools) {
            val schema = tool.inputSchema
            val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject

            assertNotNull("${tool.name} schema should have properties", properties)

            val projectPathProp = properties?.get(ParamNames.PROJECT_PATH)?.jsonObject
            assertNotNull("${tool.name} schema should include project_path property", projectPathProp)
        }
    }
}

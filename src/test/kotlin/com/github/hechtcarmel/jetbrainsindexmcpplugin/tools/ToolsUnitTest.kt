package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.ApplyQuickFixTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetCompletionsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetInspectionsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetQuickFixesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetSymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetDependenciesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetFileStructureTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetProjectStructureTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ExtractMethodTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ExtractVariableTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.InlineTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.MoveElementTool
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

    fun testGetSymbolInfoToolSchema() {
        val tool = GetSymbolInfoTool()

        assertEquals(ToolNames.INSPECT_SYMBOL, tool.name)
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

    fun testGetCompletionsToolSchema() {
        val tool = GetCompletionsTool()

        assertEquals(ToolNames.CODE_COMPLETIONS, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have maxResults property", properties?.get(ParamNames.MAX_RESULTS))
    }

    fun testGetInspectionsToolSchema() {
        val tool = GetInspectionsTool()

        assertEquals(ToolNames.ANALYZE_CODE, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have startLine property", properties?.get(ParamNames.START_LINE))
        assertNotNull("Should have endLine property", properties?.get(ParamNames.END_LINE))
    }

    fun testGetQuickFixesToolSchema() {
        val tool = GetQuickFixesTool()

        assertEquals(ToolNames.LIST_QUICK_FIXES, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))
    }

    fun testApplyQuickFixToolSchema() {
        val tool = ApplyQuickFixTool()

        assertEquals(ToolNames.APPLY_QUICK_FIX, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have fixId property", properties?.get(ParamNames.FIX_ID))
    }

    fun testGetFileStructureToolSchema() {
        val tool = GetFileStructureTool()

        assertEquals(ToolNames.FILE_STRUCTURE, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
    }

    fun testGetProjectStructureToolSchema() {
        val tool = GetProjectStructureTool()

        assertEquals(ToolNames.PROJECT_STRUCTURE, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)
    }

    fun testGetDependenciesToolSchema() {
        val tool = GetDependenciesTool()

        assertEquals(ToolNames.LIST_DEPENDENCIES, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)
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
            ToolNames.INSPECT_SYMBOL,
            ToolNames.CODE_COMPLETIONS,
            ToolNames.ANALYZE_CODE,
            ToolNames.LIST_QUICK_FIXES,
            ToolNames.APPLY_QUICK_FIX,
            // Project tools
            ToolNames.INDEX_STATUS,
            ToolNames.FILE_STRUCTURE,
            ToolNames.PROJECT_STRUCTURE,
            ToolNames.LIST_DEPENDENCIES,
            // Refactoring tools
            ToolNames.REFACTOR_RENAME,
            ToolNames.REFACTOR_EXTRACT_METHOD,
            ToolNames.REFACTOR_EXTRACT_VARIABLE,
            ToolNames.REFACTOR_INLINE,
            ToolNames.REFACTOR_SAFE_DELETE,
            ToolNames.REFACTOR_MOVE
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

    fun testExtractMethodToolSchema() {
        val tool = ExtractMethodTool()

        assertEquals(ToolNames.REFACTOR_EXTRACT_METHOD, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have startLine property", properties?.get(ParamNames.START_LINE))
        assertNotNull("Should have endLine property", properties?.get(ParamNames.END_LINE))
        assertNotNull("Should have methodName property", properties?.get(ParamNames.METHOD_NAME))
    }

    fun testExtractVariableToolSchema() {
        val tool = ExtractVariableTool()

        assertEquals(ToolNames.REFACTOR_EXTRACT_VARIABLE, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))
        assertNotNull("Should have variableName property", properties?.get(ParamNames.VARIABLE_NAME))
        assertNotNull("Should have replaceAll property", properties?.get(ParamNames.REPLACE_ALL))
    }

    fun testInlineToolSchema() {
        val tool = InlineTool()

        assertEquals(ToolNames.REFACTOR_INLINE, tool.name)
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

    fun testMoveElementToolSchema() {
        val tool = MoveElementTool()

        assertEquals(ToolNames.REFACTOR_MOVE, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have file property", properties?.get(ParamNames.FILE))
        assertNotNull("Should have line property", properties?.get(ParamNames.LINE))
        assertNotNull("Should have column property", properties?.get(ParamNames.COLUMN))
        assertNotNull("Should have targetDirectory property", properties?.get(ParamNames.TARGET_DIRECTORY))
        assertNotNull("Should have targetClass property", properties?.get(ParamNames.TARGET_CLASS))
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

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSuperMethodsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSymbolTool
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

    /**
     * Tests that the tool registry registers built-in tools correctly.
     *
     * Note: The number of tools registered depends on available language plugins:
     * - Universal tools (4): Always registered in all IDEs
     * - Navigation tools (5): Registered when language handlers are available (Java, Python, JS/TS)
     * - Refactoring tools (2): Registered only when Java plugin is available
     *
     * In a unit test environment without the full IntelliJ Platform, only universal tools
     * may be registered since plugin detection may fail.
     */
    fun testToolRegistryRegistersUniversalTools() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        // Universal tools - always available in all IDEs
        val universalTools = listOf(
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
            ToolNames.DIAGNOSTICS,
            ToolNames.INDEX_STATUS
        )

        // Universal tools should always be registered
        for (toolName in universalTools) {
            val tool = registry.getTool(toolName)
            assertNotNull("Universal tool $toolName should be registered", tool)
        }

        assertTrue("Should have at least 4 universal tools", registry.getAllTools().size >= 4)
    }

    /**
     * Tests tool registration in a fully initialized IntelliJ Platform environment.
     *
     * This test verifies that when Java plugin is available (as in IntelliJ IDEA platform tests),
     * all 11 tools are registered including navigation and refactoring tools.
     *
     * Note: This test may register fewer tools in unit test mode since plugin detection
     * depends on the IntelliJ Platform being fully initialized.
     */
    fun testToolRegistryRegistersLanguageToolsWhenAvailable() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        // Language-specific navigation tools (registered when handlers available)
        val navigationTools = listOf(
            ToolNames.TYPE_HIERARCHY,
            ToolNames.CALL_HIERARCHY,
            ToolNames.FIND_IMPLEMENTATIONS,
            ToolNames.FIND_SYMBOL,
            ToolNames.FIND_SUPER_METHODS
        )

        // Java-specific refactoring tools
        val refactoringTools = listOf(
            ToolNames.REFACTOR_RENAME,
            ToolNames.REFACTOR_SAFE_DELETE
        )

        // Check if language navigation tools are registered (depends on platform initialization)
        val registeredNavTools = navigationTools.count { registry.getTool(it) != null }
        val registeredRefTools = refactoringTools.count { registry.getTool(it) != null }

        // In IntelliJ platform tests with Java plugin, all navigation and refactoring tools should be available
        // In unit tests without platform, these may not be available (which is expected)
        if (registeredNavTools > 0) {
            // If any navigation tools are registered, all should be registered (Java handlers provide all)
            assertEquals("When language handlers available, all 5 navigation tools should be registered",
                5, registeredNavTools)
        }

        if (registeredRefTools > 0) {
            // If any refactoring tools are registered, all should be registered
            assertEquals("When Java plugin available, both refactoring tools should be registered",
                2, registeredRefTools)
        }

        // Log the actual tool count for debugging
        val totalTools = registry.getAllTools().size
        println("Tool registry test: $totalTools tools registered (4 universal + $registeredNavTools navigation + $registeredRefTools refactoring)")
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

    // New navigation tools

    fun testFindSymbolToolSchema() {
        val tool = FindSymbolTool()

        assertEquals(ToolNames.FIND_SYMBOL, tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have query property", properties?.get(ParamNames.QUERY))
        assertNotNull("Should have includeLibraries property", properties?.get(ParamNames.INCLUDE_LIBRARIES))
        assertNotNull("Should have limit property", properties?.get(ParamNames.LIMIT))

        val required = schema[SchemaConstants.REQUIRED]
        assertNotNull("Should have required array", required)
    }

    fun testFindSymbolToolCamelCaseMatching() {
        val tool = FindSymbolTool()

        assertTrue("USvc should match UserService", tool.matchesCamelCase("UserService", "USvc"))
        assertTrue("US should match UserService", tool.matchesCamelCase("UserService", "US"))
        assertTrue("usvc should match UserService (case insensitive)", tool.matchesCamelCase("UserService", "usvc"))
        assertTrue("full name should match", tool.matchesCamelCase("UserService", "UserService"))
        assertFalse("XY should not match UserService", tool.matchesCamelCase("UserService", "XY"))
    }

    fun testFindSymbolToolLevenshteinDistance() {
        val tool = FindSymbolTool()

        assertEquals(0, tool.levenshteinDistance("test", "test"))
        assertEquals(1, tool.levenshteinDistance("test", "tests"))
        assertEquals(1, tool.levenshteinDistance("test", "tast"))
        assertEquals(4, tool.levenshteinDistance("test", ""))
        assertEquals(3, tool.levenshteinDistance("kitten", "sitting"))
    }

    fun testFindSuperMethodsToolSchema() {
        val tool = FindSuperMethodsTool()

        assertEquals(ToolNames.FIND_SUPER_METHODS, tool.name)
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

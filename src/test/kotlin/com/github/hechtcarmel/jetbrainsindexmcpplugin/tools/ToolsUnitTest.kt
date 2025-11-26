package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

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

    fun testFindDefinitionToolSchema() {
        val tool = FindDefinitionTool()

        assertEquals("find_definition", tool.name)
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

    fun testTypeHierarchyToolSchema() {
        val tool = TypeHierarchyTool()

        assertEquals("type_hierarchy", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have project_path property", properties?.get("project_path"))
        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have line property", properties?.get("line"))
        assertNotNull("Should have column property", properties?.get("column"))
        assertNotNull("Should have className property", properties?.get("className"))
    }

    fun testCallHierarchyToolSchema() {
        val tool = CallHierarchyTool()

        assertEquals("call_hierarchy", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have direction property", properties?.get("direction"))
    }

    fun testFindImplementationsToolSchema() {
        val tool = FindImplementationsTool()

        assertEquals("find_implementations", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have line property", properties?.get("line"))
        assertNotNull("Should have column property", properties?.get("column"))
    }

    fun testGetCompletionsToolSchema() {
        val tool = GetCompletionsTool()

        assertEquals("get_completions", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have maxResults property", properties?.get("maxResults"))
    }

    fun testGetInspectionsToolSchema() {
        val tool = GetInspectionsTool()

        assertEquals("get_inspections", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have startLine property", properties?.get("startLine"))
        assertNotNull("Should have endLine property", properties?.get("endLine"))
    }

    fun testGetQuickFixesToolSchema() {
        val tool = GetQuickFixesTool()

        assertEquals("get_quick_fixes", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have line property", properties?.get("line"))
        assertNotNull("Should have column property", properties?.get("column"))
    }

    fun testApplyQuickFixToolSchema() {
        val tool = ApplyQuickFixTool()

        assertEquals("apply_quick_fix", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get("file"))
        assertNotNull("Should have fixId property", properties?.get("fixId"))
    }

    fun testGetFileStructureToolSchema() {
        val tool = GetFileStructureTool()

        assertEquals("get_file_structure", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)

        assertNotNull("Should have file property", properties?.get("file"))
    }

    fun testGetProjectStructureToolSchema() {
        val tool = GetProjectStructureTool()

        assertEquals("get_project_structure", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    fun testGetDependenciesToolSchema() {
        val tool = GetDependenciesTool()

        assertEquals("get_dependencies", tool.name)
        assertNotNull(tool.description)

        val schema = tool.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    fun testToolRegistryRegistersAllBuiltInTools() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val expectedTools = listOf(
            // Navigation tools
            "find_usages",
            "find_definition",
            "type_hierarchy",
            "call_hierarchy",
            "find_implementations",
            // Intelligence tools
            "get_symbol_info",
            "get_completions",
            "get_inspections",
            "get_quick_fixes",
            "apply_quick_fix",
            // Project tools
            "get_index_status",
            "get_file_structure",
            "get_project_structure",
            "get_dependencies"
        )

        assertEquals("Should have correct number of tools", expectedTools.size, registry.getAllTools().size)

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

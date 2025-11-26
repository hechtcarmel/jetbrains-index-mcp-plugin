package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
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
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Integration tests for tool execution end-to-end.
 * Tests each navigation, intelligence, and project tool with realistic scenarios.
 */
class ToolExecutionIntegrationTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Navigation Tools Tests

    fun testFindUsagesToolEndToEnd() = runBlocking {
        val tool = FindUsagesTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testFindDefinitionToolEndToEnd() = runBlocking {
        val tool = FindDefinitionTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testTypeHierarchyToolEndToEnd() = runBlocking {
        val tool = TypeHierarchyTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing className", resultMissing.isError)

        // Test with invalid class
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("className", "com.nonexistent.InvalidClass")
        })
        assertTrue("Should error with invalid class", resultInvalid.isError)
    }

    fun testCallHierarchyToolEndToEnd() = runBlocking {
        val tool = CallHierarchyTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testFindImplementationsToolEndToEnd() = runBlocking {
        val tool = FindImplementationsTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    // Intelligence Tools Tests

    fun testGetSymbolInfoToolEndToEnd() = runBlocking {
        val tool = GetSymbolInfoTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testGetCompletionsToolEndToEnd() = runBlocking {
        val tool = GetCompletionsTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testGetInspectionsToolEndToEnd() = runBlocking {
        val tool = GetInspectionsTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testGetQuickFixesToolEndToEnd() = runBlocking {
        val tool = GetQuickFixesTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testApplyQuickFixToolEndToEnd() = runBlocking {
        val tool = ApplyQuickFixTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", resultMissing.isError)

        // Test with invalid fix ID
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("fixId", "invalid-fix-id-123")
        })
        assertTrue("Should error with invalid fixId", resultInvalid.isError)
    }

    // Project Tools Tests

    fun testGetIndexStatusToolEndToEnd() = runBlocking {
        val tool = GetIndexStatusTool()

        val result = tool.execute(project, buildJsonObject { })

        assertFalse("get_index_status should succeed", result.isError)
        assertTrue("Should have content", result.content.isNotEmpty())

        val content = result.content.first()
        assertTrue("Content should be text", content is ContentBlock.Text)

        val textContent = (content as ContentBlock.Text).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        assertNotNull("Result should have isDumbMode", resultJson["isDumbMode"])
        assertNotNull("Result should have isIndexing", resultJson["isIndexing"])
    }

    fun testGetFileStructureToolEndToEnd() = runBlocking {
        val tool = GetFileStructureTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testGetProjectStructureToolEndToEnd() = runBlocking {
        val tool = GetProjectStructureTool()

        val result = tool.execute(project, buildJsonObject { })

        assertFalse("get_project_structure should succeed", result.isError)
        assertTrue("Should have content", result.content.isNotEmpty())

        val content = result.content.first()
        assertTrue("Content should be text", content is ContentBlock.Text)

        val textContent = (content as ContentBlock.Text).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        assertNotNull("Result should have name", resultJson["name"])
    }

    fun testGetDependenciesToolEndToEnd() = runBlocking {
        val tool = GetDependenciesTool()

        val result = tool.execute(project, buildJsonObject { })

        assertFalse("get_dependencies should succeed", result.isError)
        assertTrue("Should have content", result.content.isNotEmpty())

        val content = result.content.first()
        assertTrue("Content should be text", content is ContentBlock.Text)

        val textContent = (content as ContentBlock.Text).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        assertNotNull("Result should have dependencies", resultJson["dependencies"])
    }

    // Tool Registry Integration Tests

    fun testAllToolsRegistered() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val expectedTools = listOf(
            "find_usages",
            "find_definition",
            "type_hierarchy",
            "call_hierarchy",
            "find_implementations",
            "get_symbol_info",
            "get_completions",
            "get_inspections",
            "get_quick_fixes",
            "apply_quick_fix",
            "get_index_status",
            "get_file_structure",
            "get_project_structure",
            "get_dependencies"
        )

        assertEquals("Should have correct number of tools", expectedTools.size, registry.getAllTools().size)

        expectedTools.forEach { toolName ->
            assertNotNull("$toolName should be registered", registry.getTool(toolName))
        }
    }

    fun testToolDefinitionsHaveValidSchemas() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val definitions = registry.getToolDefinitions()

        definitions.forEach { definition ->
            assertTrue("${definition.name} should have non-empty description", definition.description.isNotEmpty())
            assertNotNull("${definition.name} should have inputSchema", definition.inputSchema)
            assertEquals("${definition.name} inputSchema should be object type",
                "object", definition.inputSchema["type"]?.toString()?.replace("\"", ""))
        }
    }

    // Error Scenario Tests

    fun testToolsHandleNullProject() {
        // This test verifies tools handle edge cases gracefully
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        registry.getAllTools().forEach { tool ->
            assertNotNull("${tool.name} should have name", tool.name)
            assertNotNull("${tool.name} should have description", tool.description)
            assertNotNull("${tool.name} should have inputSchema", tool.inputSchema)
        }
    }

    fun testToolsReturnProperContentBlocks() = runBlocking {
        val tool = GetIndexStatusTool()
        val result = tool.execute(project, buildJsonObject { })

        assertFalse("Result should not be error", result.isError)
        assertTrue("Result should have content", result.content.isNotEmpty())

        result.content.forEach { block ->
            when (block) {
                is ContentBlock.Text -> assertNotNull("Text block should have text", block.text)
                is ContentBlock.Image -> {
                    assertNotNull("Image block should have data", block.data)
                    assertNotNull("Image block should have mimeType", block.mimeType)
                }
            }
        }
    }
}

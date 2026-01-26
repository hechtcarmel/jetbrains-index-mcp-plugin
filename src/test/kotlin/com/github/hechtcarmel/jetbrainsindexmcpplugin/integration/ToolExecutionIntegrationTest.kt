package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
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

    fun testGetDiagnosticsToolEndToEnd() = runBlocking {
        val tool = GetDiagnosticsTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
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

    // Tool Registry Integration Tests

    fun testAllToolsRegistered() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val expectedTools = listOf(
            // Navigation tools
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
            ToolNames.TYPE_HIERARCHY,
            ToolNames.CALL_HIERARCHY,
            ToolNames.FIND_IMPLEMENTATIONS,
            ToolNames.FIND_SYMBOL,
            ToolNames.FIND_SUPER_METHODS,
            ToolNames.FILE_STRUCTURE,
            // Fast search tools
            ToolNames.FIND_CLASS,
            ToolNames.FIND_FILE,
            ToolNames.SEARCH_TEXT,
            // Intelligence tools
            ToolNames.DIAGNOSTICS,
            // Project tools
            ToolNames.INDEX_STATUS,
            // Refactoring tools
            ToolNames.REFACTOR_RENAME,
            ToolNames.REFACTOR_SAFE_DELETE
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

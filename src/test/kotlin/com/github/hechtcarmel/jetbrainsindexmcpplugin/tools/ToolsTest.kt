package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetSymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.GoToDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ToolsTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testGetIndexStatusTool() = runBlocking {
        val tool = GetIndexStatusTool()

        assertEquals("get_index_status", tool.name)
        assertNotNull(tool.description)
        assertNotNull(tool.inputSchema)

        val result = tool.execute(project, buildJsonObject { })

        assertFalse("get_index_status should succeed", result.isError)
        assertTrue("Should have content", result.content.isNotEmpty())

        val content = result.content.first()
        assertTrue("Content should be text", content is com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock.Text)

        val textContent = (content as com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock.Text).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        assertNotNull("Result should have isDumbMode", resultJson["isDumbMode"])
        assertNotNull("Result should have isIndexing", resultJson["isIndexing"])
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

    fun testFindUsagesToolMissingParams() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testFindUsagesToolInvalidFile() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isError)
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

    fun testGoToDefinitionToolMissingParams() = runBlocking {
        val tool = GoToDefinitionTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
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

    fun testGetSymbolInfoToolMissingParams() = runBlocking {
        val tool = GetSymbolInfoTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
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
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
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
 * Platform-dependent tests that require IntelliJ Platform indexing.
 * For schema and registration tests that don't need the platform, see ToolsUnitTest.
 */
class ToolsTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testGetIndexStatusTool() = runBlocking {
        val tool = GetIndexStatusTool()

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

    fun testFindDefinitionToolMissingParams() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testGetSymbolInfoToolMissingParams() = runBlocking {
        val tool = GetSymbolInfoTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    // Phase 2: Navigation Tools Tests

    fun testTypeHierarchyToolMissingParams() = runBlocking {
        val tool = TypeHierarchyTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing className", result.isError)
    }

    fun testTypeHierarchyToolInvalidClass() = runBlocking {
        val tool = TypeHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("className", "com.nonexistent.Class")
        })

        assertTrue("Should error with invalid class", result.isError)
    }

    fun testCallHierarchyToolMissingParams() = runBlocking {
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testCallHierarchyToolInvalidFile() = runBlocking {
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testFindImplementationsToolMissingParams() = runBlocking {
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testFindImplementationsToolInvalidFile() = runBlocking {
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    // Phase 2: Intelligence Tools Tests

    fun testGetCompletionsToolMissingParams() = runBlocking {
        val tool = GetCompletionsTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testGetCompletionsToolInvalidFile() = runBlocking {
        val tool = GetCompletionsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testGetInspectionsToolMissingParams() = runBlocking {
        val tool = GetInspectionsTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", result.isError)
    }

    fun testGetInspectionsToolInvalidFile() = runBlocking {
        val tool = GetInspectionsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testGetQuickFixesToolMissingParams() = runBlocking {
        val tool = GetQuickFixesTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testGetQuickFixesToolInvalidFile() = runBlocking {
        val tool = GetQuickFixesTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testApplyQuickFixToolMissingParams() = runBlocking {
        val tool = ApplyQuickFixTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testApplyQuickFixToolInvalidFixId() = runBlocking {
        val tool = ApplyQuickFixTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("fixId", "nonexistent-fix-id")
        })

        assertTrue("Should error with invalid fixId", result.isError)
    }

    // Phase 2: Project Tools Tests

    fun testGetFileStructureToolMissingParams() = runBlocking {
        val tool = GetFileStructureTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", result.isError)
    }

    fun testGetFileStructureToolInvalidFile() = runBlocking {
        val tool = GetFileStructureTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testGetProjectStructureTool() = runBlocking {
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

    fun testGetDependenciesTool() = runBlocking {
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
}

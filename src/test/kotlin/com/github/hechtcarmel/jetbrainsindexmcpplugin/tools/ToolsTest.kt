package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteTool
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

    // Navigation Tools Tests

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

    // Intelligence Tools Tests

    fun testGetDiagnosticsToolMissingParams() = runBlocking {
        val tool = GetDiagnosticsTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", result.isError)
    }

    fun testGetDiagnosticsToolInvalidFile() = runBlocking {
        val tool = GetDiagnosticsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    // Refactoring Tools Tests

    fun testRenameSymbolToolMissingParams() = runBlocking {
        val tool = RenameSymbolTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testRenameSymbolToolInvalidFile() = runBlocking {
        val tool = RenameSymbolTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
            put("newName", "newSymbol")
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testRenameSymbolToolBlankName() = runBlocking {
        val tool = RenameSymbolTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
            put("newName", "   ")
        })

        assertTrue("Should error with blank name", result.isError)
    }

    fun testSafeDeleteToolMissingParams() = runBlocking {
        val tool = SafeDeleteTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testSafeDeleteToolInvalidFile() = runBlocking {
        val tool = SafeDeleteTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isError)
    }
}

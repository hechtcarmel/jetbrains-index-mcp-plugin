package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor.GetActiveFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor.OpenFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FileStructureTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSuperMethodsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ReformatCodeTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Platform-dependent tests that require IntelliJ Platform indexing.
 * For schema and registration tests that don't need the platform, see ToolsUnitTest.
 */
class ToolsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun errorText(result: com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult): String =
        (result.content.first() as ContentBlock.Text).text

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
        assertTrue("Content should be text", content is ContentBlock.Text)

        val textContent = (content as ContentBlock.Text).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        assertNotNull("Result should have isDumbMode", resultJson["isDumbMode"])
        assertNotNull("Result should have isIndexing", resultJson["isIndexing"])
    }

    fun testFindUsagesToolMissingParams() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
        assertTrue("Should mention required params", errorText(result).contains(ErrorMessages.SYMBOL_OR_POSITION_REQUIRED))
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

    fun testFindUsagesToolPartialPosition() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("line", 1)
        })

        assertTrue("Should error with partial position params", result.isError)
        assertTrue("Should mention missing column", errorText(result).contains("column"))
    }

    fun testFindUsagesToolLanguageWithoutSymbol() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
        })

        assertTrue("Should error when language provided without symbol", result.isError)
        assertTrue("Should mention missing symbol", errorText(result).contains("symbol"))
    }

    fun testFindUsagesToolSymbolWithoutLanguage() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("symbol", "com.example.MyClass#method(String)")
        })

        assertTrue("Should error when symbol provided without language", result.isError)
        assertTrue("Should mention missing language", errorText(result).contains("language"))
    }

    fun testFindUsagesToolLanguageAndPositionExclusive() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "com.example.MyClass")
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error when both language+symbol and file+line+column provided", result.isError)
        assertTrue("Should mention mutual exclusivity", errorText(result).contains("Cannot specify both"))
    }

    fun testFindUsagesToolUnsupportedLanguage() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Cobol")
            put("symbol", "com.example.MyClass")
        })

        assertTrue("Should error with unsupported language", result.isError)
        assertTrue("Should mention unsupported language", errorText(result).contains("Cobol"))
    }

    fun testFindDefinitionToolMissingParams() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
        assertTrue("Should mention required params", errorText(result).contains(ErrorMessages.SYMBOL_OR_POSITION_REQUIRED))
    }

    fun testFindDefinitionToolPartialPosition() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
        })

        assertTrue("Should error with partial position params", result.isError)
        assertTrue("Should mention missing line", errorText(result).contains("line"))
    }

    fun testFindDefinitionToolLanguageWithoutSymbol() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
        })

        assertTrue("Should error when language provided without symbol", result.isError)
        assertTrue("Should mention missing symbol", errorText(result).contains("symbol"))
    }

    fun testFindDefinitionToolLanguageAndPositionExclusive() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "com.example.MyClass")
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error when both language+symbol and file+line+column provided", result.isError)
        assertTrue("Should mention mutual exclusivity", errorText(result).contains("Cannot specify both"))
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

    fun testCallHierarchyToolSymbolWithoutLanguage() = runBlocking {
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("symbol", "com.example.Service#processRequest(String)")
            put("direction", "callers")
        })

        assertTrue("Should error when symbol provided without language", result.isError)
        assertTrue("Should mention missing language", errorText(result).contains("language"))
    }

    fun testCallHierarchyToolUnsupportedLanguage() = runBlocking {
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Cobol")
            put("symbol", "com.example.Service#processRequest(String)")
            put("direction", "callers")
        })

        assertTrue("Should error with unsupported language", result.isError)
        assertTrue("Should mention unsupported language", errorText(result).contains("Cobol"))
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

    fun testFindImplementationsToolLanguageAndPositionExclusive() = runBlocking {
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "com.example.Repository")
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error when both language+symbol and file+line+column provided", result.isError)
        assertTrue("Should mention mutual exclusivity", errorText(result).contains("Cannot specify both"))
    }

    fun testFindImplementationsToolUnsupportedLanguage() = runBlocking {
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Cobol")
            put("symbol", "com.example.Repository")
        })

        assertTrue("Should error with unsupported language", result.isError)
        assertTrue("Should mention unsupported language", errorText(result).contains("Cobol"))
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

    // File Structure Tool Tests

    fun testFileStructureToolMissingParams() = runBlocking {
        val tool = FileStructureTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testFileStructureToolInvalidFile() = runBlocking {
        val tool = FileStructureTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.java")
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    // Editor Tools Tests

    fun testGetActiveFileTool() = runBlocking {
        val tool = GetActiveFileTool()

        val result = tool.execute(project, buildJsonObject { })

        assertFalse("get_active_file should succeed", result.isError)
        assertTrue("Should have content", result.content.isNotEmpty())

        val content = result.content.first()
        assertTrue("Content should be text", content is ContentBlock.Text)

        val textContent = (content as ContentBlock.Text).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        assertNotNull("Result should have activeFiles", resultJson["activeFiles"])
    }

    fun testOpenFileToolMissingParams() = runBlocking {
        val tool = OpenFileTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testOpenFileToolInvalidFile() = runBlocking {
        val tool = OpenFileTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testOpenFileToolColumnWithoutLine() = runBlocking {
        val tool = OpenFileTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("column", 5)
        })

        assertTrue("Should error with column without line", result.isError)
    }

    fun testOpenFileToolInvalidLine() = runBlocking {
        val tool = OpenFileTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("line", 0)
        })

        assertTrue("Should error with line < 1", result.isError)
    }

    // Reformat Code Tool Tests

    fun testReformatCodeToolMissingParams() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
    }

    fun testReformatCodeToolInvalidFile() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testReformatCodeToolStartLineWithoutEndLine() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", 1)
        })

        assertTrue("Should error when startLine provided without endLine", result.isError)
    }

    fun testReformatCodeToolEndLineWithoutStartLine() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("endLine", 10)
        })

        assertTrue("Should error when endLine provided without startLine", result.isError)
    }

    fun testReformatCodeToolInvalidLineRange() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", 10)
            put("endLine", 5)
        })

        assertTrue("Should error when endLine < startLine", result.isError)
    }

    fun testReformatCodeToolStartLineLessThanOne() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", 0)
            put("endLine", 5)
        })

        assertTrue("Should error when startLine < 1", result.isError)
    }

    // FindSuperMethods Tool Tests (language+symbol)

    fun testFindSuperMethodsToolMissingParams() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isError)
        assertTrue("Should mention required params", errorText(result).contains(ErrorMessages.SYMBOL_OR_POSITION_REQUIRED))
    }

    fun testFindSuperMethodsToolInvalidFile() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isError)
    }

    fun testFindSuperMethodsToolPartialPosition() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with partial position params", result.isError)
        assertTrue("Should mention missing file", errorText(result).contains("file"))
    }

    fun testFindSuperMethodsToolSymbolWithoutLanguage() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("symbol", "com.example.UserServiceImpl#getUser(String)")
        })

        assertTrue("Should error when symbol provided without language", result.isError)
        assertTrue("Should mention missing language", errorText(result).contains("language"))
    }

    fun testFindSuperMethodsToolLanguageAndPositionExclusive() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "com.example.UserServiceImpl#getUser(String)")
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error when both language+symbol and file+line+column provided", result.isError)
        assertTrue("Should mention mutual exclusivity", errorText(result).contains("Cannot specify both"))
    }

    fun testFindSuperMethodsToolUnsupportedLanguage() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Cobol")
            put("symbol", "com.example.UserServiceImpl#getUser(String)")
        })

        assertTrue("Should error with unsupported language", result.isError)
        assertTrue("Should mention unsupported language", errorText(result).contains("Cobol"))
    }

    // Registry tests that require platform services (McpSettings)

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
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor.GetActiveFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.editor.OpenFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FileStructureTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindClassTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSuperMethodsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SearchTextTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ReformatCodeTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SymbolData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createMatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createNameFilter
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Platform-dependent tests that require IntelliJ Platform indexing.
 * For schema and registration tests that don't need the platform, see ToolsUnitTest.
 */
class ToolsTest : BasePlatformTestCase() {

    private var localSourceRootConfigured = false

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

    fun testFindUsagesToolPrefersPositionWhenBothTargetsProvided() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "com.example.MyClass")
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should still error because test position is invalid", result.isError)
        assertFalse("Should prefer position mode instead of rejecting dual-mode requests", errorText(result).contains("Cannot specify both"))
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

    fun testFindDefinitionToolPrefersPositionWhenBothTargetsProvided() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "com.example.MyClass")
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should still error because test position is invalid", result.isError)
        assertFalse("Should prefer position mode instead of rejecting dual-mode requests", errorText(result).contains("Cannot specify both"))
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

    fun testFindImplementationsToolPrefersPositionWhenBothTargetsProvided() = runBlocking {
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "com.example.Repository")
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should still error because test position is invalid", result.isError)
        assertFalse("Should prefer position mode instead of rejecting dual-mode requests", errorText(result).contains("Cannot specify both"))
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

    fun testFindClassToolInvalidScopeReturnsStructuredError() = runBlocking {
        val tool = FindClassTool()

        val result = tool.execute(project, buildJsonObject {
            put("query", "UserService")
            put("scope", "totally_invalid")
        })

        assertTrue("Should error with invalid scope", result.isError)

        val errorJson = json.parseToJsonElement(errorText(result)).jsonObject
        assertEquals("invalid_scope", errorJson["error"]?.jsonPrimitive?.content)
        assertEquals("scope", errorJson["parameter"]?.jsonPrimitive?.content)
        assertEquals("totally_invalid", errorJson["provided"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("project_files", "project_and_libraries", "project_production_files", "project_test_files"),
            errorJson["supportedValues"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }

    fun testFindClassToolMalformedScopeTypeReturnsStructuredError() = runBlocking {
        val tool = FindClassTool()

        val result = tool.execute(project, buildJsonObject {
            put("query", "UserService")
            put("scope", buildJsonArray {
                add(JsonPrimitive("project_files"))
            })
        })

        assertTrue("Should error with malformed scope type", result.isError)

        val errorJson = json.parseToJsonElement(errorText(result)).jsonObject
        assertEquals("invalid_scope", errorJson["error"]?.jsonPrimitive?.content)
        assertEquals("scope", errorJson["parameter"]?.jsonPrimitive?.content)
        assertEquals("[\"project_files\"]", errorJson["provided"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("project_files", "project_and_libraries", "project_production_files", "project_test_files"),
            errorJson["supportedValues"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }

    fun testOptimizedSymbolSearchLegacyContributorHonorsProjectFilesScope() {
        val projectFile = myFixture.addFileToProject(
            "legacy/ProjectScopeSymbol.java",
            """
            package legacy;

            public class ProjectScopeSymbol {}
            """.trimIndent()
        )

        val libraryRoot = Files.createTempDirectory("legacy-contributor-lib")
        val libraryPackageDir = Files.createDirectories(libraryRoot.resolve("legacy"))
        val libraryPath = libraryPackageDir.resolve("LibraryScopeSymbol.java")
        Files.writeString(
            libraryPath,
            """
            package legacy;

            public class LibraryScopeSymbol {}
            """.trimIndent()
        )

        val libraryRootVFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRoot)
        val libraryFileVFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryPath)
        assertNotNull("Expected library root in temp dir", libraryRootVFile)
        assertNotNull("Expected library file in temp dir", libraryFileVFile)

        ModuleRootModificationUtil.addModuleLibrary(
            module,
            "legacy-contributor-library",
            emptyList(),
            listOf(libraryRootVFile!!.url)
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val libraryFile = PsiManager.getInstance(project).findFile(libraryFileVFile!!)
        assertNotNull("Expected library PSI file", libraryFile)

        val projectSymbol = projectFile.children.filterIsInstance<PsiClass>().firstOrNull { it.name == "ProjectScopeSymbol" }
        val librarySymbol = libraryFile!!.children.filterIsInstance<PsiClass>().firstOrNull { it.name == "LibraryScopeSymbol" }
        assertNotNull("Expected project symbol", projectSymbol)
        assertNotNull("Expected library symbol", librarySymbol)

        val contributor = LegacyContributor(
            mapOf(
                "ProjectScopeSymbol" to arrayOf<NavigationItem>(projectSymbol!!),
                "LibraryScopeSymbol" to arrayOf<NavigationItem>(librarySymbol!!)
            )
        )
        val scope = BuiltInSearchScopeResolver.resolveGlobalScope(project, BuiltInSearchScope.PROJECT_FILES)
        assertTrue("Project file should be inside project_files scope", scope.contains(projectFile.virtualFile))
        assertFalse("Library file should be outside project_files scope", scope.contains(libraryFile.virtualFile))
        val matcher = createMatcher("ScopeSymbol", "substring")
        val results = mutableListOf<SymbolData>()
        val seen = mutableSetOf<String>()

        invokeLegacySymbolContributor(
            contributor = contributor,
            pattern = "ScopeSymbol",
            scope = scope,
            languageFilter = null,
            nameFilter = { true },
            matcher = matcher,
            results = results,
            seen = seen
        )

        assertEquals(listOf("ProjectScopeSymbol"), results.map { it.name })
    }

    fun testSearchTextToolFilePatternFiltersExactSearchResults() = runBlocking {
        myFixture.addFileToProject("mappers/UserMapper.xml", "<mapper><select id=\"a\">select needle from sys_user</select></mapper>")
        myFixture.addFileToProject("web/pagination.js", "const q = 'needle';")
        myFixture.addFileToProject("tmp/opac_phase2_sql-mybatis.json", "{\"query\":\"needle\"}")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val tool = SearchTextTool()
        val result = tool.execute(project, buildJsonObject {
            put("query", "needle")
            put("filePattern", "*.xml")
            put("pageSize", 10)
        })

        assertFalse("Search should succeed", result.isError)
        val resultJson = json.parseToJsonElement((result.content.first() as ContentBlock.Text).text).jsonObject
        val files = resultJson["matches"]!!.jsonArray.map { it.jsonObject["file"]!!.jsonPrimitive.content }

        assertEquals(listOf("mappers/UserMapper.xml"), files)
    }

    fun testSearchTextToolRegexUsesFindInFilesOutsideReadAction() = runBlocking {
        myFixture.addFileToProject(
            "src/CommandRunner.java",
            """
            class CommandRunner {
                void run() throws Exception {
                    Runtime.getRuntime().exec("calc");
                }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val tool = SearchTextTool()
        val result = tool.execute(project, buildJsonObject {
            put("query", "Runtime\\.getRuntime\\(\\)\\.exec\\(")
            put("regex", true)
            put("context", "code")
            put("filePattern", "*.java")
            put("pageSize", 10)
        })

        assertFalse("Regex search should succeed", result.isError)
        val resultJson = json.parseToJsonElement((result.content.first() as ContentBlock.Text).text).jsonObject
        val files = resultJson["matches"]!!.jsonArray.map { it.jsonObject["file"]!!.jsonPrimitive.content }

        assertEquals(listOf("src/CommandRunner.java"), files)
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

    fun testMarkdownStructureHandlerBuildsHeadingHierarchy() {
        val psiFile = myFixture.addFileToProject(
            "docs/guide.md",
            """
            # Starter Pack
            Intro text.
            ## Installation
            Install steps.
            ### CLI Setup
            CLI details.
            ## Usage
            Usage details.
            """.trimIndent()
        )
        val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)

        assertNotNull("Expected Markdown structure handler", handler)
        val nodes = handler!!.getFileStructure(psiFile, project)
        val root = nodes.single()

        assertEquals("Starter Pack", root.name)
        assertEquals("HEADING", root.kind.name)
        assertEquals(1, root.line)
        assertEquals(listOf("Installation", "Usage"), root.children.map { it.name })
        assertEquals("CLI Setup", root.children.first().children.single().name)
        assertEquals(5, root.children.first().children.single().line)
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

    fun testReformatCodeToolZeroRangeIsTreatedAsFullFile() = runBlocking {
        val filePath = sourceRootPath().resolve("reformat/ZeroRangeMeansFullFile.java")
        createLocalProjectFile(
            "reformat/ZeroRangeMeansFullFile.java",
            "class ZeroRangeMeansFullFile{void test(){System.out.println(\"ok\");}}"
        )

        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/reformat/ZeroRangeMeansFullFile.java")
            put("startLine", 0)
            put("endLine", 0)
        })

        assertFalse(
            "Zero-valued optional range should behave like full-file reformat: ${if (result.isError) errorText(result) else "ok"}",
            result.isError
        )
        assertTrue("Full-file reformat should update formatting", Files.readString(filePath).contains("class ZeroRangeMeansFullFile {"))
    }

    fun testReformatCodeToolStartLineLessThanOne() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", -1)
            put("endLine", 5)
        })

        assertTrue("Should error when startLine < 1", result.isError)
    }

    fun testReformatCodeToolEndLineLessThanOne() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", 1)
            put("endLine", -1)
        })

        assertTrue("Should error when endLine < 1", result.isError)
    }

    fun testReformatCodeToolIncompleteRangeWhenZeroActsAsOmitted() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", 1)
            put("endLine", 0)
        })

        assertTrue("Should error when one line is omitted via zero and the other is positive", result.isError)
        assertTrue(
            "Should explain incomplete range pairing",
            errorText(result).contains("Both startLine and endLine must be provided together")
        )
    }

    fun testReformatCodeToolEndLineExceedsFileLineCount() = runBlocking {
        createLocalProjectFile(
            "reformat/RangeValidation.java",
            """
            class RangeValidation {
                void test() {}
            }
            """.trimIndent()
        )

        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/reformat/RangeValidation.java")
            put("startLine", 1)
            put("endLine", 99)
        })

        assertTrue("Should error when endLine exceeds file line count", result.isError)
        assertTrue("Should mention endLine overflow", errorText(result).contains("exceeds file line count"))
    }

    fun testReformatCodeToolReportsNoOpWithConservativeDefault() = runBlocking {
        val filePath = sourceRootPath().resolve("reformat/AlreadyFormatted.java")
        createLocalProjectFile(
            "reformat/AlreadyFormatted.java",
            """
            class AlreadyFormatted {
                void test() {
                    System.out.println("ok");
                }
            }
            """.trimIndent() + "\n"
        )

        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/reformat/AlreadyFormatted.java")
        })

        assertFalse(
            "Already formatted file should succeed: ${if (result.isError) errorText(result) else "ok"}",
            result.isError
        )

        val payload = json.parseToJsonElement((result.content.first() as ContentBlock.Text).text).jsonObject
        assertEquals(false, payload["success"]?.jsonPrimitive?.boolean)
        assertEquals("no_op", payload["status"]?.jsonPrimitive?.content)
        assertEquals(0, payload["changesCount"]?.jsonPrimitive?.int)
        assertEquals(
            listOf("reformat", "optimize_imports"),
            payload["verification"]?.jsonObject
                ?.get("checksRun")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("rearrange_code_skipped"),
            payload["verification"]?.jsonObject
                ?.get("warnings")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
        )
        assertTrue(
            "Message should report no-op outcome",
            payload["message"]?.jsonPrimitive?.content?.contains("No formatting changes") == true
        )
        assertEquals(
            "File text should remain unchanged",
            """
            class AlreadyFormatted {
                void test() {
                    System.out.println("ok");
                }
            }
            """.trimIndent() + "\n",
            Files.readString(filePath)
        )
    }

    fun testReformatCodeToolReportsChangedFormattingOperations() = runBlocking {
        val filePath = sourceRootPath().resolve("reformat/NeedsFormatting.java")
        createLocalProjectFile(
            "reformat/NeedsFormatting.java",
            "class NeedsFormatting{void test(){System.out.println(\"ok\");}}"
        )

        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/reformat/NeedsFormatting.java")
        })

        assertFalse(
            "Formatting changes should succeed: ${if (result.isError) errorText(result) else "ok"}",
            result.isError
        )

        val payload = json.parseToJsonElement((result.content.first() as ContentBlock.Text).text).jsonObject
        assertEquals(true, payload["success"]?.jsonPrimitive?.boolean)
        assertEquals("success", payload["status"]?.jsonPrimitive?.content)
        assertEquals(1, payload["changesCount"]?.jsonPrimitive?.int)
        assertEquals(
            listOf("reformat", "optimize_imports"),
            payload["verification"]?.jsonObject
                ?.get("checksRun")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("rearrange_code_skipped"),
            payload["verification"]?.jsonObject
                ?.get("warnings")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
        )
        assertTrue(
            "Message should report formatting changes",
            payload["message"]?.jsonPrimitive?.content?.contains("Reformatted src/reformat/NeedsFormatting.java") == true
        )
        val formattedText = Files.readString(filePath)
        assertTrue(
            "File text should be reformatted",
            formattedText.contains("void test() {") && formattedText.contains("System.out.println(\"ok\");")
        )
    }

    private fun createLocalProjectFile(relativePath: String, content: String): PsiFile {
        val basePath = project.basePath ?: error("Project base path is required for local file tests")
        val sourceRootPath = sourceRootPath()
        Files.createDirectories(sourceRootPath)
        val sourceRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceRootPath)
            ?: error("Failed to refresh source root into LocalFileSystem")

        if (!localSourceRootConfigured) {
            val projectRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(basePath))
                ?: error("Failed to refresh project root into LocalFileSystem")
            PsiTestUtil.addContentRoot(module, projectRoot)
            PsiTestUtil.addSourceRoot(module, sourceRoot)
            localSourceRootConfigured = true
        }

        val filePath = sourceRootPath.resolve(relativePath)
        Files.createDirectories(filePath.parent ?: Path.of(basePath))
        Files.writeString(filePath, content)

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
            ?: error("Failed to refresh $relativePath into LocalFileSystem")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return PsiManager.getInstance(project).findFile(virtualFile)
            ?: error("Failed to create PSI for $relativePath")
    }

    private fun sourceRootPath(): Path {
        val basePath = project.basePath ?: error("Project base path is required for local file tests")
        return Path.of(basePath).resolve("src")
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

    fun testFindSuperMethodsToolPrefersPositionWhenBothTargetsProvided() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "com.example.UserServiceImpl#getUser(String)")
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should still error because test position is invalid", result.isError)
        assertFalse("Should prefer position mode instead of rejecting dual-mode requests", errorText(result).contains("Cannot specify both"))
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

    private fun invokeLegacySymbolContributor(
        contributor: ChooseByNameContributor,
        pattern: String,
        scope: GlobalSearchScope,
        languageFilter: Set<String>?,
        nameFilter: (String) -> Boolean,
        matcher: MinusculeMatcher,
        results: MutableList<SymbolData>,
        seen: MutableSet<String>
    ) {
        val method = OptimizedSymbolSearch::class.java.declaredMethods.first {
            it.name == "processContributor" && it.parameterCount == 10
        }
        method.isAccessible = true
        method.invoke(
            OptimizedSymbolSearch,
            contributor,
            project,
            pattern,
            scope,
            10,
            languageFilter,
            nameFilter,
            matcher,
            results,
            seen
        )
    }

    class LegacyContributor(
        private val itemsByName: Map<String, Array<NavigationItem>>
    ) : ChooseByNameContributor {
        override fun getNames(project: com.intellij.openapi.project.Project, includeNonProjectItems: Boolean): Array<String> =
            itemsByName.keys.toTypedArray()

        override fun getItemsByName(
            name: String,
            pattern: String,
            project: com.intellij.openapi.project.Project,
            includeNonProjectItems: Boolean
        ): Array<NavigationItem> = itemsByName[name] ?: emptyArray()
    }
}

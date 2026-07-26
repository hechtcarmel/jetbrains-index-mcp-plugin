package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool.Companion.buildCompiledElementErrorMessage
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
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SuperMethodsResult
import com.intellij.lang.java.JavaLanguage
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypes
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.impl.light.LightMethodBuilder
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Platform-dependent tests that require IntelliJ Platform indexing.
 * For schema and registration tests that don't need the platform, see ToolsUnitTest.
 */
class ToolsTest : McpPlatformTestCase() {

    private companion object {
        const val JS_TS_FIXTURE_SOURCE_ROOT = "src/test/testData/javascript/webstormIntegration"
        const val JS_TS_FIXTURE_PROJECT_ROOT = "src/webstormIntegration"
    }

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

    private fun errorText(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        (result.content.first() as TextContent).text

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testGetIndexStatusTool() = runBlocking {
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val tool = GetIndexStatusTool()

        val result = tool.execute(project, buildJsonObject { })

        assertFalse("get_index_status should succeed", result.isFailure)
        assertTrue("Should have content", result.content.isNotEmpty())

        val content = result.content.first()
        assertTrue("Content should be text", content is TextContent)

        val textContent = (content as TextContent).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        assertEquals(
            "Indexes are ready, so the IDE must report smart mode",
            false,
            resultJson["isDumbMode"]?.jsonPrimitive?.boolean
        )
        assertEquals(
            "isIndexing mirrors isDumbMode",
            false,
            resultJson["isIndexing"]?.jsonPrimitive?.boolean
        )
    }

    fun testFindUsagesToolMissingParams() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
        assertTrue("Should mention required params", errorText(result).contains(ErrorMessages.SYMBOL_OR_POSITION_REQUIRED))
    }

    fun testFindUsagesToolInvalidFile() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals(
            ErrorMessages.noElementAtPosition("nonexistent/file.kt", 1, 1),
            errorText(result)
        )
    }

    fun testFindUsagesToolPartialPosition() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("line", 1)
        })

        assertTrue("Should error with partial position params", result.isFailure)
        assertTrue("Should mention missing column", errorText(result).contains("column"))
    }

    fun testFindUsagesToolLanguageWithoutSymbol() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
        })

        assertTrue("Should error when language provided without symbol", result.isFailure)
        assertTrue("Should mention missing symbol", errorText(result).contains("symbol"))
    }

    fun testFindUsagesToolSymbolWithoutLanguage() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("symbol", "com.example.MyClass#method(String)")
        })

        assertTrue("Should error when symbol provided without language", result.isFailure)
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

        assertTrue("Should error when both language+symbol and file+line+column provided", result.isFailure)
        assertTrue("Should mention mutual exclusivity", errorText(result).contains("Cannot specify both"))
    }

    fun testFindUsagesToolUnsupportedLanguage() = runBlocking {
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Cobol")
            put("symbol", "com.example.MyClass")
        })

        assertTrue("Should error with unsupported language", result.isFailure)
        assertTrue("Should mention unsupported language", errorText(result).contains("Cobol"))
    }

    fun testFindDefinitionToolMissingParams() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
        assertTrue("Should mention required params", errorText(result).contains(ErrorMessages.SYMBOL_OR_POSITION_REQUIRED))
    }

    fun testFindDefinitionToolPartialPosition() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
        })

        assertTrue("Should error with partial position params", result.isFailure)
        assertTrue("Should mention missing line", errorText(result).contains("line"))
    }

    fun testFindDefinitionToolLanguageWithoutSymbol() = runBlocking {
        val tool = FindDefinitionTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Java")
        })

        assertTrue("Should error when language provided without symbol", result.isFailure)
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

        assertTrue("Should error when both language+symbol and file+line+column provided", result.isFailure)
        assertTrue("Should mention mutual exclusivity", errorText(result).contains("Cannot specify both"))
    }

    fun testFindDefinitionToolJavaScriptLanguageSymbolUsesHandlerResolutionPath() = runBlocking {
        val tool = FindDefinitionTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "JavaScript")
            put("symbol", "invalidSymbolWithoutHash")
        })

        assertTrue("Malformed JS symbol should fail deterministically", result.isFailure)
        val message = errorText(result)
        assertTrue("Should go through JS/TS symbol handler", message.contains("unsupported_grammar:"))
        assertFalse("Should not fail early with unsupported language", message.contains("Unsupported language for symbol references"))
    }

    // Navigation Tools Tests

    fun testTypeHierarchyToolMissingParams() = runBlocking {
        val tool = TypeHierarchyTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing className", result.isFailure)
    }

    fun testTypeHierarchyToolInvalidClass() = runBlocking {
        val tool = TypeHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("className", "com.nonexistent.Class")
        })

        assertTrue("Should error with invalid class", result.isFailure)
        assertTrue(
            "Error should name the class that could not be resolved: ${errorText(result)}",
            errorText(result).contains("Class 'com.nonexistent.Class' not found")
        )
    }

    fun testCallHierarchyToolMissingParams() = runBlocking {
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
    }

    fun testCallHierarchyToolInvalidFile() = runBlocking {
        val tool = CallHierarchyTool()

        // 'direction' is validated before the position is resolved; without it the tool would
        // reject the call for a missing parameter and never exercise file resolution.
        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
            put("direction", "callers")
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals(
            ErrorMessages.noElementAtPosition("nonexistent/file.kt", 1, 1),
            errorText(result)
        )
    }

    fun testCallHierarchyToolSymbolWithoutLanguage() = runBlocking {
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("symbol", "com.example.Service#processRequest(String)")
            put("direction", "callers")
        })

        assertTrue("Should error when symbol provided without language", result.isFailure)
        assertTrue("Should mention missing language", errorText(result).contains("language"))
    }

    fun testCallHierarchyToolUnsupportedLanguage() = runBlocking {
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Cobol")
            put("symbol", "com.example.Service#processRequest(String)")
            put("direction", "callers")
        })

        assertTrue("Should error with unsupported language", result.isFailure)
        assertTrue("Should mention unsupported language", errorText(result).contains("Cobol"))
    }

    fun testCallHierarchyToolJavaScriptFixtureRoutesThroughBarrelImports() = runBlocking {
        addWebstormIntegrationFixtures(
            "barrels/plugin-config.ts",
            "barrels/named-barrel.ts",
            "barrels/export-star-barrel.ts",
            "barrels/barrel-consumer.ts",
            "barrels/unrelated-plugin-config.ts",
            "barrels/unrelated-barrel.ts",
            "barrels/unrelated-barrel-consumer.ts"
        )

        val tool = CallHierarchyTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "JavaScript")
            put("symbol", fixtureSymbol("barrels/plugin-config.ts", "loadPluginConfig"))
            put("direction", "callers")
        })

        assertFalse("Barrel-import callers should be routed through JS/TS symbol resolution", result.isFailure)
        val payload = json.decodeFromString<CallHierarchyResult>(errorTextless(result))
        val callersByName = payload.calls.associateBy { it.name }
        val callerNames = callersByName.keys
        val productionCaller = callersByName["loadProductionConfigThroughNamedBarrel"]
        assertTrue("Named barrel caller should be present", callerNames.contains("loadFromNamedBarrel"))
        assertTrue("Export-star barrel caller should be present", callerNames.contains("loadFromExportStarBarrel"))
        assertNotNull("Proposal barrel-import production caller should be present", productionCaller)
        assertEquals(
            "Proposal caller should come from the production barrel-consumer fixture",
            fixtureProjectPath("barrels/barrel-consumer.ts"),
            productionCaller?.file
        )
        assertFalse(
            "Unrelated same-named barrel consumer should not be reported as a caller",
            callerNames.contains("loadFromUnrelatedBarrel")
        )
    }

    fun testCallHierarchyToolTypeScriptOverloadSymbolSeedsImplementationCapableEntry() = runBlocking {
        addWebstormIntegrationFixture("overloads/overloaded-export.ts")

        val tool = CallHierarchyTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "TypeScript")
            put("symbol", fixtureSymbol("overloads/overloaded-export.ts", "getProjectId"))
            put("direction", "callees")
        })

        assertFalse("TypeScript overload symbols should resolve to an implementation-capable call-hierarchy seed", result.isFailure)
        val payload = json.decodeFromString<CallHierarchyResult>(errorTextless(result))
        assertEquals("Seed should point at the implementation signature", 10, payload.element.line)
        assertTrue(
            "Implementation seed should expose callees that declaration-only overload signatures miss",
            payload.calls.any { it.name == "readProjectIdFromConfig" }
        )
    }

    fun testCallHierarchyToolTypeScriptOverloadPositionSeedNormalizesToImplementationForCallees() = runBlocking {
        addWebstormIntegrationFixture("overloads/overloaded-export.ts")

        val tool = CallHierarchyTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", fixtureProjectPath("overloads/overloaded-export.ts"))
            put("line", 4)
            put("column", 17)
            put("direction", "callees")
        })

        assertFalse("Overload signature position should normalize to the implementation for callees", result.isFailure)
        val payload = json.decodeFromString<CallHierarchyResult>(errorTextless(result))
        assertEquals("Normalized seed should point at implementation line", 10, payload.element.line)
        assertTrue("Normalized position seed should expose readProjectIdFromConfig callee", payload.calls.any { it.name == "readProjectIdFromConfig" })
    }

    fun testCallHierarchyToolTypeScriptOverloadPositionSeedNormalizesToImplementationForCallers() = runBlocking {
        writeWebstormIntegrationFile(
            "overloads/overloaded-position-callers.ts",
            """
            export function getProjectId(input: string): string;
            export function getProjectId(input: { workspace: string; project: string }): string;
            export function getProjectId(input: string | { workspace: string; project: string }): string {
              return typeof input === "string" ? input : `${'$'}{input.workspace}/${'$'}{input.project}`;
            }
            """.trimIndent()
        )
        writeWebstormIntegrationFile(
            "overloads/overloaded-position-callers-consumer.ts",
            """
            import { getProjectId } from "./overloaded-position-callers";

            export function readProjectId(): string {
              return getProjectId("workspace/project");
            }
            """.trimIndent()
        )

        val tool = CallHierarchyTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", fixtureProjectPath("overloads/overloaded-position-callers.ts"))
            put("line", 1)
            put("column", 17)
            put("direction", "callers")
        })

        assertFalse("Overload signature position should normalize to the implementation for callers", result.isFailure)
        val payload = json.decodeFromString<CallHierarchyResult>(errorTextless(result))
        assertEquals("Normalized caller seed should point at implementation line", 3, payload.element.line)
        assertTrue("Normalized caller seed should expose consumer call sites", payload.calls.any { it.name == "readProjectId" })
    }

    fun testCallHierarchyToolJavaScriptFixturePrioritizesRealisticIndexBarrelImportsBeforeVisibleLimit() = runBlocking {
        addWebstormIntegrationFixtures(
            "barrels/realistic/config/loader.ts",
            "barrels/realistic/config/index.ts",
            "barrels/realistic/src/loader.test.ts",
            "barrels/realistic/src/index.ts",
            "barrels/realistic/unrelated-config/loader.ts",
            "barrels/realistic/unrelated-config/index.ts",
            "barrels/realistic/src/unrelated.ts"
        )

        val tool = CallHierarchyTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "JavaScript")
            put("symbol", fixtureSymbol("barrels/realistic/config/loader.ts", "loadPluginConfig"))
            put("direction", "callers")
        })

        assertFalse("Realistic index barrel callers should be routed through JS/TS symbol resolution", result.isFailure)
        val payload = json.decodeFromString<CallHierarchyResult>(errorTextless(result))
        val callersByName = payload.calls.associateBy { it.name }
        val productionCaller = callersByName["bootstrapPluginConfig"]
        assertEquals(
            "Visible caller list should stay capped even when test noise exceeds the limit",
            20,
            payload.calls.size
        )
        assertTrue(
            "Fixture should exercise the >20 direct test callers regression",
            callersByName.keys.any { it.startsWith("loadPluginConfigFromTest") }
        )
        assertNotNull("Directory index consumer should be present", productionCaller)
        assertEquals(
            "Directory index consumer should come from realistic src/index.ts",
            fixtureProjectPath("barrels/realistic/src/index.ts"),
            productionCaller?.file
        )
        assertFalse(
            "Unrelated same-named config barrel consumer should not be reported as a caller",
            callersByName.containsKey("bootstrapUnrelatedPluginConfig")
        )
    }

    fun testFindImplementationsToolMissingParams() = runBlocking {
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
    }

    fun testFindImplementationsToolInvalidFile() = runBlocking {
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals(
            ErrorMessages.noElementAtPosition("nonexistent/file.kt", 1, 1),
            errorText(result)
        )
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

        assertTrue("Should error when both language+symbol and file+line+column provided", result.isFailure)
        assertTrue("Should mention mutual exclusivity", errorText(result).contains("Cannot specify both"))
    }

    fun testFindImplementationsToolUnsupportedLanguage() = runBlocking {
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Cobol")
            put("symbol", "com.example.Repository")
        })

        assertTrue("Should error with unsupported language", result.isFailure)
        assertTrue("Should mention unsupported language", errorText(result).contains("Cobol"))
    }

    fun testFindImplementationsToolJavaScriptLanguageSymbolUsesHandlerResolutionPath() = runBlocking {
        val tool = FindImplementationsTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "JavaScript")
            put("symbol", "invalidSymbolWithoutHash")
        })

        assertTrue("Malformed JS symbol should fail deterministically", result.isFailure)
        val message = errorText(result)
        assertTrue("Should go through JS/TS symbol handler", message.contains("unsupported_grammar:"))
        assertFalse("Should not fail early with unsupported language", message.contains("Unsupported language for symbol references"))
    }

    fun testFindClassToolInvalidScopeReturnsStructuredError() = runBlocking {
        val tool = FindClassTool()

        val result = tool.execute(project, buildJsonObject {
            put("query", "UserService")
            put("scope", "totally_invalid")
        })

        assertTrue("Should error with invalid scope", result.isFailure)

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

        assertTrue("Should error with malformed scope type", result.isFailure)

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
            scope = scope,
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

        assertFalse("Search should succeed", result.isFailure)
        val resultJson = json.parseToJsonElement((result.content.first() as TextContent).text).jsonObject
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

        assertFalse("Regex search should succeed", result.isFailure)
        val resultJson = json.parseToJsonElement((result.content.first() as TextContent).text).jsonObject
        val files = resultJson["matches"]!!.jsonArray.map { it.jsonObject["file"]!!.jsonPrimitive.content }

        assertEquals(listOf("src/CommandRunner.java"), files)
    }

    fun testSearchTextToolFindsSubstringOfUnderscoreSeparatedToken() = runBlocking {
        myFixture.addFileToProject(
            "infra/alerts.yaml",
            """
            rules:
              - alert: StuckCases
                expr: 'max(a_word_and_another_word) > 0'
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "a_word")
        })

        assertFalse("Search should not return an error", result.isFailure)
        val resultJson = json.parseToJsonElement((result.content.first() as TextContent).text).jsonObject
        val matches = resultJson["matches"]!!.jsonArray
        assertTrue(
            "Should find 'a_word' as a substring of 'a_word_and_another_word' — " +
                    "matching IDE Find in Files behavior. Got 0 matches.",
            matches.isNotEmpty()
        )
        val files = matches.map { it.jsonObject["file"]!!.jsonPrimitive.content }
        assertTrue(
            "Match should be in infra/alerts.yaml, got: $files",
            files.any { it.endsWith("alerts.yaml") }
        )
    }


    fun testSearchTextToolContextFilterLimitsToComments() = runBlocking {
        myFixture.addFileToProject(
            "src/Example.java",
            """
            // needle in a comment
            class Example {
                String s = "needle in a string";
                void needle_in_code() {}
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "needle")
            put("context", "comments")
            put("pageSize", 50)
        })

        assertFalse("Search should succeed", result.isFailure)
        val matches = json.parseToJsonElement((result.content.first() as TextContent).text)
            .jsonObject["matches"]!!.jsonArray
        val contextTypes = matches.map { it.jsonObject["contextType"]!!.jsonPrimitive.content }
        assertTrue("Should have at least one comment match", contextTypes.any { it == "COMMENT" })
        assertTrue("Should not return code or string matches", contextTypes.all { it == "COMMENT" })
    }

    fun testSearchTextToolCaseInsensitivePlainText() = runBlocking {
        myFixture.addFileToProject(
            "config/settings.properties",
            """
            MaxRetryCount=3
            max_timeout_seconds=30
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", "maxretrycount")
            put("caseSensitive", false)
            put("pageSize", 10)
        })

        assertFalse("Search should succeed", result.isFailure)
        val matches = json.parseToJsonElement((result.content.first() as TextContent).text)
            .jsonObject["matches"]!!.jsonArray
        assertTrue(
            "Case-insensitive search for 'maxretrycount' should match 'MaxRetryCount'",
            matches.isNotEmpty()
        )
    }

    // Intelligence Tools Tests

    fun testGetDiagnosticsToolMissingParams() = runBlocking {
        val tool = GetDiagnosticsTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", result.isFailure)
    }

    fun testGetDiagnosticsToolInvalidFile() = runBlocking {
        val tool = GetDiagnosticsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals("File not found: nonexistent/file.kt", errorText(result))
    }

    fun testFindUsagesToolJavaScriptLanguageSymbolUsesHandlerResolutionPath() = runBlocking {
        val tool = FindUsagesTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "JavaScript")
            put("symbol", "invalidSymbolWithoutHash")
        })

        assertTrue("Malformed JS symbol should fail deterministically", result.isFailure)
        val message = errorText(result)
        assertTrue("Should go through JS/TS symbol handler", message.contains("unsupported_grammar:"))
        assertFalse("Should not fail early with unsupported language", message.contains("Unsupported language for symbol references"))
    }

    // Refactoring Tools Tests

    fun testRenameSymbolToolMissingParams() = runBlocking {
        val tool = RenameSymbolTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
    }

    fun testRenameSymbolToolInvalidFile() = runBlocking {
        val tool = RenameSymbolTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
            put("newName", "newSymbol")
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals("No element found at the specified position", errorText(result))
    }

    fun testRenameSymbolToolBlankName() = runBlocking {
        val tool = RenameSymbolTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("line", 1)
            put("column", 1)
            put("newName", "   ")
        })

        assertTrue("Should error with blank name", result.isFailure)
    }

    fun testRenameSymbolToolCompiledElementReturnsHelpfulError() = runBlocking {
        // Regression test: renaming a compiled class (e.g. a JDK type) used to trigger
        // an assertion in RenameProcessor constructor, logged as SEVERE "Plugin to blame".
        // Fix: validateAndPrepare() detects PsiCompiledElement before reaching RenameProcessor
        // and returns a clear error message containing "compiled".
        //
        // In the test fixture the JDK may not be fully indexed, so java.lang.String may not
        // resolve to a PsiCompiledElement — we verify via unit-level logic below instead.
        val psiFile = myFixture.addFileToProject(
            "Usage.java",
            """
            public class Usage {
                public void method() {
                    String s = "hello";
                }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
            .getDocument(psiFile) ?: error("no document")
        val offset = document.text.indexOf("String s")
        val line = document.getLineNumber(offset) + 1
        val column = offset - document.getLineStartOffset(line - 1) + 1

        val tool = RenameSymbolTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", psiFile.virtualFile.path)
            put("line", line)
            put("column", column)
            put("newName", "MyString")
        })

        // The rename must not succeed — either the compiled check fires (error mentions
        // "compiled") or the element didn't resolve (some other error). Either way, no
        // SEVERE "Plugin to blame" assertion must fire, which is the key regression property.
        assertTrue("Renaming a JDK type reference must not succeed", result.isFailure)
        val msg = (result.content.firstOrNull() as? TextContent)?.text ?: ""
        assertFalse(
            "Success response must not be returned for a compiled/unresolved symbol",
            msg.contains("Successfully renamed", ignoreCase = true)
        )
    }

    fun testRenameCompiledElementCheckLogic() {
        // Unit-level regression test for the compiled-element guard in validateAndPrepare().
        // Verifies the check logic directly without needing a real PsiCompiledElement from the JDK.
        // The guard is: if (namedElement is PsiCompiledElement) return error mentioning "compiled".
        val errorForCompiled = buildCompiledElementErrorMessage("description", "/path/to/Something.class")
        assertTrue(
            "Error message for compiled element must mention 'compiled'",
            errorForCompiled.contains("compiled", ignoreCase = true)
        )
        assertTrue(
            "Error message must name the element",
            errorForCompiled.contains("description")
        )
    }

    fun testSafeDeleteToolMissingParams() = runBlocking {
        val tool = SafeDeleteTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
    }

    fun testSafeDeleteToolInvalidFile() = runBlocking {
        val tool = SafeDeleteTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals("File not found: nonexistent/file.kt", errorText(result))
    }

    // File Structure Tool Tests

    fun testFileStructureToolMissingParams() = runBlocking {
        val tool = FileStructureTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
    }

    fun testFileStructureToolInvalidFile() = runBlocking {
        val tool = FileStructureTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.java")
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals("File not found: nonexistent/file.java", errorText(result))
    }

    fun testFileStructureToolTypeAliasFixtureCoverageHook() = runBlocking {
        addWebstormIntegrationFixture("types/type-alias-vs-interface.ts")

        val tool = FileStructureTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", fixtureProjectPath("types/type-alias-vs-interface.ts"))
        })

        assertFalse("Type alias fixture should be accepted by file structure tool", result.isFailure)
        val payload = json.decodeFromString<FileStructureResult>(errorTextless(result))
        assertTrue("Type alias output should remain distinct from classes", payload.structure.contains("typealias FileStructureAlias"))
        assertFalse("Type alias output should not regress back to class formatting", payload.structure.contains("class FileStructureAlias"))
        assertTrue("Interface output should remain visible beside type aliases", payload.structure.contains("interface FileStructureInterface"))
        assertTrue("Class output should remain visible beside type aliases", payload.structure.contains("class FileStructureClass"))
    }

    fun testFindImplementationsToolInterfaceImplementsFixtureCoverageHook() = runBlocking {
        addWebstormIntegrationFixture("interface-implements/thoth-client-interface.ts")

        val tool = FindImplementationsTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "JavaScript")
            put("symbol", fixtureSymbol("interface-implements/thoth-client-interface.ts", "ThothClient"))
        })

        assertFalse("Interface+implements fixture should be wired into implementations coverage", result.isFailure)
        val payload = json.decodeFromString<ImplementationResult>(errorTextless(result))
        assertTrue(
            "HttpThothClient should remain the regression implements target",
            payload.implementations.any { it.name == "HttpThothClient" }
        )
        assertTrue(
            "MemoryThothClient should remain the second deterministic implements target",
            payload.implementations.any { it.name == "MemoryThothClient" }
        )
    }

    fun testFindSuperMethodsToolInterfaceImplementsMethodFixtureCoverageHook() = runBlocking {
        addWebstormIntegrationFixture("interface-implements/thoth-client-interface.ts")

        val tool = FindSuperMethodsTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", fixtureProjectPath("interface-implements/thoth-client-interface.ts"))
            put("line", 6)
            put("column", 3)
        })

        assertFalse("Class implements method fixture should be accepted by find super methods", result.isFailure)
        val payload = json.decodeFromString<SuperMethodsResult>(errorTextless(result))
        assertEquals("fetch", payload.method.name)
        assertTrue(
            "Implements scenario should resolve back to the interface method",
            payload.hierarchy.any { it.name == "fetch" && it.containingClass == "ThothClient" && it.isInterface }
        )
    }

    fun testFileStructureToolTypeImportAliasFixtureCoverageHook() = runBlocking {
        addWebstormIntegrationFixtures(
            "aliases/alias-source.ts",
            "aliases/import-type-alias.ts"
        )

        val tool = FileStructureTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", fixtureProjectPath("aliases/import-type-alias.ts"))
        })

        assertFalse("Type import alias fixture should be accepted by file structure tool", result.isFailure)
        val structure = json.decodeFromString<FileStructureResult>(errorTextless(result)).structure
        assertTrue("Type import alias coverage should mention ImportedPluginNameAlias", structure.contains("typealias ImportedPluginNameAlias"))
        assertTrue("Type import alias coverage should keep importedPluginName visible", structure.contains("var importedPluginName"))
        assertTrue("Type import alias coverage should keep echoImportedPluginName visible", structure.contains("function echoImportedPluginName"))
    }

    fun testFileStructureToolAsConstDerivedTypeFixtureCoverageHook() = runBlocking {
        addWebstormIntegrationFixtures(
            "derived/const-derived-types.ts"
        )

        val tool = FileStructureTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", fixtureProjectPath("derived/const-derived-types.ts"))
        })

        assertFalse("as const derived fixture should be accepted by file structure tool", result.isFailure)
        val derivedStructure = json.decodeFromString<FileStructureResult>(errorTextless(result)).structure
        assertTrue("as const coverage should mention THOTH_STATUS", derivedStructure.contains("var THOTH_STATUS"))
        assertTrue("Derived type coverage should mention ThothStatus", derivedStructure.contains("typealias ThothStatus"))
        assertTrue("Derived type coverage should mention DEFAULT_THOTH_STATUS", derivedStructure.contains("var DEFAULT_THOTH_STATUS"))
        assertTrue("Derived type coverage should mention formatThothStatus", derivedStructure.contains("function formatThothStatus"))
    }

    fun testJavaStructureHandlerBuildsPhysicalSourceStructure() {
        val psiFile = myFixture.addFileToProject(
            "com/example/SampleDto.java",
            """
            package com.example;

            public class SampleDto {
                private String name;
                private int count;

                public SampleDto() {
                }

                public String getName() {
                    return name;
                }
            }
            """.trimIndent()
        )
        val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)

        assertNotNull("Expected Java structure handler", handler)
        val nodes = handler!!.getFileStructure(psiFile, project)
        val root = nodes.single()

        assertEquals("SampleDto", root.name)
        assertEquals("CLASS", root.kind.name)
        assertEquals(3, root.line)
        assertEquals(
            listOf("name", "count", "SampleDto", "getName"),
            root.children.map { it.name }
        )
        assertEquals(listOf(4, 5, 7, 10), root.children.map { it.line })
    }

    fun testJavaStructureHandlerSkipsAugmentedMethodWithoutSourceOffset() {
        ExtensionTestUtil.maskExtensions(
            PsiAugmentProvider.EP_NAME,
            listOf(object : PsiAugmentProvider() {
                override fun <Psi : PsiElement> getAugments(
                    element: PsiElement,
                    type: Class<Psi>,
                    nameHint: String?
                ): List<Psi> {
                    if (type != PsiMethod::class.java) return emptyList()
                    if (element !is PsiClass || element.name != "AugmentedDto") return emptyList()

                    val method = LightMethodBuilder(element.manager, JavaLanguage.INSTANCE, "generatedGetter")
                        .setMethodReturnType(PsiTypes.intType())
                        .setContainingClass(element)

                    @Suppress("UNCHECKED_CAST")
                    return listOf(method as Psi)
                }
            }),
            testRootDisposable
        )

        val psiFile = myFixture.addFileToProject(
            "com/example/AugmentedDto.java",
            """
            package com.example;

            public class AugmentedDto {
                private int count;
            }
            """.trimIndent()
        )
        val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)

        assertNotNull("Expected Java structure handler", handler)
        val nodes = handler!!.getFileStructure(psiFile, project)
        val root = nodes.single()

        assertEquals(listOf("count"), root.children.map { it.name })
        assertFalse(root.children.any { it.name == "generatedGetter" })
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

        assertFalse("get_active_file should succeed", result.isFailure)
        assertTrue("Should have content", result.content.isNotEmpty())

        val content = result.content.first()
        assertTrue("Content should be text", content is TextContent)

        val textContent = (content as TextContent).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        val activeFiles = resultJson["activeFiles"]?.jsonArray
        assertNotNull("Result should have activeFiles", activeFiles)
        assertEquals(
            "No editor was opened by this test, so the tool must report an empty list: $activeFiles",
            0,
            activeFiles!!.size
        )
    }

    fun testOpenFileToolMissingParams() = runBlocking {
        val tool = OpenFileTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
    }

    fun testOpenFileToolInvalidFile() = runBlocking {
        val tool = OpenFileTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals("File not found: nonexistent/file.kt", errorText(result))
    }

    fun testOpenFileToolColumnWithoutLine() = runBlocking {
        val tool = OpenFileTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("column", 5)
        })

        assertTrue("Should error with column without line", result.isFailure)
    }

    fun testOpenFileToolInvalidLine() = runBlocking {
        val tool = OpenFileTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("line", 0)
        })

        assertTrue("Should error with line < 1", result.isFailure)
    }

    // Reformat Code Tool Tests

    fun testReformatCodeToolMissingParams() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
    }

    fun testReformatCodeToolInvalidFile() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals("File not found: nonexistent/file.kt", errorText(result))
    }

    fun testReformatCodeToolStartLineWithoutEndLine() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", 1)
        })

        assertTrue("Should error when startLine provided without endLine", result.isFailure)
    }

    fun testReformatCodeToolEndLineWithoutStartLine() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("endLine", 10)
        })

        assertTrue("Should error when endLine provided without startLine", result.isFailure)
    }

    fun testReformatCodeToolInvalidLineRange() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", 10)
            put("endLine", 5)
        })

        assertTrue("Should error when endLine < startLine", result.isFailure)
    }

    fun testReformatCodeToolStartLineLessThanOne() = runBlocking {
        val tool = ReformatCodeTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "test.kt")
            put("startLine", 0)
            put("endLine", 5)
        })

        assertTrue("Should error when startLine < 1", result.isFailure)
    }

    // FindSuperMethods Tool Tests (language+symbol)

    fun testFindSuperMethodsToolMissingParams() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing params", result.isFailure)
        assertTrue("Should mention required params", errorText(result).contains(ErrorMessages.SYMBOL_OR_POSITION_REQUIRED))
    }

    fun testFindSuperMethodsToolInvalidFile() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent/file.kt")
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with invalid file", result.isFailure)
        assertEquals(
            ErrorMessages.noElementAtPosition("nonexistent/file.kt", 1, 1),
            errorText(result)
        )
    }

    fun testFindSuperMethodsToolPartialPosition() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("line", 1)
            put("column", 1)
        })

        assertTrue("Should error with partial position params", result.isFailure)
        assertTrue("Should mention missing file", errorText(result).contains("file"))
    }

    fun testFindSuperMethodsToolSymbolWithoutLanguage() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("symbol", "com.example.UserServiceImpl#getUser(String)")
        })

        assertTrue("Should error when symbol provided without language", result.isFailure)
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

        assertTrue("Should error when both language+symbol and file+line+column provided", result.isFailure)
        assertTrue("Should mention mutual exclusivity", errorText(result).contains("Cannot specify both"))
    }

    fun testFindSuperMethodsToolUnsupportedLanguage() = runBlocking {
        val tool = FindSuperMethodsTool()

        val result = tool.execute(project, buildJsonObject {
            put("language", "Cobol")
            put("symbol", "com.example.UserServiceImpl#getUser(String)")
        })

        assertTrue("Should error with unsupported language", result.isFailure)
        assertTrue("Should mention unsupported language", errorText(result).contains("Cobol"))
    }

    fun testFindSuperMethodsToolJavaScriptLanguageSymbolUsesHandlerResolutionPath() = runBlocking {
        val tool = FindSuperMethodsTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "JavaScript")
            put("symbol", "invalidSymbolWithoutHash")
        })

        // Representative routing check only: JS super-method semantics may not be meaningful in minimal fixtures,
        // but malformed symbol grammar must still be rejected by the JS/TS symbol handler path.
        assertTrue("Malformed JS symbol should fail deterministically", result.isFailure)
        val message = errorText(result)
        assertTrue("Should go through JS/TS symbol handler", message.contains("unsupported_grammar:"))
        assertFalse("Should not fail early with unsupported language", message.contains("Unsupported language for symbol references"))
    }

    fun testCallHierarchyToolJavaScriptLanguageSymbolUsesHandlerResolutionPath() = runBlocking {
        val tool = CallHierarchyTool()
        val result = tool.execute(project, buildJsonObject {
            put("language", "JavaScript")
            put("symbol", "invalidSymbolWithoutHash")
            put("direction", "callers")
        })

        assertTrue("Malformed JS symbol should fail deterministically", result.isFailure)
        val message = errorText(result)
        assertTrue("Should go through JS/TS symbol handler", message.contains("unsupported_grammar:"))
        assertFalse("Should not fail early with unsupported language", message.contains("Unsupported language for symbol references"))
    }

    // Registry tests that require platform services (McpSettings)

    fun testToolDefinitionsHaveRequiredFields() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val definitions = registry.getToolDefinitions()

        assertTrue("Registry should expose built-in tools, otherwise this loop asserts nothing", definitions.isNotEmpty())

        for (definition in definitions) {
            assertNotNull("Definition should have name", definition.name)
            assertTrue("Name should not be empty", definition.name.isNotEmpty())

            assertNotNull("Definition should have description", definition.description)
            assertTrue("Description should not be empty", definition.description!!.isNotEmpty())

            assertNotNull("Definition should have inputSchema", definition.inputSchema)
            assertEquals(SchemaConstants.TYPE_OBJECT, definition.inputSchema.type)
        }
    }

    private fun invokeLegacySymbolContributor(
        contributor: ChooseByNameContributor,
        scope: GlobalSearchScope,
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
            "ScopeSymbol",
            scope,
            10,
            null,
            nameFilter,
            matcher,
            results,
            seen
        )
    }

    private fun addWebstormIntegrationFixtures(vararg relativePaths: String) {
        relativePaths.forEach(::addWebstormIntegrationFixture)
    }

    private var jsTsFixtureRootRegistered = false

    private fun addWebstormIntegrationFixture(relativePath: String) {
        val sourcePath = Path.of(JS_TS_FIXTURE_SOURCE_ROOT).resolve(relativePath)
        writeWebstormIntegrationFile(relativePath, Files.readString(sourcePath))
    }

    /**
     * Materializes JS/TS fixture content on the real filesystem, under a registered source root.
     *
     * Not `myFixture.addFileToProject`: that writes to the in-memory `temp://` VFS, which
     * `LocalFileSystem` — the only filesystem the production resolvers consult — cannot see.
     * See [com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase].
     */
    private fun writeWebstormIntegrationFile(relativePath: String, content: String) {
        if (!jsTsFixtureRootRegistered) {
            registerSourceRoot(JS_TS_FIXTURE_PROJECT_ROOT)
            jsTsFixtureRootRegistered = true
        }
        writeProjectFile(fixtureProjectPath(relativePath), content)
    }

    private fun fixtureProjectPath(relativePath: String): String = "$JS_TS_FIXTURE_PROJECT_ROOT/$relativePath"

    private fun fixtureSymbol(relativePath: String, exportName: String): String {
        return "$JS_TS_FIXTURE_PROJECT_ROOT/${relativePath.removeJsTsExtension()}#$exportName"
    }

    private fun String.removeJsTsExtension(): String {
        return removeSuffix(".d.ts")
            .removeSuffix(".ts")
            .removeSuffix(".tsx")
            .removeSuffix(".js")
            .removeSuffix(".jsx")
            .removeSuffix(".mjs")
            .removeSuffix(".cjs")
    }

    private fun errorTextless(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        (result.content.first() as TextContent).text

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

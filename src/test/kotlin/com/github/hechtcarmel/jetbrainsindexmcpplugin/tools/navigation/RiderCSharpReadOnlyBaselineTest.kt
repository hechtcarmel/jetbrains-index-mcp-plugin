package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RIDER_CALL_HIERARCHY_SYMBOL_MODE_UNSUPPORTED
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendResponse
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderBackendSemanticService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.UsageLocation
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RiderCSharpReadOnlyBaselineTest : BasePlatformTestCase() {
    private var csharpRootsConfigured = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testFindDefinitionAndReferencesPreserveCurrentRiderCSharpSupportedPayloads() = runBlocking {
        mockkObject(RiderBackendSemanticService)
        try {
            every {
                RiderBackendSemanticService.findDefinition(
                    project = project,
                    file = null,
                    line = null,
                    column = null,
                    language = "C#",
                    symbol = "Demo.ReadOnlyBaselineService#Run(System.String)",
                    fullElementPreview = false,
                    maxPreviewLines = 50
                )
            } returns RiderBackendResponse(
                handled = true,
                value = DefinitionResult(
                    file = "src/Services/ReadOnlyBaselineService.cs",
                    line = 12,
                    column = 19,
                    preview = "public void Run(string input)",
                    symbolName = "Run",
                    astPath = listOf("class ReadOnlyBaselineService", "method Run")
                )
            )
            every {
                RiderBackendSemanticService.findReferences(
                    project = project,
                    file = null,
                    line = null,
                    column = null,
                    language = "C#",
                    symbol = "Demo.ReadOnlyBaselineService#Run(System.String)",
                    scope = BuiltInSearchScope.PROJECT_FILES,
                    limit = any()
                )
            } returns RiderBackendResponse(
                handled = true,
                value = listOf(
                    UsageLocation(
                        file = "src/Consumers/ReadOnlyBaselineConsumer.cs",
                        line = 22,
                        column = 13,
                        context = "service.Run(\"baseline\");",
                        type = "method_call",
                        astPath = listOf("class ReadOnlyBaselineConsumer", "method Invoke")
                    )
                )
            )

            val definitionResult = FindDefinitionTool().execute(project, buildJsonObject {
                put("language", "C#")
                put("symbol", "Demo.ReadOnlyBaselineService#Run(System.String)")
            })
            assertFalse(definitionResult.isError)
            val definition = decode<DefinitionResult>(definitionResult)
            assertEquals("src/Services/ReadOnlyBaselineService.cs", definition.file)
            assertEquals("Run", definition.symbolName)

            val referencesResult = FindUsagesTool().execute(project, buildJsonObject {
                put("language", "C#")
                put("symbol", "Demo.ReadOnlyBaselineService#Run(System.String)")
                put("scope", "project_files")
                put("pageSize", 1)
            })
            assertFalse(referencesResult.isError)
            val references = decode<FindUsagesResult>(referencesResult)
            assertEquals(1, references.totalCount)
            assertEquals("src/Consumers/ReadOnlyBaselineConsumer.cs", references.usages.single().file)
            assertEquals("method_call", references.usages.single().type)
        } finally {
            unmockkObject(RiderBackendSemanticService)
        }
    }

    fun testImplementationsCallHierarchyAndSuperMethodsStayExplicitlyBoundedWhenRiderCannotMapCSharpSymbol() = runBlocking {
        mockkObject(RiderBackendSemanticService)
        try {
            every {
                RiderBackendSemanticService.resolveSymbolToPosition(project, "C#", "Demo.ReadOnlyBaselineService#Run(System.String)")
            } returns null
            every {
                RiderBackendSemanticService.getCallHierarchy(
                    project = project,
                    file = null,
                    line = null,
                    column = null,
                    language = "C#",
                    symbol = "Demo.ReadOnlyBaselineService#Run(System.String)",
                    direction = "callers",
                    depth = 3,
                    scope = BuiltInSearchScope.PROJECT_FILES
                )
            } returns RiderBackendResponse(handled = true, errorMessage = RIDER_CALL_HIERARCHY_SYMBOL_MODE_UNSUPPORTED)

            val implementations = FindImplementationsTool().execute(project, buildJsonObject {
                put("language", "C#")
                put("symbol", "Demo.ReadOnlyBaselineService#Run(System.String)")
            })
            assertTrue(implementations.isError)
            assertContains(
                renderText(implementations),
                "Rider C# symbol-mode implementations require backend-native symbol resolution"
            )

            val callHierarchy = CallHierarchyTool().execute(project, buildJsonObject {
                put("language", "C#")
                put("symbol", "Demo.ReadOnlyBaselineService#Run(System.String)")
                put("direction", "callers")
            })
            assertTrue(callHierarchy.isError)
            assertContains(renderText(callHierarchy), RIDER_CALL_HIERARCHY_SYMBOL_MODE_UNSUPPORTED)

            val superMethods = FindSuperMethodsTool().execute(project, buildJsonObject {
                put("language", "C#")
                put("symbol", "Demo.ReadOnlyBaselineService#Run(System.String)")
            })
            assertTrue(superMethods.isError)
            assertContains(
                renderText(superMethods),
                "Rider C# symbol-mode super methods require backend-native symbol resolution"
            )
        } finally {
            unmockkObject(RiderBackendSemanticService)
        }
    }

    fun testTypeHierarchyAndFindClassKeepCurrentRiderCSharpBackendLane() = runBlocking {
        mockkObject(RiderBackendSemanticService)
        try {
            val match = SymbolMatch(
                name = "ReadOnlyBaselineService",
                qualifiedName = "Demo.ReadOnlyBaselineService",
                kind = "class",
                file = "src/Services/ReadOnlyBaselineService.cs",
                line = 7,
                column = 14,
                containerName = "Demo",
                language = "C#"
            )

            every {
                RiderBackendSemanticService.findTypes(
                    project = project,
                    query = "ReadOnlyBaselineService",
                    matchMode = "substring",
                    scope = BuiltInSearchScope.PROJECT_FILES,
                    language = "C#",
                    limit = any()
                )
            } returns RiderBackendResponse(handled = true, value = listOf(match))
            every {
                RiderBackendSemanticService.findTypes(
                    project = project,
                    query = "Demo.ReadOnlyBaselineService",
                    matchMode = "exact",
                    scope = BuiltInSearchScope.PROJECT_FILES,
                    language = "C#",
                    limit = 5
                )
            } returns RiderBackendResponse(handled = true, value = listOf(match))
            every {
                RiderBackendSemanticService.getTypeHierarchy(
                    project = project,
                    file = "src/Services/ReadOnlyBaselineService.cs",
                    line = 7,
                    column = 14,
                    scope = BuiltInSearchScope.PROJECT_FILES,
                    language = "C#"
                )
            } returns RiderBackendResponse(
                handled = true,
                value = TypeHierarchyData(
                    element = TypeElementData(
                        name = "ReadOnlyBaselineService",
                        qualifiedName = "Demo.ReadOnlyBaselineService",
                        file = "src/Services/ReadOnlyBaselineService.cs",
                        line = 7,
                        kind = "class",
                        language = "C#"
                    ),
                    supertypes = listOf(
                        TypeElementData(
                            name = "BaseService",
                            qualifiedName = "Demo.BaseService",
                            file = "src/Services/BaseService.cs",
                            line = 3,
                            kind = "class",
                            language = "C#"
                        )
                    ),
                    subtypes = emptyList()
                )
            )

            val findClassResult = FindClassTool().execute(project, buildJsonObject {
                put("query", "ReadOnlyBaselineService")
                put("language", "C#")
            })
            assertFalse(findClassResult.isError)
            val classes = decode<FindClassResult>(findClassResult)
            assertEquals("Demo.ReadOnlyBaselineService", classes.classes.single().qualifiedName)

            val typeHierarchyResult = TypeHierarchyTool().execute(project, buildJsonObject {
                put("className", "Demo.ReadOnlyBaselineService")
                put("language", "C#")
                put("scope", "project_files")
            })
            assertFalse(typeHierarchyResult.isError)
            val hierarchy = decode<TypeHierarchyResult>(typeHierarchyResult)
            assertEquals("ReadOnlyBaselineService", hierarchy.element.name)
            assertEquals("BaseService", hierarchy.supertypes.single().name)
        } finally {
            unmockkObject(RiderBackendSemanticService)
        }
    }

    fun testFindFileAndSearchTextKeepCurrentCSharpIndexedBaseline() = runBlocking {
        createCSharpFile(
            relativePath = "Controllers/ReadOnlyBaselineController.cs",
            content = "namespace Demo.Controllers { public class ReadOnlyBaselineController { private const string ReadOnlyBaselineMarker = \"baseline\"; } }",
            isTestSource = false
        )
        createCSharpFile(
            relativePath = "Controllers/ReadOnlyBaselineController.cs",
            content = "namespace Demo.Tests { public class ReadOnlyBaselineController { } }",
            isTestSource = true
        )

        val findFileResult = FindFileTool().execute(project, buildJsonObject {
            put("query", "ReadOnlyBaselineController.cs")
            put("scope", "project_production_files")
        })
        assertFalse(findFileResult.isError)
        val files = decode<FindFileResult>(findFileResult)
        assertTrue(files.files.any { it.path.replace('\\', '/').endsWith("src/Controllers/ReadOnlyBaselineController.cs") })
        assertFalse(files.files.any { it.path.replace('\\', '/').endsWith("tests/Controllers/ReadOnlyBaselineController.cs") })

        val searchTextResult = SearchTextTool().execute(project, buildJsonObject {
            put("query", "ReadOnlyBaselineMarker")
            put("context", "code")
        })
        assertFalse(searchTextResult.isError)
        val searchMatches = decode<SearchTextResult>(searchTextResult)
        assertTrue(searchMatches.matches.any { it.file.replace('\\', '/').endsWith("src/Controllers/ReadOnlyBaselineController.cs") })
    }

    fun testFileStructureKeepsCurrentCSharpSupportBoundary() = runBlocking {
        createCSharpFile(
            relativePath = "Services/ReadOnlyBaselineStructure.cs",
            content = "namespace Demo.Services { public class ReadOnlyBaselineStructure { public void Run() { } } }",
            isTestSource = false
        )

        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "src/Services/ReadOnlyBaselineStructure.cs")
        })

        assertTrue(result.isError)
        assertContains(renderText(result), "Language not supported for file structure")
    }

    fun testDiagnosticsKeepCurrentCSharpEligibilityBoundary() = runBlocking {
        createCSharpFile(
            relativePath = "Services/ReadOnlyBaselineDiagnostics.cs",
            content = "namespace Demo.Services { public class ReadOnlyBaselineDiagnostics { } }",
            isTestSource = false
        )

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("file", "src/Services/ReadOnlyBaselineDiagnostics.cs")
        })

        assertFalse(result.isError)
        val diagnostics = decode<DiagnosticsResult>(result)
        assertTrue(diagnostics.analysisFresh == true)
        assertFalse(diagnostics.analysisTimedOut == true)
        val batchCaveat = "Closed-file diagnostics use public batch analysis"
        val editorUnavailable = "Intentions are unavailable because the file is not open in an editor."
        val analysisMessage = diagnostics.analysisMessage
        if (analysisMessage == null) {
            assertNull("Live open-editor analysis should not report closed-file batch caveats", analysisMessage)
        } else {
            assertContains(analysisMessage, batchCaveat)
            assertFalse(analysisMessage.contains(editorUnavailable))
        }
    }

    private fun createCSharpFile(relativePath: String, content: String, isTestSource: Boolean) {
        ensureCSharpRootsConfigured()
        val rootPath = rootPath(isTestSource)
        val filePath = rootPath.resolve(relativePath)
        Files.createDirectories(filePath.parent)
        Files.writeString(filePath, content)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
            ?: error("Failed to refresh $relativePath into LocalFileSystem")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        PsiManager.getInstance(project).findFile(virtualFile)
            ?: error("Failed to create PSI for $relativePath")
    }

    private fun ensureCSharpRootsConfigured() {
        if (csharpRootsConfigured) return
        val basePath = project.basePath ?: error("Project base path is required for Rider C# baseline tests")
        val prodRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath(isTestSource = false).also { Files.createDirectories(it) })
            ?: error("Failed to refresh production source root into LocalFileSystem")
        val testRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath(isTestSource = true).also { Files.createDirectories(it) })
            ?: error("Failed to refresh test source root into LocalFileSystem")

        PsiTestUtil.addSourceRoot(module, prodRoot, false)
        PsiTestUtil.addSourceRoot(module, testRoot, true)
        csharpRootsConfigured = true
    }

    private fun rootPath(isTestSource: Boolean): Path {
        val basePath = project.basePath ?: error("Project base path is required for Rider C# baseline tests")
        return Path.of(basePath).resolve(if (isTestSource) "tests" else "src")
    }

    private inline fun <reified T> decode(result: ToolCallResult): T {
        val text = renderText(result)
        return json.decodeFromString(text)
    }

    private fun renderText(result: ToolCallResult): String {
        return (result.content.single() as ContentBlock.Text).text
    }

    private fun assertContains(text: String, needle: String) {
        assertTrue("Expected '$needle' in: $text", text.contains(needle))
    }
}

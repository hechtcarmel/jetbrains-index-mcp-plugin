package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindClassTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NavigationFiltersIntegrationTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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

    fun testTypeHierarchyRespectsProjectProductionFilesScope() = runBlocking {
        val fixture = createLibraryInterfaceFixture()
        val tool = TypeHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("className", fixture.interfaceFqn)
            put("scope", "project_production_files")
        })

        assertFalse("Type hierarchy should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val hierarchy = json.decodeFromString<TypeHierarchyResult>(content.text)
        val subtypeNames = hierarchy.subtypes.map { it.name }

        assertTrue("Production implementation should remain visible", subtypeNames.any { it.contains("ProdRepositoryImpl") })
        assertFalse("Test implementation should be filtered out by project_production_files", subtypeNames.any { it.contains("TestRepositoryImpl") })
        assertFalse("Library implementation should be filtered out by project_production_files", subtypeNames.any { it.contains("LibraryRepositoryImpl") })
    }

    fun testFindImplementationsRespectsProjectTestFilesScope() = runBlocking {
        val fixture = createLibraryInterfaceFixture()
        val (line, column) = findPosition(fixture.interfaceSource, "ExternalRepository")
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", fixture.interfaceFile.toString().replace('\\', '/'))
            put("line", line)
            put("column", column)
            put("scope", "project_test_files")
        })

        assertFalse("Find implementations should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val implementations = json.decodeFromString<ImplementationResult>(content.text)
        val implementationNames = implementations.implementations.map { it.name }

        assertFalse("Production implementation should be filtered out by project_test_files", implementationNames.any { it.contains("ProdRepositoryImpl") })
        assertTrue("Test implementation should remain visible", implementationNames.any { it.contains("TestRepositoryImpl") })
        assertFalse("Library implementation should be filtered out by project_test_files", implementationNames.any { it.contains("LibraryRepositoryImpl") })
    }

    fun testFindClassRespectsProjectTestFilesScope() = runBlocking {
        createLibraryInterfaceFixture()
        val tool = FindClassTool()

        val result = tool.execute(project, buildJsonObject {
            put("query", "RepositoryImpl")
            put("scope", "project_test_files")
        })

        assertFalse("Find class should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val classes = json.decodeFromString<FindClassResult>(content.text)
        val classNames = classes.classes.map { it.name }

        assertTrue("Primary test implementation should remain visible", classNames.any { it == "TestRepositoryImpl" })
        assertTrue("Secondary test implementation should remain visible", classNames.any { it == "MoreTestRepositoryImpl" })
        assertFalse("Production implementation should be filtered out by project_test_files", classNames.any { it == "ProdRepositoryImpl" })
        assertFalse("Library implementation should be filtered out by project_test_files", classNames.any { it == "LibraryRepositoryImpl" })
    }

    fun testFindSymbolRespectsProjectTestFilesScopeAcrossPagination() = runBlocking {
        createLibraryInterfaceFixture()
        val tool = FindSymbolTool()

        val firstPage = tool.execute(project, buildJsonObject {
            put("query", "fetch")
            put("scope", "project_test_files")
            put("pageSize", 1)
        })

        assertFalse("Find symbol first page should succeed: ${firstPage.content}", firstPage.isError)

        val firstContent = firstPage.content.first() as ContentBlock.Text
        val firstResult = json.decodeFromString<FindSymbolResult>(firstContent.text)
        val aggregatedFiles = firstResult.symbols.map { it.file }.toMutableList()
        var nextCursor = firstResult.nextCursor

        while (nextCursor != null) {
            val nextPage = tool.execute(project, buildJsonObject {
                put("cursor", nextCursor)
                put("pageSize", 1)
            })
            assertFalse("Find symbol page for cursor $nextCursor should succeed: ${nextPage.content}", nextPage.isError)
            val nextContent = nextPage.content.first() as ContentBlock.Text
            val nextResult = json.decodeFromString<FindSymbolResult>(nextContent.text)
            aggregatedFiles += nextResult.symbols.map { it.file }
            nextCursor = nextResult.nextCursor
        }

        assertTrue("Expected test-symbol results to be returned", aggregatedFiles.isNotEmpty())
        assertTrue("Primary test implementation method should be visible", aggregatedFiles.any { it.endsWith("TestRepositoryImpl.java") })
        assertTrue("Secondary test implementation method should be visible", aggregatedFiles.any { it.endsWith("MoreTestRepositoryImpl.java") })
        assertFalse("Production methods should be filtered out by project_test_files", aggregatedFiles.any { it.endsWith("ProdRepositoryImpl.java") })
        assertFalse("Library methods should be filtered out by project_test_files", aggregatedFiles.any { it.endsWith("LibraryRepositoryImpl.java") })
    }

    fun testFindSymbolDoesNotApplyLegacyExcludedPathFilteringWithinScope() = runBlocking {
        val fixture = createExcludedPathScopeProbeFixture()
        val classTool = FindClassTool()
        val fileTool = FindFileTool()
        val symbolTool = FindSymbolTool()

        val classResult = classTool.execute(project, buildJsonObject {
            put("query", fixture.className)
            put("matchMode", "exact")
            put("scope", "project_production_files")
        })
        assertFalse("Find class should succeed: ${classResult.content}", classResult.isError)
        val classContent = classResult.content.first() as ContentBlock.Text
        val classes = json.decodeFromString<FindClassResult>(classContent.text)
        assertTrue(
            "Class search should include the probe inside project_production_files",
            classes.classes.any { it.name == fixture.className && it.file.endsWith(fixture.relativePath) }
        )

        val fileResult = fileTool.execute(project, buildJsonObject {
            put("query", fixture.fileName)
            put("scope", "project_production_files")
        })
        assertFalse("Find file should succeed: ${fileResult.content}", fileResult.isError)
        val fileContent = fileResult.content.first() as ContentBlock.Text
        val files = json.decodeFromString<FindFileResult>(fileContent.text)
        assertTrue(
            "File search should include the probe inside project_production_files",
            files.files.any { it.name == fixture.fileName && it.path.endsWith(fixture.relativePath) }
        )

        val symbolResult = symbolTool.execute(project, buildJsonObject {
            put("query", fixture.className)
            put("matchMode", "exact")
            put("scope", "project_production_files")
        })
        assertFalse("Find symbol should succeed: ${symbolResult.content}", symbolResult.isError)
        val symbolContent = symbolResult.content.first() as ContentBlock.Text
        val symbols = json.decodeFromString<FindSymbolResult>(symbolContent.text)
        assertTrue(
            "Symbol search should include files accepted by the selected built-in scope even under a venv path",
            symbols.symbols.any { it.name == fixture.className && it.file.endsWith(fixture.relativePath) }
        )
    }

    fun testCallHierarchyRespectsProjectProductionFilesScope() = runBlocking {
        val fixture = createProjectMethodFixture()
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", fixture.targetFilePath)
            put("line", fixture.targetLine)
            put("column", fixture.targetColumn)
            put("direction", "callers")
            put("scope", "project_production_files")
        })

        assertFalse("Call hierarchy should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val hierarchy = json.decodeFromString<CallHierarchyResult>(content.text)
        val callerNames = hierarchy.calls.map { it.name }

        assertTrue("Production caller should remain visible", callerNames.any { it.contains("ProdCaller.call") })
        assertFalse("Test caller should be filtered out by project_production_files", callerNames.any { it.contains("TargetServiceTest.exercise") })
    }

    fun testCallHierarchyRespectsProjectAndLibrariesScope() = runBlocking {
        val fixture = createLibraryMethodFixture()
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", fixture.targetFile.toString().replace('\\', '/'))
            put("line", fixture.targetLine)
            put("column", fixture.targetColumn)
            put("direction", "callers")
            put("scope", "project_and_libraries")
        })

        assertFalse("Call hierarchy should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val hierarchy = json.decodeFromString<CallHierarchyResult>(content.text)
        val callerNames = hierarchy.calls.map { it.name }

        assertTrue("Project caller should be visible", callerNames.any { it.contains("ProjectCaller.call") })
        assertTrue("Library caller should be visible when scope includes libraries", callerNames.any { it.contains("LibraryCaller.call") })
    }

    fun testFindReferencesRespectsProjectAndLibrariesScope() = runBlocking {
        val fixture = createLibraryMethodFixture()
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", fixture.targetFile.toString().replace('\\', '/'))
            put("line", fixture.targetLine)
            put("column", fixture.targetColumn)
            put("scope", "project_and_libraries")
        })

        assertFalse("Find references should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val usages = json.decodeFromString<FindUsagesResult>(content.text)
        val usageFiles = usages.usages.map { it.file }

        assertTrue("Project usage should remain visible", usageFiles.any { it.endsWith("ProjectCaller.java") })
        assertTrue("Library usage should remain visible when scope includes libraries", usageFiles.any { it.contains("LibraryCaller.java") })
    }

    private data class LibraryInterfaceFixture(
        val interfaceFqn: String,
        val interfaceFile: Path,
        val interfaceSource: String
    )

    private data class ProjectMethodFixture(
        val targetFilePath: String,
        val targetLine: Int,
        val targetColumn: Int
    )

    private data class LibraryMethodFixture(
        val targetFile: Path,
        val targetLine: Int,
        val targetColumn: Int
    )

    private data class ExcludedPathScopeProbeFixture(
        val className: String,
        val fileName: String,
        val relativePath: String
    )

    private fun createLibraryInterfaceFixture(): LibraryInterfaceFixture {
        val prodRootPath = createProjectDirectory("prod-src")
        val testRootPath = createProjectDirectory("test-src")
        val prodRoot = refreshVfsDirectory(prodRootPath)
        val testRoot = refreshVfsDirectory(testRootPath)
        PsiTestUtil.addSourceRoot(module, prodRoot, false)
        PsiTestUtil.addSourceRoot(module, testRoot, true)

        val librarySourceRoot = Files.createTempDirectory("jetbrains-index-mcp-lib-src")
        val libraryClassesRoot = Files.createTempDirectory("jetbrains-index-mcp-lib-classes")
        val interfaceSource = """
            package libpkg;

            public interface ExternalRepository {
                String fetch();
            }
        """.trimIndent()
        val libraryImplSource = """
            package libpkg;

            public class LibraryRepositoryImpl implements ExternalRepository {
                @Override
                public String fetch() {
                    return "library";
                }
            }
        """.trimIndent()

        val interfaceFile = writePathFile(librarySourceRoot, "libpkg/ExternalRepository.java", interfaceSource)
        val libraryImplFile = writePathFile(librarySourceRoot, "libpkg/LibraryRepositoryImpl.java", libraryImplSource)
        compileJavaSources(listOf(interfaceFile, libraryImplFile), libraryClassesRoot)

        val sourceRootVFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(librarySourceRoot)
        val classesRootVFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryClassesRoot)
        assertNotNull("Library source root should resolve in VFS", sourceRootVFile)
        assertNotNull("Library classes root should resolve in VFS", classesRootVFile)
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            "external-repository-library",
            listOf(classesRootVFile!!.url),
            listOf(sourceRootVFile!!.url)
        )

        val prodImplFile = writePathFile(
            prodRootPath,
            "app/ProdRepositoryImpl.java",
            """
                package app;

                import libpkg.ExternalRepository;

                public class ProdRepositoryImpl implements ExternalRepository {
                    @Override
                    public String fetch() {
                        return "prod";
                    }
                }
            """.trimIndent()
        )
        val testImplFile = writePathFile(
            testRootPath,
            "app/TestRepositoryImpl.java",
            """
                package app;

                import libpkg.ExternalRepository;

                public class TestRepositoryImpl implements ExternalRepository {
                    @Override
                    public String fetch() {
                        return "test";
                    }
                }
            """.trimIndent()
        )
        val secondTestImplFile = writePathFile(
            testRootPath,
            "app/MoreTestRepositoryImpl.java",
            """
                package app;

                import libpkg.ExternalRepository;

                public class MoreTestRepositoryImpl implements ExternalRepository {
                    @Override
                    public String fetch() {
                        return "test-more";
                    }
                }
            """.trimIndent()
        )

        refreshVfsFile(prodImplFile)
        refreshVfsFile(testImplFile)
        refreshVfsFile(secondTestImplFile)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return LibraryInterfaceFixture(
            interfaceFqn = "libpkg.ExternalRepository",
            interfaceFile = interfaceFile,
            interfaceSource = interfaceSource
        )
    }

    private fun createProjectMethodFixture(): ProjectMethodFixture {
        val prodRootPath = createProjectDirectory("callers-src")
        val testRootPath = createProjectDirectory("callers-test")
        val prodRoot = refreshVfsDirectory(prodRootPath)
        val testRoot = refreshVfsDirectory(testRootPath)
        PsiTestUtil.addSourceRoot(module, prodRoot, false)
        PsiTestUtil.addSourceRoot(module, testRoot, true)

        val targetSource = """
            package callers;

            public class TargetService {
                public void run() {
                }
            }
        """.trimIndent()
        val targetFile = writePathFile(prodRootPath, "callers/TargetService.java", targetSource)

        val prodCallerFile = writePathFile(
            prodRootPath,
            "callers/ProdCaller.java",
            """
                package callers;

                public class ProdCaller {
                    public void call(TargetService service) {
                        service.run();
                    }
                }
            """.trimIndent()
        )
        val testCallerFile = writePathFile(
            testRootPath,
            "callers/TargetServiceTest.java",
            """
                package callers;

                public class TargetServiceTest {
                    public void exercise(TargetService service) {
                        service.run();
                    }
                }
            """.trimIndent()
        )

        refreshVfsFile(targetFile)
        refreshVfsFile(prodCallerFile)
        refreshVfsFile(testCallerFile)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val targetVirtualFile = refreshVfsFile(targetFile)
        val targetPsiFile = requireNotNull(PsiManager.getInstance(project).findFile(targetVirtualFile)) {
            "Expected PSI file for $targetFile"
        }
        val targetDocument = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(targetPsiFile)) {
            "Expected document for $targetFile"
        }
        val offset = targetDocument.text.indexOf("run")
        assertTrue("Target method name should exist in fixture document", offset >= 0)
        val line = targetDocument.getLineNumber(offset) + 1
        val column = offset - targetDocument.getLineStartOffset(line - 1) + 1

        return ProjectMethodFixture(
            targetFilePath = ProjectUtils.getRelativePath(project, targetFile.toString().replace('\\', '/')),
            targetLine = line,
            targetColumn = column
        )
    }

    private fun createLibraryMethodFixture(): LibraryMethodFixture {
        val prodRootPath = createProjectDirectory("library-callers-src")
        val prodRoot = refreshVfsDirectory(prodRootPath)
        PsiTestUtil.addSourceRoot(module, prodRoot, false)

        val librarySourceRoot = Files.createTempDirectory("jetbrains-index-mcp-lib-callers-src")
        val libraryClassesRoot = Files.createTempDirectory("jetbrains-index-mcp-lib-callers-classes")
        val targetSource = """
            package libcallers;

            public class TargetApi {
                public void run() {
                }
            }
        """.trimIndent()
        val libraryCallerSource = """
            package libcallers;

            public class LibraryCaller {
                public void call(TargetApi api) {
                    api.run();
                }
            }
        """.trimIndent()

        val targetFile = writePathFile(librarySourceRoot, "libcallers/TargetApi.java", targetSource)
        val libraryCallerFile = writePathFile(librarySourceRoot, "libcallers/LibraryCaller.java", libraryCallerSource)
        compileJavaSources(listOf(targetFile, libraryCallerFile), libraryClassesRoot)

        val sourceRootVFile = refreshVfsDirectory(librarySourceRoot)
        val classesRootVFile = refreshVfsDirectory(libraryClassesRoot)
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            "library-callers-library",
            listOf(classesRootVFile.url),
            listOf(sourceRootVFile.url)
        )

        val projectCallerFile = writePathFile(
            prodRootPath,
            "app/ProjectCaller.java",
            """
                package app;

                import libcallers.TargetApi;

                public class ProjectCaller {
                    public void call(TargetApi api) {
                        api.run();
                    }
                }
            """.trimIndent()
        )

        refreshVfsFile(targetFile)
        refreshVfsFile(libraryCallerFile)
        refreshVfsFile(projectCallerFile)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val (line, column) = findPosition(targetSource, "run")
        return LibraryMethodFixture(
            targetFile = targetFile,
            targetLine = line,
            targetColumn = column
        )
    }

    private fun createExcludedPathScopeProbeFixture(): ExcludedPathScopeProbeFixture {
        val prodRootPath = createProjectDirectory("excluded-path-src")
        val prodRoot = refreshVfsDirectory(prodRootPath)
        PsiTestUtil.addSourceRoot(module, prodRoot, false)

        val className = "VenvScopeProbe"
        val relativePath = "excluded-path-src/venv/excludedpath/$className.java"
        val source = """
            package excludedpath;

            public class $className {
                public String marker() {
                    return "scope-probe";
                }
            }
        """.trimIndent()

        val probeFile = writePathFile(prodRootPath, "venv/excludedpath/$className.java", source)
        refreshVfsFile(probeFile)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return ExcludedPathScopeProbeFixture(
            className = className,
            fileName = "$className.java",
            relativePath = relativePath
        )
    }

    private fun createProjectDirectory(relativePath: String): Path {
        val projectRoot = Path.of(requireNotNull(project.basePath) { "Project base path should be available in tests" })
        return Files.createDirectories(projectRoot.resolve(relativePath))
    }

    private fun refreshVfsDirectory(path: Path): VirtualFile {
        return requireNotNull(refreshVfsPath(path)) {
            "Expected VFS directory for $path"
        }
    }

    private fun refreshVfsFile(path: Path): VirtualFile {
        return requireNotNull(refreshVfsPath(path)) {
            "Expected VFS file for $path"
        }
    }

    private fun refreshVfsPath(path: Path): VirtualFile? {
        val localFileSystem = LocalFileSystem.getInstance()
        val normalizedPath = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        return localFileSystem.refreshAndFindFileByNioFile(path)
            ?: localFileSystem.refreshAndFindFileByPath(normalizedPath)
            ?: localFileSystem.findFileByPath(normalizedPath)
    }

    private fun writePathFile(root: Path, relativePath: String, text: String): Path {
        val file = root.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    private fun compileJavaSources(sourceFiles: List<Path>, outputDir: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val args = buildList {
            add("-d")
            add(outputDir.toString())
            sourceFiles.forEach { add(it.toString()) }
        }

        val exitCode = if (compiler != null) {
            compiler.run(null, null, null, *args.toTypedArray())
        } else {
            val javaHome = System.getenv("JAVA_HOME")
            assertNotNull("Tests require a JDK with javac available", javaHome)

            val javac = Path.of(javaHome!!, "bin", "javac").toString()
            val process = ProcessBuilder(listOf(javac) + args)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText()
                val completed = process.waitFor()
                assertEquals("javac should compile library sources successfully. Output: $output", 0, completed)
            }
            0
        }

        assertEquals("Library sources should compile successfully", 0, exitCode)
    }

    private fun findPosition(text: String, needle: String): Pair<Int, Int> {
        val offset = text.indexOf(needle)
        assertTrue("Needle '$needle' should exist in fixture text", offset >= 0)

        val before = text.substring(0, offset)
        val line = before.count { it == '\n' } + 1
        val column = offset - before.lastIndexOf('\n').let { if (it == -1) -1 else it }
        return line to column
    }
}

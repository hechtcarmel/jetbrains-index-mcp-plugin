package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
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

    fun testTypeHierarchyCanExcludeLibrariesAndTests() = runBlocking {
        val fixture = createLibraryInterfaceFixture()
        val tool = TypeHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("className", fixture.interfaceFqn)
            put("includeLibraries", false)
            put("includeTests", false)
        })

        assertFalse("Type hierarchy should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val hierarchy = json.decodeFromString<TypeHierarchyResult>(content.text)
        val subtypeNames = hierarchy.subtypes.map { it.name }

        assertTrue("Production implementation should remain visible", subtypeNames.any { it.contains("ProdRepositoryImpl") })
        assertFalse("Test implementation should be filtered out", subtypeNames.any { it.contains("TestRepositoryImpl") })
        assertFalse("Library implementation should be filtered out", subtypeNames.any { it.contains("LibraryRepositoryImpl") })
    }

    fun testFindImplementationsCanExcludeLibrariesAndTests() = runBlocking {
        val fixture = createLibraryInterfaceFixture()
        val (line, column) = findPosition(fixture.interfaceSource, "ExternalRepository")
        val tool = FindImplementationsTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", fixture.interfaceFile.toString().replace('\\', '/'))
            put("line", line)
            put("column", column)
            put("includeLibraries", false)
            put("includeTests", false)
        })

        assertFalse("Find implementations should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val implementations = json.decodeFromString<ImplementationResult>(content.text)
        val implementationNames = implementations.implementations.map { it.name }

        assertTrue("Production implementation should remain visible", implementationNames.any { it.contains("ProdRepositoryImpl") })
        assertFalse("Test implementation should be filtered out", implementationNames.any { it.contains("TestRepositoryImpl") })
        assertFalse("Library implementation should be filtered out", implementationNames.any { it.contains("LibraryRepositoryImpl") })
    }

    fun testCallHierarchyCanExcludeTestCallers() = runBlocking {
        val fixture = createProjectMethodFixture()
        val tool = CallHierarchyTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", fixture.targetFilePath)
            put("line", fixture.targetLine)
            put("column", fixture.targetColumn)
            put("direction", "callers")
            put("includeTests", false)
        })

        assertFalse("Call hierarchy should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val hierarchy = json.decodeFromString<CallHierarchyResult>(content.text)
        val callerNames = hierarchy.calls.map { it.name }

        assertTrue("Production caller should remain visible", callerNames.any { it.contains("ProdCaller.call") })
        assertFalse("Test caller should be filtered out", callerNames.any { it.contains("TargetServiceTest.exercise") })
    }

    fun testFindReferencesCanExcludeTestUsages() = runBlocking {
        val fixture = createProjectMethodFixture()
        val tool = FindUsagesTool()

        val result = tool.execute(project, buildJsonObject {
            put("file", fixture.targetFilePath)
            put("line", fixture.targetLine)
            put("column", fixture.targetColumn)
            put("includeTests", false)
        })

        assertFalse("Find references should succeed: ${result.content}", result.isError)

        val content = result.content.first() as ContentBlock.Text
        val usages = json.decodeFromString<FindUsagesResult>(content.text)
        val usageFiles = usages.usages.map { it.file }

        assertTrue("Production usage should remain visible", usageFiles.any { it.endsWith("ProdCaller.java") })
        assertFalse("Test usage should be filtered out", usageFiles.any { it.endsWith("TargetServiceTest.java") })
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

        refreshVfsFile(prodImplFile)
        refreshVfsFile(testImplFile)
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

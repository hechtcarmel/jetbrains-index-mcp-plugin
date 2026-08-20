package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.ReadFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ReadFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

/**
 * Integration tests for tool execution end-to-end.
 * Tests each navigation, intelligence, and project tool with realistic scenarios.
 */
class ToolExecutionIntegrationTest : McpPlatformTestCase() {

    private companion object {
        const val DEFINITION_SRC = "definition-src"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Navigation Tools Tests

    fun testFindUsagesToolEndToEnd() = runBlocking {
        val tool = FindUsagesTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isFailure)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isFailure)
    }

    fun testFindDefinitionToolEndToEnd() = runBlocking {
        val tool = FindDefinitionTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isFailure)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isFailure)
    }

    fun testFindDefinitionToolFullElementPreview() = runBlocking {
        registerSourceRoot(DEFINITION_SRC)
        val serviceSource = """
            package definitionpkg;

            public class Service {
                public void doWork() {
                    System.out.println("done");
                }
            }
        """.trimIndent()
        val callerSource = """
            package definitionpkg;

            public class Caller {
                private Service service = new Service();
                public void call() {
                    service.doWork();
                }
            }
        """.trimIndent()
        writeProjectFile("$DEFINITION_SRC/definitionpkg/Service.java", serviceSource)
        writeProjectFile("$DEFINITION_SRC/definitionpkg/Caller.java", callerSource)

        val (callLine, callColumn) = findPosition(callerSource, "doWork")
        val result = FindDefinitionTool().execute(project, buildJsonObject {
            put("file", "$DEFINITION_SRC/definitionpkg/Caller.java")
            put("line", callLine)
            put("column", callColumn)
            put("fullElementPreview", true)
        })

        assertToolSucceeded("Cross-file definition lookup should succeed", result)
        val definition = json.decodeFromString<DefinitionResult>(toolText(result))

        val (declarationLine, declarationColumn) = findPosition(serviceSource, "doWork")
        assertEquals("$DEFINITION_SRC/definitionpkg/Service.java", definition.file)
        assertEquals("doWork", definition.symbolName)
        assertEquals(declarationLine, definition.line)
        assertEquals(declarationColumn, definition.column)
        assertEquals("astPath should contain enclosing class", listOf("Service"), definition.astPath)

        // fullElementPreview must return the declaration verbatim. The default preview returns
        // surrounding document lines prefixed with "<line>: ", so an exact match is what
        // distinguishes the two modes.
        assertEquals(
            """
            public void doWork() {
                    System.out.println("done");
                }
            """.trimIndent(),
            definition.preview
        )
    }

    fun testFindDefinitionToolDefaultPreviewIncludesDefinitionOnLastFileLine() = runBlocking {
        val sourceRoot = "definition-lastline-src"
        registerSourceRoot(sourceRoot)
        // The entire definition file is a single line: the definition sits on the file's
        // last line, which the old exclusive lineCount-1 bound could never render — the
        // tool returned an empty preview for a successful lookup.
        val tailSource = "public class Tail { public static void work() {} }"
        val callerSource = """
            public class TailCaller {
                void call() {
                    Tail.work();
                }
            }
        """.trimIndent()
        writeProjectFile("$sourceRoot/Tail.java", tailSource)
        writeProjectFile("$sourceRoot/TailCaller.java", callerSource)

        val (callLine, callColumn) = findPosition(callerSource, "work")
        val result = FindDefinitionTool().execute(project, buildJsonObject {
            put("file", "$sourceRoot/TailCaller.java")
            put("line", callLine)
            put("column", callColumn)
        })

        assertToolSucceeded("Definition lookup should succeed", result)
        val definition = json.decodeFromString<DefinitionResult>(toolText(result))
        assertEquals("$sourceRoot/Tail.java", definition.file)
        assertEquals("work", definition.symbolName)
        assertEquals("1: $tailSource", definition.preview)
    }

    fun testFindDefinitionToolDefaultPreviewShowsLastLineDefinitionWithPrecedingContext() = runBlocking {
        val sourceRoot = "definition-lastline-multiline-src"
        registerSourceRoot(sourceRoot)
        // Definition on the last line of a multi-line file: the preview used to show only
        // the preceding line(s) and silently omit the definition itself.
        val helperSource = "public class TailHelper {\n    public static void assist() {} }"
        val callerSource = """
            public class TailHelperCaller {
                void call() {
                    TailHelper.assist();
                }
            }
        """.trimIndent()
        writeProjectFile("$sourceRoot/TailHelper.java", helperSource)
        writeProjectFile("$sourceRoot/TailHelperCaller.java", callerSource)

        val (callLine, callColumn) = findPosition(callerSource, "assist")
        val result = FindDefinitionTool().execute(project, buildJsonObject {
            put("file", "$sourceRoot/TailHelperCaller.java")
            put("line", callLine)
            put("column", callColumn)
        })

        assertToolSucceeded("Definition lookup should succeed", result)
        val definition = json.decodeFromString<DefinitionResult>(toolText(result))
        assertEquals("$sourceRoot/TailHelper.java", definition.file)
        assertEquals(
            "1: public class TailHelper {\n2:     public static void assist() {} }",
            definition.preview
        )
    }

    fun testReadFileToolValidation() = runBlocking {
        val tool = ReadFileTool()

        val missing = tool.execute(project, buildJsonObject { })
        assertTrue("Missing file/qualifiedName should error", missing.isFailure)

        val endLineOnly = tool.execute(project, buildJsonObject {
            put("file", "Test.java")
            put("endLine", 2)
        })
        assertTrue("endLine without startLine should error", endLineOnly.isFailure)

        val invalidRange = tool.execute(project, buildJsonObject {
            put("file", "Test.java")
            put("startLine", 3)
            put("endLine", 2)
        })
        assertTrue("endLine < startLine should error", invalidRange.isFailure)

        val invalidStart = tool.execute(project, buildJsonObject {
            put("file", "Test.java")
            put("startLine", 0)
            put("endLine", 1)
        })
        assertTrue("startLine < 1 should error", invalidStart.isFailure)
    }

    fun testReadFileToolReadsLinesAndMetadata() = runBlocking {
        val basePath = project.basePath?.let { File(it) }
        val readmeFile = if (basePath != null && basePath.exists()) {
            File(basePath, "ReadMe.java")
        } else {
            Files.createTempFile("jetbrains-index-mcp", "ReadMe.java").toFile()
        }
        Files.writeString(readmeFile.toPath(), "line1\nline2\nline3\nline4")

        val tool = ReadFileTool()
        val result = tool.execute(project, buildJsonObject {
            val fileArg = if (basePath != null && basePath.exists()) "ReadMe.java" else readmeFile.absolutePath
            put("file", fileArg)
            put("startLine", 2)
            put("endLine", 3)
        })

        assertFalse("Should succeed for valid file", result.isFailure)
        val content = result.content.first() as TextContent
        val readFile = json.decodeFromString<ReadFileResult>(content.text)

        assertTrue("Resolved path should end with filename", readFile.file.endsWith("ReadMe.java"))
        assertEquals("line2\nline3", readFile.content)
        assertEquals(4, readFile.lineCount)
        assertEquals(2, readFile.startLine)
        assertEquals(3, readFile.endLine)
        if (basePath != null && basePath.exists()) {
            assertFalse("Project files should not be marked as library", readFile.isLibraryFile)
        }

        val singleLine = tool.execute(project, buildJsonObject {
            val fileArg = if (basePath != null && basePath.exists()) "ReadMe.java" else readmeFile.absolutePath
            put("file", fileArg)
            put("startLine", 4)
        })
        assertFalse("Single-line read should succeed", singleLine.isFailure)
        val singleContent = singleLine.content.first() as TextContent
        val singleResult = json.decodeFromString<ReadFileResult>(singleContent.text)
        assertEquals("line4", singleResult.content)
        assertEquals(4, singleResult.startLine)
        assertEquals(4, singleResult.endLine)
    }

    fun testFindFileToolPreservesAbsolutePathForLibrarySources() = runBlocking {
        Assume.assumeTrue("Index must be ready for library file search", !DumbService.isDumb(project))

        val libraryRoot = Files.createTempDirectory("jetbrains-index-mcp-lib")
        val packageDir = Files.createDirectories(libraryRoot.resolve("libpkg"))
        val className = "ExternalLib${System.nanoTime().toString().takeLast(8)}"
        val libraryFile = packageDir.resolve("$className.java")
        Files.writeString(
            libraryFile,
            """
            package libpkg;

            public class $className {}
            """.trimIndent()
        )

        val libraryRootVFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRoot)
        assertNotNull("Library root should resolve in VFS", libraryRootVFile)
        ModuleRootModificationUtil.addModuleLibrary(module, "external-library-src", emptyList(), listOf(libraryRootVFile!!.url))
        DumbService.getInstance(project).waitForSmartMode()

        val tool = FindFileTool()
        val projectOnlyResult = tool.execute(project, buildJsonObject {
            put("query", "$className.java")
            put("scope", "project_files")
        })

        assertFalse("Project-only file search should succeed", projectOnlyResult.isFailure)
        val projectOnlyContent = projectOnlyResult.content.first() as TextContent
        val projectOnlyMatches = json.decodeFromString<FindFileResult>(projectOnlyContent.text)
        assertNull(
            "Library file should not be returned when scope excludes libraries",
            projectOnlyMatches.files.firstOrNull { it.name == "$className.java" }
        )

        val result = tool.execute(project, buildJsonObject {
            put("query", "$className.java")
            put("scope", "project_and_libraries")
        })

        assertFalse("Library file search should succeed", result.isFailure)
        val content = result.content.first() as TextContent
        val findFile = json.decodeFromString<FindFileResult>(content.text)
        val match = findFile.files.firstOrNull { it.name == "$className.java" }
        assertNotNull("Library file should be returned when scope includes libraries", match)
        assertEquals(
            "External library paths should remain absolute",
            libraryFile.toString().replace('\\', '/'),
            match!!.path
        )
    }

    fun testFindDefinitionToolResolvesLibrarySourceByAbsolutePath() = runBlocking {
        val libraryRoot = Files.createTempDirectory("jetbrains-index-mcp-lib")
        val packageDir = Files.createDirectories(libraryRoot.resolve("libpkg"))
        val className = "ExternalLib${System.nanoTime().toString().takeLast(8)}"
        val libraryFile = packageDir.resolve("$className.java")
        Files.writeString(
            libraryFile,
            """
            package libpkg;

            public class $className {
                public void ping() {}
            }
            """.trimIndent()
        )

        val libraryRootVFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRoot)
        assertNotNull("Library root should resolve in VFS", libraryRootVFile)
        ModuleRootModificationUtil.addModuleLibrary(module, "external-library-src", emptyList(), listOf(libraryRootVFile!!.url))
        DumbService.getInstance(project).waitForSmartMode()

        val tool = FindDefinitionTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", libraryFile.toString().replace('\\', '/'))
            put("line", 3)
            put("column", 14)
        })

        assertFalse("Library source definition lookup should succeed", result.isFailure)
        val content = result.content.first() as TextContent
        val definition = json.decodeFromString<DefinitionResult>(content.text)
        assertEquals(libraryFile.toString().replace('\\', '/'), definition.file)
        assertEquals(className, definition.symbolName)
    }

    fun testFindDefinitionToolStillRejectsUnrelatedExternalFiles() = runBlocking {
        val unrelatedFile = Files.createTempFile("jetbrains-index-mcp-unrelated", ".java")
        Files.writeString(
            unrelatedFile,
            """
            public class Unrelated {}
            """.trimIndent()
        )
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(unrelatedFile)

        val tool = FindDefinitionTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", unrelatedFile.toString().replace('\\', '/'))
            put("line", 1)
            put("column", 14)
        })

        assertTrue("Unrelated external files must remain inaccessible", result.isFailure)
    }

    fun testFindUsagesToolFindsProjectUsagesFromLibrarySourcePath() = runBlocking {
        Assume.assumeTrue("Index must be ready for library-source usage search", !DumbService.isDumb(project))

        val className = "ExternalLib${System.nanoTime().toString().takeLast(8)}"
        val sourceRoot = Files.createTempDirectory("jetbrains-index-mcp-lib-src")
        val classesRoot = Files.createTempDirectory("jetbrains-index-mcp-lib-classes")
        val packageDir = Files.createDirectories(sourceRoot.resolve("libpkg"))
        val libraryFile = packageDir.resolve("$className.java")
        val librarySource = """
            package libpkg;

            public class $className {
                public static void ping() {}
            }
        """.trimIndent()
        Files.writeString(libraryFile, librarySource)
        compileJavaSource(libraryFile, classesRoot)

        val sourceRootVFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceRoot)
        val classesRootVFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(classesRoot)
        assertNotNull("Library source root should resolve in VFS", sourceRootVFile)
        assertNotNull("Library classes root should resolve in VFS", classesRootVFile)
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            "external-library-bin-and-src",
            listOf(classesRootVFile!!.url),
            listOf(sourceRootVFile!!.url)
        )

        myFixture.addFileToProject(
            "UseExternalLib.java",
            """
            import libpkg.$className;

            public class UseExternalLib {
                public void call() {
                    $className.ping();
                }
            }
            """.trimIndent()
        )

        DumbService.getInstance(project).waitForSmartMode()

        val (line, column) = findPosition(librarySource, "ping")
        val tool = FindUsagesTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", libraryFile.toString().replace('\\', '/'))
            put("line", line)
            put("column", column)
        })

        assertFalse("Library-source usages lookup should succeed", result.isFailure)
        val content = result.content.first() as TextContent
        val usages = json.decodeFromString<FindUsagesResult>(content.text)
        assertTrue(
            "Project usage should be found from external library declaration",
            usages.usages.any { it.file.endsWith("UseExternalLib.java") && it.context.contains("$className.ping()") }
        )
    }

    fun testTypeHierarchyToolEndToEnd() = runBlocking {
        val tool = TypeHierarchyTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing className", resultMissing.isFailure)

        // Test with invalid class
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("className", "com.nonexistent.InvalidClass")
        })
        assertTrue("Should error with invalid class", resultInvalid.isFailure)
    }

    fun testCallHierarchyToolEndToEnd() = runBlocking {
        val tool = CallHierarchyTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isFailure)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isFailure)
    }

    fun testFindImplementationsToolEndToEnd() = runBlocking {
        val tool = FindImplementationsTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isFailure)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isFailure)
    }

    // Intelligence Tools Tests

    fun testGetDiagnosticsToolEndToEnd() = runBlocking {
        val tool = GetDiagnosticsTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isFailure)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
        })
        assertTrue("Should error with invalid file", resultInvalid.isFailure)
    }

    // Project Tools Tests

    fun testGetIndexStatusToolEndToEnd() = runBlocking {
        val tool = GetIndexStatusTool()

        val result = tool.execute(project, buildJsonObject { })

        assertFalse("get_index_status should succeed", result.isFailure)
        assertTrue("Should have content", result.content.isNotEmpty())

        val content = result.content.first()
        assertTrue("Content should be text", content is TextContent)

        val textContent = (content as TextContent).text
        val resultJson = json.parseToJsonElement(textContent).jsonObject

        assertNotNull("Result should have isDumbMode", resultJson["isDumbMode"])
        assertNotNull("Result should have isIndexing", resultJson["isIndexing"])
    }

    // Tool Registry Integration Tests

    fun testAllToolsRegistered() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val expectedTools = mutableListOf(
            // Navigation tools
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
            ToolNames.SYMBOL_INFO,
            ToolNames.TYPE_HIERARCHY,
            ToolNames.CALL_HIERARCHY,
            ToolNames.FIND_IMPLEMENTATIONS,
            ToolNames.FIND_SYMBOL,
            ToolNames.FIND_SUPER_METHODS,
            ToolNames.FILE_STRUCTURE,
            // Fast search tools
            ToolNames.FIND_CLASS,
            ToolNames.FIND_FILE,
            ToolNames.READ_FILE,
            ToolNames.SEARCH_TEXT,
            // Intelligence tools
            ToolNames.DIAGNOSTICS,
            ToolNames.PROJECT_DIAGNOSTICS,
            // Project tools
            ToolNames.BUILD_PROJECT,
            ToolNames.CREATE_MODULE,
            ToolNames.LINK_BUILD_SYSTEM,
            ToolNames.INDEX_STATUS,
            ToolNames.SYNC_FILES,
            ToolNames.RUN_TESTS,
            // Refactoring tools
            ToolNames.REFACTOR_RENAME,
            ToolNames.REFACTOR_MOVE,
            ToolNames.REFACTOR_SAFE_DELETE,
            ToolNames.REFORMAT_CODE,
            ToolNames.OPTIMIZE_IMPORTS,
            // Advanced refactoring tools
            ToolNames.STRUCTURAL_SEARCH_REPLACE,
            ToolNames.CHANGE_SIGNATURE,
            ToolNames.CREATE_FILE,
            // Code editing tools
            ToolNames.EDIT_MEMBER,
            ToolNames.INSERT_MEMBER,
            ToolNames.REPLACE_MEMBER,
            // Editor tools
            ToolNames.GET_ACTIVE_FILE,
            ToolNames.OPEN_FILE,
            // Plugin dev tools
            ToolNames.INSTALL_PLUGIN,
            ToolNames.REPLACE_TEXT_IN_FILE,
            ToolNames.RESTART_IDE,
            // Project window management tools
            ToolNames.CLOSE_PROJECT,
            ToolNames.OPEN_PROJECT,
            ToolNames.SET_POWER_SAVE_MODE,
            // Lifecycle management tools
            ToolNames.ENROLL_ALL_PROJECTS,
            ToolNames.GET_PROJECT_MODES,
            ToolNames.LIFECYCLE_LOG,
            ToolNames.LIFECYCLE_LOG_FILE, // ide_set_lifecycle_log_file
            ToolNames.PROJECT_STATUS,
            ToolNames.RELEASE_ALL_PROJECTS,
            ToolNames.RELEASE_PROJECT,
            ToolNames.RELOAD_PROJECT,
            ToolNames.SET_ALL_PROJECT_MODES,
            ToolNames.SET_PROJECT_MODE
        )
        if (PluginDetectors.maven.isAvailable) {
            expectedTools.add(ToolNames.IMPORT_MODULES)
            expectedTools.add(ToolNames.OPEN_WORKSPACE)
        }
        if (PluginDetectors.java.isAvailable) {
            expectedTools.add(ToolNames.LIST_TESTS)
        }
        if (PluginDetectors.java.isAvailable && PluginDetectors.kotlin.isAvailable) {
            expectedTools.add(ToolNames.CONVERT_JAVA_TO_KOTLIN)
        }

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
            assertTrue("${definition.name} should have non-empty description", !definition.description.isNullOrEmpty())
            assertNotNull("${definition.name} should have inputSchema", definition.inputSchema)
            assertEquals("${definition.name} inputSchema should be object type",
                "object", definition.inputSchema.type)
        }
    }

    private fun compileJavaSource(sourceFile: Path, outputDir: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val exitCode = if (compiler != null) {
            compiler.run(
                null,
                null,
                null,
                "-d",
                outputDir.toString(),
                sourceFile.toString()
            )
        } else {
            val javac = resolveJavacCommand()
            val process = ProcessBuilder(
                javac,
                "-d",
                outputDir.toString(),
                sourceFile.toString()
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText()
                val completed = process.waitFor()
                assertEquals("javac should compile library source successfully. Output: $output", 0, completed)
            }
            0
        }
        assertEquals("Library source should compile successfully", 0, exitCode)
    }

    private fun resolveJavacCommand(): String {
        val javaHome = System.getenv("JAVA_HOME")
        val runtimeHome = System.getProperty("java.home")

        val candidates = buildList {
            if (!javaHome.isNullOrBlank()) add(Path.of(javaHome, "bin", "javac"))
            if (!runtimeHome.isNullOrBlank()) {
                val runtimePath = Path.of(runtimeHome)
                add(runtimePath.resolve("bin").resolve("javac"))
                runtimePath.parent?.let { add(it.resolve("bin").resolve("javac")) }
            }
        }

        return candidates
            .firstOrNull { Files.isRegularFile(it) }
            ?.toString()
            ?: "javac"
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

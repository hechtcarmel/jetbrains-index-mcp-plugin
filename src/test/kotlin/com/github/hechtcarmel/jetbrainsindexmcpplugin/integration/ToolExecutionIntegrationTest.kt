package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindImplementationsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindUsagesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.ReadFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ReadFileResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Integration tests for tool execution end-to-end.
 * Tests each navigation, intelligence, and project tool with realistic scenarios.
 */
class ToolExecutionIntegrationTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Navigation Tools Tests

    fun testFindUsagesToolEndToEnd() = runBlocking {
        val tool = FindUsagesTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testFindDefinitionToolEndToEnd() = runBlocking {
        val tool = FindDefinitionTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testFindDefinitionToolFullElementPreview() = runBlocking {
        val basePath = project.basePath
        if (basePath == null || !File(basePath).exists()) return@runBlocking
        if (DumbService.isDumb(project)) return@runBlocking

        val serviceFile = File(basePath, "Service.java")
        val callerFile = File(basePath, "Caller.java")

        Files.writeString(serviceFile.toPath(), """
            public class Service {
                public void doWork() {
                    System.out.println("done");
                }
            }
        """.trimIndent())
        Files.writeString(callerFile.toPath(), """
            public class Caller {
                private Service service = new Service();
                public void call() {
                    service.doWork();
                }
            }
        """.trimIndent())

        val callerVirtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(callerFile.absolutePath)
        assertNotNull("Caller.java should be found in LocalFileSystem", callerVirtualFile)

        val callerPsi = PsiManager.getInstance(project).findFile(callerVirtualFile!!)
        assertNotNull("Caller.java should have a PSI file", callerPsi)
        val document = PsiDocumentManager.getInstance(project).getDocument(callerPsi!!)
        assertNotNull("Caller.java should have a document", document)
        val offset = document!!.text.indexOf("doWork")
        assertTrue("Should find doWork reference in Caller.java", offset >= 0)
        val line = document!!.getLineNumber(offset) + 1
        val column = offset - document.getLineStartOffset(line - 1) + 1

        val tool = FindDefinitionTool()
        val result = try {
            tool.execute(project, buildJsonObject {
                put("file", "Caller.java")
                put("line", line)
                put("column", column)
                put("fullElementPreview", true)
            })
        } catch (e: com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.IndexNotReadyException) {
            return@runBlocking
        }

        assertFalse("Should succeed for valid reference", result.isError)
        val content = result.content.first() as ContentBlock.Text
        val definition = json.decodeFromString<DefinitionResult>(content.text)

        assertTrue("Should resolve to Service.java", definition.file.endsWith("Service.java"))
        assertTrue("Full preview should include method name", definition.preview.contains("doWork"))
    }

    fun testReadFileToolValidation() = runBlocking {
        val tool = ReadFileTool()

        val missing = tool.execute(project, buildJsonObject { })
        assertTrue("Missing file/qualifiedName should error", missing.isError)

        val endLineOnly = tool.execute(project, buildJsonObject {
            put("file", "Test.java")
            put("endLine", 2)
        })
        assertTrue("endLine without startLine should error", endLineOnly.isError)

        val invalidRange = tool.execute(project, buildJsonObject {
            put("file", "Test.java")
            put("startLine", 3)
            put("endLine", 2)
        })
        assertTrue("endLine < startLine should error", invalidRange.isError)

        val invalidStart = tool.execute(project, buildJsonObject {
            put("file", "Test.java")
            put("startLine", 0)
            put("endLine", 1)
        })
        assertTrue("startLine < 1 should error", invalidStart.isError)
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

        assertFalse("Should succeed for valid file", result.isError)
        val content = result.content.first() as ContentBlock.Text
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
        assertFalse("Single-line read should succeed", singleLine.isError)
        val singleContent = singleLine.content.first() as ContentBlock.Text
        val singleResult = json.decodeFromString<ReadFileResult>(singleContent.text)
        assertEquals("line4", singleResult.content)
        assertEquals(4, singleResult.startLine)
        assertEquals(4, singleResult.endLine)
    }

    fun testTypeHierarchyToolEndToEnd() = runBlocking {
        val tool = TypeHierarchyTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing className", resultMissing.isError)

        // Test with invalid class
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("className", "com.nonexistent.InvalidClass")
        })
        assertTrue("Should error with invalid class", resultInvalid.isError)
    }

    fun testCallHierarchyToolEndToEnd() = runBlocking {
        val tool = CallHierarchyTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    fun testFindImplementationsToolEndToEnd() = runBlocking {
        val tool = FindImplementationsTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
            put("line", 1)
            put("column", 1)
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    // Intelligence Tools Tests

    fun testGetDiagnosticsToolEndToEnd() = runBlocking {
        val tool = GetDiagnosticsTool()

        // Test missing required parameter
        val resultMissing = tool.execute(project, buildJsonObject { })
        assertTrue("Should error with missing file", resultMissing.isError)

        // Test with invalid file
        val resultInvalid = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.kt")
        })
        assertTrue("Should error with invalid file", resultInvalid.isError)
    }

    // Project Tools Tests

    fun testGetIndexStatusToolEndToEnd() = runBlocking {
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

    // Tool Registry Integration Tests

    fun testAllToolsRegistered() {
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        val expectedTools = listOf(
            // Navigation tools
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
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
            // Project tools
            ToolNames.INDEX_STATUS,
            // Refactoring tools
            ToolNames.REFACTOR_RENAME,
            ToolNames.REFACTOR_SAFE_DELETE
        )

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
            assertTrue("${definition.name} should have non-empty description", definition.description.isNotEmpty())
            assertNotNull("${definition.name} should have inputSchema", definition.inputSchema)
            assertEquals("${definition.name} inputSchema should be object type",
                "object", definition.inputSchema["type"]?.toString()?.replace("\"", ""))
        }
    }

    // Error Scenario Tests

    fun testToolsHandleNullProject() {
        // This test verifies tools handle edge cases gracefully
        val registry = ToolRegistry()
        registry.registerBuiltInTools()

        registry.getAllTools().forEach { tool ->
            assertNotNull("${tool.name} should have name", tool.name)
            assertNotNull("${tool.name} should have description", tool.description)
            assertNotNull("${tool.name} should have inputSchema", tool.inputSchema)
        }
    }

    fun testToolsReturnProperContentBlocks() = runBlocking {
        val tool = GetIndexStatusTool()
        val result = tool.execute(project, buildJsonObject { })

        assertFalse("Result should not be error", result.isError)
        assertTrue("Result should have content", result.content.isNotEmpty())

        result.content.forEach { block ->
            when (block) {
                is ContentBlock.Text -> assertNotNull("Text block should have text", block.text)
                is ContentBlock.Image -> {
                    assertNotNull("Image block should have data", block.data)
                    assertNotNull("Image block should have mimeType", block.mimeType)
                }
            }
        }
    }
}

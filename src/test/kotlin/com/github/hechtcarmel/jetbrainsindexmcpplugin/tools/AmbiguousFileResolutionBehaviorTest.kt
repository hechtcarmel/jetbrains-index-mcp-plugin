package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.GetDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.ProjectDiagnosticsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ReplaceTextInFileTool
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

class AmbiguousFileResolutionBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        registerSourceRoot("module-a/src")
        registerSourceRoot("module-b/src")
    }

    fun testAmbiguousRelativePathReportsErrorWithBothMatches() = runBlocking {
        writeProjectFile("module-a/src/com/example/Helper.java", """
            package com.example;
            public class Helper { int x = 1; }
        """.trimIndent())

        writeProjectFile("module-b/src/com/example/Helper.java", """
            package com.example;
            public class Helper { int x = 2; }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "com/example/Helper.java")
            put("searchText", "int x")
            put("replaceText", "int y")
        })

        assertToolFailed("Should fail when file path is ambiguous", result)
        val text = toolText(result)
        assertTrue("Error should mention 'Ambiguous', was: $text", text.contains("Ambiguous"))
        assertTrue("Error should list module-a path, was: $text", text.contains("module-a"))
        assertTrue("Error should list module-b path, was: $text", text.contains("module-b"))
        assertFileContains("module-a/src/com/example/Helper.java", "int x = 1")
        assertFileContains("module-b/src/com/example/Helper.java", "int x = 2")
    }

    fun testFullPathFromProjectRootResolvesUnambiguously() = runBlocking {
        writeProjectFile("module-a/src/com/example/Helper.java", """
            package com.example;
            public class Helper { int x = 1; }
        """.trimIndent())

        writeProjectFile("module-b/src/com/example/Helper.java", """
            package com.example;
            public class Helper { int x = 2; }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "module-b/src/com/example/Helper.java")
            put("searchText", "int x = 2")
            put("replaceText", "int y = 2")
        })

        assertToolSucceeded("Full path should resolve unambiguously", result)
        assertFileContains("module-b/src/com/example/Helper.java", "int y = 2")
        assertFileContains("module-a/src/com/example/Helper.java", "int x = 1")
    }

    fun testRootFileResolvesToBasePathEvenWhenSubmoduleHasSameName() = runBlocking {
        writeProjectFile("README.md", "root readme")
        writeProjectFile("module-a/src/README.md", "module-a readme")

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "README.md")
            put("searchText", "root readme")
            put("replaceText", "updated root readme")
        })

        assertToolSucceeded("Root file should resolve via basePath", result)
        assertFileContains("README.md", "updated root readme")
        assertFileContains("module-a/src/README.md", "module-a readme")
    }

    fun testAmbiguityAcrossRootsOutsideProjectListsAbsolutePaths() = runBlocking {
        // Real path, so the roots compare equal to the canonical paths resolveFile reports.
        val workspace = Files.createTempDirectory("ambiguous-outside-roots").toRealPath()
        val roots = listOf("svc-a", "svc-b").map { name ->
            val file = workspace.resolve("$name/src/com/example/Helper.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, "package com.example;\npublic class Helper {}\n")
            requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)) {
                "Failed to refresh $file"
            }
            requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workspace.resolve("$name/src")))
        }
        roots.forEach { PsiTestUtil.addContentRoot(module, it) }
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val basePath = requireNotNull(project.basePath)
            assertFalse(
                "Precondition: roots must be outside the project base path",
                roots.any { it.path.startsWith("$basePath/") }
            )

            val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
                put("file", "com/example/Helper.java")
                put("searchText", "class Helper")
                put("replaceText", "class Renamed")
            })

            assertToolFailed("Same relative path under two outside roots is ambiguous", result)
            val text = toolText(result)
            for (root in roots) {
                assertTrue(
                    "Error should list the match under ${root.path} by absolute path, was: $text",
                    text.contains("${root.path}/com/example/Helper.java")
                )
            }
        } finally {
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries
                    .filter { entry -> roots.any { it.url == entry.url } }
                    .forEach(model::removeContentEntry)
            }
            workspace.toFile().deleteRecursively()
        }
    }

    fun testBatchDiagnosticsReportsAmbiguousEntryWithoutFailingTheBatch() = runBlocking {
        writeProjectFile("module-a/src/com/example/Helper.java", "package com.example;\npublic class Helper {}\n")
        writeProjectFile("module-b/src/com/example/Helper.java", "package com.example;\npublic class Helper {}\n")
        writeProjectFile("module-a/src/com/example/Other.java", "package com.example;\npublic class Other {}\n")

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray {
                add(JsonPrimitive("com/example/Helper.java"))
                add(JsonPrimitive("module-a/src/com/example/Other.java"))
            })
        })

        assertToolSucceeded("One ambiguous entry must not fail the whole batch", result)
        val analyses = requireNotNull(json.decodeFromString<DiagnosticsResult>(toolText(result)).fileAnalyses)
        assertEquals(
            "Every requested file keeps its own coverage entry",
            listOf("com/example/Helper.java", "module-a/src/com/example/Other.java"),
            analyses.map { it.file }
        )
        val ambiguous = analyses.first()
        assertEquals(ProjectDiagnosticsTool.STATE_FAILED, ambiguous.state)
        val reason = ambiguous.reason.orEmpty()
        assertTrue("Reason should explain the ambiguity, was: $reason", reason.contains("Ambiguous"))
        for (match in listOf("module-a/src/com/example/Helper.java", "module-b/src/com/example/Helper.java")) {
            assertTrue("Reason should list $match, was: $reason", reason.contains(match))
        }
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MoveFileToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun createProjectDirectory(relativePath: String): PsiDirectory {
        val projectBasePath = requireNotNull(project.basePath)
        val path = Path.of(projectBasePath, relativePath)
        Files.createDirectories(path)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        assertNotNull("Directory should resolve in VFS: $relativePath", virtualFile)
        return com.intellij.psi.PsiManager.getInstance(project).findDirectory(virtualFile!!)
            ?: error("Directory should resolve as PSI: $relativePath")
    }

    private fun determineComposerNamespace(tool: MoveFileTool, targetDirectory: PsiDirectory): String? {
        val method = MoveFileTool::class.java.getDeclaredMethod(
            "determinePhpNamespaceFromComposer",
            com.intellij.openapi.project.Project::class.java,
            PsiDirectory::class.java
        )
        method.isAccessible = true
        return method.invoke(tool, project, targetDirectory) as? String
    }

    private fun resultMessage(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String {
        val resultJson = json.parseToJsonElement(toolText(result)).jsonObject
        return resultJson["message"]?.jsonPrimitive?.content ?: error("Missing message")
    }

    private fun resultWarnings(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): List<String>? {
        val resultJson = json.parseToJsonElement(toolText(result)).jsonObject
        val warnings = resultJson["warnings"] ?: return null
        if (warnings is JsonNull) return null
        return warnings.jsonArray.map { it.jsonPrimitive.content }
    }

    fun testComposerNamespaceInferenceUsesNestedComposerJson() {
        writeProjectFile(
            "platform/composer.json",
            """
                {
                  "autoload": {
                    "psr-4": {
                      "App\\": "src/"
                    }
                  }
                }
            """.trimIndent()
        )
        val targetDirectory = createProjectDirectory("platform/src/History/Revision/Domain")

        val namespace = determineComposerNamespace(MoveFileTool(), targetDirectory)

        assertEquals("App\\History\\Revision\\Domain", namespace)
    }

    fun testMoveFileToolFailsFastWhenPhpSemanticMoveIsUnsupported() = runBlocking {
        writeProjectFile("src/Unsupported.php", "<?php class Unsupported {}")

        val tool = object : MoveFileTool() {
            override fun selectMoveBackend(
                project: com.intellij.openapi.project.Project,
                psiFile: PsiFile
            ): MoveBackendSelection {
                return MoveBackendSelection.Unsupported("semantic PHP move blocked for test")
            }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/Unsupported.php")
            put("destination", "src/UnsupportedTarget")
        })

        assertToolFailed("Unsupported PHP semantic moves should fail fast", result)
        assertTrue(toolText(result).contains("semantic PHP move blocked for test"))
        assertProjectFileExists("src/Unsupported.php")
        assertProjectFileAbsent("src/UnsupportedTarget/Unsupported.php")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testMoveFileToolReportsPhpSemanticBackendWhenSelected() = runBlocking {
        writeProjectFile("src/Foo.php", "<?php class Foo {}")

        val tool = object : MoveFileTool() {
            override fun selectMoveBackend(
                project: com.intellij.openapi.project.Project,
                psiFile: PsiFile
            ): MoveBackendSelection {
                val pointer = SmartPointerManager.getInstance(project)
                    .createSmartPsiElementPointer<PsiElement>(psiFile)
                return MoveBackendSelection.PhpSemanticMove(pointer, "Foo")
            }

            override fun executePhpSemanticMove(
                project: com.intellij.openapi.project.Project,
                preparation: MovePreparation
            ) {
                WriteCommandAction.writeCommandAction(project).run<Throwable> {
                    preparation.psiFile.virtualFile.move(this, preparation.targetDirectory.virtualFile)
                }
            }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/Foo.php")
            put("destination", "src/Internal")
        })

        assertToolSucceeded("PHP semantic backend test move should succeed", result)
        val message = resultMessage(result)
        assertTrue(message.contains("using PhpStorm semantic PHP move"))
        assertTrue(message.contains("src/Internal/Foo.php"))
        assertProjectFileAbsent("src/Foo.php")
        assertProjectFileExists("src/Internal/Foo.php")
        assertFileContains("src/Internal/Foo.php", "class Foo")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testMoveFileToolGenericPathNoLongerClaimsReferencesUpdated() = runBlocking {
        writeProjectFile("notes/todo.txt", "todo")

        val result = MoveFileTool().execute(project, buildJsonObject {
            put("file", "notes/todo.txt")
            put("destination", "archive")
        })

        assertToolSucceeded("Generic file move should succeed for plain text files", result)
        val message = resultMessage(result)
        assertTrue(message.contains("using IDE file move semantics"))
        assertFalse(message.contains("references updated"))
        assertProjectFileAbsent("notes/todo.txt")
        assertProjectFileExists("archive/todo.txt")
        assertFileContains("archive/todo.txt", "todo")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    // Cross-file coverage: the point of moving through the IDE rather than `mv` is that the
    // moved file's package and every importer are rewritten. A source root is required so the
    // package can be derived and `ReferencesSearch` can see the importer.
    fun testJavaMoveUpdatesPackageAndImportInReferencingFile() = runBlocking {
        registerSourceRoot("move-src")
        writeProjectFile(
            "move-src/moveorigin/MovedService.java", """
            package moveorigin;

            public class MovedService {
                public String describe() {
                    return "moved";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "move-src/moveconsumer/ServiceClient.java", """
            package moveconsumer;

            import moveorigin.MovedService;

            public class ServiceClient {
                public String describe() {
                    return new MovedService().describe();
                }
            }
        """.trimIndent()
        )

        val result = MoveFileTool().execute(project, buildJsonObject {
            put("file", "move-src/moveorigin/MovedService.java")
            put("destination", "move-src/movetarget")
        })

        assertToolSucceeded("Java file move should succeed", result)
        assertTrue(resultMessage(result).contains("move-src/movetarget/MovedService.java"))
        assertNull("Unconflicted move must not report warnings", resultWarnings(result))
        assertProjectFileAbsent("move-src/moveorigin/MovedService.java")
        assertProjectFileExists("move-src/movetarget/MovedService.java")
        assertRenamedInFile("move-src/movetarget/MovedService.java", "package moveorigin;", "package movetarget;")
        assertRenamedInFile(
            "move-src/moveconsumer/ServiceClient.java",
            "moveorigin.MovedService",
            "movetarget.MovedService"
        )
        assertFileContains("move-src/moveconsumer/ServiceClient.java", "new MovedService().describe()")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    // A package-private class moved out of the package that uses it is the canonical move
    // conflict: the processor proceeds headlessly (correct for autonomous operation), but the
    // detected conflicts must surface as plain-text warnings instead of vanishing into a
    // clean-success response.
    fun testConflictedJavaMoveSucceedsAndSurfacesSanitizedConflictWarnings() = runBlocking {
        registerSourceRoot("conflict-src")
        writeProjectFile(
            "conflict-src/moveconflict/Hidden.java", """
            package moveconflict;

            class Hidden {
                String describe() {
                    return "hidden";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "conflict-src/moveconflict/HiddenUser.java", """
            package moveconflict;

            public class HiddenUser {
                public String describe() {
                    return new Hidden().describe();
                }
            }
        """.trimIndent()
        )

        val result = MoveFileTool().execute(project, buildJsonObject {
            put("file", "conflict-src/moveconflict/Hidden.java")
            put("destination", "conflict-src/moveother")
        })

        assertToolSucceeded("Conflicted move should still complete headlessly", result)
        assertProjectFileAbsent("conflict-src/moveconflict/Hidden.java")
        assertProjectFileExists("conflict-src/moveother/Hidden.java")

        val warnings = resultWarnings(result)
        assertNotNull("Conflicted move must surface the detected conflicts as warnings", warnings)
        assertTrue("Expected at least one conflict warning", warnings!!.isNotEmpty())
        assertNotNull(
            "Expected a conflict warning naming the moved class 'Hidden', got: $warnings",
            warnings.firstOrNull { it.contains("Hidden") }
        )
        warnings.forEach { warning ->
            assertFalse(
                "Warning must not contain HTML markup: $warning",
                warning.contains('<') || warning.contains('>')
            )
            assertFalse(
                "Warning must not contain XML entities: $warning",
                listOf("&lt;", "&gt;", "&quot;", "&amp;", "&#39;").any { warning.contains(it) }
            )
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}

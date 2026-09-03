package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
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

    // ── Same-package moves (issue #360) ─────────────────────────────────────────────────

    private fun javaFile(relativePath: String): PsiJavaFile {
        val basePath = requireNotNull(project.basePath)
        val virtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")) {
            "File should resolve in VFS: $relativePath"
        }
        return PsiManager.getInstance(project).findFile(virtualFile) as PsiJavaFile
    }

    /** Asserts the first code reference named [referenceName] in [relativePath] resolves into [targetRelativePath]. */
    private fun assertReferenceResolvesInto(relativePath: String, referenceName: String, targetRelativePath: String) {
        val reference = PsiTreeUtil.findChildrenOfType(javaFile(relativePath), PsiJavaCodeReferenceElement::class.java)
            .firstOrNull {
                it.referenceName == referenceName &&
                    PsiTreeUtil.getParentOfType(it, PsiImportStatementBase::class.java) == null
            }
            ?: error("No reference named '$referenceName' in $relativePath")
        val resolved = reference.resolve()
        assertNotNull("'$referenceName' in $relativePath must still resolve after the move", resolved)
        val resolvedPath = resolved!!.containingFile.virtualFile.path
        assertTrue(
            "'$referenceName' in $relativePath resolves into $resolvedPath, expected $targetRelativePath",
            resolvedPath.endsWith("/$targetRelativePath")
        )
    }

    private fun relativePathOf(restored: RestoredImports): String =
        restored.file.path.removePrefix("${project.basePath}/")

    // A file moved between two source roots that map the same package keeps its fully
    // qualified name, so a consumer's wildcard import is exactly as valid after the move as
    // before. The consumer must come out of the move still importing the package, still calling
    // the class by its simple name, and still resolving it — now at the new location.
    fun testSamePackageMoveAcrossSourceRootsKeepsWildcardImporterIntact() = runBlocking {
        registerSourceRoot("samepkg-a")
        registerSourceRoot("samepkg-b")
        writeProjectFile(
            "samepkg-a/shared/api/Moved.java", """
            package shared.api;

            public class Moved {
                public String describe() {
                    return "moved";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "samepkg-a/consumer/WildcardConsumer.java", """
            package consumer;

            import shared.api.*;

            public class WildcardConsumer {
                public String describe() {
                    return new Moved().describe();
                }
            }
        """.trimIndent()
        )

        val result = MoveFileTool().execute(project, buildJsonObject {
            put("file", "samepkg-a/shared/api/Moved.java")
            put("destination", "samepkg-b/shared/api")
        })

        assertToolSucceeded("Same-package move across source roots should succeed", result)
        assertProjectFileAbsent("samepkg-a/shared/api/Moved.java")
        assertProjectFileExists("samepkg-b/shared/api/Moved.java")
        assertFileContains("samepkg-b/shared/api/Moved.java", "package shared.api;")
        assertFileContains("samepkg-a/consumer/WildcardConsumer.java", "import shared.api.*;")
        assertFileContains("samepkg-a/consumer/WildcardConsumer.java", "new Moved().describe()")
        assertFileDoesNotContain("samepkg-a/consumer/WildcardConsumer.java", "shared.api.Moved")
        assertReferenceResolvesInto("samepkg-a/consumer/WildcardConsumer.java", "Moved", "samepkg-b/shared/api/Moved.java")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    // A destination no module compiles is the other half of the "extract a new module" trap:
    // the move itself succeeds, and the file silently drops out of every build until the new
    // module is registered. The result must say so.
    fun testMoveOutOfEverySourceRootWarnsThatNoModuleCompilesTheFile() = runBlocking {
        registerSourceRoot("escape-src")
        writeProjectFile("escape-src/escaping/Escaping.java", "package escaping;\n\npublic class Escaping {}\n")

        val result = MoveFileTool().execute(project, buildJsonObject {
            put("file", "escape-src/escaping/Escaping.java")
            put("destination", "unregistered-module/src/escaping")
        })

        assertToolSucceeded("Move out of the source roots should still succeed", result)
        assertProjectFileAbsent("escape-src/escaping/Escaping.java")
        assertProjectFileExists("unregistered-module/src/escaping/Escaping.java")
        val warnings = resultWarnings(result)
        assertNotNull("Leaving every source root must be reported as a warning", warnings)
        val warning = warnings!!.firstOrNull { it.contains("outside every source root") }
        assertNotNull("Expected a source-root warning, got: $warnings", warning)
        assertTrue("Warning must name the destination: $warning", warning!!.contains("unregistered-module/src/escaping"))
        assertTrue("Warning must name the moved file: $warning", warning.contains("Escaping.java"))
        assertTrue("Warning must point at the build-system tools: $warning", warning.contains("ide_reload_project"))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    // ── JavaOnDemandImportGuard ─────────────────────────────────────────────────────────
    //
    // The guard is the repair behind issue #360. The end-to-end tests above cover whatever the
    // IDE build under test does to a same-package move; these drive the guard directly with the
    // damage applied by hand, so the repair path is verified independently of that behavior.

    fun testImportGuardRestoresPackageImportsRemovedFromUsageFiles() {
        registerSourceRoot("guard-a")
        registerSourceRoot("guard-b")
        writeProjectFile("guard-a/guarded/Target.java", "package guarded;\n\npublic class Target {}\n")
        writeProjectFile(
            "guard-a/users/Wildcard.java", """
            package users;

            import guarded.*;

            public class Wildcard {
                Target target;
            }
        """.trimIndent()
        )
        writeProjectFile(
            "guard-a/users/Single.java", """
            package users;

            import guarded.Target;

            public class Single {
                Target target;
            }
        """.trimIndent()
        )
        val target = javaFile("guard-a/guarded/Target.java")
        val wildcard = javaFile("guard-a/users/Wildcard.java")
        val single = javaFile("guard-a/users/Single.java")

        val guard = JavaOnDemandImportGuard.forMove(target, createProjectDirectory("guard-b/guarded"))
        assertNotNull("Same package on both sides: the guard must apply", guard)
        guard!!.beforeRetarget(listOf(wildcard, single, target))

        WriteCommandAction.runWriteCommandAction(project) {
            wildcard.importList!!.importStatements.forEach { it.delete() }
            single.importList!!.importStatements.forEach { it.delete() }
        }
        assertFileDoesNotContain("guard-a/users/Wildcard.java", "import guarded.*;")
        assertFileDoesNotContain("guard-a/users/Single.java", "import guarded.Target;")

        var restored: List<RestoredImports> = emptyList()
        WriteCommandAction.runWriteCommandAction(project) { restored = guard.afterRetarget() }

        assertEquals(
            mapOf(
                "guard-a/users/Wildcard.java" to listOf("guarded.*"),
                "guard-a/users/Single.java" to listOf("guarded.Target")
            ),
            restored.associate { relativePathOf(it) to it.imports }
        )
        assertFileContains("guard-a/users/Wildcard.java", "import guarded.*;")
        assertFileContains("guard-a/users/Single.java", "import guarded.Target;")
        assertReferenceResolvesInto("guard-a/users/Wildcard.java", "Target", "guard-a/guarded/Target.java")
        assertReferenceResolvesInto("guard-a/users/Single.java", "Target", "guard-a/guarded/Target.java")
    }

    fun testImportGuardLeavesSurvivingAndFoldedImportsAlone() {
        registerSourceRoot("guard-c")
        registerSourceRoot("guard-d")
        writeProjectFile("guard-c/kept/Kept.java", "package kept;\n\npublic class Kept {}\n")
        writeProjectFile(
            "guard-c/users/Untouched.java", """
            package users;

            import kept.*;

            public class Untouched {
                Kept kept;
            }
        """.trimIndent()
        )
        writeProjectFile(
            "guard-c/users/Folded.java", """
            package users;

            import kept.Kept;

            public class Folded {
                Kept kept;
            }
        """.trimIndent()
        )
        val untouched = javaFile("guard-c/users/Untouched.java")
        val folded = javaFile("guard-c/users/Folded.java")

        val guard = requireNotNull(JavaOnDemandImportGuard.forMove(javaFile("guard-c/kept/Kept.java"), createProjectDirectory("guard-d/kept")))
        guard.beforeRetarget(listOf(untouched, folded))

        // The IDE legitimately folds single imports into a wildcard; that is not damage.
        WriteCommandAction.runWriteCommandAction(project) {
            val importList = folded.importList!!
            importList.importStatements.forEach { it.delete() }
            importList.add(JavaPsiFacade.getElementFactory(project).createImportStatementOnDemand("kept"))
        }

        var restored: List<RestoredImports> = emptyList()
        WriteCommandAction.runWriteCommandAction(project) { restored = guard.afterRetarget() }

        assertTrue("Nothing to repair, so nothing may be reported: $restored", restored.isEmpty())
        assertFileContains("guard-c/users/Untouched.java", "import kept.*;")
        assertFileContains("guard-c/users/Folded.java", "import kept.*;")
        assertFileDoesNotContain("guard-c/users/Folded.java", "import kept.Kept;")
    }

    fun testImportGuardAppliesOnlyWhenThePackageStaysTheSame() {
        registerSourceRoot("guard-e")
        registerSourceRoot("guard-f")
        writeProjectFile("guard-e/before/Mover.java", "package before;\n\npublic class Mover {}\n")
        val mover = javaFile("guard-e/before/Mover.java")

        assertNull(
            "A real package change legitimately rewrites imports; the guard must stay out of it",
            JavaOnDemandImportGuard.forMove(mover, createProjectDirectory("guard-e/after"))
        )
        assertNotNull(
            "Same package under another source root is the issue #360 case",
            JavaOnDemandImportGuard.forMove(mover, createProjectDirectory("guard-f/before"))
        )
        assertNotNull(
            "A destination without a package leaves the file's package as is, so the guard applies",
            JavaOnDemandImportGuard.forMove(mover, createProjectDirectory("guard-plain/before"))
        )
    }
}

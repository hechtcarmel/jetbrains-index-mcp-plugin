package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderEnvironment
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MoveFileToolBehaviorTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun textResult(result: com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult): String =
        (result.content.first() as ContentBlock.Text).text

    private fun writeProjectFile(relativePath: String, content: String): Path {
        val projectBasePath = requireNotNull(project.basePath)
        val path = Path.of(projectBasePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    fun testMoveFileToolFailsFastWhenPhpSemanticMoveIsUnsupported() = runBlocking {
        writeProjectFile("src/Foo.php", "<?php class Foo {}")

        val tool = object : MoveFileTool() {
            override fun selectMoveBackend(
                project: com.intellij.openapi.project.Project,
                psiFile: PsiFile
            ): MoveBackendSelection {
                return MoveBackendSelection.Unsupported("semantic PHP move blocked for test")
            }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "src/Foo.php")
            put("destination", "src/Internal")
        })

        assertTrue("Unsupported PHP semantic moves should fail fast", result.isError)
        assertTrue(textResult(result).contains("semantic PHP move blocked for test"))
    }

    fun testMoveFileToolReportsPhpSemanticBackendWhenSelected() = runBlocking {
        val sourceFile = writeProjectFile("src/Foo.php", "<?php class Foo {}")

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

        assertFalse("PHP semantic backend test move should succeed", result.isError)
        val resultJson = json.parseToJsonElement(textResult(result)).jsonObject
        val message = resultJson["message"]?.jsonPrimitive?.content ?: error("Missing message")
        assertTrue(message.contains("using PhpStorm semantic PHP move"))
        assertTrue(message.contains("src/Internal/Foo.php"))
        assertFalse("Source file should be moved away", Files.exists(sourceFile))
        assertTrue("Moved file should exist in target directory", Files.exists(sourceFile.parent.resolve("Internal/Foo.php")))
    }

    fun testMoveFileToolGenericPathNoLongerClaimsReferencesUpdated() = runBlocking {
        val sourceFile = writeProjectFile("notes/todo.txt", "todo")

        val result = MoveFileTool().execute(project, buildJsonObject {
            put("file", "notes/todo.txt")
            put("destination", "archive")
        })

        assertFalse("Generic file move should succeed for plain text files", result.isError)
        val resultJson = json.parseToJsonElement(textResult(result)).jsonObject
        val message = resultJson["message"]?.jsonPrimitive?.content ?: error("Missing message")
        assertTrue(message.contains("using IDE file move semantics"))
        assertFalse(message.contains("references updated"))
        assertFalse(Files.exists(sourceFile))
        assertTrue(Files.exists(Path.of(requireNotNull(project.basePath), "archive/todo.txt")))
    }

    fun testMoveFileToolReportsCSharpNamespaceImportLimitations() = runBlocking {
        val sourceFile = writeProjectFile(
            "Clipthrough/Models/SampleThing.cs",
            """
            namespace Clipthrough.Models;

            public sealed class SampleThing
            {
            }
            """.trimIndent()
        )

        val result = MoveFileTool().execute(project, buildJsonObject {
            put("file", "Clipthrough/Models/SampleThing.cs")
            put("destination", "Clipthrough/Services")
        })

        assertFalse("C# file move should succeed", result.isError)
        val resultJson = json.parseToJsonElement(textResult(result)).jsonObject
        val message = resultJson["message"]?.jsonPrimitive?.content ?: error("Missing message")
        assertTrue(message.contains("C# namespace updated"))
        if (RiderEnvironment.isAvailable) {
            assertTrue(message.contains("cross-file using/import updates depend on Rider frontend support"))
        } else {
            assertFalse(message.contains("cross-file using/import updates depend on Rider frontend support"))
        }
        assertFalse("Source file should be moved away", Files.exists(sourceFile))
        val movedFile = Path.of(requireNotNull(project.basePath), "Clipthrough/Services/SampleThing.cs")
        assertTrue("Moved file should exist in target directory", Files.exists(movedFile))
        assertTrue(Files.readString(movedFile).contains("namespace Clipthrough.Services;"))
    }
}

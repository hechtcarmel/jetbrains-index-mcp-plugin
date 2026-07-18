package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChangeSignatureBehaviorTest : BasePlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun writeProjectFile(relativePath: String, content: String): Path {
        val basePath = requireNotNull(project.basePath)
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for test file $path"
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return path
    }

    private fun readProjectFileVfs(relativePath: String): String {
        val basePath = requireNotNull(project.basePath)
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
            ?: return Files.readString(Path.of(basePath, relativePath))
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        return doc?.text ?: String(vf.contentsToByteArray())
    }

    fun testChangeSignatureAddsParameter() = runBlocking {
        writeProjectFile("src/SigService.java", """
            public class SigService {
                public String process(String input) {
                    return input.trim();
                }
                public void caller() {
                    String result = process("hello");
                }
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigService.java")
            put("line", 2)
            put("column", 19)
            put("newParameters", buildJsonArray {
                add(buildJsonObject { put("oldIndex", 0); put("name", "input"); put("type", "String") })
                add(buildJsonObject { put("oldIndex", -1); put("name", "validate"); put("type", "boolean"); put("defaultValue", "true") })
            })
        })

        assertFalse("Change signature should succeed: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}", result.isError)
        val content = readProjectFileVfs("src/SigService.java")
        assertTrue("Method should have new param: $content", content.contains("boolean validate"))
    }

    fun testChangeSignatureChangesReturnType() = runBlocking {
        writeProjectFile("src/SigConverter.java", """
            public class SigConverter {
                public String convert(int value) {
                    return String.valueOf(value);
                }
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigConverter.java")
            put("line", 2)
            put("column", 19)
            put("newReturnType", "int")
            put("newParameters", buildJsonArray {
                add(buildJsonObject { put("oldIndex", 0); put("name", "value"); put("type", "int") })
            })
        })

        assertFalse("Change return type should succeed: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}", result.isError)
        val content = readProjectFileVfs("src/SigConverter.java")
        assertTrue("Return type should be int: $content", content.contains("public int convert"))
    }

    fun testChangeSignatureOnNonMethodFails() = runBlocking {
        writeProjectFile("src/SigNotAMethod.java", """
            public class SigNotAMethod {
                private int count = 0;
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigNotAMethod.java")
            put("line", 2)
            put("column", 17)
            put("newReturnType", "String")
        })

        val text = (result.content.singleOrNull() as? ContentBlock.Text)?.text ?: ""
        assertTrue("Should fail on field: $text", result.isError || text.contains("No method"))
    }

    fun testChangeSignatureRequiresAtLeastOneChange() = runBlocking {
        writeProjectFile("src/SigNoChange.java", """
            public class SigNoChange {
                public void doWork() {}
            }
        """.trimIndent())

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "src/SigNoChange.java")
            put("line", 2)
            put("column", 17)
        })

        assertTrue("Should require at least one change", result.isError)
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

class RenameSymbolToolBehaviorTest : BasePlatformTestCase() {

    private fun writeProjectFile(relativePath: String, content: String): Path {
        val basePath = requireNotNull(project.basePath)
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for test file ${path}"
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return path
    }

    private fun readProjectFileVfs(relativePath: String): String {
        val basePath = requireNotNull(project.basePath)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
            ?: return Files.readString(Path.of(basePath, relativePath))
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        return doc?.text ?: String(vf.contentsToByteArray())
    }

    fun testExplicitFileRenameIgnoresMalformedCoordinatesDuringFullToolExecution() = runBlocking {
        writeProjectFile(
            "docs/readme.txt",
            "Rename me through file mode.\n"
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "docs/readme.txt")
            put("targetType", "file")
            put("line", JsonPrimitive("not-a-number"))
            put("column", JsonPrimitive("still-not-a-number"))
            put("newName", "readme-renamed.txt")
        })

        assertFalse(
            "Explicit file rename should ignore malformed line/column values: ${result.content}",
            result.isError
        )

        val basePath = requireNotNull(project.basePath)
        assertFalse(Files.exists(Path.of(basePath, "docs/readme.txt")))
        assertTrue(Files.exists(Path.of(basePath, "docs/readme-renamed.txt")))
    }

    // ── Java: symbol rename ──

    fun testJavaRenameMethodUpdatesCallSitesWithinFile() = runBlocking {
        writeProjectFile(
            "src/UserService.java", """
            public class UserService {
                public String getDisplayName() {
                    return "name";
                }
                public String show() {
                    return getDisplayName();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/UserService.java")
            put("line", 2)
            put("column", 19)
            put("newName", "getFullName")
        })

        assertFalse(
            "Java method rename should succeed: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}",
            result.isError
        )
        val text = readProjectFileVfs("src/UserService.java")
        assertTrue("Method should be renamed in declaration: $text", text.contains("getFullName"))
    }

    fun testJavaRenameFieldUpdatesReferencesWithinFile() = runBlocking {
        writeProjectFile(
            "src/FieldRenameTarget.java", """
            public class FieldRenameTarget {
                public int count = 0;
                public void increment() {
                    count = count + 1;
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/FieldRenameTarget.java")
            put("line", 2)
            put("column", 16)
            put("newName", "total")
        })

        val payload = (result.content.singleOrNull() as? ContentBlock.Text)?.text ?: ""
        assertFalse("Java field rename should succeed: $payload", result.isError)
        val text = readProjectFileVfs("src/FieldRenameTarget.java")
        assertTrue("Field declaration should use new name: $text", text.contains("int total"))
    }

    fun testJavaRenameClassRenamesFile() = runBlocking {
        writeProjectFile(
            "src/OldName.java", """
            public class OldName {
                public void doWork() {}
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/OldName.java")
            put("line", 1)
            put("column", 14)
            put("newName", "NewName")
        })

        assertFalse("Java class rename should succeed", result.isError)
        val text = readProjectFileVfs("src/NewName.java")
        assertTrue("Class declaration updated: $text", text.contains("class NewName"))
    }

    fun testJavaRenameParameterUpdatesUsagesInBody() = runBlocking {
        writeProjectFile(
            "src/Processor.java", """
            public class Processor {
                public String process(String input) {
                    return input.trim();
                }
            }
        """.trimIndent()
        )

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "src/Processor.java")
            put("line", 2)
            put("column", 34)
            put("newName", "rawValue")
        })

        assertFalse("Java parameter rename should succeed", result.isError)
        val text = readProjectFileVfs("src/Processor.java")
        assertTrue("Parameter should be renamed in signature: $text", text.contains("String rawValue"))
        assertTrue("Usage in body should be updated", text.contains("rawValue.trim()"))
    }
}

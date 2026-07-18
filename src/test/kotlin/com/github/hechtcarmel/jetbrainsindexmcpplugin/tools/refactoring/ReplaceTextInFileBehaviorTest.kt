package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReplaceTextInFileBehaviorTest : BasePlatformTestCase() {

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

    private fun parseResult(result: com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult): ReplaceTextInFileTool.ReplaceTextResult {
        return json.decodeFromString<ReplaceTextInFileTool.ReplaceTextResult>((result.content.single() as ContentBlock.Text).text)
    }

    fun testReplaceLiteralTextMultipleOccurrences() = runBlocking {
        writeProjectFile("src/Caller.java", """
            public class Caller {
                void run() {
                    Helper.wrap(getValue());
                    Helper.wrap(getName());
                    Helper.wrap(getAge());
                }
            }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Caller.java")
            put("searchText", "Helper.wrap(")
            put("replaceText", "(")
        })

        assertFalse("Replace should succeed: ${(result.content.singleOrNull() as? ContentBlock.Text)?.text}", result.isError)
        val payload = parseResult(result)
        assertTrue(payload.success)
        assertEquals(3, payload.replacements)

        val content = readProjectFileVfs("src/Caller.java")
        assertFalse("Old text should be gone", content.contains("Helper.wrap("))
        assertTrue("Should have plain call", content.contains("(getValue())"))
        assertTrue("Should have plain call", content.contains("(getName())"))
        assertTrue("Should have plain call", content.contains("(getAge())"))
    }

    fun testReplaceLiteralNoMatch() = runBlocking {
        writeProjectFile("src/NoMatch.java", """
            public class NoMatch {
                void run() { System.out.println("hello"); }
            }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/NoMatch.java")
            put("searchText", "nonexistent pattern")
            put("replaceText", "replacement")
        })

        assertFalse(result.isError)
        val payload = parseResult(result)
        assertTrue(payload.success)
        assertEquals(0, payload.replacements)
    }

    fun testReplaceWithRegex() = runBlocking {
        writeProjectFile("src/Logger.java", """
            public class Logger {
                void run() {
                    LOG.debug("first message");
                    LOG.debug("second message");
                }
            }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Logger.java")
            put("searchText", """LOG\.debug\((".*?")\)""")
            put("replaceText", "LOG.trace($1)")
            put("regex", true)
        })

        assertFalse("Regex replace should succeed", result.isError)
        val payload = parseResult(result)
        assertEquals(2, payload.replacements)

        val content = readProjectFileVfs("src/Logger.java")
        assertTrue("Should use trace", content.contains("LOG.trace("))
        assertFalse("Should not have debug", content.contains("LOG.debug("))
    }

    fun testReplaceCaseInsensitive() = runBlocking {
        writeProjectFile("src/Mixed.java", """
            public class Mixed {
                String TODO = "fix";
                String todo = "later";
                String Todo = "maybe";
            }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Mixed.java")
            put("searchText", "todo")
            put("replaceText", "FIXME")
            put("caseSensitive", false)
        })

        assertFalse(result.isError)
        val payload = parseResult(result)
        assertEquals(3, payload.replacements)

        val content = readProjectFileVfs("src/Mixed.java")
        assertFalse(content.lowercase().contains("todo"))
    }

    fun testReplaceEmptySearchTextFails() = runBlocking {
        writeProjectFile("src/Empty.java", """
            public class Empty {}
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Empty.java")
            put("searchText", "")
            put("replaceText", "x")
        })

        assertTrue("Empty search should fail", result.isError)
    }

    fun testReplaceIdenticalTextFails() = runBlocking {
        writeProjectFile("src/Same.java", """
            public class Same {}
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Same.java")
            put("searchText", "Same")
            put("replaceText", "Same")
        })

        assertTrue("Identical search/replace should fail", result.isError)
    }

    fun testReplaceFileNotFound() = runBlocking {
        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/DoesNotExist.java")
            put("searchText", "a")
            put("replaceText", "b")
        })

        assertTrue("Missing file should fail", result.isError)
    }

    fun testReplaceTextEscapesNewlines() = runBlocking {
        writeProjectFile("src/Imports.java", """
            package io.example;

            public class Imports {}
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Imports.java")
            put("searchText", "package io.example;")
            put("replaceText", "package io.example;\\n\\nimport io.example.TaskStatus;")
        })

        assertFalse("Replace should succeed", result.isError)
        val payload = parseResult(result)
        assertEquals(1, payload.replacements)

        val content = readProjectFileVfs("src/Imports.java")
        assertTrue("Should contain import on its own line", content.contains("import io.example.TaskStatus;"))
        assertFalse("Should not contain literal backslash-n", content.contains("\\n"))
    }

    fun testReplaceInvalidRegexFails() = runBlocking {
        writeProjectFile("src/BadRegex.java", """
            public class BadRegex {}
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/BadRegex.java")
            put("searchText", "[unclosed")
            put("replaceText", "x")
            put("regex", true)
        })

        assertTrue("Invalid regex should fail", result.isError)
    }
}

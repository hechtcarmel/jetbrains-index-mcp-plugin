package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReplaceTextInFileBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseResult(result: CallToolResult): ReplaceTextInFileTool.ReplaceTextResult =
        json.decodeFromString(toolText(result))

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

        assertToolSucceeded("Replace should succeed", result)
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

        assertToolSucceeded("Replace with no match should still succeed", result)
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

        assertToolSucceeded("Regex replace should succeed", result)
        val payload = parseResult(result)
        assertEquals(2, payload.replacements)

        assertFileContains("src/Logger.java", "LOG.trace(\"first message\")")
        assertFileContains("src/Logger.java", "LOG.trace(\"second message\")")
        assertFileDoesNotContain("src/Logger.java", "LOG.debug(")
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

        assertToolSucceeded("Case-insensitive replace should succeed", result)
        val payload = parseResult(result)
        assertEquals(3, payload.replacements)

        val content = readProjectFileVfs("src/Mixed.java")
        assertFalse("No casing of the old identifier should survive", content.lowercase().contains("todo"))
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

        assertToolFailed("Empty search should fail", result)
        assertEquals("searchText must not be empty.", toolText(result))
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

        assertToolFailed("Identical search/replace should fail", result)
        assertEquals("searchText and replaceText are identical — nothing to replace.", toolText(result))
    }

    fun testReplaceFileNotFound() = runBlocking {
        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/DoesNotExist.java")
            put("searchText", "a")
            put("replaceText", "b")
        })

        assertToolFailed("Missing file should fail", result)
        assertEquals("File not found: src/DoesNotExist.java", toolText(result))
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

        assertToolSucceeded("Replace should succeed", result)
        val payload = parseResult(result)
        assertEquals(1, payload.replacements)

        val content = readProjectFileVfs("src/Imports.java")
        assertTrue("Should contain import on its own line", content.contains("\nimport io.example.TaskStatus;"))
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

        assertToolFailed("Invalid regex should fail", result)
        assertTrue(
            "Error should name the invalid regex: ${toolText(result)}",
            toolText(result).startsWith("Invalid regex: ")
        )
    }
}

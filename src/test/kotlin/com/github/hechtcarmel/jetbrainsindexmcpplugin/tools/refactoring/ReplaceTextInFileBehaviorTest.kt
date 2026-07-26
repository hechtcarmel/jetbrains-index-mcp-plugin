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
        assertNull("No replacements means no affected lines", payload.affectedLines)
    }

    fun testAffectedLinesLiteralReplacement() = runBlocking {
        writeProjectFile("src/Lines.java", """
            public class Lines {
                int a = OLD; int b = OLD;
                int c = 1;
                int d = 2;
                int e = OLD;
            }
        """.trimIndent())

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Lines.java")
            put("searchText", "OLD")
            put("replaceText", "NEW")
        })

        assertToolSucceeded("Replace should succeed", result)
        val payload = parseResult(result)
        assertEquals(3, payload.replacements)
        assertEquals("Two matches on line 2 must collapse to one entry", listOf(2, 5), payload.affectedLines)

        assertFileDoesNotContain("src/Lines.java", "OLD")
        assertFileContains("src/Lines.java", "int a = NEW; int b = NEW;")
        assertFileContains("src/Lines.java", "int e = NEW;")
    }

    fun testAffectedLinesRegexReplacementTracksLineShifts() = runBlocking {
        writeProjectFile("src/Shift.java", "AAA FOO\nBBB FOO")

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Shift.java")
            put("searchText", "FOO")
            put("replaceText", "LONG1\\nLONG2")
            put("regex", true)
        })

        assertToolSucceeded("Regex replace should succeed", result)
        val payload = parseResult(result)
        assertEquals(2, payload.replacements)
        // The first replacement inserts a newline, pushing the second match from
        // line 2 of the old text to line 3 of the new text.
        assertEquals(listOf(1, 3), payload.affectedLines)

        assertFileDoesNotContain("src/Shift.java", "FOO")
        assertEquals("AAA LONG1\nLONG2\nBBB LONG1\nLONG2", readProjectFileVfs("src/Shift.java"))
    }

    fun testAffectedLinesCappedAtOneHundred() = runBlocking {
        val content = (1..150).joinToString("\n") { "// MARK line $it" }
        writeProjectFile("src/Many.java", content)

        val result = ReplaceTextInFileTool().execute(project, buildJsonObject {
            put("file", "src/Many.java")
            put("searchText", "MARK")
            put("replaceText", "DONE")
        })

        assertToolSucceeded("Replace should succeed", result)
        val payload = parseResult(result)
        assertEquals(150, payload.replacements)
        assertEquals((1..100).toList(), payload.affectedLines)

        assertFileDoesNotContain("src/Many.java", "MARK")
        assertFileContains("src/Many.java", "// DONE line 150")
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

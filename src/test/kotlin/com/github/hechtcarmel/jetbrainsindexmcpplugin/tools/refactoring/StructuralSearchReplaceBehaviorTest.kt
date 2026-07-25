package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StructuralSearchReplaceBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseResult(result: ToolCallResult): StructuralSearchReplaceTool.SsrResult =
        json.decodeFromString(toolText(result))

    // Fixtures use invented API names (SsrLogSink, SsrLegacyLog, ...) rather than JDK calls so that
    // exact match counts stay meaningful: SSR searches the whole project, and the light-fixture
    // project base path is shared across test methods. Each test therefore also gets its own
    // source root — the default `project_files` scope only covers files inside a content root, so
    // a fixture written outside one is invisible to the search and every count would be 0.

    fun testSearchOnlyReturnsMatches() = runBlocking {
        registerSourceRoot("ssr-search-src")
        writeProjectFile(
            "ssr-search-src/SsrLogger.java", """
            public class SsrLogger {
                public void log(String msg) {
                    SsrLogSink.write(msg);
                    SsrLogSink.write("debug: " + msg);
                }
            }
        """.trimIndent()
        )

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "SsrLogSink.write(\$arg\$)")
            put("filePattern", "*.java")
        })

        assertToolSucceeded("Structural search should succeed", result)
        val ssrResult = parseResult(result)
        assertEquals("Both call sites should match", 2, ssrResult.matchCount)
        assertEquals("Search-only must not replace", 0, ssrResult.replacedCount)
        assertEquals(
            "Matches should point at both call lines",
            setOf(3, 4),
            ssrResult.matches?.map { it.line }?.toSet()
        )
        assertTrue(
            "Matches should be reported against the searched file: ${ssrResult.matches}",
            ssrResult.matches!!.all { it.file.endsWith("SsrLogger.java") }
        )
        assertFileContains("ssr-search-src/SsrLogger.java", "SsrLogSink.write(msg);")
    }

    fun testSearchNoMatchesReturnsZero() = runBlocking {
        registerSourceRoot("ssr-empty-src")
        writeProjectFile(
            "ssr-empty-src/SsrEmpty.java", """
            public class SsrEmpty {
                public void nothing() {}
            }
        """.trimIndent()
        )

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "SsrAbsentApi.call(\$arg\$)")
        })

        assertToolSucceeded("Structural search should succeed", result)
        val ssrResult = parseResult(result)
        assertEquals("Should find zero matches", 0, ssrResult.matchCount)
        assertEquals("Nothing to replace in search-only mode", 0, ssrResult.replacedCount)
        assertTrue(
            "Match list should be empty, not absent: ${ssrResult.matches}",
            ssrResult.matches?.isEmpty() == true
        )
        assertEquals("Found 0 match(es)", ssrResult.message)
    }

    fun testReplaceRewritesEveryMatch() = runBlocking {
        registerSourceRoot("ssr-replace-src")
        writeProjectFile(
            "ssr-replace-src/SsrMigration.java", """
            public class SsrMigration {
                void run() {
                    SsrLegacyLog.print("hello");
                    SsrLegacyLog.print("world");
                }
            }
        """.trimIndent()
        )

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "SsrLegacyLog.print(\$arg\$)")
            put("replacePattern", "SsrModernLog.info(\$arg\$)")
            put("filePattern", "*.java")
        })

        assertToolSucceeded("Structural replace should succeed", result)
        val ssrResult = parseResult(result)
        assertEquals("Both call sites should match", 2, ssrResult.matchCount)
        assertEquals("Both matches should be replaced", 2, ssrResult.replacedCount)
        assertEquals("Replaced 2 of 2 match(es)", ssrResult.message)

        assertFileContains("ssr-replace-src/SsrMigration.java", "SsrModernLog.info(\"hello\")")
        assertFileContains("ssr-replace-src/SsrMigration.java", "SsrModernLog.info(\"world\")")
        assertFileDoesNotContain("ssr-replace-src/SsrMigration.java", "SsrLegacyLog")
    }

    fun testReplaceWithNoMatchesLeavesFileUntouched() = runBlocking {
        registerSourceRoot("ssr-untouched-src")
        writeProjectFile(
            "ssr-untouched-src/SsrUntouched.java", """
            public class SsrUntouched {
                void run() {
                    SsrKeep.call("value");
                }
            }
        """.trimIndent()
        )

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "SsrAbsentApi.call(\$arg\$)")
            put("replacePattern", "SsrModernLog.info(\$arg\$)")
            put("filePattern", "*.java")
        })

        assertToolSucceeded("Replace with no matches should succeed", result)
        val ssrResult = parseResult(result)
        assertEquals("Should find zero matches", 0, ssrResult.matchCount)
        assertEquals("Nothing should be replaced", 0, ssrResult.replacedCount)
        assertEquals("No matches found — nothing to replace.", ssrResult.message)
        assertFileContains("ssr-untouched-src/SsrUntouched.java", "SsrKeep.call(\"value\");")
    }

    fun testEmptySearchPatternFails() = runBlocking {
        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "")
        })

        assertToolFailed("Empty pattern should fail", result)
        assertEquals("searchPattern must not be empty.", toolText(result))
    }

    fun testMissingSearchPatternFails() = runBlocking {
        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {})

        assertToolFailed("Missing pattern should fail", result)
        assertEquals("Missing required parameter: searchPattern", toolText(result))
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Behavior of the `paths` parameter on `ide_structural_search_replace` (issue #328).
 *
 * The replace-mode test is the load-bearing one: a scope filter that only trimmed the
 * *reported* matches while still rewriting everything would pass any search-only check,
 * so both files are asserted in both directions after the replace.
 *
 * Fixtures use invented API names unique per test method (SSR searches the whole project
 * and the light-fixture project directory is shared across methods).
 */
class StructuralSearchReplacePathsBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseResult(result: CallToolResult): StructuralSearchReplaceTool.SsrResult =
        json.decodeFromString(toolText(result))

    fun testSearchOnlyPathsRestrictsMatches() = runBlocking {
        registerSourceRoot("ssrp-search-src")
        writeProjectFile(
            "ssrp-search-src/keep/SsrpKeep.java", """
            public class SsrpKeep {
                void run() {
                    SsrpSearchApi.call("kept");
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "ssrp-search-src/skip/SsrpSkip.java", """
            public class SsrpSkip {
                void run() {
                    SsrpSearchApi.call("skipped");
                }
            }
        """.trimIndent()
        )

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "SsrpSearchApi.call(\$arg\$)")
            put("filePattern", "*.java")
            putJsonArray("paths") { add("ssrp-search-src/keep/**") }
        })

        assertToolSucceeded("Structural search with paths should succeed", result)
        val ssrResult = parseResult(result)
        assertEquals("Only the call site inside the glob may match", 1, ssrResult.matchCount)
        assertEquals(
            listOf("ssrp-search-src/keep/SsrpKeep.java"),
            ssrResult.matches?.map { it.file }
        )
    }

    fun testReplaceOnlyRewritesFilesInsideTheGlob() = runBlocking {
        registerSourceRoot("ssrp-replace-src")
        writeProjectFile(
            "ssrp-replace-src/keep/SsrpMigrate.java", """
            public class SsrpMigrate {
                void run() {
                    SsrpOldApi.log("migrate-me");
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "ssrp-replace-src/skip/SsrpStay.java", """
            public class SsrpStay {
                void run() {
                    SsrpOldApi.log("leave-me");
                }
            }
        """.trimIndent()
        )

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "SsrpOldApi.log(\$arg\$)")
            put("replacePattern", "SsrpNewApi.log(\$arg\$)")
            put("filePattern", "*.java")
            putJsonArray("paths") { add("ssrp-replace-src/keep/**") }
        })

        assertToolSucceeded("Structural replace with paths should succeed", result)
        val ssrResult = parseResult(result)
        assertEquals("Only the in-glob call site may match", 1, ssrResult.matchCount)
        assertEquals("Only the in-glob call site may be replaced", 1, ssrResult.replacedCount)

        assertFileContains("ssrp-replace-src/keep/SsrpMigrate.java", "SsrpNewApi.log(\"migrate-me\")")
        assertFileDoesNotContain("ssrp-replace-src/keep/SsrpMigrate.java", "SsrpOldApi")
        assertFileContains("ssrp-replace-src/skip/SsrpStay.java", "SsrpOldApi.log(\"leave-me\")")
        assertFileDoesNotContain("ssrp-replace-src/skip/SsrpStay.java", "SsrpNewApi")
    }

    fun testUnresolvableIncludeGlobFailsInsteadOfReturningNoMatches() = runBlocking {
        registerSourceRoot("ssrp-bad-src")
        writeProjectFile(
            "ssrp-bad-src/real/SsrpReal.java", """
            public class SsrpReal {
                void run() {
                    SsrpBadApi.call("x");
                }
            }
        """.trimIndent()
        )

        val result = StructuralSearchReplaceTool().execute(project, buildJsonObject {
            put("searchPattern", "SsrpBadApi.call(\$arg\$)")
            putJsonArray("paths") { add("ssrp-bad-src/nosuchdir/**") }
        })

        assertToolFailed("A typo'd include glob must error, not report zero matches", result)
        assertTrue(
            "Error must name the offending glob, got: ${toolText(result)}",
            toolText(result).contains("ssrp-bad-src/nosuchdir/**")
        )
    }
}

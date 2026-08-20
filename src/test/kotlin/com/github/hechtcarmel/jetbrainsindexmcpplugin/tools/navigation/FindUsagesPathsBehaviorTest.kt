package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Behavior of the `paths` parameter on `ide_find_references` (issue #328): globs restrict
 * which reference locations are returned, composing with the built-in `scope`, and the
 * restriction applies inside the search scope itself (totalCount reflects it) rather than
 * post-filtering a page.
 */
class FindUsagesPathsBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(resultText: String): FindUsagesResult = json.decodeFromString(resultText)

    /** Two call sites of fuPing() in two sibling packages, plus the declaration. */
    private fun writeFixture() {
        registerSourceRoot("fu-paths-src")
        writeProjectFile(
            "fu-paths-src/fupaths/FuPathsService.java",
            """
            package fupaths;

            public class FuPathsService {
                public static void fuPing() {
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "fu-paths-src/fupaths/callera/FuCallerA.java",
            """
            package fupaths.callera;

            import fupaths.FuPathsService;

            public class FuCallerA {
                void go() {
                    FuPathsService.fuPing();
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "fu-paths-src/fupaths/callerb/FuCallerB.java",
            """
            package fupaths.callerb;

            import fupaths.FuPathsService;

            public class FuCallerB {
                void go() {
                    FuPathsService.fuPing();
                }
            }
            """.trimIndent()
        )
    }

    private suspend fun findUsagesOfFuPing(paths: List<String>?): FindUsagesResult {
        val result = FindUsagesTool().execute(project, buildJsonObject {
            put("file", "fu-paths-src/fupaths/FuPathsService.java")
            put("line", 4)
            put("column", 25)
            if (paths != null) {
                putJsonArray("paths") { paths.forEach { add(it) } }
            }
        })
        assertToolSucceeded("find_references on fuPing", result)
        return decode(toolText(result))
    }

    fun testWithoutPathsBothCallSitesAreFound() = runBlocking {
        writeFixture()

        val parsed = findUsagesOfFuPing(paths = null)
        assertEquals("Both call sites must be found without paths", 2, parsed.totalCount)
        assertEquals(
            setOf("fu-paths-src/fupaths/callera/FuCallerA.java", "fu-paths-src/fupaths/callerb/FuCallerB.java"),
            parsed.usages.map { it.file }.toSet()
        )
    }

    fun testIncludeGlobRestrictsUsagesAndTotalCount() = runBlocking {
        writeFixture()

        val parsed = findUsagesOfFuPing(paths = listOf("fu-paths-src/fupaths/callera/**"))
        assertEquals(
            "Only the callera call site may be returned",
            listOf("fu-paths-src/fupaths/callera/FuCallerA.java"),
            parsed.usages.map { it.file }
        )
        assertEquals(
            "The glob must narrow the search scope itself, not just the returned page",
            1,
            parsed.totalCount
        )
    }

    fun testExcludeGlobRemovesDirectoryFromResults() = runBlocking {
        writeFixture()

        val parsed = findUsagesOfFuPing(paths = listOf("!**/callerb/**"))
        assertEquals(
            "The excluded package's call site must be gone, the other kept",
            listOf("fu-paths-src/fupaths/callera/FuCallerA.java"),
            parsed.usages.map { it.file }
        )
    }

    fun testUnresolvableIncludeGlobFailsInsteadOfReturningNoUsages() = runBlocking {
        writeFixture()

        val result = FindUsagesTool().execute(project, buildJsonObject {
            put("file", "fu-paths-src/fupaths/FuPathsService.java")
            put("line", 4)
            put("column", 25)
            putJsonArray("paths") { add("fu-paths-src/nosuchpkg/**") }
        })

        assertToolFailed("A typo'd include glob must error, not report zero usages", result)
        assertTrue(
            "Error must name the offending glob, got: ${toolText(result)}",
            toolText(result).contains("fu-paths-src/nosuchpkg/**")
        )
    }
}

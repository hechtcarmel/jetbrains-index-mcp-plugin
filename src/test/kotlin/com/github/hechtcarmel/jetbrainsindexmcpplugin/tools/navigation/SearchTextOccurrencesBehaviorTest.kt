package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SearchTextResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `ide_search_text` returns every occurrence, not every line.
 *
 * Results were deduplicated by `file:line`, so the second match of `stxOccNeedle(2)` on the same
 * line as `stxOccNeedle(1)` was silently dropped. Each occurrence now has its own result and
 * column, for plain-text and regex queries alike.
 */
class SearchTextOccurrencesBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        registerSourceRoot("stx-occ-src")
        writeProjectFile(
            "stx-occ-src/occ/Twice.java",
            //language=java
            """
            package occ;

            class Twice {
                int sum() { return stxOccNeedle(1) + stxOccNeedle(2); }
                static int stxOccNeedle(int v) { return v; }
            }
            """.trimIndent()
        )
    }

    private fun columnsOnLine4(query: String, regex: Boolean): List<Int> = runBlocking {
        val result = SearchTextTool().execute(project, buildJsonObject {
            put("query", query)
            put("regex", regex)
            putJsonArray("paths") { add("stx-occ-src/**") }
        })
        assertToolSucceeded("search_text '$query'", result)
        json.decodeFromString<SearchTextResult>(toolText(result)).matches
            .filter { it.line == 4 }
            .map { it.column }
            .sorted()
    }

    fun testRegexReportsEveryMatchOnALine() {
        assertEquals(listOf(24, 42), columnsOnLine4("stxOccNeedle\\(\\d\\)", regex = true))
    }

    fun testPlainTextReportsEveryMatchOnALine() {
        assertEquals(listOf(24, 42), columnsOnLine4("stxOccNeedle(", regex = false))
    }
}

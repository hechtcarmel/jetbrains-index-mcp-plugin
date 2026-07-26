package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Behavior test for `ide_find_implementations` pagination beyond the default page size.
 *
 * The tool creates its pagination cursor with `searchExtender = null`, which the
 * `PaginationService` interprets as "the cached results are the complete set" when computing
 * `hasMore`. The language handlers used to truncate collection at 100 — exactly the default
 * page size — so an interface with more than 100 implementations produced a first page with
 * `hasMore = false` and no cursor: a silently truncated result presented as complete. This
 * test pins the fixed contract: everything is collected up front, the first page reports
 * `hasMore = true`, and cursor iteration surfaces every implementation exactly once.
 */
class FindImplementationsPaginationBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun decode(resultText: String): ImplementationResult =
        json.decodeFromString<ImplementationResult>(resultText)

    fun testMoreImplementationsThanDefaultPageSizeArePagedNotTruncated() = runBlocking {
        val implCount = 105
        registerSourceRoot("impl-pagination-src")
        writeProjectFile(
            "impl-pagination-src/implcap/Payload.java",
            """
            package implcap;

            public interface Payload {
                void handle();
            }
            """.trimIndent()
        )
        val implementations = (1..implCount).joinToString("\n\n") { i ->
            "class Impl%03d implements Payload {\n    public void handle() {\n    }\n}".format(i)
        }
        writeProjectFile(
            "impl-pagination-src/implcap/Impls.java",
            "package implcap;\n\n$implementations\n"
        )

        val tool = FindImplementationsTool()
        val firstPage = tool.execute(project, buildJsonObject {
            put("file", "impl-pagination-src/implcap/Payload.java")
            put("line", 3)
            put("column", 18)
        })
        assertToolSucceeded("first page of implementations", firstPage)
        val first = decode(toolText(firstPage))

        assertEquals("Default page size must cap the first page at 100", 100, first.implementations.size)
        assertEquals(
            "All $implCount implementations must be collected up front — 100 means the handler truncated the search",
            implCount,
            first.totalCollected
        )
        assertEquals(implCount, first.totalCount)
        assertTrue(
            "With $implCount implementations and page size 100 the first page must report hasMore",
            first.hasMore
        )
        assertNotNull("nextCursor must be issued when results exceed the page size", first.nextCursor)

        val seen = first.implementations.toMutableList()
        var page = first
        var guard = 0
        while (page.nextCursor != null) {
            assertTrue("Cursor iteration must terminate", guard++ < 20)
            val next = tool.execute(project, buildJsonObject { put("cursor", page.nextCursor!!) })
            assertToolSucceeded("cursor page $guard", next)
            page = decode(toolText(next))
            seen += page.implementations
        }

        assertFalse("Terminal page must report exhaustion", page.hasMore)
        assertEquals("Terminal page must still report the full collection", implCount, page.totalCollected)

        val expectedNames = (1..implCount).map { "implcap.Impl%03d".format(it) }.toSet()
        val seenNames = seen.map { it.name }.toSet()
        assertEquals(
            "Union of all pages must be exactly the $implCount implementations — nothing missing, nothing extra",
            expectedNames,
            seenNames
        )
        assertEquals("Pages must not overlap — each implementation appears exactly once", implCount, seen.size)
        assertTrue(
            "Every result must point at the declaring file, got: ${seen.map { it.file }.distinct()}",
            seen.all { it.file.endsWith("Impls.java") }
        )
    }
}

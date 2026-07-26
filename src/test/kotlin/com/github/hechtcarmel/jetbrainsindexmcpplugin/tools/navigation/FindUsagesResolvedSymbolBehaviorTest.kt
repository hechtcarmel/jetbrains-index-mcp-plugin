package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Behavior tests for the `resolvedSymbol` echo and `totalIsExact` flag of `ide_find_references`.
 *
 * Position-based lookup silently snaps comments/whitespace to the nearest enclosing named
 * element; the echo makes that snap visible to clients. `totalIsExact` distinguishes a true
 * total from the internal over-collection cap being reported as `totalCount`.
 */
class FindUsagesResolvedSymbolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(resultText: String): FindUsagesResult =
        json.decodeFromString<FindUsagesResult>(resultText)

    private fun writeEchoFixture() {
        registerSourceRoot("resolved-echo-src")
        writeProjectFile(
            "resolved-echo-src/echo/UserService.java",
            """
            package echo;

            public class UserService {
                /** Finds a user by id. */
                public String findUser(String id) {
                    return id;
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "resolved-echo-src/echo/UserClient.java",
            """
            package echo;

            public class UserClient {
                public String call() {
                    return new UserService().findUser("42");
                }
            }
            """.trimIndent()
        )
    }

    /**
     * A position inside the javadoc on line 4 does not error — it snaps to the commented
     * method declared on line 5. The echo must document that snap, and the usages must still
     * be the method's real call sites (not usages of some other element).
     */
    fun testCommentPositionEchoesSnappedSymbol() = runBlocking {
        writeEchoFixture()

        val result = FindUsagesTool().execute(project, buildJsonObject {
            put("file", "resolved-echo-src/echo/UserService.java")
            put("line", 4)
            put("column", 10)
        })
        assertToolSucceeded("find_references from a comment position", result)
        val parsed = decode(toolText(result))

        val resolved = parsed.resolvedSymbol
        assertNotNull("resolvedSymbol must echo the snapped target", resolved)
        assertEquals("Snap target must be the commented method", "findUser", resolved!!.name)
        assertEquals("Echoed line must be the declaration line, not the comment line", 5, resolved.line)
        assertTrue(
            "Echoed file must be the declaring file, got: ${resolved.file}",
            resolved.file?.endsWith("UserService.java") == true
        )

        assertEquals("Exactly one call site exists", 1, parsed.totalCount)
        assertEquals(1, parsed.usages.size)
        val usage = parsed.usages.single()
        assertTrue("Usage must be the call site in UserClient.java, got: ${usage.file}", usage.file.endsWith("UserClient.java"))
        assertEquals("Usage must point at the call line", 5, usage.line)
    }

    /**
     * A position directly on the identifier must produce the same echo — guards against the
     * echo describing the leaf token instead of the resolved declaration.
     */
    fun testIdentifierPositionEchoesSameSymbol() = runBlocking {
        writeEchoFixture()

        val result = FindUsagesTool().execute(project, buildJsonObject {
            put("file", "resolved-echo-src/echo/UserService.java")
            put("line", 5)
            put("column", 19)
        })
        assertToolSucceeded("find_references from the identifier position", result)
        val parsed = decode(toolText(result))

        val resolved = parsed.resolvedSymbol
        assertNotNull("resolvedSymbol must be echoed for exact positions too", resolved)
        assertEquals("findUser", resolved!!.name)
        assertEquals(5, resolved.line)
        assertTrue(
            "Echoed file must be the declaring file, got: ${resolved.file}",
            resolved.file?.endsWith("UserService.java") == true
        )
        assertEquals(1, parsed.usages.size)
        assertTrue(parsed.usages.single().file.endsWith("UserClient.java"))
    }

    /**
     * With fewer references than the internal collection cap, every page must report an exact
     * total, and every page — not just the first — must carry the resolvedSymbol echo through
     * the cursor metadata round-trip.
     */
    fun testTotalIsExactTrueAndEchoSurvivesPagination() = runBlocking {
        registerSourceRoot("exact-total-src")
        writeProjectFile(
            "exact-total-src/exact/Ping.java",
            """
            package exact;

            public class Ping {
                public void ping() {
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "exact-total-src/exact/PingCaller.java",
            """
            package exact;

            public class PingCaller {
                public void callAll(Ping p) {
                    p.ping();
                    p.ping();
                    p.ping();
                }
            }
            """.trimIndent()
        )

        val tool = FindUsagesTool()
        val firstPage = tool.execute(project, buildJsonObject {
            put("file", "exact-total-src/exact/Ping.java")
            put("line", 4)
            put("column", 17)
            put("pageSize", 2)
        })
        assertToolSucceeded("first page", firstPage)
        val first = decode(toolText(firstPage))

        assertEquals("All 3 references must be counted on the first page", 3, first.totalCount)
        assertTrue("More pages exist", first.truncated)
        assertTrue("totalCount must be exact below the collection cap", first.totalIsExact)
        assertNotNull("First page carries the echo", first.resolvedSymbol)
        assertEquals("ping", first.resolvedSymbol!!.name)
        assertEquals(4, first.resolvedSymbol!!.line)
        assertNotNull("nextCursor expected with 3 results and pageSize 2", first.nextCursor)
        assertEquals(2, first.usages.size)

        val secondPage = tool.execute(project, buildJsonObject {
            put("cursor", first.nextCursor!!)
        })
        assertToolSucceeded("second page", secondPage)
        val second = decode(toolText(secondPage))

        assertEquals("Echo must survive the cursor round-trip", first.resolvedSymbol, second.resolvedSymbol)
        assertTrue("Exactness must survive the cursor round-trip", second.totalIsExact)
        assertEquals(3, second.totalCount)
        assertFalse("No further pages", second.hasMore)
        assertEquals(1, second.usages.size)

        val allLines = (first.usages + second.usages).map { it.line }.toSortedSet()
        assertEquals("The three distinct call lines must be returned", sortedSetOf(5, 6, 7), allLines)
        assertTrue((first.usages + second.usages).all { it.file.endsWith("PingCaller.java") })
    }

    /**
     * With more references than the 500 collection cap, the first page must stop reporting the
     * cap as the true total: totalIsExact=false marks totalCount as a lower bound. Once cursor
     * iteration exhausts the search, the final page must report the true count as exact.
     */
    fun testTotalCountIsLowerBoundWhenCapped() = runBlocking {
        val refCount = 520
        registerSourceRoot("capped-total-src")
        writeProjectFile(
            "capped-total-src/capped/Hot.java",
            """
            package capped;

            public class Hot {
                public void hot() {
                }
            }
            """.trimIndent()
        )
        val calls = (1..refCount).joinToString("\n") { "        h.hot();" }
        writeProjectFile(
            "capped-total-src/capped/HotCaller.java",
            "package capped;\n\npublic class HotCaller {\n    public void callAll(Hot h) {\n$calls\n    }\n}\n"
        )

        val tool = FindUsagesTool()
        val firstPage = tool.execute(project, buildJsonObject {
            put("file", "capped-total-src/capped/Hot.java")
            put("line", 4)
            put("column", 17)
        })
        assertToolSucceeded("first capped page", firstPage)
        val first = decode(toolText(firstPage))

        assertEquals("First page reports only the collection cap", 500, first.totalCount)
        assertFalse("Capped totalCount must be marked as a lower bound", first.totalIsExact)
        assertNotNull(first.resolvedSymbol)
        assertEquals("hot", first.resolvedSymbol!!.name)
        // The wire payload must actually carry the flag — a decode default would hide its removal.
        val rawFirst = json.parseToJsonElement(toolText(firstPage)).jsonObject
        assertTrue("totalIsExact must be serialized on the wire", rawFirst.containsKey("totalIsExact"))

        var page = first
        val seen = page.usages.toMutableList()
        var guard = 0
        while (page.nextCursor != null) {
            assertTrue("Cursor iteration must terminate", guard++ < 50)
            val next = tool.execute(project, buildJsonObject { put("cursor", page.nextCursor!!) })
            assertFalse("Cursor page should succeed: ${toolText(next)}", next.isFailure)
            page = decode(toolText(next))
            seen += page.usages
        }

        assertEquals("Exhausted iteration must surface every reference", refCount, seen.size)
        assertEquals("Final page must report the true total", refCount, page.totalCount)
        assertTrue("Total is exact once the search is exhausted", page.totalIsExact)
        assertEquals("Echo must persist to the final page", "hot", page.resolvedSymbol?.name)
    }
}

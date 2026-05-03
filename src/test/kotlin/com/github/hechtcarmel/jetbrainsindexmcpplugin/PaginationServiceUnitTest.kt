package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import junit.framework.TestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class PaginationServiceUnitTest : TestCase() {

    // --- Encoding tests ---

    fun testEncodeCursorRoundTrip() {
        val service = createTestService()
        val token = service.encodeCursor("entry123", 200)
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals("entry123", decoded!!.entryId)
        assertEquals(200, decoded.offset)
        assertNull(decoded.pageSize)
    }

    fun testEncodeCursorWithPageSizeRoundTrip() {
        val service = createTestService()
        val token = service.encodeCursor("entry123", 200, 25)
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals("entry123", decoded!!.entryId)
        assertEquals(200, decoded.offset)
        assertEquals(25, decoded.pageSize)
    }

    fun testDecodeMalformedToken() {
        val service = createTestService()
        assertNull(service.decodeCursor("not-valid-base64!!"))
        assertNull(service.decodeCursor(""))
        assertNull(service.decodeCursor("YWJj"))  // base64 of "abc" - no colon separator
    }

    /**
     * Contract: PaginationService maintains strict decode behavior for non-empty malformed cursors.
     * Blank-cursor bypass (treating "" and whitespace as fresh-search signals) is a tool-layer
     * concern, handled by paginated tools (FindUsagesTool, FindClassTool, etc.) via argument
     * normalization helpers in AbstractMcpTool. This separation ensures:
     * - PaginationService remains a pure cursor codec with no knowledge of tool semantics
     * - Tools control when blank cursors trigger fresh searches vs. cursor-path validation
     * - Malformed non-empty cursors always fail decode, preventing accidental fallthrough
     */
    fun testMalformedNonEmptyCursorFailsDecodeStrictly() {
        val service = createTestService()
        // Non-empty malformed cursors must fail decode — tools cannot accidentally bypass validation
        assertNull(service.decodeCursor("invalid-token-format"))
        assertNull(service.decodeCursor("not-base64!!"))
        assertNull(service.decodeCursor("YWJj"))  // base64 of "abc" - no colon separator
    }

    fun testDecodeCursorWithZeroOffset() {
        val service = createTestService()
        val token = service.encodeCursor("id", 0)
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals(0, decoded!!.offset)
    }

    fun testEncodeCursorWithUuidLikeId() {
        val service = createTestService()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val token = service.encodeCursor(uuid, 500)
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals(uuid, decoded!!.entryId)
        assertEquals(500, decoded.offset)
    }

    fun testLegacyCursorWithoutPageSizeStillDecodes() {
        val service = createTestService()
        // Legacy format: entryId:offset (no pageSize)
        val token = service.encodeCursor("abc123", 42)
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals("abc123", decoded!!.entryId)
        assertEquals(42, decoded.offset)
        assertNull(decoded.pageSize)
    }

    // --- createCursor & LRU eviction ---

    fun testCreateCursorReturnsToken() {
        val service = createTestService()
        val results = listOf(
            PaginationService.SerializedResult("key1", JsonPrimitive("data1")),
            PaginationService.SerializedResult("key2", JsonPrimitive("data2"))
        )
        val token = service.createCursor("test_tool", results, setOf("key1", "key2"), null, 42L, "/project/path")
        assertNotNull(token)
        assertTrue(token.isNotEmpty())
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals(0, decoded!!.offset)
    }

    fun testLruEvictionWhenAtCapacity() {
        val service = createTestService()
        val tokens = mutableListOf<String>()
        for (i in 1..PaginationService.MAX_CURSORS) {
            tokens.add(service.createCursor("tool", emptyList(), emptySet(), null, 0L, "/path"))
            Thread.sleep(2)
        }
        val newToken = service.createCursor("tool", emptyList(), emptySet(), null, 0L, "/path")
        assertNotNull(newToken)
        assertTrue(newToken.isNotEmpty())
    }

    // --- getPage core logic ---

    fun testGetPageSuccess() = runBlocking {
        val service = createTestService()
        val results = (1..50).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, results.map { it.key }.toSet(), null, 42L, "/project")
        val result = service.getPage(token, 10, "/project", 42L)
        assertTrue(result is PaginationService.GetPageResult.Success)
        val page = (result as PaginationService.GetPageResult.Success).page
        assertEquals(10, page.items.size)
        assertEquals(0, page.offset)
        assertTrue(page.hasMore)
        assertFalse(page.stale)
        assertNotNull(page.nextCursor)
    }

    fun testGetPageSecondPage() = runBlocking {
        val service = createTestService()
        val results = (1..50).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, results.map { it.key }.toSet(), null, 42L, "/project")
        val firstResult = service.getPage(token, 10, "/project", 42L) as PaginationService.GetPageResult.Success
        val nextCursor = firstResult.page.nextCursor!!
        val secondResult = service.getPage(nextCursor, 10, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(10, secondResult.page.offset)
        assertEquals(JsonPrimitive("data11"), secondResult.page.items.first())
    }

    fun testGetPageIdempotent() = runBlocking {
        val service = createTestService()
        val results = (1..10).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, emptySet(), null, 42L, "/project")
        val r1 = service.getPage(token, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        val r2 = service.getPage(token, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(r1.page.items, r2.page.items)
    }

    fun testGetPageMalformedToken() = runBlocking {
        val service = createTestService()
        val result = service.getPage("garbage", 10, "/project", 42L)
        assertTrue(result is PaginationService.GetPageResult.Error)
        assertEquals(PaginationService.CursorError.MALFORMED, (result as PaginationService.GetPageResult.Error).reason)
    }

    /**
     * Contract: PaginationService.getPage() rejects malformed non-empty cursors with MALFORMED error.
     * Blank cursors ("", whitespace) are NOT normalized here — that is a tool-layer responsibility.
     * Tools must normalize blank cursors to null before calling getPage(), triggering fresh-search logic.
     * This ensures PaginationService remains a pure cursor codec without tool-specific semantics.
     */
    fun testGetPageRejectsNonEmptyMalformedCursorStrictly() = runBlocking {
        val service = createTestService()
        // Non-empty malformed cursors fail decode — tools cannot accidentally bypass validation
        val result = service.getPage("not-a-valid-cursor-token", 10, "/project", 42L)
        assertTrue(result is PaginationService.GetPageResult.Error)
        assertEquals(PaginationService.CursorError.MALFORMED, (result as PaginationService.GetPageResult.Error).reason)
    }

    fun testGetPageWrongProject() = runBlocking {
        val service = createTestService()
        val token = service.createCursor("tool", emptyList(), emptySet(), null, 42L, "/project-a")
        val result = service.getPage(token, 10, "/project-b", 42L)
        assertTrue(result is PaginationService.GetPageResult.Error)
        assertEquals(PaginationService.CursorError.WRONG_PROJECT, (result as PaginationService.GetPageResult.Error).reason)
    }

    fun testGetPageStaleDetection() = runBlocking {
        val service = createTestService()
        val results = (1..10).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, emptySet(), null, 42L, "/project")
        val result = service.getPage(token, 10, "/project", 99L) as PaginationService.GetPageResult.Success
        assertTrue(result.page.stale)
    }

    fun testGetPageLastPageHasMoreFalse() = runBlocking {
        val service = createTestService()
        val results = (1..5).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, emptySet(), null, 42L, "/project")
        val result = service.getPage(token, 10, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(5, result.page.items.size)
        assertFalse(result.page.hasMore)
        assertNull(result.page.nextCursor)
    }

    fun testConcurrentPageRequests() = runBlocking {
        val service = createTestService()
        val results = (1..100).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, results.map { it.key }.toSet(), null, 42L, "/project")
        val page1 = service.getPage(token, 50, "/project", 42L) as PaginationService.GetPageResult.Success
        val nextToken = page1.page.nextCursor!!
        val (r1, r2) = coroutineScope {
            val d1 = async { service.getPage(nextToken, 50, "/project", 42L) }
            val d2 = async { service.getPage(nextToken, 50, "/project", 42L) }
            Pair(d1.await(), d2.await())
        }
        assertEquals(
            (r1 as PaginationService.GetPageResult.Success).page.items,
            (r2 as PaginationService.GetPageResult.Success).page.items
        )
    }

    // --- Cache extension via searchExtender ---

    fun testExtenderCalledWhenCacheExhausted() = runBlocking {
        val service = createTestService()
        val initial = (1..5).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        var extenderCallCount = 0
        val extender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { seen, _ ->
            extenderCallCount++
            // Return new results that aren't already seen
            (6..10).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
                .filter { it.key !in seen }
        }
        val token = service.createCursor("tool", initial, initial.map { it.key }.toSet(), extender, 42L, "/project")
        val p1 = service.getPage(token, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        assertTrue(extenderCallCount > 0)
        assertTrue(p1.page.hasMore)
        val p2 = service.getPage(p1.page.nextCursor!!, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(5, p2.page.items.size)
    }

    fun testMaxCachedResultsStopsExtension() = runBlocking {
        val service = createTestService()
        val max = PaginationService.MAX_CACHED_RESULTS_PER_CURSOR
        val results = (1..max).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        var extenderCalled = false
        val extender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { _, _ ->
            extenderCalled = true
            listOf(PaginationService.SerializedResult("extra", JsonPrimitive("extra")))
        }
        val token = service.createCursor("tool", results, results.map { it.key }.toSet(), extender, 42L, "/project")
        val lastOffset = max - 10
        val cursor = service.encodeCursor(service.decodeCursor(token)!!.entryId, lastOffset)
        val result = service.getPage(cursor, 100, "/project", 42L) as PaginationService.GetPageResult.Success
        assertFalse(extenderCalled)
        assertFalse(result.page.hasMore)
    }

    fun testExtenderFailureReturnsSearchInvalidated() = runBlocking {
        val service = createTestService()
        // Use more results than pageSize so extension is NOT triggered on first page
        val initial = (1..5).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val extender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { _, _ ->
            throw IllegalStateException("Target element no longer valid")
        }
        val token = service.createCursor("tool", initial, initial.map { it.key }.toSet(), extender, 42L, "/project")
        // First page: offset=0, pageSize=3 → 0+3 < 5 → no extension
        val p1 = service.getPage(token, 3, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(3, p1.page.items.size)
        // Second page: offset=3, pageSize=3 → 3+3 >= 5 → extension triggered → fails
        val result = service.getPage(p1.page.nextCursor!!, 3, "/project", 42L)
        assertTrue(result is PaginationService.GetPageResult.Error)
        assertEquals(PaginationService.CursorError.SEARCH_INVALIDATED, (result as PaginationService.GetPageResult.Error).reason)
    }

    // --- BUG-1: hasMore=false when extender is exhausted ---

    fun testHasMoreFalseWhenExtenderExhaustedOnSinglePage() = runBlocking {
        val service = createTestService()
        val initial = (1..10).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val extender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { _, _ ->
            emptyList() // No more results
        }
        val token = service.createCursor("tool", initial, initial.map { it.key }.toSet(), extender, 42L, "/project")
        // Request all results in one page — extender returns empty
        val result = service.getPage(token, 500, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(10, result.page.items.size)
        assertFalse("hasMore should be false when extender is exhausted", result.page.hasMore)
        assertNull(result.page.nextCursor)
    }

    fun testHasMoreFalseOnLastPageWithExtender() = runBlocking {
        val service = createTestService()
        val initial = (1..10).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val extender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { _, _ ->
            emptyList()
        }
        val token = service.createCursor("tool", initial, initial.map { it.key }.toSet(), extender, 42L, "/project")
        // First page: 5 results
        val p1 = service.getPage(token, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(5, p1.page.items.size)
        assertTrue(p1.page.hasMore)
        // Second page: remaining 5 — extender probed and returns empty
        val p2 = service.getPage(p1.page.nextCursor!!, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(5, p2.page.items.size)
        assertFalse("hasMore should be false on last page when extender is exhausted", p2.page.hasMore)
        assertNull(p2.page.nextCursor)
    }

    fun testHasMoreTrueWhenExtenderReturnsResults() = runBlocking {
        val service = createTestService()
        val initial = (1..5).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val extender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { seen, _ ->
            (6..15).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
                .filter { it.key !in seen }
        }
        val token = service.createCursor("tool", initial, initial.map { it.key }.toSet(), extender, 42L, "/project")
        // Request exactly 5 → extension probed → finds 10 more → hasMore=true
        val result = service.getPage(token, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(5, result.page.items.size)
        assertTrue("hasMore should be true when extender added results", result.page.hasMore)
    }

    // --- BUG-3: pageSize preserved in cursor ---

    fun testPageSizePreservedInNextCursor() = runBlocking {
        val service = createTestService()
        val results = (1..50).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, results.map { it.key }.toSet(), null, 42L, "/project")
        // Request with pageSize=7
        val p1 = service.getPage(token, 7, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(7, p1.page.items.size)
        // Decode nextCursor — should have pageSize=7 embedded
        val decoded = service.decodeCursor(p1.page.nextCursor!!)
        assertNotNull(decoded)
        assertEquals(7, decoded!!.pageSize)
    }

    fun testCursorPageSizeUsedWhenNoExplicitPageSize() = runBlocking {
        val service = createTestService()
        val results = (1..50).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, results.map { it.key }.toSet(), null, 42L, "/project")
        // First request: pageSize=7
        val p1 = service.getPage(token, 7, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(7, p1.page.items.size)
        // Second request: null pageSize → uses cursor-embedded 7
        val p2 = service.getPage(p1.page.nextCursor!!, null, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(7, p2.page.items.size)
        assertEquals(7, p2.page.offset)
    }

    fun testExplicitPageSizeOverridesCursorPageSize() = runBlocking {
        val service = createTestService()
        val results = (1..50).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, results.map { it.key }.toSet(), null, 42L, "/project")
        // First request: pageSize=7
        val p1 = service.getPage(token, 7, "/project", 42L) as PaginationService.GetPageResult.Success
        // Second request: explicit pageSize=3 overrides cursor's 7
        val p2 = service.getPage(p1.page.nextCursor!!, 3, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals(3, p2.page.items.size)
    }

    fun testNullPageSizeWithLegacyCursorReturnsMalformed() = runBlocking {
        val service = createTestService()
        val results = (1..10).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, results.map { it.key }.toSet(), null, 42L, "/project")
        // createCursor returns a legacy cursor (no pageSize) — passing null should fail gracefully
        val result = service.getPage(token, null, "/project", 42L)
        assertTrue(result is PaginationService.GetPageResult.Error)
        assertEquals(PaginationService.CursorError.MALFORMED, (result as PaginationService.GetPageResult.Error).reason)
    }

    // --- Periodic sweep, TTL expiry, and dispose ---

    fun testExpiredCursorReturnsError() = runBlocking {
        val service = createTestService()
        val results = listOf(PaginationService.SerializedResult("k", JsonPrimitive("v")))
        val token = service.createCursor("tool", results, emptySet(), null, 0L, "/project")
        service.expireEntryForTesting(service.decodeCursor(token)!!.entryId)
        val result = service.getPage(token, 10, "/project", 0L)
        assertTrue(result is PaginationService.GetPageResult.Error)
        assertEquals(PaginationService.CursorError.EXPIRED, (result as PaginationService.GetPageResult.Error).reason)
    }

    fun testDisposeClearsAllEntries() = runBlocking {
        val service = createTestService()
        val token = service.createCursor("tool", emptyList(), emptySet(), null, 0L, "/project")
        service.dispose()
        val result = service.getPage(token, 10, "/project", 0L)
        assertTrue(result is PaginationService.GetPageResult.Error)
        assertEquals(PaginationService.CursorError.NOT_FOUND, (result as PaginationService.GetPageResult.Error).reason)
    }

    // --- Metadata round-trip ---

    fun testMetadataRoundTrip() = runBlocking {
        val service = createTestService()
        val results = (1..10).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val token = service.createCursor("tool", results, emptySet(), null, 42L, "/project",
            metadata = mapOf("query" to "test_query"))
        val result = service.getPage(token, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        assertEquals("test_query", result.page.metadata["query"])
    }

    // --- Helper ---

    private fun createTestService(): PaginationService {
        return PaginationService(CoroutineScope(Dispatchers.Default))
    }
}

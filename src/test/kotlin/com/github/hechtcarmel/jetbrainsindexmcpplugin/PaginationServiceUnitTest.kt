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

    // --- Task 1: Encoding tests ---

    fun testEncodeCursorRoundTrip() {
        val service = createTestService()
        val token = service.encodeCursor("entry123", 200)
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals("entry123", decoded!!.first)
        assertEquals(200, decoded.second)
    }

    fun testDecodeMalformedToken() {
        val service = createTestService()
        assertNull(service.decodeCursor("not-valid-base64!!"))
        assertNull(service.decodeCursor(""))
        assertNull(service.decodeCursor("YWJj"))  // base64 of "abc" - no colon separator
    }

    fun testDecodeCursorWithZeroOffset() {
        val service = createTestService()
        val token = service.encodeCursor("id", 0)
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals(0, decoded!!.second)
    }

    fun testEncodeCursorWithUuidLikeId() {
        val service = createTestService()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val token = service.encodeCursor(uuid, 500)
        val decoded = service.decodeCursor(token)
        assertNotNull(decoded)
        assertEquals(uuid, decoded!!.first)
        assertEquals(500, decoded.second)
    }

    // --- Task 2: createCursor & LRU eviction ---

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
        assertEquals(0, decoded!!.second)
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

    // --- Task 3: getPage core logic ---

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

    // --- Task 4: Cache extension via searchExtender ---

    fun testExtenderCalledWhenCacheExhausted() = runBlocking {
        val service = createTestService()
        val initial = (1..5).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        var extenderCalled = false
        val extender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { seen, _ ->
            extenderCalled = true
            assertEquals(5, seen.size)
            (6..10).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        }
        val token = service.createCursor("tool", initial, initial.map { it.key }.toSet(), extender, 42L, "/project")
        val p1 = service.getPage(token, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        val p2 = service.getPage(p1.page.nextCursor!!, 5, "/project", 42L) as PaginationService.GetPageResult.Success
        assertTrue(extenderCalled)
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
        val cursor = service.encodeCursor(service.decodeCursor(token)!!.first, lastOffset)
        val result = service.getPage(cursor, 100, "/project", 42L) as PaginationService.GetPageResult.Success
        assertFalse(extenderCalled)
        assertFalse(result.page.hasMore)
    }

    fun testExtenderFailureReturnsSearchInvalidated() = runBlocking {
        val service = createTestService()
        val initial = (1..3).map { PaginationService.SerializedResult("key$it", JsonPrimitive("data$it")) }
        val extender: suspend (Set<String>, Int) -> List<PaginationService.SerializedResult> = { _, _ ->
            throw IllegalStateException("Target element no longer valid")
        }
        val token = service.createCursor("tool", initial, initial.map { it.key }.toSet(), extender, 42L, "/project")
        val p1 = service.getPage(token, 3, "/project", 42L) as PaginationService.GetPageResult.Success
        val result = service.getPage(p1.page.nextCursor!!, 3, "/project", 42L)
        assertTrue(result is PaginationService.GetPageResult.Error)
        assertEquals(PaginationService.CursorError.SEARCH_INVALIDATED, (result as PaginationService.GetPageResult.Error).reason)
    }

    // --- Task 5: Periodic sweep, TTL expiry, and dispose ---

    fun testExpiredCursorReturnsError() = runBlocking {
        val service = createTestService()
        val results = listOf(PaginationService.SerializedResult("k", JsonPrimitive("v")))
        val token = service.createCursor("tool", results, emptySet(), null, 0L, "/project")
        service.expireEntryForTesting(service.decodeCursor(token)!!.first)
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

    // --- Helper ---

    private fun createTestService(): PaginationService {
        return PaginationService(CoroutineScope(Dispatchers.Default))
    }
}

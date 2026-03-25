package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import junit.framework.TestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    // --- Helper ---

    private fun createTestService(): PaginationService {
        return PaginationService(CoroutineScope(Dispatchers.Default))
    }
}

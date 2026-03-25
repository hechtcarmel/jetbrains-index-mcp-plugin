package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import junit.framework.TestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class PaginationServiceUnitTest : TestCase() {

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

    private fun createTestService(): PaginationService {
        return PaginationService(CoroutineScope(Dispatchers.Default))
    }
}

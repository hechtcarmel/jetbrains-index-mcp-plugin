package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import junit.framework.TestCase

class StreamableHttpSessionManagerUnitTest : TestCase() {

    private lateinit var manager: StreamableHttpSessionManager

    override fun setUp() {
        super.setUp()
        manager = StreamableHttpSessionManager()
    }

    fun testCreateSessionReturnsNonEmptyId() {
        val sessionId = manager.createSession()
        assertTrue("Session ID should not be empty", sessionId.isNotEmpty())
    }

    fun testCreateSessionReturnsUniqueIds() {
        val ids = (1..100).map { manager.createSession() }.toSet()
        assertEquals("All session IDs should be unique", 100, ids.size)
    }

    fun testGetSessionReturnsNullForUnknownId() {
        assertNull(manager.getSession("nonexistent"))
    }

    fun testGetSessionReturnsSessionAfterCreate() {
        val sessionId = manager.createSession()
        assertNotNull(manager.getSession(sessionId))
    }

    fun testSessionContainsCorrectId() {
        val sessionId = manager.createSession()
        val session = manager.getSession(sessionId)
        assertEquals(sessionId, session?.sessionId)
    }

    fun testRemoveSessionMakesItUnavailable() {
        val sessionId = manager.createSession()
        assertNotNull(manager.getSession(sessionId))
        manager.removeSession(sessionId)
        assertNull(manager.getSession(sessionId))
    }

    fun testRemoveNonexistentSessionDoesNotThrow() {
        manager.removeSession("nonexistent")
    }

    fun testGetActiveSessionCount() {
        assertEquals(0, manager.getActiveSessionCount())
        val id1 = manager.createSession()
        assertEquals(1, manager.getActiveSessionCount())
        manager.createSession()
        assertEquals(2, manager.getActiveSessionCount())
        manager.removeSession(id1)
        assertEquals(1, manager.getActiveSessionCount())
    }

    fun testCloseAllSessions() {
        manager.createSession()
        manager.createSession()
        manager.createSession()
        assertEquals(3, manager.getActiveSessionCount())
        manager.closeAllSessions()
        assertEquals(0, manager.getActiveSessionCount())
    }

    fun testSessionIdContainsOnlyVisibleAscii() {
        val sessionId = manager.createSession()
        sessionId.forEach { char ->
            assertTrue(
                "Character '$char' (${char.code}) should be visible ASCII (0x21-0x7E)",
                char.code in 0x21..0x7E
            )
        }
    }
}

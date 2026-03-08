package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.intellij.openapi.diagnostic.logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Streamable HTTP sessions for the MCP server (protocol 2025-03-26).
 *
 * Each session is created during the `initialize` handshake and identified by
 * an `Mcp-Session-Id` header. Sessions are independent from legacy SSE sessions.
 *
 * Thread-safe using ConcurrentHashMap.
 */
class StreamableHttpSessionManager {

    private val sessions = ConcurrentHashMap<String, StreamableHttpSession>()

    companion object {
        private val LOG = logger<StreamableHttpSessionManager>()
    }

    fun createSession(): String {
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val session = StreamableHttpSession(sessionId)
        sessions[sessionId] = session
        LOG.info("Created Streamable HTTP session: $sessionId (active sessions: ${sessions.size})")
        return sessionId
    }

    fun getSession(sessionId: String): StreamableHttpSession? = sessions[sessionId]

    fun removeSession(sessionId: String) {
        val removed = sessions.remove(sessionId)
        if (removed != null) {
            LOG.info("Removed Streamable HTTP session: $sessionId (active sessions: ${sessions.size})")
        }
    }

    fun getActiveSessionCount(): Int = sessions.size

    fun closeAllSessions() {
        sessions.keys.toList().forEach { removeSession(it) }
    }
}

class StreamableHttpSession(val sessionId: String)

package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.intellij.openapi.diagnostic.logger
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active SSE sessions for the Ktor-based MCP server.
 *
 * Each SSE connection is assigned a unique session ID. When a client POSTs
 * a JSON-RPC request, the session ID is used to route the response back
 * through the correct SSE stream.
 *
 * Thread-safe using ConcurrentHashMap.
 */
class KtorSseSessionManager {

    private val sessions = ConcurrentHashMap<String, KtorSseSession>()

    companion object {
        private val LOG = logger<KtorSseSessionManager>()
    }

    /**
     * Creates a new SSE session.
     *
     * @return The generated session ID
     */
    fun createSession(): String {
        val sessionId = UUID.randomUUID().toString()
        val session = KtorSseSession(sessionId)
        sessions[sessionId] = session
        LOG.info("Created SSE session: $sessionId (active sessions: ${sessions.size})")
        return sessionId
    }

    /**
     * Registers a channel for sending events to a session.
     *
     * @param sessionId The session ID
     * @param channel The channel to send events through
     */
    fun registerChannel(sessionId: String, channel: Channel<String>) {
        sessions[sessionId]?.channel = channel
    }

    /**
     * Gets an active session by ID.
     *
     * @param sessionId The session ID
     * @return The session, or null if not found
     */
    fun getSession(sessionId: String): KtorSseSession? = sessions[sessionId]

    /**
     * Removes a session by ID.
     *
     * @param sessionId The session ID to remove
     */
    fun removeSession(sessionId: String) {
        val removed = sessions.remove(sessionId)
        if (removed != null) {
            removed.channel?.close()
            LOG.info("Removed SSE session: $sessionId (active sessions: ${sessions.size})")
        }
    }

    /**
     * Sends an SSE event to a specific session.
     *
     * @param sessionId The session ID
     * @param eventType The SSE event type (e.g., "message", "endpoint")
     * @param data The event data
     * @return true if sent successfully, false if session not found or inactive
     */
    suspend fun sendEvent(sessionId: String, eventType: String, data: String): Boolean {
        val session = sessions[sessionId]
        if (session == null) {
            LOG.warn("Cannot send event to session $sessionId: session not found")
            return false
        }
        return session.sendEvent(eventType, data)
    }

    /**
     * Gets the number of active sessions.
     */
    fun getActiveSessionCount(): Int = sessions.size

    /**
     * Closes all active sessions.
     */
    fun closeAllSessions() {
        sessions.keys.toList().forEach { sessionId ->
            removeSession(sessionId)
        }
    }
}

/**
 * Represents an active SSE session for Ktor.
 *
 * @param sessionId The unique session identifier
 */
class KtorSseSession(val sessionId: String) {

    internal var channel: Channel<String>? = null

    companion object {
        private val LOG = logger<KtorSseSession>()
    }

    /**
     * Checks if the session has an active channel.
     */
    fun isActive(): Boolean = channel?.isClosedForSend == false

    /**
     * Sends an SSE event over this session's channel.
     *
     * @param eventType The SSE event type
     * @param data The event data (will be sent as-is in the data field)
     * @return true if sent successfully
     */
    suspend fun sendEvent(eventType: String, data: String): Boolean {
        val ch = channel
        if (ch == null || ch.isClosedForSend) {
            LOG.warn("Cannot send event to session $sessionId: channel inactive")
            return false
        }

        return try {
            // SSE spec: each line of data must be prefixed with "data: "
            val dataLines = data.lines().joinToString("\n") { "data: $it" }
            val sseEvent = "event: $eventType\n$dataLines\n\n"
            ch.send(sseEvent)
            true
        } catch (e: ClosedSendChannelException) {
            LOG.warn("Channel closed while sending event to session $sessionId")
            false
        } catch (e: Exception) {
            LOG.error("Failed to send SSE event to session $sessionId", e)
            false
        }
    }
}

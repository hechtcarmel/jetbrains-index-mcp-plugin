package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.intellij.openapi.diagnostic.logger
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Legacy HTTP+SSE transport (MCP 2024-11-05).
 *
 * Hand-wired rather than using the SDK's `Route.mcp(path)` helper for one reason: that helper
 * registers the POST endpoint at the *same* path as the SSE stream and emits a relative
 * `endpoint` event. This plugin has always advertised `/index-mcp?sessionId=…` as the POST
 * target, and clients configured against it would break. [SseServerTransport] takes the endpoint
 * as a constructor argument, so the exact legacy contract is preserved while the SDK still owns
 * the framing, session ids and JSON-RPC handling.
 *
 * Replaces the former hand-written `KtorSseSessionManager` — the session map below is all that
 * was left of it once the SDK owned event framing.
 */
class LegacySseTransports {
    private val transports = ConcurrentHashMap<String, SseServerTransport>()

    fun register(transport: SseServerTransport) {
        transports[transport.sessionId] = transport
        LOG.info("Created SSE session: ${transport.sessionId} (active sessions: ${transports.size})")
    }

    fun remove(sessionId: String) {
        if (transports.remove(sessionId) != null) {
            LOG.info("Removed SSE session: $sessionId (active sessions: ${transports.size})")
        }
    }

    fun get(sessionId: String): SseServerTransport? = transports[sessionId]

    fun activeSessionCount(): Int = transports.size

    companion object {
        private val LOG = logger<LegacySseTransports>()
    }
}

internal fun Route.legacySseRoutes(
    factory: McpServerFactory,
    transports: LegacySseTransports,
    scope: CoroutineScope
) {
    sse(McpConstants.SSE_ENDPOINT_PATH) {
        val transport = SseServerTransport(McpConstants.MCP_ENDPOINT_PATH, this)
        transports.register(transport)

        val server = factory.newServer()
        server.onClose { transports.remove(transport.sessionId) }
        server.createSession(transport)

        try {
            awaitCancellation()
        } finally {
            transports.remove(transport.sessionId)
            // close() is suspending and this scope is already cancelled; hand it to the
            // service scope so the session is actually torn down.
            scope.launch { server.close() }
        }
    }

    // Both paths accept the POST for backwards compatibility: `/index-mcp` is what the endpoint
    // event advertises, `/index-mcp/sse` is what a client following the SDK's own convention
    // would guess.
    post(McpConstants.MCP_ENDPOINT_PATH) { handleLegacyPost(factory, transports, scope) }
    post(McpConstants.SSE_ENDPOINT_PATH) { handleLegacyPost(factory, transports, scope) }
}

private suspend fun RoutingContext.handleLegacyPost(
    factory: McpServerFactory,
    transports: LegacySseTransports,
    scope: CoroutineScope
) {
    val sessionId = call.request.queryParameters[McpConstants.SESSION_ID_PARAM]

    // No sessionId: the stateless convenience mode the hand-written server offered on this path
    // since before Streamable HTTP existed. Some clients were configured against it, so it stays.
    if (sessionId.isNullOrBlank()) {
        handleStatelessPost(factory, scope)
        return
    }

    val transport = transports.get(sessionId)
    if (transport == null) {
        call.respond(HttpStatusCode.NotFound, "Session not found: $sessionId")
        return
    }

    transport.handlePostMessage(call)
}

private suspend fun RoutingContext.handleStatelessPost(factory: McpServerFactory, scope: CoroutineScope) {
    val transport = StreamableHttpServerTransport(
        StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
    ).also { it.setSessionIdGenerator(null) }

    val server = factory.newServer()
    // Same per-request lifetime as the Streamable HTTP endpoint: nothing else closes the session.
    call.coroutineContext.job.invokeOnCompletion { scope.launch { server.close() } }
    server.createSession(transport)

    transport.handleRequest(null, call)
}

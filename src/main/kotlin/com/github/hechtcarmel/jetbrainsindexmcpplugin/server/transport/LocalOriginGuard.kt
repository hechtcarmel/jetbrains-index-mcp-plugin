package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import java.net.URI

/**
 * Origin/Host validation and CORS for the MCP endpoints.
 *
 * ### Why not the SDK's `DnsRebindingProtection`, and why not Ktor's `CORS`
 *
 * The SDK ships `DnsRebindingProtection`, but its 0.10.x implementation compares the raw `Host`
 * header *including the port* (so `127.0.0.1:29170` fails an allow-list of `127.0.0.1`) and
 * rejects requests that carry **no** `Origin` header — which is every curl invocation and
 * several MCP clients.
 *
 * Ktor's `CORS` plugin matches `allowHost` against host *and* port, while this server has always
 * accepted any loopback origin regardless of port (a browser-based client on any dev-server port
 * must work).
 *
 * Both behaviours matter, so the ~40 lines below keep the plugin's original semantics:
 *
 *  - no `Origin` header → allowed, no CORS headers (non-browser client)
 *  - `Origin` present → scheme must be http/https and the host must be loopback; the response
 *    reflects the origin back
 *  - `Host` must be loopback when the server is bound to loopback; the port is ignored. A
 *    deliberately non-loopback bind skips the check — see [installMcpOriginGuard]
 */
internal val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

private val PORT_SUFFIX = Regex(":\\d{1,5}")

private val PREFLIGHT_METHODS = listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Delete, HttpMethod.Options)
    .joinToString(", ") { it.value }

private val PREFLIGHT_HEADERS = listOf(
    HttpHeaders.ContentType,
    HttpHeaders.Accept,
    MCP_SESSION_ID_HEADER,
    MCP_PROTOCOL_VERSION_HEADER
).joinToString(", ")

private fun normalizeHost(host: String) = host.removePrefix("[").removeSuffix("]")

internal fun isLoopbackOrigin(origin: String): Boolean {
    val uri = try {
        URI(origin)
    } catch (_: Exception) {
        return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = normalizeHost(uri.host?.lowercase() ?: return false)
    return scheme in setOf("http", "https") && host in LOOPBACK_HOSTS
}

/**
 * Extracts the hostname from a `Host` header value, dropping the port.
 *
 * Returns null for anything that is not a bare `host[:port]` / `[ipv6][:port]`, so URL-only
 * characters cannot smuggle a different authority past the check (`evil.example@localhost`).
 */
internal fun hostnameOf(hostHeader: String): String? {
    if (hostHeader.isBlank()) return null
    if (hostHeader.any { it == '/' || it == '@' || it == '?' || it == '#' || it.isWhitespace() }) return null

    if (hostHeader.startsWith("[")) {
        val end = hostHeader.indexOf(']')
        if (end <= 1) return null
        val rest = hostHeader.substring(end + 1)
        if (rest.isNotEmpty() && !rest.matches(PORT_SUFFIX)) return null
        return normalizeHost(hostHeader.substring(0, end + 1).lowercase())
    }

    val colon = hostHeader.indexOf(':')
    if (colon == 0) return null
    if (colon < 0) return hostHeader.lowercase()
    if (!hostHeader.substring(colon).matches(PORT_SUFFIX)) return null
    return hostHeader.substring(0, colon).lowercase()
}

/**
 * Installs the guard for every path under [pathPrefix].
 *
 * A single application-level intercept rather than per-route plugins: the SDK builds its own
 * route subtree for the Streamable HTTP endpoint, and a path check is both simpler and immune to
 * how that subtree happens to be shaped. `finish()` is required — a Ktor plugin `onCall` hook
 * cannot stop the pipeline, so a rejected call would still reach the SDK handler and respond
 * twice.
 *
 * @param bindHost the address the server is bound to, i.e. the `serverHost` setting. The `Host`
 *   allow-list applies **only to loopback binds**. DNS rebinding is a loopback threat — it tricks a
 *   browser into reaching a server on the user's own machine through an attacker-controlled name.
 *   A user who deliberately binds to `0.0.0.0` or a LAN address (the settings UI warns in red
 *   first) is reached under whatever name or IP routes there, none of which can be enumerated
 *   here; enforcing the list would just lock them out of their own server.
 */
internal fun Application.installMcpOriginGuard(
    pathPrefix: String,
    bindHost: String = McpConstants.DEFAULT_SERVER_HOST
) {
    val enforceHostAllowList = normalizeHost(bindHost.trim().lowercase()) in LOOPBACK_HOSTS

    intercept(ApplicationCallPipeline.Plugins) {
        if (!context.request.path().startsWith(pathPrefix)) return@intercept

        val origin = context.request.headers[HttpHeaders.Origin]

        if (context.request.httpMethod == HttpMethod.Options) {
            if (origin == null || !isLoopbackOrigin(origin)) {
                context.respondText("Origin not allowed", status = HttpStatusCode.Forbidden)
            } else {
                context.reflectOrigin(origin)
                context.response.header(HttpHeaders.AccessControlAllowMethods, PREFLIGHT_METHODS)
                context.response.header(HttpHeaders.AccessControlAllowHeaders, PREFLIGHT_HEADERS)
                context.respondText("", status = HttpStatusCode.NoContent)
            }
            return@intercept finish()
        }

        if (origin != null) {
            if (!isLoopbackOrigin(origin)) {
                context.rejectForbidden("Origin not allowed")
                return@intercept finish()
            }
            context.reflectOrigin(origin)
        }

        if (!enforceHostAllowList) return@intercept

        val hostHeader = context.request.headers[HttpHeaders.Host]
        if (hostHeader != null && hostnameOf(hostHeader) !in LOOPBACK_HOSTS) {
            context.rejectForbidden("Host not allowed")
            return@intercept finish()
        }
    }
}

private fun ApplicationCall.reflectOrigin(origin: String) {
    response.header(HttpHeaders.AccessControlAllowOrigin, origin)
    response.header(HttpHeaders.Vary, HttpHeaders.Origin)
}

private suspend fun ApplicationCall.rejectForbidden(message: String) {
    if (request.httpMethod == HttpMethod.Get) {
        respondText(message, status = HttpStatusCode.Forbidden)
    } else {
        respondText(
            """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"$message"}}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden
        )
    }
}

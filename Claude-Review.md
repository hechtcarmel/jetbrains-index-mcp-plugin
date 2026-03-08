# PR #80 Review: Streamable HTTP Transport

**Reviewer**: Claude Opus 4.6
**Date**: 2026-03-08
**PR**: [feat/streamable-http](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/pull/80)
**Scope**: +1655 / -189 lines across 18 files
**Verdict**: **Approve with changes** - solid implementation, a few bugs and documentation gaps to address

---

## Executive Summary

This PR adds Streamable HTTP transport (MCP 2025-03-26) as the primary transport while preserving the existing SSE transport for backwards compatibility. The implementation is well-structured with proper session management, CORS enforcement, batch request handling, and good test coverage. I found one confirmed bug, several spec compliance gaps, and a documentation inconsistency that should be fixed before merge.

---

## MCP 2025-03-26 Spec Compliance Audit

Verified against the [official MCP Transports specification](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports).

### Compliant

| Spec Requirement | Status | Notes |
|---|---|---|
| Single MCP endpoint for POST/GET/DELETE | PASS | `/index-mcp/streamable-http` handles all three |
| POST for JSON-RPC messages | PASS | `handleStreamableHttpPostRequest` |
| GET returns `text/event-stream` OR 405 | PASS | Returns 405 (server doesn't offer SSE on this endpoint) |
| DELETE for session termination | PASS | `handleStreamableHttpDeleteRequest` |
| Session ID on successful `InitializeResult` | PASS | `handleStreamableHttpInitialize` only creates session when response has `result` key |
| Session ID: visible ASCII (0x21-0x7E) | PASS | UUID hex chars, verified by `testSessionIdContainsOnlyVisibleAscii` |
| Session ID: globally unique, cryptographically secure | PASS | `UUID.randomUUID()` uses `SecureRandom` |
| Require `Mcp-Session-Id` on subsequent requests | PASS | `validateStreamableSession` returns 400 if missing |
| 404 Not Found for expired/unknown session | PASS | Returned when session not found |
| Notifications return 202 Accepted with no body | PASS | Both single and batch notifications |
| Responses return 202 Accepted with no body | PASS | Single response messages and response batches |
| Requests return `application/json` | PASS | All request responses use `ContentType.Application.Json` |
| Batch: requests and/or notifications | PASS | `classifyStreamableBatch` allows mixing requests+notifications |
| Batch: responses only (no mixing) | PASS | Mixed batches rejected as invalid |
| CORS: validate Origin header | PASS | `validateOrigin` checks against loopback hosts |
| Localhost binding | PASS | Binds to `127.0.0.1` only |
| `Mcp-Session-Id` in `Access-Control-Expose-Headers` | PASS | `setCorsResponseHeaders` sets this |
| Protocol version `2025-03-26` in InitializeResult | PASS | Passed via `STREAMABLE_HTTP_MCP_PROTOCOL_VERSION` |

### Non-Compliant / Gaps

| Spec Requirement | Status | Severity | Details |
|---|---|---|---|
| Client MUST include `Accept: application/json, text/event-stream` | SKIP | Low | Server doesn't validate the `Accept` header. This is technically a client-side MUST, but best practice is to reject requests missing the required Accept values. |
| 405 SHOULD include `Allow` header | SKIP | Low | GET on streamable endpoint returns bare 405 without `Allow: POST, DELETE` header. RFC 9110 Section 15.5.6 recommends this. |
| Server MAY respond to DELETE with 405 | N/A | Info | Implementation handles DELETE (doesn't return 405), which is valid. |

### Reasonable Deviations

| Area | Notes |
|---|---|
| No SSE streaming on POST responses | Server always returns `application/json`, never `text/event-stream` for POST responses. This is permitted by spec ("server MUST either return `text/event-stream`... or `application/json`"). Acceptable since all tool calls are synchronous. |
| `initialize` rejected in batch | Not explicitly forbidden by spec but reasonable: session creation is tied to the initialize response header. |
| No resumability/redelivery | Spec says "MAY" - not required. Acceptable for a local-only server. |
| No GET SSE stream on streamable endpoint | Spec says server MAY return 405 for GET. This means no server-initiated messages outside request context, which is fine for this use case. |

---

## Bugs Found

### BUG-1: Error response missing request ID in single-request path (Medium)

**File**: `KtorMcpServer.kt:330-336`

```kotlin
} catch (e: Exception) {
    LOG.error("Error processing MCP request (Streamable HTTP)", e)
    call.respondText(
        createJsonRpcError(null as JsonElement?, -32603, e.message ?: "Internal error"),
        //                 ^^^^ should be parsed["id"] or requestId
        ContentType.Application.Json
    )
}
```

When `runWithIdeModality` throws an infrastructure-level exception for a request with an `id`, the error response uses `null` as the JSON-RPC `id`. The `requestId` variable (line 293: `val requestId = parsed["id"]`) is in scope but unused. Per JSON-RPC 2.0 spec, the response `id` MUST match the request `id` so clients can correlate errors to requests.

**Contrast with batch handler** (`KtorMcpServer.kt:389`) which correctly uses `parsed?.get("id")`:
```kotlin
createJsonRpcError(parsed?.get("id"), -32603, e.message ?: "Internal error")
```

**Fix**: Replace `null as JsonElement?` with `requestId` on line 333.

### BUG-2: README client configuration examples still show legacy SSE URLs (Medium)

**File**: `README.md:111-156`

The README's manual configuration examples still show the old SSE endpoint URLs, contradicting the PR's intent of making Streamable HTTP the primary transport:

```bash
# README shows (WRONG for this PR):
claude mcp add --transport http intellij-index http://127.0.0.1:29170/index-mcp/sse --scope user
codex mcp add --transport sse intellij-index http://127.0.0.1:29170/index-mcp/sse

# Should be:
claude mcp add --transport http intellij-index http://127.0.0.1:29170/index-mcp/streamable-http --scope user
codex mcp add intellij-index --url http://127.0.0.1:29170/index-mcp/streamable-http
```

The Cursor example similarly points to `/index-mcp/sse` instead of `/index-mcp/streamable-http`.

The `ClientConfigGenerator` code generates the correct URLs, but the hardcoded README examples are stale.

---

## Design Observations

### Good

1. **Session only created on successful initialize** - Prevents orphaned sessions from failed handshakes.
2. **Sessions cleared on server stop** - Old `Mcp-Session-Id` values don't survive restart. Test `testStopClearsStreamableSessionsBeforeRestart` verifies this.
3. **CORS restricted to loopback** - Proper security for local-only server. IPv6 `[::1]` normalized correctly.
4. **Legacy transport preserved** - SSE endpoint fully functional. Protocol version correctly set to `2024-11-05` for legacy requests.
5. **Batch classification is clean** - `StreamableBatchKind` enum + `classifyStreamableBatch` clearly separates request/notification batches from response batches.
6. **Test coverage is solid** - 11 new transport tests covering happy paths, error cases, CORS, batches, SSE regression.

### Concerns

#### No Session TTL / Maximum Session Count (Low)

`StreamableHttpSessionManager` stores sessions in an unbounded `ConcurrentHashMap` with no TTL. A misbehaving or crashing client could create sessions without ever DELETE-ing them, leading to slow memory growth over long IDE uptimes. Consider:
- A configurable TTL (e.g., 24h) with a periodic cleanup coroutine
- A max session count (e.g., 100) with oldest-session eviction

#### `StreamableHttpSession` is a trivial wrapper (Low)

```kotlin
class StreamableHttpSession(val sessionId: String)
```

This class holds only the session ID. A `ConcurrentHashMap<String, Long>` (sessionId → createdAt timestamp) would be simpler and enable TTL-based eviction. If you plan to add per-session state later (last-activity timestamp, client info), the wrapper class makes sense as a forward-looking choice.

#### Re-initialize creates orphaned sessions (Low)

If a client sends `initialize` twice (e.g., after a connection reset), a new session is created each time. The old session remains in the map but the client uses only the new session ID. This is mild since sessions are lightweight, but could be addressed by:
- Accepting an existing `Mcp-Session-Id` on `initialize` and reusing/replacing that session
- Or relying on the eventual server restart to clean up

#### AGENTS.md is a near-copy of CLAUDE.md (Info)

`AGENTS.md` (502 lines) differs from `CLAUDE.md` by only 2 lines (a typo "Codex Desktop" vs "Claude Desktop" and a self-reference). Consider whether both files are needed, or if one can reference the other to avoid drift.

---

## Backwards Compatibility: SSE Transport

### Verified Working

The legacy SSE transport is **not broken** by this PR. Evidence:

1. **Routes preserved**: `GET /index-mcp/sse` and `POST /index-mcp?sessionId=xxx` routes are unchanged.
2. **Protocol version correct**: Legacy requests use `McpConstants.LEGACY_MCP_PROTOCOL_VERSION` ("2024-11-05").
3. **SSE handshake test**: `testLegacySseHandshakeAdvertisesEndpointAndStreamsResponses` verifies the full flow: SSE connection → endpoint event → POST → 202 Accepted → SSE message event with response.
4. **Protocol version test**: `testLegacyPostInitializeReturns2024ProtocolVersion` verifies the initialize response on the legacy endpoint contains `"protocolVersion": "2024-11-05"`.
5. **Stateless fallback preserved**: POST to `/index-mcp` without `sessionId` still works as stateless HTTP with legacy protocol version.

### Migration Path

The spec's backwards compatibility guide says servers wanting to support older clients should "continue to host both the SSE and POST endpoints of the old transport, alongside the new MCP endpoint." This PR follows that guidance exactly, with separate endpoint paths.

---

## File-by-File Review

### Core Implementation

| File | Assessment |
|---|---|
| `KtorMcpServer.kt` (+442/-26) | Clean implementation. Single-request error ID bug (BUG-1). Well-organized routing with clear method extraction. |
| `StreamableHttpSessionManager.kt` (+47) | Simple, thread-safe. Could benefit from TTL. |
| `McpConstants.kt` (+8/-4) | New endpoint paths and protocol versions properly defined. |
| `JsonRpcHandler.kt` (+9/-6) | Added `protocolVersion` parameter to `handleRequest` - clean change. Protocol version correctly flows to `processInitialize`. |
| `McpServerService.kt` (+24/-21) | Added `streamableHttpSessionManager`, updated URLs. `getServerUrl()` now returns Streamable HTTP URL. `getLegacySseUrl()` added for legacy. |
| `McpModels.kt` (+1/-1) | `InitializeResult.protocolVersion` default updated to `"2025-03-26"`. |

### Client Configuration

| File | Assessment |
|---|---|
| `ClientConfigGenerator.kt` (+41/-45) | Refactored to generate Streamable HTTP URLs. Codex CLI uses native `--url` flag instead of `mcp-remote` bridge. Claude Code uses `--transport http`. |
| `CopyClientConfigAction.kt` (+8/-8) | UI updated with "Streamable HTTP" and "Legacy SSE" sections. Clean. |

### Tests

| File | Assessment |
|---|---|
| `KtorMcpServerUnitTest.kt` (+373) | Excellent coverage: init+session header, batch requests, mixed batch rejection, notification batches, DELETE errors, CORS, IPv6, SSE regression, session cleanup after restart. |
| `StreamableHttpSessionManagerUnitTest.kt` (+78) | Covers creation, uniqueness, removal, count, closeAll, ASCII validation. Solid. |
| `JsonRpcHandlerUnitTest.kt` (+26) | Added `testInitializeRequestCanOverrideProtocolVersion` and `testNotificationReturnsNull`. Good. |
| `ClientConfigGeneratorUnitTest.kt` (+38/-54) | Updated for new command formats. Tests verify Streamable HTTP URLs, `--transport http` flag, `--url` for Codex. |
| `McpModelsUnitTest.kt` (+2/-2) | Updated protocol version in test assertions. |

### Documentation

| File | Assessment |
|---|---|
| `CHANGELOG.md` (+22/-1) | Thorough, well-organized. Breaking change clearly marked. |
| `CLAUDE.md` (+17/-8) | Updated transport documentation, endpoint paths, client config example. |
| `README.md` (+16/-12) | **Incomplete** - manual config examples still show legacy SSE URLs (BUG-2). |
| `AGENTS.md` (+502) | Near-copy of CLAUDE.md. Two minor text differences. |

---

## Test Gaps

| Missing Test | Priority |
|---|---|
| Batch with notification + request mix (verify partial responses) | Medium |
| Re-initialize on existing session (orphan behavior) | Low |
| Initialize with existing `Mcp-Session-Id` header (should still create new session) | Low |
| DELETE with valid session returns 200 (happy path) | Low - implicit in session cleanup test but no explicit assertion |
| Error response includes request ID in single-request catch block | Medium - would catch BUG-1 |

---

## Recommended Actions

### Must Fix (before merge)

1. **BUG-1**: Use `requestId` instead of `null` in `KtorMcpServer.kt:333`
2. **BUG-2**: Update `README.md` client configuration examples to use `/index-mcp/streamable-http` URLs and the new Codex CLI `--url` syntax

### Should Fix

3. Add `Allow: POST, DELETE` header to the 405 response for GET on the streamable endpoint (`KtorMcpServer.kt:148`)
4. Add a test for error response ID correlation in single-request path
5. Fix the "Codex Desktop" typo in `AGENTS.md` line 164 (should be "Claude Desktop")

### Nice to Have

6. Add session TTL and/or max session count to `StreamableHttpSessionManager`
7. Consider deduplicating `AGENTS.md` / `CLAUDE.md` or adding a generation mechanism

---

## Sources

- [MCP Transports Specification (2025-03-26)](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [MCP Spec (spec.modelcontextprotocol.io)](https://spec.modelcontextprotocol.io/specification/2025-03-26/basic/transports/)
- [Why MCP Deprecated SSE for Streamable HTTP](https://blog.fka.dev/blog/2025-06-06-why-mcp-deprecated-sse-and-go-with-streamable-http/)
- [JSON-RPC 2.0 Specification](https://www.jsonrpc.org/specification)

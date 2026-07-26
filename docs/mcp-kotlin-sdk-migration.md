# Migrating the MCP protocol layer to the MCP Kotlin SDK

Status: **implemented**. Kept as the rationale record for the change; §11 lists where reality
diverged from this design.
Scope: replace the hand-written JSON-RPC + MCP + SSE layer with `io.modelcontextprotocol:kotlin-sdk`.
Non-goal: change any tool's behaviour, name, schema, or result payload.

---

## 1. What exists today

The plugin implements MCP from scratch on top of a hand-rolled Ktor 2 server:

| File | LOC | What it does |
|------|----:|--------------|
| `server/JsonRpcHandler.kt` | 389 | JSON-RPC envelope parse/validate/route, `initialize`/`ping`/`tools/list`/`tools/call`, project resolution, settings gate, command history |
| `server/transport/KtorMcpServer.kt` | 673 | Ktor CIO server, 3 transports, batch classification, Origin/CORS, modality wrapping, lifecycle |
| `server/transport/KtorSseSessionManager.kt` | 150 | SSE session map + event framing |
| `server/models/McpModels.kt` | 71 | `ToolDefinition`, `ToolCallResult`, `ContentBlock`, `InitializeResult`, `ServerCapabilities`, … |
| `server/models/JsonRpcModels.kt` | 47 | `JsonRpcRequest/Response/Error`, error codes |
| `constants/JsonRpcMethods.kt` | 9 | method-name strings |
| **total** | **1339** | |

Plus ~1300 lines of test that exist only to verify this layer (`JsonRpcHandlerUnitTest` 420,
`KtorMcpServerUnitTest` 353, `McpServerIntegrationTest` 265, `JsonRpcHandlerTest` 107,
`McpModelsUnitTest` 86, `JsonRpcHandlerHistoryFailureTest` 90).

### The finding that makes this a slam dunk

`io.modelcontextprotocol:kotlin-sdk-jvm:0.4.0` is **already a declared `implementation`
dependency and is imported by exactly zero source files**:

```
$ grep -rn "io.modelcontextprotocol" src/          # → no matches in .kt
$ grep -rn "modelcontextprotocol" src/             # → 1 hit, a URL in plugin.xml
```

It is not free. `./gradlew dependencies --configuration runtimeClasspath` today resolves:

```
io.ktor:ktor-server-cio:2.3.12        ← used by KtorMcpServer
io.ktor:ktor-server-cio:3.0.2         ← dead, from the unused SDK
io.ktor:ktor-server-core:2.3.12       io.ktor:ktor-server-core:3.0.2
io.ktor:ktor-client-cio:3.0.2         io.ktor:ktor-server-sse:3.0.2
io.ktor:ktor-server-websockets:3.0.2  io.ktor:ktor-websocket-serialization:3.0.2
…14 more dead io.ktor:*:3.0.2 artifacts…
org.jetbrains.kotlin:kotlin-stdlib:2.0.21          ← must not be bundled by an IJ plugin
org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.1 ← must not be bundled by an IJ plugin
```

So the plugin **already ships two major versions of Ktor**, a `kotlin-stdlib`, and a coroutines
artifact, for no benefit. The existing `exclude` list catches `kotlinx-coroutines-core` and
`slf4j` but misses `kotlinx-coroutines-jdk8` (pulled by Ktor 2) and `kotlin-stdlib` entirely.

Fixing the dependency hygiene is worth doing on its own; doing it *while* adopting the SDK means
one Ktor, one MCP implementation.

### What the SDK gives us

`kotlin-sdk-server` 0.14.0 (`io.modelcontextprotocol`, MIT, maintained jointly by Anthropic and
JetBrains) provides, verified against the 0.14.0 sources:

| Need | SDK |
|------|-----|
| JSON-RPC envelope, ids, batching, errors | `shared/Protocol.kt`, `types/jsonRpc.kt` |
| `initialize` + version negotiation | `ServerSession.handleInitialize` — negotiates over `2025-11-25`, `2025-06-18`, `2025-03-26`, `2024-11-05` |
| `ping`, `notifications/initialized` | built in |
| `tools/list`, `tools/call` | `Server.addTools` / `handleListTools` / `handleCallTool` |
| Stateless Streamable HTTP | `Application.mcpStatelessStreamableHttp(path, …)` — POST only, 405 on GET/DELETE |
| Legacy SSE (2024-11-05) | `SseServerTransport(endpoint, session)` + `Route.mcp(path)` |
| Origin **and Host** validation | `DnsRebindingProtection` plugin, localhost defaults |
| Request body size cap | `maxRequestBodySize`, default 4 MiB |
| Resumable event stream, elicitation, sampling, prompts, resources, tasks | free, unused for now |

Its schema type matches ours exactly — this is the single most important compatibility fact:

```kotlin
public data class ToolSchema(
    @SerialName("\$schema") val schema: String? = null,
    val properties: JsonObject? = null,
    val required: List<String>? = null,
    @SerialName("\$defs") val defs: JsonObject? = null,
) { @EncodeDefault val type: String = "object" }
```

and every schema this plugin emits uses only `{type, properties, required}` (verified across all
47 tools in `src/test/resources/contract/tool-manifest.json`). With `McpJson`
(`explicitNulls = false, encodeDefaults = true`) the serialized bytes are identical.

---

## 2. Goals

1. Delete the hand-written protocol layer. Own tools, not transports.
2. **Zero tool regressions.** Same 50 tools, same names, same input schemas, same result payloads.
   The golden `tool-manifest.json` and `result-shapes.txt` are the contract; both must stay
   byte-identical except where §4 says otherwise, deliberately.
3. One Ktor. No bundled `kotlin-stdlib`, no bundled coroutines.
4. Spec conformance we do not currently have (Host validation, body-size cap, correct version
   negotiation, `isError` semantics), for free.
5. Future MCP features (resources, prompts, elicitation, resumable streams) become additive
   instead of "write another 400 lines".

---

## 3. Target architecture

### Before

```
HTTP ──► KtorMcpServer (673)
           ├─ origin/CORS, batch classification, 3 transports
           └─► JsonRpcHandler (389)
                 ├─ envelope validation
                 ├─ initialize / ping / tools.list / tools.call
                 ├─ settings gate, ProjectResolver, CommandHistory
                 └─► ToolRegistry ─► McpTool.execute
```

### After

```
HTTP ──► McpKtorServer (~160)                    ← lifecycle + CORS only
           ├─ install(CORS)
           ├─ mcpStatelessStreamableHttp("/index-mcp/streamable-http") ─┐   SDK
           └─ routing { legacySseRoutes() }  ─────────────────────────┐ │   SDK transports
                                                                      ▼ ▼
                                                    io.modelcontextprotocol…Server
                                                       (built by McpServerFactory)
                                                                      │
                                                    per-tool handler ─┘
                                                                      ▼
                                                    McpToolDispatcher (~80)
                                                       settings gate → ProjectResolver
                                                       → CommandHistory → McpTool.execute
                                                                      ▼
                                                              ToolRegistry (unchanged)
                                                              50 tools     (unchanged)
```

### File-level plan

| File | Action |
|------|--------|
| `server/JsonRpcHandler.kt` | **delete** (389) |
| `server/models/JsonRpcModels.kt` | **delete** (47) |
| `server/models/McpModels.kt` | **delete** (71) |
| `constants/JsonRpcMethods.kt` | **delete** (9) |
| `server/transport/KtorSseSessionManager.kt` | **delete** (150) |
| `server/transport/KtorMcpServer.kt` | rewrite 673 → ~160, rename `McpKtorServer` |
| `server/mcp/McpServerFactory.kt` | **new** ~90 |
| `server/mcp/McpToolDispatcher.kt` | **new** ~80 |
| `server/transport/LegacySseRoutes.kt` | **new** ~60 |
| `server/McpServerService.kt` | edit: drop `jsonRpcHandler`/`sseSessionManager` fields and their getters |
| `server/ProjectResolver.kt` | edit: type rename only |
| `tools/McpTool.kt`, `tools/AbstractMcpTool.kt` | edit: type rename only |
| `tools/ToolRegistry.kt` | edit: `getToolDefinitions()` returns `List<Tool>` (SDK) |
| `tools/schema/SchemaBuilder.kt` | edit: `build(): ToolSchema` instead of `JsonObject` |
| 47 tool files | **untouched** (they only name `ToolCallResult` as a return type) |

Net: **−1339 / +390** production lines.

### The three new classes, concretely

**`McpToolDispatcher`** — everything `JsonRpcHandler.processToolCall` did *except* envelope
handling. This is the seam every existing tool-level test will target.

```kotlin
class McpToolDispatcher(
    private val toolRegistry: ToolRegistry,
    private val recordHistory: (Project, CommandEntry) -> Unit = …,
    private val updateHistory: (Project, String, CommandStatus, String?, Long?) -> Unit = …,
) {
    suspend fun call(toolName: String, arguments: JsonObject): CallToolResult {
        val tool = toolRegistry.getTool(toolName)
            ?: return CallToolResult.error("Tool '$toolName' not found")
        if (!McpSettings.getInstance().isToolEnabled(toolName))
            return CallToolResult.error("Tool '$toolName' is disabled. Enable it in " +
                "Settings → Index MCP Server → Available Tools.")

        val projectPath = arguments[ParamNames.PROJECT_PATH]?.stringOrNull()
        val resolved = ProjectResolver.resolveOrOpen(projectPath)
        if (resolved.isError) return resolved.errorResult!!
        val project = resolved.project!!

        val entry = CommandEntry(toolName = toolName, parameters = arguments)
        recordHistorySafely(project, entry)
        val started = System.currentTimeMillis()
        return try {
            tool.execute(project, arguments).also { updateHistorySafely(project, entry, it, started) }
        } catch (e: ProcessCanceledException) { throw e
        } catch (e: CancellationException)    { throw e
        } catch (e: IndexNotReadyException) {
            updateHistorySafely(project, entry, CommandStatus.ERROR, e.message, started)
            CallToolResult.error(e.message ?: "IDE index is not ready")
        } catch (e: Exception) {
            LOG.error("Tool execution failed: $toolName", e)
            updateHistorySafely(project, entry, CommandStatus.ERROR, e.message, started)
            CallToolResult.error(e.message ?: ErrorMessages.UNKNOWN_ERROR)
        }
    }
}
```

Note the modality wrapper (`withContext(ModalityState.any().asContextElement())`) that
`KtorMcpServer.runWithIdeModality` applies today **moves here** — it must wrap tool execution,
not HTTP handling, and the SDK owns HTTP handling now. Losing it would reintroduce
"write-unsafe context" failures in `commitAllDocuments`.

**`McpServerFactory`** — turns the registry into an SDK `Server`.

```kotlin
class McpServerFactory(private val registry: ToolRegistry, private val dispatcher: McpToolDispatcher) {

    fun newServer(): Server = Server(
        serverInfo = Implementation(
            name    = McpConstants.getServerName(),
            version = pluginVersion(),                 // from PluginManagerCore, not the stale constant
            title   = McpConstants.PLUGIN_NAME,
            websiteUrl = McpConstants.REPOSITORY_URL,
        ),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = McpConstants.SERVER_DESCRIPTION,   // see §4.2
    ).apply { addTools(enabledTools()) }

    private fun enabledTools(): List<RegisteredTool> {
        val settings = McpSettings.getInstance()
        return registry.getAllTools()
            .filter { settings.isToolEnabled(it.name) }
            .map { tool ->
                RegisteredTool(
                    Tool(name = tool.name, description = tool.description, inputSchema = tool.inputSchema)
                ) { _, request -> dispatcher.call(tool.name, request.params.arguments ?: EmptyJsonObject) }
            }
    }
}
```

`enabledTools()` is recomputed per connection, which is what makes the settings toggle take
effect without a server restart — the same guarantee `getToolDefinitions()` gives today.

**Lifecycle, and a leak to avoid.** In stateless Streamable HTTP the SDK calls the factory block
**once per POST** and never closes the resulting `Server`. Return a fresh one and close it when
the call completes, or `Server.sessions` and its notification scope grow without bound:

```kotlin
mcpStatelessStreamableHttp(path = STREAMABLE_HTTP_ENDPOINT_PATH, allowedHosts = allowedHosts()) {
    factory.newServer().also { server ->
        call.coroutineContext.job.invokeOnCompletion { scope.launch { server.close() } }
    }
}
```

This gets an explicit regression test (§6).

**`LegacySseRoutes`** — preserves the exact legacy wire contract. `Route.mcp(path)` would place
the POST endpoint at `/index-mcp/sse`, changing the `endpoint` event. `SseServerTransport` is
public and takes the endpoint, so we keep `/index-mcp?sessionId=…`:

```kotlin
private val sessions = ConcurrentHashMap<String, SseServerTransport>()   // replaces KtorSseSessionManager

fun Route.legacySseRoutes(factory: McpServerFactory, scope: CoroutineScope) {
    sse(McpConstants.SSE_ENDPOINT_PATH) {
        val transport = SseServerTransport(McpConstants.MCP_ENDPOINT_PATH, this)
        sessions[transport.sessionId] = transport
        val server = factory.newServer()
        server.onClose { sessions.remove(transport.sessionId) }
        server.createSession(transport)
        try { awaitCancellation() } finally { sessions.remove(transport.sessionId); scope.launch { server.close() } }
    }
    post(McpConstants.MCP_ENDPOINT_PATH)  { handleLegacyPost(call) }
    post(McpConstants.SSE_ENDPOINT_PATH)  { handleLegacyPost(call) }
}
```

`install(SSE)` must happen exactly once — `Application.mcpStatelessStreamableHttp` installs it
unconditionally and would throw `DuplicatePluginException` if a second `Application.mcp(...)`
were called. Call `mcpStatelessStreamableHttp` first, then register the legacy routes inside our
own `routing { }` block.

CORS stays ours (`install(CORS)` from `ktor-server-cors`, already a declared dependency) — the
SDK's `DnsRebindingProtection` validates `Origin` but emits no CORS response headers, so browser
clients need the Ktor plugin. That replaces ~55 hand-written lines
(`handleCorsPreflight` / `validateOrigin` / `setCorsResponseHeaders` / `isAllowedOrigin`).

---

## 4. Wire-contract delta

This is the part that decides whether "we didn't lose anything" is true. Every row is a
deliberate decision, not an accident.

### 4.1 Unchanged (verified)

- `tools/list` — `{name, description, inputSchema:{type,properties,required}}`, byte-identical.
  All 47 snapshotted schemas use only those three keys; `ToolSchema` emits exactly those,
  `$schema`/`$defs` stay absent because we pass `null`.
- `tools/call` success — `{content:[{type:"text",text:"…"}], isError:false}`. SDK `TextContent`
  serializes with `classDiscriminatorMode = NONE` and `type` as an `@EncodeDefault` property, so
  no discriminator is added.
- Endpoint paths: `/index-mcp/streamable-http` (POST; 405 GET/DELETE), `/index-mcp/sse` (GET),
  `/index-mcp?sessionId=` (POST). SSE `endpoint` event payload unchanged.
- No `Mcp-Session-Id` header on the stateless endpoint (`sessionIdGenerator = null`).
- Requests without an `Origin` header are still accepted (curl keeps working).
- JSON-RPC batching still supported (`parseBody` handles `JsonArray`).
- `tools/call` still works without a prior `initialize` on the stateless endpoint
  (`validateSession` returns `true` immediately when stateless).
- All 61 tool result payload models — untouched, `result-shapes.txt` unchanged.

### 4.2 Changed — accepted, and why

| # | Today | After | Rationale |
|---|-------|-------|-----------|
| 1 | POST to `/index-mcp/streamable-http` with any `Accept` | **406** unless `Accept` includes *both* `application/json` and `text/event-stream` | Mandated by the Streamable HTTP spec since 2025-03-26; every real client already sends it. **Breaks the repo's own curl smoke tests — see §7 Phase 6.** |
| 2 | Any `Content-Type` accepted | **415** unless `application/json` | spec |
| 3 | No `Host` validation | `Host` must be `localhost`/`127.0.0.1`/`[::1]` **or the configured `serverHost`** | DNS-rebinding protection. Must pass `allowedHosts = LOCALHOST + settings.serverHost`, otherwise a user who sets a non-loopback bind address loses all access. |
| 4 | `IndexNotReadyException` → JSON-RPC error `-32001` | `{isError:true, content:[…same message…]}` | The spec says tool-execution errors go in the result, not the envelope. Protocol errors are hard failures for clients; `isError` reaches the model, which is what the message ("call `ide_index_status` until…") is written for. **This is a behaviour improvement, but it is a wire change.** |
| 5 | Unknown tool → `-32601`; disabled tool → `-32602` | `{isError:true, content:["Tool '…' not found" / "… is disabled …"]}` | same reason |
| 6 | `initialize.serverInfo.description` (non-standard field) | `initialize.instructions` | `Implementation` has no `description`. `instructions` is the spec's slot for exactly this text and clients feed it to the model. |
| 7 | `serverInfo.version = "4.10.4"` (hardcoded, stale — plugin is 4.31.0) | real plugin version from `PluginManagerCore` | bug fix |
| 8 | Legacy `/index-mcp` always answers `protocolVersion: 2024-11-05` | echoes whatever supported version the client asked for | correct negotiation; a client asking 2024-11-05 still gets 2024-11-05 |
| 9 | Scalar JSON body → `-32600` | `-32700` (parse error) | SDK classification; both are HTTP 400 |
| 10 | Mixed request/response batch → `-32600` | processed per-message | drops a hand-written guard the spec does not require |
| 11 | No request body limit | 4 MiB, `413` beyond | new protection; raise via `maxRequestBodySize` if any tool ever needs it |
| 12 | max protocol `2025-03-26` | up to `2025-11-25` negotiated | free upgrade |

Rows 1, 4, 5, 6 are user-visible. They go in `CHANGELOG.md` under `### Breaking` and justify a
**major version bump to 5.0.0** per `CONTRIBUTING.md` ("Breaking schema / transport change").

### 4.3 Feature inventory — where every existing behaviour lands

The protocol layer is thinner than it looks. Most of what feels like "our features" lives in
`AbstractMcpTool`, `ProjectResolver` and `McpSettings`, below the layer being replaced.

**Untouched — the SDK never sees these**

| Feature | Where it lives | Why it is safe |
|---------|----------------|----------------|
| **TOON response format** | `AbstractMcpTool.createJsonResult` / `createStructuredErrorResult` → `ResponseFormatter.formatStructuredPayload` → `JToon.encodeJson` | TOON is applied to the payload *before* it becomes a content block. The result is a `String` in the `text` field. The only edit is `ContentBlock.Text(text = toon)` → `TextContent(text = toon)` — a type name. `ProjectResolver.buildStructuredErrorResult` does the same and gets the same one-word change. |
| Cursor pagination (`PaginationService`) | inside individual tools | never crosses the envelope |
| `syncExternalChanges` PSI sync | `AbstractMcpTool.execute` | above `doExecute`, below the transport |
| Lifecycle auto-enroll / auto-wake, `participatesInLifecycle` | `AbstractMcpTool.execute` | same |
| Dumb-mode and "Outdated stub" catch-and-explain messages | `AbstractMcpTool.execute` | same — note the *text* survives verbatim; only its envelope changes (§4.2 row 4) |
| `availableProjectsMode` EXPANDED / COMPACT | `ProjectResolver` | same |
| `requiresPsiSync` opt-out | `AbstractMcpTool` | same |
| All 50 tool names, descriptions, schemas, payloads | tool classes + `SchemaBuilder` | pinned by both golden files |

**Moves into `McpToolDispatcher` — same code, new home**

- tool enable/disable gate (`McpSettings.isToolEnabled`)
- project resolution and auto-open (`ProjectResolver.resolveOrOpen`)
- command-history record/update, including the 4 KB result truncation
- the IDE modality wrapper (`ModalityState.any().asContextElement()`)

**Moves into `McpServerFactory`**

- `tools/list` filtering to enabled tools only, recomputed per connection

**Stays in `McpKtorServer`**

- configurable port/host and restart-on-change
- watchdog, backoff restart, port-in-use notification, `SERVER_STATUS_TOPIC`
- CORS — now `install(CORS)` instead of 55 hand-written lines

**No request timeout is introduced.** The SDK's `DEFAULT_REQUEST_TIMEOUT = 60.seconds` applies
only to *outgoing* server→client requests (`Protocol.request`); `Protocol.onRequest` invokes the
handler with no `withTimeout`. And `StreamableHttpServerTransport.handlePostRequest` suspends
through `_onMessage` → handler → `send()` → `call.respond(payload)`, so the HTTP call stays open
for the full duration exactly as it does today. `ide_open_project` (600 s default),
`ide_build_project`, and `ide_run_tests` are unaffected.

**One pre-existing gap this surfaces, not caused by the migration:** `plugin.xml` declares

```xml
<extensionPoint name="mcpTool" interface="…tools.McpTool" dynamic="true"/>
```

and `McpTool`'s KDoc advertises it, but **no code ever reads it** — `ToolRegistry` has no
`ExtensionPointName`. Third-party tool contribution does not currently work. The migration
neither fixes nor breaks it. If it should work, wiring it is ~5 lines in
`ToolRegistry.registerBuiltInTools()` and is cleaner after this refactor
(`McpServerFactory` already rebuilds the tool list per connection, so a dynamic EP would
hot-reload). Decide separately; out of scope here.

### 4.4 Explicitly rejected alternatives

- *Keep `-32001` for dumb mode by bypassing `Server.handleCallTool`.* `handleCallTool` catches
  `Exception` broadly and `createSession` is not `open`, so preserving it means not using the
  SDK's tool dispatch — i.e. keeping the thing we are deleting. Not worth it for a deviation the
  spec tells us not to have.
- *Shim the `Accept` header for backwards compatibility.* Ktor request headers are immutable;
  every workaround is more code than updating two markdown files.
- *Keep `ToolCallResult`/`ContentBlock` as plugin types and adapt at the boundary.* One more
  parallel model to keep in sync, and `ContentBlock` collides by name with the SDK's. The rename
  touches 3 files with real edits and 56 with a mechanical type-name change.

---

## 5. Dependencies

```toml
# gradle/libs.versions.toml
mcpKotlinSdk = "0.14.0"          # was 0.4.0 (unused)
ktor = "3.4.3"                   # was 2.3.12 — must match the SDK's Ktor
# remove: kotlinxSerializationJson (comes from the SDK at the version it was compiled against)

mcp-kotlin-sdk-server = { group = "io.modelcontextprotocol", name = "kotlin-sdk-server-jvm", version.ref = "mcpKotlinSdk" }
mcp-kotlin-sdk-client = { group = "io.modelcontextprotocol", name = "kotlin-sdk-client-jvm", version.ref = "mcpKotlinSdk" }
```

```kotlin
// build.gradle.kts
//
// Every artifact that the IntelliJ Platform already provides on the plugin classloader's parent
// must be excluded — bundling a second kotlin-stdlib or kotlinx-coroutines is explicitly
// forbidden for IntelliJ plugins. Verified present in 2025.3:
//   kotlin-stdlib, kotlinx-coroutines  → lib/util-8.jar
//   slf4j-api                          → lib/util-8.jar
//   kotlinx-io, kotlinx-collections-immutable, kotlinx-serialization-json
//                                      → lib/module-intellij.libraries.*.jar
// ktor-server is NOT in the platform (only ktor-client), so we bundle it.
fun ExternalModuleDependency.platformProvided() {
    exclude(group = "org.jetbrains.kotlin",  module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    exclude(group = "org.slf4j")
}

dependencies {
    implementation(libs.mcp.kotlin.sdk.server) { platformProvided() }
    implementation(libs.ktor.server.cio)       { platformProvided() }
    implementation(libs.ktor.server.cors)      { platformProvided() }
    implementation(libs.jtoon)

    testImplementation(libs.mcp.kotlin.sdk.client) { platformProvided() }   // conformance tests
    testImplementation(libs.ktor.client.cio)       { platformProvided() }
    …
}
```

Dropped entirely: `ktor-server-core` (transitive from the SDK), `kotlinx-serialization-json`
(transitive), the whole dead 3.0.2 tree.

### Classloading notes

- IntelliJ's `PluginClassLoader` resolves the plugin's own jars first, so bundling Ktor 3.4.3
  server does not clash with the platform's Ktor 3.x **client** in `lib/`. Precedent: JetBrains'
  own bundled `plugins/mcpserver` ships `ktor-server-cio-internal.jar`.
- SDK 0.14.0 is compiled against coroutines 1.11.0; the platform ships ~1.10.x (JetBrains fork,
  `InternalForInheritanceCoroutinesApi` present ⇒ ≥ 1.9). The SDK uses only long-stable coroutine
  API, but **this is the one thing Phase 0 must actually run, not reason about.**
- SDK 0.14.0 is compiled against kotlinx-serialization 1.11.0 while the project compiles with the
  2.1.20 serialization plugin. Generated serializers are backward-compatible; also proven by
  Phase 0.
- `kotlin-logging` (SDK runtime dep) needs `org.slf4j.LoggerFactory`, which the platform provides
  — hence keeping the `org.slf4j` exclude is correct, not lucky.

---

## 6. Test strategy

The previous PR built the safety net precisely for this change. Use it, and be honest about which
old tests were testing *our* protocol code (they should die with it) versus testing *tool
behaviour* through it (they must survive).

### Contract goldens — the primary net

| Golden | Expectation |
|--------|-------------|
| `contract/tool-manifest.json` | **byte-identical.** If it moves, the migration dropped or mutated a tool. Regenerate only if §4 justifies it — it should not. |
| `contract/result-shapes.txt` | **byte-identical.** Nothing in this change touches tool payload models. |

The manifest renderer changes from `canonicalJson(tool.inputSchema)` to
`canonicalJson(McpJson.encodeToJsonElement(tool.inputSchema))` because `inputSchema` becomes a
`ToolSchema`. Output must not change — that is the assertion.

Add one new contract test:

- **`ToolSchemaFidelityUnitTest`** — every registered tool's schema round-trips
  `JsonObject → ToolSchema → JsonObject` with no key loss, i.e. no tool has crept in using
  `$defs`, `additionalProperties`, or another top-level keyword `ToolSchema` cannot carry.
  Without this, a future tool could silently lose part of its schema on the wire.

### Existing test disposition

| Test | LOC | Disposition |
|------|----:|-------------|
| `JsonRpcHandlerUnitTest` | 420 | **delete.** 21 tests, all envelope validation (`params` not an object, non-string `method`, bad `jsonrpc`, parse errors). That is the SDK's job now; keeping them means testing the SDK. Two behaviours worth keeping move to `McpProtocolConformanceTest` as black-box HTTP assertions: unknown method → `-32601`, notification → no response body. |
| `JsonRpcHandlerTest` | 107 | **port** to `McpToolDispatcherTest` — valid tool call, disabled tool hidden and rejected. `testToolsListRequest` moves to the conformance test. |
| `JsonRpcHandlerHistoryFailureTest` | 90 | **port** to `McpToolDispatcherTest` verbatim; the injected history lambdas move to the dispatcher constructor. |
| `MultiProjectResolutionTest`, `WorkspaceResolutionTest` | 330 | **port** — swap `handler.handleRequest(json)` for `dispatcher.call(name, args)`. Assertions unchanged. |
| `McpServerIntegrationTest` | 265 | **port**, split: tool-level cases → `McpToolDispatcherTest`; protocol cases (`initialize`, `ping`, `tools/list` contents) → `McpProtocolConformanceTest`. |
| `McpModelsUnitTest` | 86 | **rewrite** as `McpWireFormatUnitTest`: assert the SDK's serialization of `Tool`, `CallToolResult`, `TextContent`, `ImageContent` produces the exact keys we shipped before. This is the test that would have caught a `$schema` field appearing, a discriminator being added, or `isError` being dropped. |
| `KtorMcpServerUnitTest` | 353 | **keep, adjust 4 assertions** — it already drives real HTTP against a real server on a free port, which is exactly the right shape. Changes: add the required `Accept` header to the helper; `testStreamableScalarJson…` now expects `-32700`; `testStreamableMixedBatch…` deleted (§4.2 row 10); `testRejectsNonLocalOrigin` re-pinned against `DnsRebindingProtection`'s status/body. |
| `KtorMcpServerWatchdogTest` | 158 | **keep as-is.** `start`/`stop`/`isRunning`/`onUnexpectedStop`/port-in-use survive; `EmbeddedServer.monitor` and `environment.monitor` both still exist in Ktor 3.4.3 (verified via `javap`). |
| `McpServerServiceTest` | 62 | keep; drop assertions on the removed getters. |

### New tests

1. **`McpProtocolConformanceTest`** (platform tier) — starts the real server and drives it with
   the **SDK's own `Client`** over Streamable HTTP:
   ```kotlin
   val client = Client(Implementation("conformance-test", "1.0"))
   client.connect(StreamableHttpClientTransport(httpClient, url))
   assertTrue(client.listTools().tools.any { it.name == ToolNames.FIND_REFERENCES })
   val r = client.callTool(ToolNames.INDEX_STATUS, emptyMap())
   ```
   An independent MCP implementation agreeing with ours is a far stronger statement than any
   hand-written JSON assertion, and it is ~60 lines.
2. **`LegacySseTransportTest`** — GET `/index-mcp/sse`, assert the `endpoint` event is exactly
   `/index-mcp?sessionId=<uuid>`, POST a `tools/list` to it, assert the response arrives on the
   stream. Pins §4.1's promise that legacy clients are unaffected.
3. **`StatelessServerLifecycleTest`** — issue 200 sequential `tools/call` POSTs; assert no growth
   in live `Server` sessions and that JVM thread count is stable within a small delta. Guards the
   per-request-`Server` leak described in §3.
4. **`AcceptHeaderContractTest`** — assert 406 without the dual `Accept`, 200 with it. Makes the
   §4.2 row-1 break a *pinned decision* rather than a surprise.

### Command

```bash
./gradlew test
```

Full suite, platform tier included, ~40s. Per `CONTRIBUTING.md`, do not push on `-Ptier=unit`.

---

## 7. Phased implementation

Every phase compiles and leaves `./gradlew test` green. Each is a reviewable commit.

### Phase 0 — dependency spike (½ day, throwaway)

Prove the classpath before writing any code. On a scratch branch:

1. Swap the dependency block per §5. Keep `KtorMcpServer` compiling against Ktor 3 (the only
   breaks should be `environment.monitor` and `respondTextWriter` imports).
2. `./gradlew buildPlugin` and inspect `build/distributions/*.zip` → `lib/`: assert **no**
   `kotlin-stdlib*.jar`, **no** `kotlinx-coroutines*.jar`, exactly **one** Ktor version.
3. `./gradlew runIde`, hit the server with curl, confirm no `NoSuchMethodError` /
   `NoClassDefFoundError` from coroutines or serialization version skew.
4. `./gradlew verifyPlugin` — must stay clean at `COMPATIBILITY_PROBLEMS` + `INTERNAL_API_USAGES`.

**Gate:** if step 3 fails, stop and reassess before touching the protocol layer. Everything after
this assumes the SDK loads cleanly inside a plugin classloader.

### Phase 1 — types (mechanical)

- `SchemaBuilder.build(): ToolSchema`.
- `McpTool.inputSchema: ToolSchema`; `McpTool.execute` and `AbstractMcpTool.doExecute` return
  `CallToolResult`.
- Delete `server/models/McpModels.kt`. `ContentBlock.Text` → `TextContent`,
  `ContentBlock.Image` → `ImageContent` (3 files).
- IDE rename `ToolCallResult` → `CallToolResult` across the tree (56 files, no logic change).
- Update `ToolManifestContractUnitTest`'s renderer.

**Exit:** `tool-manifest.json` and `result-shapes.txt` unchanged; full suite green.
This phase alone is the highest-risk-of-noise, lowest-risk-of-breakage step — land it separately
so its diff is reviewable as "rename only".

### Phase 2 — extract the dispatcher

- New `McpToolDispatcher` holding the settings gate, `ProjectResolver`, command history, error
  mapping, and the IDE-modality wrapper.
- `JsonRpcHandler.processToolCall` becomes a 5-line delegation to it.
- Port the tool-level tests to drive the dispatcher.

**Exit:** no behaviour change at all; the old protocol layer is still live. Full suite green.

### Phase 3 — build the SDK server

- New `McpServerFactory`.
- New `McpWireFormatUnitTest` pinning the serialized shapes (§6).
- Nothing is wired to HTTP yet; test the factory through the SDK's in-memory transport pair.

**Exit:** two implementations coexist. Full suite green.

### Phase 4 — swap the transport

- Rewrite `KtorMcpServer` → `McpKtorServer`: `embeddedServer(CIO)`, `install(CORS)`,
  `mcpStatelessStreamableHttp(...)`, `routing { legacySseRoutes(...) }`, plus the existing
  start/stop/`isRunning`/watchdog surface unchanged.
- New `LegacySseRoutes.kt`.
- Delete `JsonRpcHandler`, `JsonRpcModels`, `JsonRpcMethods`, `KtorSseSessionManager`.
- Trim `McpServerService`.

**Exit:** the deltas in §4.2 become real. Adjust `KtorMcpServerUnitTest` here and nowhere else.

### Phase 5 — the new nets

- `McpProtocolConformanceTest`, `LegacySseTransportTest`, `StatelessServerLifecycleTest`,
  `AcceptHeaderContractTest`, `ToolSchemaFidelityUnitTest`.

### Phase 6 — docs, changelog, release

- `CHANGELOG.md` `[Unreleased]` → `### Breaking` with §4.2 rows 1, 4, 5, 6; `### Changed` with
  the rest; `### Fixed` for the stale `serverInfo.version`.
- `gradle.properties`: `pluginVersion = 5.0.0`.
- `smoke-tests/mcp-protocol.md` and `docs/smoke-test-protocol.md`: **every curl example needs
  `-H 'Accept: application/json, text/event-stream'`.** They currently send `Accept:
  application/json` and would all return 406 — this is the concrete, already-written breakage.
- `CLAUDE.md` §"MCP Server Architecture": replace the `JsonRpcHandler` / `KtorSseSessionManager`
  description with the SDK layering; note the supported protocol versions are now
  `2024-11-05 … 2025-11-25`.
- `README.md` / `USAGE.md`: same Accept-header note for anyone testing by hand.
- `./scripts/check-pr.sh`.

### Phase 7 — manual verification

Run the full smoke protocol in `smoke-tests/mcp-protocol.md` against a real IDE with:
Claude Code, Cursor, and one legacy SSE client. The unit suite cannot prove client interop.

---

## 8. Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| coroutines / serialization version skew between SDK 0.14.0 and the platform | medium | build loads but fails at runtime | Phase 0 gate — `runIde` + real request before any refactor |
| An existing client sends only `Accept: application/json` and starts getting 406 | **high for hand-written curl, low for real MCP clients** | connection fails | documented in `### Breaking`; pinned by `AcceptHeaderContractTest`; error body names the fix |
| `Server`-per-request leak in stateless mode | high if unhandled | slow memory growth over a long IDE session | explicit `close()` on call completion + `StatelessServerLifecycleTest` |
| Non-loopback `serverHost` users locked out by `Host` validation | low (unusual config, but supported by settings) | total loss of access | `allowedHosts = LOCALHOST + settings.serverHost`; add a test for a custom host |
| `install(SSE)` called twice → `DuplicatePluginException` at startup | medium during implementation | server never starts | single `Application.mcpStatelessStreamableHttp` call; legacy routes registered via `Route.*` |
| SDK 0.x makes breaking API changes | certain over time | migration churn | pin the version in the catalog; the SDK surface we touch is ~6 symbols; Dependabot already watches the catalog |
| A tool schema keyword `ToolSchema` cannot carry is added later | low | silent schema loss on the wire | `ToolSchemaFidelityUnitTest` |
| Losing the IDE modality context around tool execution | medium during implementation | "write-unsafe context" failures in `commitAllDocuments` | it moves into `McpToolDispatcher`; `AbstractMcpToolCommitDocumentsTest` already covers it |

## 9. Rollback

Phases 0–3 are additive and independently revertable. Phase 4 is the point of no return; it is a
single commit that deletes five files and rewrites one, so `git revert` restores the old
transport wholesale. Do not squash phases 1–4 together.

## 10. Effort

| Phase | Estimate |
|-------|----------|
| 0 spike | 0.5 d |
| 1 types | 0.5 d |
| 2 dispatcher | 0.5 d |
| 3 factory | 0.5 d |
| 4 transport swap | 1 d |
| 5 tests | 1 d |
| 6 docs/release | 0.5 d |
| 7 manual interop | 0.5 d |
| **total** | **~5 days** |

Deleting 1339 production lines and ~800 test lines while gaining Host validation, body-size
limits, correct version negotiation, resumable streams, and a path to resources/prompts/
elicitation.

---

## 11. What actually happened

Five things this design got wrong or did not foresee. Recorded because each one is a trap for
the next person who touches these versions.

### 11.1 SDK 0.14.0 does not compile against this platform — 0.10.0 is the ceiling

The design picked the newest SDK. It does not build:

```
Class 'io.ktor.server.application.ApplicationCall' was compiled with an incompatible version of
Kotlin. The actual metadata version is 2.3.0, but the compiler version 2.1.0 can read up to 2.2.0.
```

IntelliJ 2025.3 — the `pluginSinceBuild = 253` floor — ships Kotlin **2.2.20** and a stdlib with
metadata version 2.2.0. kotlin-sdk 0.11.0 and later are built against stdlib 2.3.x, which fails
twice over: at compile time as above, and at run time because a library compiled against a newer
stdlib may call members the IDE's stdlib does not have. Bumping our own Kotlin compiler fixes
only the first.

**kotlin-sdk 0.10.0 (stdlib 2.2.21, Ktor 3.2.3) is the newest release inside the platform's
Kotlin generation**, and it carries the entire API this migration needs. The version catalog now
says so in a comment; do not bump either pin without checking the Kotlin generation first.

The cost of staying on 0.10.0 is small: `ToolSchema` has no `$schema` field (which we did not
want anyway) and there is no `maxRequestBodySize`, so §4.2 row 11 — the 4 MiB body cap — did not
land.

### 11.2 The SDK's DNS-rebinding protection is unusable here

0.10.x `validateHeaders` compares the raw `Host` header **including the port** (so
`127.0.0.1:29170` fails an allow-list of `127.0.0.1`) and rejects any request with **no `Origin`
header** — which is every curl invocation and several MCP clients. §4.2 row 3 assumed it could be
switched on with `allowedHosts`.

Instead `enableDnsRebindingProtection = false` and `LocalOriginGuard` keeps the plugin's original
semantics, extended with the port-aware Host check the design wanted. It also emits the CORS
headers, which the SDK never did.

Ktor's own `CORS` plugin turned out to be the wrong tool for the same reason: `allowHost` matches
host *and* port, while this server has always accepted any loopback origin regardless of port.
`ktor-server-cors` was dropped from the dependency list entirely.

### 11.3 SDK 0.10.0 installs SSE but not ContentNegotiation

This one cost the most time and is invisible from the API surface. `Application.mcpStatelessStreamableHttp`
installs the `SSE` plugin and nothing else, while every transport reply goes through
`call.respond(<serializable>)`. With no JSON converter registered, Ktor answers **every** MCP
request with an empty `406 Not Acceptable` — which reads exactly like the SDK's own Accept-header
rejection, and is not.

0.14.0 added `installMcpContentNegotiation()` internally. Until we can move to it,
`KtorMcpServer` installs `ContentNegotiation { json(McpJson) }` itself. `McpJson` specifically:
its `explicitNulls = false` is what keeps `structuredContent`, `_meta` and friends off the wire.

### 11.4 The legacy stateless POST was nearly lost

§4.1 listed `POST /index-mcp` (no `sessionId`) as unchanged, and the first implementation
returned `400 sessionId query parameter is not provided` for it — a real regression for anyone
configured against the pre-Streamable-HTTP endpoint. `LegacySseRoutes.handleStatelessPost` now
runs a per-request stateless transport there, the same way the Streamable HTTP endpoint does.
`LegacySseTransportTest` pins it.

### 11.5 Two predicted wire changes did not happen; one new one did

- Scalar JSON still returns `-32600`, not `-32700` (§4.2 row 9 was wrong).
- `initialize` now requires `capabilities` in params. The MCP schema always mandated it and the
  hand-written handler ignored params entirely, so this is new strictness the design missed.
- `DELETE` on the Streamable HTTP endpoint still returns 405 but no longer sets `Allow: POST`.

### Test outcome

862 tests, all green. Both golden files — `tool-manifest.json` (47 tools × complete input schema)
and `result-shapes.txt` (61 result models) — are **byte-identical** across the migration, which is
the evidence that no tool surface moved.

Test changes: `JsonRpcHandlerUnitTest` (420 lines of envelope validation) deleted as the SDK's
job; `JsonRpcHandlerTest`/`JsonRpcHandlerHistoryFailureTest`/`McpServerIntegrationTest` ported to
`McpToolDispatcher*`; `McpModelsUnitTest` rewritten as `McpWireFormatUnitTest`;
`KtorMcpServerUnitTest` moved to the platform tier as `KtorMcpServerHttpTest` (the server reads
`McpSettings` per connection now, so it needs an Application). Added
`McpProtocolConformanceTest` (drives the running server with the SDK's own client),
`LegacySseTransportTest`, `StatelessServerLifecycleTest` and `ToolSchemaFidelityUnitTest`.

# IntelliJ Index MCP Plugin - Design Document

**Document Version**: 1.1
**Status**: Draft
**Based on**: requirements.md v1.0

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Package Structure](#2-package-structure)
3. [Core Components](#3-core-components)
4. [Data Models](#4-data-models)
5. [MCP Server Implementation](#5-mcp-server-implementation)
6. [Tool Implementation](#6-tool-implementation)
7. [Resource Providers](#7-resource-providers)
8. [GUI Components](#8-gui-components)
9. [Settings & Configuration](#9-settings--configuration)
10. [Threading Strategy](#10-threading-strategy)
11. [Error Handling](#11-error-handling)
12. [Testing Strategy](#12-testing-strategy)

---

## 1. Architecture Overview

### 1.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           AI Coding Agents                                   │
│              (Claude, Cursor, VS Code, Windsurf, etc.)                      │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │
                                  │ HTTP+SSE Transport
                                  │ GET  /index-mcp/sse → SSE stream (endpoint event)
                                  │ POST /index-mcp     → JSON-RPC requests
                                  │
┌─────────────────────────────────▼───────────────────────────────────────────┐
│                         IntelliJ IDEA Instance                               │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │              IDE Built-in Web Server (port 63342, etc.)               │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │  /api/mcp/*     → Built-in JetBrains MCP (2025.2+)              │  │  │
│  │  │  /index-mcp/*   → Our Plugin MCP Server                         │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                  │                                           │
│  ┌───────────────────────────────▼───────────────────────────────────────┐  │
│  │                    MCP Plugin Architecture                             │  │
│  │                                                                        │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                    Transport Layer                               │  │  │
│  │  │  ┌───────────────────────┐  ┌───────────────────────────────┐  │  │  │
│  │  │  │  McpRequestHandler    │  │     JsonRpcHandler            │  │  │  │
│  │  │  │  (HttpRequestHandler) │  │                               │  │  │  │
│  │  │  └───────────────────────┘  └───────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                  │                                     │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                     Service Layer                                │  │  │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │  │  │
│  │  │  │McpServer    │  │ToolRegistry │  │ CommandHistoryService   │  │  │  │
│  │  │  │Service      │  │             │  │                         │  │  │  │
│  │  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                  │                                     │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                      Tool Layer                                  │  │  │
│  │  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌─────────────┐  │  │  │
│  │  │  │Navigation │  │Refactoring│  │Intelligence│  │ Project     │  │  │  │
│  │  │  │Tools      │  │Tools      │  │Tools       │  │ Tools       │  │  │  │
│  │  │  └───────────┘  └───────────┘  └───────────┘  └─────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                  │                                     │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                      GUI Layer                                   │  │  │
│  │  │  ┌─────────────────────┐  ┌─────────────────────────────────┐  │  │  │
│  │  │  │ McpToolWindowFactory│  │ CommandHistoryPanel             │  │  │  │
│  │  │  └─────────────────────┘  └─────────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                  │                                           │
│  ┌───────────────────────────────▼───────────────────────────────────────┐  │
│  │                    IntelliJ Platform APIs                              │  │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌─────────────────────┐ │  │
│  │  │    PSI    │  │  Indexes  │  │Refactoring│  │     DumbService     │ │  │
│  │  └───────────┘  └───────────┘  └───────────┘  └─────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Multi-Instance Support

Each IntelliJ IDE instance has its own built-in web server on a unique port:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              User's Machine                                  │
│                                                                              │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │
│  │   IntelliJ #1       │  │   IntelliJ #2       │  │   IntelliJ #3       │  │
│  │   Project: myapp    │  │   Project: api      │  │   Project: web      │  │
│  │   Port: 63342       │  │   Port: 63343       │  │   Port: 63344       │  │
│  │   /index-mcp        │  │   /index-mcp        │  │   /index-mcp        │  │
│  └─────────────────────┘  └─────────────────────┘  └─────────────────────┘  │
│                                                                              │
│  Client connects to specific IDE via port:                                   │
│  - http://localhost:63342/index-mcp  → IntelliJ #1 (myapp)                  │
│  - http://localhost:63343/index-mcp  → IntelliJ #2 (api)                    │
│  - http://localhost:63344/index-mcp  → IntelliJ #3 (web)                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Client Configuration Example (Claude Desktop / Cursor):**
```json
{
  "mcpServers": {
    "intellij-myapp": {
      "url": "http://localhost:63342/index-mcp/sse"
    },
    "intellij-api": {
      "url": "http://localhost:63343/index-mcp/sse"
    }
  }
}
```

**Note:** The `/sse` suffix is required for HTTP+SSE transport. The client will:
1. Connect to `/index-mcp/sse` via GET to establish SSE stream
2. Receive an `endpoint` event with the POST URL (`/index-mcp`)
3. Send JSON-RPC requests to that POST endpoint

**How to find IDE port:**
- Settings → Build, Execution, Deployment → Debugger → Built-in Server Port
- Or check IDE status bar / About dialog

### 1.3 Multi-Project Resolution

When multiple projects are open in a single IDE instance, the server must determine which project to use:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Multi-Project Resolution Flow                         │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Tool Call Request                                 │   │
│  │                                                                      │   │
│  │   {                                                                  │   │
│  │     "name": "find_usages",                                          │   │
│  │     "arguments": {                                                   │   │
│  │       "project_path": "/path/to/project",  ← OPTIONAL               │   │
│  │       "file": "src/Main.kt",                                        │   │
│  │       "line": 10, "column": 5                                       │   │
│  │     }                                                                │   │
│  │   }                                                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                  │                                          │
│                                  ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Project Resolution Logic                          │   │
│  │                                                                      │   │
│  │   if (project_path provided) {                                      │   │
│  │       → Find project matching path                                   │   │
│  │       → Error if not found                                          │   │
│  │   } else if (only 1 project open) {                                 │   │
│  │       → Use that project                                            │   │
│  │   } else {                                                          │   │
│  │       → Return error with available projects list                   │   │
│  │   }                                                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                  │                                          │
│                                  ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │         Error Response (when multiple projects, no path)             │   │
│  │                                                                      │   │
│  │   {                                                                  │   │
│  │     "isError": true,                                                │   │
│  │     "content": [{                                                    │   │
│  │       "type": "text",                                               │   │
│  │       "text": {                                                      │   │
│  │         "error": "multiple_projects_open",                          │   │
│  │         "message": "Multiple projects are open. Please specify...", │   │
│  │         "available_projects": [                                      │   │
│  │           {"name": "myapp", "path": "/Users/dev/myapp"},            │   │
│  │           {"name": "api", "path": "/Users/dev/api"}                 │   │
│  │         ]                                                            │   │
│  │       }                                                              │   │
│  │     }]                                                               │   │
│  │   }                                                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.4 Component Interaction Flow

```
┌─────────┐      ┌────────────────┐      ┌─────────────┐      ┌──────────────┐
│  Client │─────▶│McpRequestHandler│─────▶│JsonRpcHandler│─────▶│ToolRegistry  │
└─────────┘      └────────────────┘      └─────────────┘      └──────────────┘
                                          │                     │
                                          ▼                     ▼
                                   ┌─────────────┐      ┌──────────────┐
                                   │CommandHistory│◀────│  Tool.execute│
                                   │   Service   │      └──────────────┘
                                   └─────────────┘              │
                                          │                     ▼
                                          ▼              ┌──────────────┐
                                   ┌─────────────┐      │IntelliJ APIs │
                                   │    GUI      │      └──────────────┘
                                   │   Update    │
                                   └─────────────┘
```

---

## 2. Package Structure

```
src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/
├── McpBundle.kt                           # Resource bundle accessor
│
├── server/                                # MCP Server components
│   ├── McpServerService.kt                # Main server service (application-level)
│   ├── McpRequestHandler.kt               # HttpRequestHandler for /index-mcp
│   └── JsonRpcHandler.kt                  # JSON-RPC 2.0 message handling
│
├── tools/                                 # MCP Tool implementations
│   ├── ToolRegistry.kt                    # Tool registration and lookup
│   ├── McpTool.kt                         # Base tool interface
│   ├── ToolResult.kt                      # Tool result wrapper
│   │
│   ├── navigation/                        # Navigation tools
│   │   ├── FindUsagesTool.kt
│   │   ├── FindDefinitionTool.kt
│   │   ├── TypeHierarchyTool.kt
│   │   ├── CallHierarchyTool.kt
│   │   └── FindImplementationsTool.kt
│   │
│   ├── refactoring/                       # Refactoring tools
│   │   ├── RenameSymbolTool.kt
│   │   ├── ExtractMethodTool.kt
│   │   ├── ExtractVariableTool.kt
│   │   ├── InlineTool.kt
│   │   ├── SafeDeleteTool.kt
│   │   └── MoveElementTool.kt
│   │
│   ├── intelligence/                      # Code intelligence tools
│   │   ├── GetCompletionsTool.kt
│   │   ├── GetInspectionsTool.kt
│   │   ├── GetQuickFixesTool.kt
│   │   ├── ApplyQuickFixTool.kt
│   │   └── GetSymbolInfoTool.kt
│   │
│   └── project/                           # Project structure tools
│       ├── GetProjectStructureTool.kt
│       ├── GetFileStructureTool.kt
│       ├── GetDependenciesTool.kt
│       └── GetIndexStatusTool.kt
│
├── resources/                             # MCP Resource providers
│   ├── ResourceRegistry.kt
│   ├── McpResource.kt
│   ├── ProjectStructureResource.kt
│   ├── FileContentResource.kt
│   ├── SymbolInfoResource.kt
│   └── IndexStatusResource.kt
│
├── history/                               # Command history
│   ├── CommandHistoryService.kt           # History management service
│   ├── CommandEntry.kt                    # History entry data class
│   └── CommandStatus.kt                   # Status enum
│
├── ui/                                    # GUI components
│   ├── McpToolWindowFactory.kt            # Tool window factory
│   ├── McpToolWindowPanel.kt              # Main panel
│   ├── ServerStatusPanel.kt               # Server status display
│   ├── CommandHistoryPanel.kt             # Command history list
│   ├── CommandDetailPanel.kt              # Command detail view
│   ├── CommandListCellRenderer.kt         # Custom list renderer
│   └── FilterToolbar.kt                   # Filter/search toolbar
│
├── settings/                              # Plugin settings
│   ├── McpSettings.kt                     # Settings state
│   ├── McpSettingsConfigurable.kt         # Settings UI
│   └── McpSettingsComponent.kt            # Settings form component
│
├── util/                                  # Utilities
│   ├── PsiUtils.kt                        # PSI helper functions
│   ├── ProjectUtils.kt                    # Project path utilities
│   ├── JsonUtils.kt                       # JSON serialization helpers
│   └── ThreadingUtils.kt                  # Threading utilities
│
├── actions/                               # IDE actions
│   ├── CopyServerUrlAction.kt
│   ├── ClearHistoryAction.kt
│   ├── ExportHistoryAction.kt
│   └── RefreshServerAction.kt
│
└── startup/                               # Startup activities
    └── McpServerStartupActivity.kt        # Auto-start server on project open

src/main/resources/
├── META-INF/
│   ├── plugin.xml                         # Plugin configuration
│   └── pluginIcon.svg                     # Plugin icon
├── messages/
│   └── McpBundle.properties               # i18n messages
└── icons/
    ├── mcp-server.svg
    ├── status-running.svg
    ├── status-stopped.svg
    └── status-error.svg
```

---

## 3. Core Components

### 3.1 McpServerService

**Responsibility**: Application-level service managing tool and resource registries.

```kotlin
@Service(Service.Level.APPLICATION)
class McpServerService : Disposable {

    private val toolRegistry: ToolRegistry = ToolRegistry()
    private val resourceRegistry: ResourceRegistry = ResourceRegistry()
    private val jsonRpcHandler: JsonRpcHandler

    init {
        jsonRpcHandler = JsonRpcHandler(toolRegistry, resourceRegistry)
        toolRegistry.registerBuiltInTools()
    }

    fun getToolRegistry(): ToolRegistry = toolRegistry
    fun getResourceRegistry(): ResourceRegistry = resourceRegistry
    fun getJsonRpcHandler(): JsonRpcHandler = jsonRpcHandler

    fun getServerUrl(project: Project): String {
        val port = BuiltInServerManager.getInstance().port
        return "http://localhost:$port/index-mcp"
    }

    override fun dispose() {
        // Cleanup if needed
    }

    companion object {
        fun getInstance(): McpServerService = service()
    }
}
```

### 3.2 McpRequestHandler

**Responsibility**: Handles HTTP+SSE transport on `/index-mcp` and `/index-mcp/sse` paths via IDE's built-in web server.

```kotlin
class McpRequestHandler : HttpRequestHandler() {

    companion object {
        const val MCP_PATH = "/index-mcp"
        const val SSE_PATH = "$MCP_PATH/sse"
    }

    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.uri().startsWith(MCP_PATH)
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        val path = urlDecoder.path()

        return when {
            // GET /index-mcp/sse → SSE stream
            request.method() == HttpMethod.GET && path == SSE_PATH -> {
                handleSseRequest(context)
                true
            }
            // POST /index-mcp OR /index-mcp/sse → JSON-RPC
            // (Some clients POST to /sse endpoint for streamable HTTP fallback)
            request.method() == HttpMethod.POST && (path == MCP_PATH || path == SSE_PATH) -> {
                handlePostRequest(request, context)
                true
            }
            // OPTIONS for CORS
            request.method() == HttpMethod.OPTIONS -> {
                handleOptionsRequest(context)
                true
            }
            else -> false
        }
    }

    private fun handleSseRequest(context: ChannelHandlerContext) {
        // Send SSE response headers
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
            set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
            set(HttpHeaderNames.CONNECTION, "keep-alive")
        }
        context.write(response)

        // Send endpoint event with POST URL
        val port = BuiltInServerManager.getInstance().port
        val endpointUrl = "http://localhost:$port$MCP_PATH"
        val endpointEvent = "event: endpoint\ndata: $endpointUrl\n\n"
        val buffer = Unpooled.copiedBuffer(endpointEvent, StandardCharsets.UTF_8)
        context.writeAndFlush(DefaultHttpContent(buffer))
    }

    private fun handlePostRequest(
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ) {
        val body = request.content().toString(Charsets.UTF_8)
        val mcpService = McpServerService.getInstance()

        // Process JSON-RPC request
        val response = runBlocking {
            mcpService.getJsonRpcHandler().handleRequest(body)
        }

        sendJsonResponse(context, HttpResponseStatus.OK, response)
    }

    private fun sendJsonResponse(
        context: ChannelHandlerContext,
        status: HttpResponseStatus,
        json: String
    ) {
        val content = Unpooled.copiedBuffer(json, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8")
            set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
            // CORS headers
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS")
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Accept")
        }
        context.writeAndFlush(response)
    }
}
```

### 3.3 JsonRpcHandler

**Responsibility**: Parses JSON-RPC 2.0 messages and routes to appropriate handlers.

```kotlin
class JsonRpcHandler(
    private val toolRegistry: ToolRegistry,
    private val resourceRegistry: ResourceRegistry,
    private val commandHistoryService: CommandHistoryService
) {
    suspend fun handleRequest(jsonString: String): String
    suspend fun handleNotification(jsonString: String)

    private suspend fun processToolCall(request: JsonRpcRequest): JsonRpcResponse
    private suspend fun processResourceRead(request: JsonRpcRequest): JsonRpcResponse
    private fun processToolsList(): JsonRpcResponse
    private fun processResourcesList(): JsonRpcResponse
    private fun processInitialize(request: JsonRpcRequest): JsonRpcResponse

    private fun createErrorResponse(
        id: JsonElement?,
        code: Int,
        message: String
    ): JsonRpcResponse
}
```

### 3.4 ToolRegistry

**Responsibility**: Manages registration and lookup of MCP tools.

```kotlin
class ToolRegistry {
    private val tools = mutableMapOf<String, McpTool>()

    fun register(tool: McpTool)
    fun unregister(toolName: String)
    fun getTool(name: String): McpTool?
    fun getAllTools(): List<McpTool>
    fun getToolDefinitions(): List<ToolDefinition>

    fun registerBuiltInTools(project: Project)
}
```

### 3.5 CommandHistoryService

**Responsibility**: Records and manages command execution history.

```kotlin
@Service(Service.Level.PROJECT)
class CommandHistoryService(private val project: Project) {
    private val history = mutableListOf<CommandEntry>()
    private val listeners = mutableListOf<CommandHistoryListener>()

    val entries: List<CommandEntry>
        get() = history.toList()

    fun recordCommand(entry: CommandEntry)
    fun updateCommandStatus(id: String, status: CommandStatus, result: String?)
    fun clearHistory()
    fun getFilteredHistory(filter: CommandFilter): List<CommandEntry>
    fun exportToJson(): String
    fun exportToCsv(): String

    fun addListener(listener: CommandHistoryListener)
    fun removeListener(listener: CommandHistoryListener)

    private fun trimHistoryIfNeeded()
    private fun notifyListeners(event: CommandHistoryEvent)
}

interface CommandHistoryListener {
    fun onCommandAdded(entry: CommandEntry)
    fun onCommandUpdated(entry: CommandEntry)
    fun onHistoryCleared()
}
```

---

## 4. Data Models

### 4.1 JSON-RPC Models

```kotlin
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

// IMPORTANT: @EncodeDefault(EncodeDefault.Mode.NEVER) is critical!
// Without it, null fields are serialized as "error":null which causes
// client parsing errors (e.g., Cursor's "Unrecognized key(s) in object: 'error'")
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val result: JsonElement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Custom error codes for MCP tools
    const val INDEX_NOT_READY = -32001
    const val FILE_NOT_FOUND = -32002
    const val SYMBOL_NOT_FOUND = -32003
    const val REFACTORING_CONFLICT = -32004
}
```

### 4.2 MCP Protocol Models

```kotlin
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class ToolCallResult(
    val content: List<ContentBlock>,
    val isError: Boolean = false
)

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(val data: String, val mimeType: String) : ContentBlock()
}

@Serializable
data class ResourceDefinition(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String
)

@Serializable
data class ResourceContent(
    val uri: String,
    val mimeType: String,
    val text: String? = null,
    val blob: String? = null
)
```

### 4.3 Command History Models

```kotlin
data class CommandEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
    val toolName: String,
    val parameters: JsonObject,
    var status: CommandStatus = CommandStatus.PENDING,
    var result: String? = null,
    var error: String? = null,
    var durationMs: Long? = null,
    var affectedFiles: List<String>? = null
)

enum class CommandStatus {
    PENDING,
    SUCCESS,
    ERROR
}

data class CommandFilter(
    val toolName: String? = null,
    val status: CommandStatus? = null,
    val searchText: String? = null
)
```

### 4.4 Tool Input/Output Models

```kotlin
// Common input for position-based tools
@Serializable
data class PositionInput(
    val file: String,
    val line: Int,
    val column: Int
)

// find_usages output
@Serializable
data class UsageLocation(
    val file: String,
    val line: Int,
    val column: Int,
    val context: String,
    val type: String
)

@Serializable
data class FindUsagesResult(
    val usages: List<UsageLocation>,
    val totalCount: Int
)

// find_definition output
@Serializable
data class DefinitionResult(
    val file: String,
    val line: Int,
    val column: Int,
    val preview: String,
    val symbolName: String
)

// type_hierarchy output
@Serializable
data class TypeHierarchyResult(
    val element: TypeElement,
    val supertypes: List<TypeElement>,
    val subtypes: List<TypeElement>
)

@Serializable
data class TypeElement(
    val name: String,
    val file: String?,
    val kind: String
)

// call_hierarchy output
@Serializable
data class CallHierarchyResult(
    val element: CallElement,
    val calls: List<CallElement>
)

@Serializable
data class CallElement(
    val name: String,
    val file: String,
    val line: Int,
    val children: List<CallElement>? = null
)

// Refactoring result
@Serializable
data class RefactoringResult(
    val success: Boolean,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String
)
```

---

## 5. MCP Server Implementation

### 5.1 Server Architecture

The plugin registers an `HttpRequestHandler` on the IDE's built-in web server. The server is always available when the IDE is running - no separate start/stop lifecycle.

```
┌─────────────────────────────────────────────────────────────────┐
│                    IDE Built-in Web Server                       │
│                                                                  │
│   Port: Configured in Settings (default 63342)                  │
│   Find via: Settings → Debugger → Built-in Server Port          │
│                                                                  │
│   Registered Handlers:                                           │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │  /api/mcp/*     →  JetBrains Built-in MCP               │   │
│   │  /index-mcp/sse →  Our Plugin SSE endpoint              │   │
│   │  /index-mcp     →  Our Plugin JSON-RPC POST endpoint   │   │
│   │  /api/*         →  Other IDE APIs                       │   │
│   └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 HTTP+SSE Endpoint Design

**SSE Endpoint**: `http://localhost:{IDE_PORT}/index-mcp/sse`

| Method | Purpose | Response |
|--------|---------|----------|
| GET | Establish SSE stream | SSE stream with `endpoint` event |

**JSON-RPC Endpoint**: `http://localhost:{IDE_PORT}/index-mcp`

| Method | Purpose | Request Body | Response |
|--------|---------|--------------|----------|
| POST | JSON-RPC requests | JSON-RPC Request | JSON-RPC Response |

**Connection Flow:**
1. Client opens GET to `/index-mcp/sse`
2. Server sends: `event: endpoint\ndata: http://localhost:{port}/index-mcp\n\n`
3. Client POSTs JSON-RPC to that endpoint
4. Server responds with JSON-RPC response

**plugin.xml Registration:**
```xml
<extensions defaultExtensionNs="com.intellij">
    <httpRequestHandler implementation="...server.McpRequestHandler"/>
</extensions>
```

### 5.3 Request Processing Pipeline

```kotlin
suspend fun handleMcpRequest(call: ApplicationCall) {
    val requestBody = call.receiveText()

    // 1. Parse JSON
    val jsonRpcRequest = try {
        Json.decodeFromString<JsonRpcRequest>(requestBody)
    } catch (e: Exception) {
        call.respond(createParseErrorResponse())
        return
    }

    // 2. Record in history
    val commandEntry = commandHistoryService.recordCommand(
        CommandEntry(
            toolName = jsonRpcRequest.method,
            parameters = jsonRpcRequest.params ?: JsonObject(emptyMap())
        )
    )

    // 3. Route and execute
    val response = try {
        when {
            jsonRpcRequest.method == "initialize" ->
                processInitialize(jsonRpcRequest)
            jsonRpcRequest.method == "tools/list" ->
                processToolsList()
            jsonRpcRequest.method == "tools/call" ->
                processToolCall(jsonRpcRequest)
            jsonRpcRequest.method == "resources/list" ->
                processResourcesList()
            jsonRpcRequest.method == "resources/read" ->
                processResourceRead(jsonRpcRequest)
            else ->
                createMethodNotFoundResponse(jsonRpcRequest.id)
        }
    } catch (e: Exception) {
        createInternalErrorResponse(jsonRpcRequest.id, e.message)
    }

    // 4. Update history
    commandHistoryService.updateCommandStatus(
        commandEntry.id,
        if (response.error != null) CommandStatus.ERROR else CommandStatus.SUCCESS,
        response.result?.toString()
    )

    // 5. Respond
    call.respond(HttpStatusCode.OK, Json.encodeToString(response))
}
```

---

## 6. Tool Implementation

### 6.1 Tool Interface

```kotlin
interface McpTool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    suspend fun execute(
        project: Project,
        arguments: JsonObject
    ): ToolCallResult
}
```

### 6.2 Abstract Base Tool

```kotlin
abstract class AbstractMcpTool : McpTool {

    protected fun requireSmartMode(project: Project) {
        if (DumbService.isDumb(project)) {
            throw IndexNotReadyException("IDE is in dumb mode, indexes not available")
        }
    }

    protected fun <T> readAction(action: () -> T): T {
        return ReadAction.compute<T, Throwable>(action)
    }

    protected fun writeAction(project: Project, commandName: String, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, commandName, null, action)
    }

    protected fun resolveFile(project: Project, relativePath: String): VirtualFile? {
        return project.basePath?.let { basePath ->
            LocalFileSystem.getInstance().findFileByPath("$basePath/$relativePath")
        }
    }

    protected fun findPsiElement(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): PsiElement? {
        val virtualFile = resolveFile(project, file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return null

        val offset = document.getLineStartOffset(line - 1) + (column - 1)
        return psiFile.findElementAt(offset)
    }

    protected fun createSuccessResult(text: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(text)),
            isError = false
        )
    }

    protected fun createErrorResult(message: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(ContentBlock.Text(message)),
            isError = true
        )
    }

    protected inline fun <reified T> createJsonResult(data: T): ToolCallResult {
        val json = Json.encodeToString(data)
        return ToolCallResult(
            content = listOf(ContentBlock.Text(json)),
            isError = false
        )
    }
}
```

### 6.3 Example Tool Implementation: FindUsagesTool

```kotlin
class FindUsagesTool : AbstractMcpTool() {

    override val name = "ide_find_references"

    override val description = """
        Finds all references to a symbol across the entire project using IntelliJ's semantic index.
        Use when locating where a method, class, variable, or field is called or accessed.
        Use when understanding code dependencies or preparing for refactoring.
        Returns file locations with line numbers, column positions, context snippets, and reference types (METHOD_CALL, FIELD_ACCESS, IMPORT, etc.).
    """.trimIndent()

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to the file relative to project root")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number")
            }
        }
        putJsonArray("required") {
            add("file")
            add("line")
            add("column")
        }
    }

    override suspend fun execute(
        project: Project,
        arguments: JsonObject
    ): ToolCallResult {
        requireSmartMode(project)

        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")

        return readAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult("No element found at position")

            val targetElement = element.parent as? PsiNamedElement
                ?: return@readAction createErrorResult("No named element at position")

            val usages = ReferencesSearch.search(targetElement)
                .findAll()
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val refFile = refElement.containingFile?.virtualFile?.path
                        ?.removePrefix(project.basePath ?: "")
                        ?.removePrefix("/")
                        ?: return@mapNotNull null

                    val document = PsiDocumentManager.getInstance(project)
                        .getDocument(refElement.containingFile)
                        ?: return@mapNotNull null

                    val lineNumber = document.getLineNumber(refElement.textOffset) + 1
                    val columnNumber = refElement.textOffset -
                        document.getLineStartOffset(lineNumber - 1) + 1

                    val lineText = document.getText(
                        TextRange(
                            document.getLineStartOffset(lineNumber - 1),
                            document.getLineEndOffset(lineNumber - 1)
                        )
                    ).trim()

                    UsageLocation(
                        file = refFile,
                        line = lineNumber,
                        column = columnNumber,
                        context = lineText,
                        type = classifyUsage(refElement)
                    )
                }

            createJsonResult(FindUsagesResult(
                usages = usages,
                totalCount = usages.size
            ))
        }
    }

    private fun classifyUsage(element: PsiElement): String {
        return when (element.parent) {
            is PsiMethodCallExpression -> "METHOD_CALL"
            is PsiReferenceExpression -> "REFERENCE"
            is PsiField -> "FIELD_ACCESS"
            else -> "UNKNOWN"
        }
    }
}
```

### 6.4 Tool Registration

```kotlin
// In ToolRegistry.kt
fun registerBuiltInTools(project: Project) {
    // Navigation tools (ide_* prefix)
    register(FindUsagesTool())        // ide_find_references
    register(FindDefinitionTool())    // ide_find_definition
    register(TypeHierarchyTool())     // ide_type_hierarchy
    register(CallHierarchyTool())     // ide_call_hierarchy
    register(FindImplementationsTool()) // ide_find_implementations

    // Intelligence tools (ide_* prefix)
    register(GetSymbolInfoTool())     // ide_inspect_symbol
    register(GetCompletionsTool())    // ide_code_completions
    register(GetInspectionsTool())    // ide_analyze_code
    register(GetQuickFixesTool())     // ide_list_quick_fixes
    register(ApplyQuickFixTool())     // ide_apply_quick_fix

    // Project tools (ide_* prefix)
    register(GetProjectStructureTool()) // ide_project_structure
    register(GetFileStructureTool())    // ide_file_structure
    register(GetDependenciesTool())     // ide_list_dependencies
    register(GetIndexStatusTool())      // ide_index_status

    // Refactoring tools (ide_refactor_* prefix)
    register(RenameSymbolTool())      // ide_refactor_rename
    register(ExtractMethodTool())     // ide_refactor_extract_method
    register(ExtractVariableTool())   // ide_refactor_extract_variable
    register(InlineTool())            // ide_refactor_inline
    register(SafeDeleteTool())        // ide_refactor_safe_delete
    register(MoveElementTool())       // ide_refactor_move
}
```

---

## 7. Resource Providers

### 7.1 Resource Interface

```kotlin
interface McpResource {
    val uri: String
    val name: String
    val description: String
    val mimeType: String

    suspend fun read(project: Project): ResourceContent
}
```

### 7.2 Resource Implementations

```kotlin
class ProjectStructureResource : McpResource {
    override val uri = "project://structure"
    override val name = "Project Structure"
    override val description = "Current project module structure"
    override val mimeType = "application/json"

    override suspend fun read(project: Project): ResourceContent {
        val structure = readAction {
            val moduleManager = ModuleManager.getInstance(project)
            buildJsonObject {
                put("name", project.name)
                put("basePath", project.basePath)
                putJsonArray("modules") {
                    moduleManager.modules.forEach { module ->
                        addJsonObject {
                            put("name", module.name)
                            putJsonArray("sourceRoots") {
                                ModuleRootManager.getInstance(module)
                                    .sourceRoots
                                    .forEach { root ->
                                        add(root.path)
                                    }
                            }
                        }
                    }
                }
            }
        }
        return ResourceContent(
            uri = uri,
            mimeType = mimeType,
            text = structure.toString()
        )
    }
}

class IndexStatusResource : McpResource {
    override val uri = "index://status"
    override val name = "Index Status"
    override val description = "IDE indexing status"
    override val mimeType = "application/json"

    override suspend fun read(project: Project): ResourceContent {
        val status = buildJsonObject {
            put("isDumbMode", DumbService.isDumb(project))
            put("isIndexing", DumbService.getInstance(project).isAlternativeResolveEnabled)
        }
        return ResourceContent(
            uri = uri,
            mimeType = mimeType,
            text = status.toString()
        )
    }
}
```

---

## 8. GUI Components

### 8.1 Tool Window Structure

```
McpToolWindowFactory
    └── McpToolWindowPanel
            ├── ServerStatusPanel
            │       ├── StatusLabel (Running/Stopped)
            │       ├── UrlLabel + CopyButton
            │       └── ProjectLabel
            │
            ├── FilterToolbar
            │       ├── ToolNameFilter (ComboBox)
            │       ├── StatusFilter (ComboBox)
            │       └── SearchField
            │
            ├── CommandHistoryPanel (JBList)
            │       └── CommandListCellRenderer
            │
            └── CommandDetailPanel (JBSplitter - bottom)
                    ├── RequestJsonViewer
                    └── ResponseJsonViewer
```

### 8.2 McpToolWindowFactory

```kotlin
class McpToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(
            panel,
            "MCP Server",
            false
        )
        toolWindow.contentManager.addContent(content)

        // Set up toolbar actions
        val actionGroup = DefaultActionGroup().apply {
            add(RefreshServerAction())
            add(CopyServerUrlAction())
            addSeparator()
            add(ClearHistoryAction())
            add(ExportHistoryAction())
            addSeparator()
            add(OpenSettingsAction())
        }
        toolWindow.setTitleActions(listOf(actionGroup))
    }

    override fun shouldBeAvailable(project: Project) = true
}
```

### 8.3 CommandHistoryPanel

```kotlin
class CommandHistoryPanel(
    private val project: Project
) : JBPanel<CommandHistoryPanel>(BorderLayout()), CommandHistoryListener {

    private val listModel = DefaultListModel<CommandEntry>()
    private val commandList = JBList(listModel)
    private val historyService = CommandHistoryService.getInstance(project)

    init {
        commandList.cellRenderer = CommandListCellRenderer()
        commandList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        commandList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                onCommandSelected(commandList.selectedValue)
            }
        }

        add(JBScrollPane(commandList), BorderLayout.CENTER)

        historyService.addListener(this)
        refreshList()
    }

    override fun onCommandAdded(entry: CommandEntry) {
        ApplicationManager.getApplication().invokeLater {
            listModel.add(0, entry)
            if (McpSettings.getInstance().autoScroll) {
                commandList.selectedIndex = 0
            }
        }
    }

    override fun onCommandUpdated(entry: CommandEntry) {
        ApplicationManager.getApplication().invokeLater {
            val index = (0 until listModel.size).firstOrNull {
                listModel.getElementAt(it).id == entry.id
            }
            index?.let {
                listModel.setElementAt(entry, it)
            }
        }
    }

    override fun onHistoryCleared() {
        ApplicationManager.getApplication().invokeLater {
            listModel.clear()
        }
    }

    private fun refreshList() {
        listModel.clear()
        historyService.entries.forEach { listModel.addElement(it) }
    }

    private fun onCommandSelected(entry: CommandEntry?) {
        // Notify detail panel
    }
}
```

### 8.4 CommandListCellRenderer

```kotlin
class CommandListCellRenderer : ListCellRenderer<CommandEntry> {

    private val panel = JPanel(BorderLayout())
    private val timestampLabel = JBLabel()
    private val toolNameLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val expandIcon = JBLabel()

    override fun getListCellRendererComponent(
        list: JList<out CommandEntry>,
        value: CommandEntry,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        // Timestamp
        timestampLabel.text = DateTimeFormatter
            .ofPattern("HH:mm:ss")
            .format(value.timestamp.atZone(ZoneId.systemDefault()))

        // Tool name
        toolNameLabel.text = value.toolName
        toolNameLabel.font = toolNameLabel.font.deriveFont(Font.BOLD)

        // Status with color
        statusLabel.text = value.status.name
        statusLabel.foreground = when (value.status) {
            CommandStatus.SUCCESS -> JBColor.GREEN
            CommandStatus.ERROR -> JBColor.RED
            CommandStatus.PENDING -> JBColor.YELLOW
        }

        // Background
        panel.background = if (isSelected) {
            list.selectionBackground
        } else {
            list.background
        }

        return panel
    }
}
```

---

## 9. Settings & Configuration

### 9.1 McpSettings

```kotlin
@Service(Service.Level.APPLICATION)
@State(
    name = "McpPluginSettings",
    storages = [Storage("mcp-plugin.xml")]
)
class McpSettings : PersistentStateComponent<McpSettings.State> {

    data class State(
        var maxHistorySize: Int = 100,
        var autoScroll: Boolean = true,
        var showTimestamps: Boolean = true,
        var confirmWriteOperations: Boolean = true,
        var logToFile: Boolean = false,
        var logFilePath: String = ""
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var maxHistorySize: Int
        get() = state.maxHistorySize
        set(value) { state.maxHistorySize = value }

    var autoScroll: Boolean
        get() = state.autoScroll
        set(value) { state.autoScroll = value }

    var showTimestamps: Boolean
        get() = state.showTimestamps
        set(value) { state.showTimestamps = value }

    var confirmWriteOperations: Boolean
        get() = state.confirmWriteOperations
        set(value) { state.confirmWriteOperations = value }

    var logToFile: Boolean
        get() = state.logToFile
        set(value) { state.logToFile = value }

    companion object {
        fun getInstance(): McpSettings = service()
    }
}
```

### 9.2 plugin.xml Configuration

```xml
<idea-plugin>
    <id>com.github.hechtcarmel.jetbrainsindexmcpplugin</id>
    <name>IntelliJ Index MCP Server</name>
    <vendor>hechtcarmel</vendor>

    <depends>com.intellij.modules.platform</depends>
    <depends optional="true" config-file="java-features.xml">
        com.intellij.modules.java
    </depends>
    <depends optional="true" config-file="kotlin-features.xml">
        org.jetbrains.kotlin
    </depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Project Service -->
        <projectService
            serviceImplementation="...server.McpServerService"/>

        <!-- Command History Service -->
        <projectService
            serviceImplementation="...history.CommandHistoryService"/>

        <!-- Tool Window -->
        <toolWindow
            id="MCP Server"
            anchor="bottom"
            icon="McpIcons.ToolWindow"
            factoryClass="...ui.McpToolWindowFactory"/>

        <!-- Settings -->
        <applicationService
            serviceImplementation="...settings.McpSettings"/>
        <applicationConfigurable
            id="mcp.settings"
            displayName="MCP Server"
            instance="...settings.McpSettingsConfigurable"/>

        <!-- Startup Activity -->
        <postStartupActivity
            implementation="...startup.McpServerStartupActivity"/>

        <!-- Notifications -->
        <notificationGroup
            id="MCP Server"
            displayType="BALLOON"/>
    </extensions>

    <actions>
        <group id="McpServer.Actions">
            <action id="McpServer.CopyUrl"
                class="...actions.CopyServerUrlAction"
                text="Copy Server URL"/>
            <action id="McpServer.ClearHistory"
                class="...actions.ClearHistoryAction"
                text="Clear History"/>
            <action id="McpServer.ExportHistory"
                class="...actions.ExportHistoryAction"
                text="Export History..."/>
        </group>
    </actions>
</idea-plugin>
```

---

## 10. Threading Strategy

### 10.1 Thread Usage Matrix

| Operation | Thread | API |
|-----------|--------|-----|
| HTTP Request Handling | Ktor IO Dispatcher | `suspend fun` |
| PSI Read | Any thread with ReadAction | `ReadAction.compute { }` |
| PSI Write | EDT | `WriteCommandAction.runWriteCommandAction()` |
| Index Access | Any (check dumb first) | `DumbService.isDumb()` |
| GUI Update | EDT | `invokeLater { }` |
| File I/O | Background | Coroutine dispatcher |
| History Update | Any + EDT notification | Service + `invokeLater` |

### 10.2 Threading Utilities

```kotlin
object ThreadingUtils {

    suspend fun <T> readActionSuspend(action: () -> T): T {
        return withContext(Dispatchers.Default) {
            ReadAction.compute<T, Throwable>(action)
        }
    }

    suspend fun writeActionSuspend(
        project: Project,
        commandName: String,
        action: () -> Unit
    ) {
        withContext(Dispatchers.Main) {
            WriteCommandAction.runWriteCommandAction(
                project,
                commandName,
                null,
                { action() }
            )
        }
    }

    fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }

    suspend fun <T> runWhenSmart(
        project: Project,
        action: () -> T
    ): T {
        return suspendCancellableCoroutine { continuation ->
            DumbService.getInstance(project).runWhenSmart {
                try {
                    val result = ReadAction.compute<T, Throwable>(action)
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }
}
```

---

## 11. Error Handling

### 11.1 Exception Hierarchy

```kotlin
sealed class McpException(
    message: String,
    val errorCode: Int
) : Exception(message)

class ParseErrorException(message: String) :
    McpException(message, JsonRpcErrorCodes.PARSE_ERROR)

class InvalidRequestException(message: String) :
    McpException(message, JsonRpcErrorCodes.INVALID_REQUEST)

class MethodNotFoundException(method: String) :
    McpException("Method not found: $method", JsonRpcErrorCodes.METHOD_NOT_FOUND)

class InvalidParamsException(message: String) :
    McpException(message, JsonRpcErrorCodes.INVALID_PARAMS)

class InternalErrorException(message: String) :
    McpException(message, JsonRpcErrorCodes.INTERNAL_ERROR)

// Tool-specific exceptions
class IndexNotReadyException(message: String) :
    McpException(message, -32001)

class FileNotFoundException(path: String) :
    McpException("File not found: $path", -32002)

class SymbolNotFoundException(message: String) :
    McpException(message, -32003)

class RefactoringConflictException(message: String) :
    McpException(message, -32004)
```

### 11.2 Error Response Builder

```kotlin
object ErrorResponseBuilder {

    fun fromException(id: JsonElement?, e: Exception): JsonRpcResponse {
        return when (e) {
            is McpException -> JsonRpcResponse(
                id = id,
                error = JsonRpcError(
                    code = e.errorCode,
                    message = e.message ?: "Unknown error"
                )
            )
            else -> JsonRpcResponse(
                id = id,
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.INTERNAL_ERROR,
                    message = e.message ?: "Internal error"
                )
            )
        }
    }
}
```

---

## 12. Testing Strategy

### 12.1 Test Categories

| Category | Framework | Coverage |
|----------|-----------|----------|
| Unit Tests | JUnit 5 + MockK | Tools, Services, Utilities |
| Integration Tests | IntelliJ Test Framework | PSI operations, Full flows |
| UI Tests | IntelliJ UI Test Robot | Tool window, Settings |

### 12.2 Test Structure

```
src/test/kotlin/
├── server/
│   ├── McpServerServiceTest.kt
│   ├── HttpTransportTest.kt
│   ├── JsonRpcHandlerTest.kt
│   └── PortManagerTest.kt
│
├── tools/
│   ├── navigation/
│   │   ├── FindUsagesToolTest.kt
│   │   └── FindDefinitionToolTest.kt
│   └── refactoring/
│       └── RenameSymbolToolTest.kt
│
├── history/
│   └── CommandHistoryServiceTest.kt
│
└── integration/
    ├── McpServerIntegrationTest.kt
    └── ToolExecutionIntegrationTest.kt
```

### 12.3 Example Test

```kotlin
class FindUsagesToolTest : BasePlatformTestCase() {

    private lateinit var tool: FindUsagesTool

    override fun setUp() {
        super.setUp()
        tool = FindUsagesTool()
    }

    fun `test find usages returns correct locations`() {
        // Given
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                private void foo() {}
                private void bar() {
                    foo();
                    foo();
                }
            }
            """.trimIndent()
        )

        // When
        val result = runBlocking {
            tool.execute(
                project,
                buildJsonObject {
                    put("file", "Test.java")
                    put("line", 2)
                    put("column", 18)
                }
            )
        }

        // Then
        assertFalse(result.isError)
        val text = (result.content.first() as ContentBlock.Text).text
        val usages = Json.decodeFromString<FindUsagesResult>(text)
        assertEquals(2, usages.totalCount)
    }
}
```

---

## Appendix A: Dependencies

### build.gradle.kts additions

```kotlin
dependencies {
    // MCP Kotlin SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.3.0")

    // Ktor for HTTP
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

---

## Appendix B: Document History

| Version | Date | Description |
|---------|------|-------------|
| 1.0 | 2025-01-25 | Initial design document |
| 1.1 | 2025-01-26 | Updated HTTP+SSE transport implementation, added @EncodeDefault annotation for JSON-RPC serialization |

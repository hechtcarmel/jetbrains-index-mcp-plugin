# IntelliJ Index MCP Plugin - Task Breakdown

**Document Version**: 1.1
**Based on**: design.md v1.1, requirements.md v1.1

---

## Progress Legend

| Symbol | Meaning |
|--------|---------|
| `[ ]` | Not started |
| `[~]` | In progress |
| `[x]` | Completed |
| `[!]` | Blocked |

---

## Phase 1: Foundation (MVP)

**Goal**: Establish core infrastructure with working HTTP+SSE transport and basic navigation tools.

### 1.1 Project Setup & Dependencies

- [x] **TASK-001**: Update `gradle/libs.versions.toml` with new dependencies
  - Add MCP Kotlin SDK version
  - Add Ktor versions (server-core, server-netty, content-negotiation, serialization)
  - Add kotlinx-serialization-json version
  - Add kotlinx-coroutines-core version
  - Add MockK version for testing

- [x] **TASK-002**: Update `build.gradle.kts` with dependencies
  - Add MCP SDK implementation dependency
  - Add Ktor server dependencies
  - Add serialization dependencies
  - Add coroutines dependencies
  - Add test dependencies

- [x] **TASK-003**: Update `plugin.xml` with basic configuration
  - Update plugin ID and name
  - Add `com.intellij.modules.platform` dependency
  - Add optional Java and Kotlin dependencies
  - Register project services
  - Register notification group

- [x] **TASK-004**: Create resource bundle `McpBundle.kt` and `McpBundle.properties`
  - Define message keys for UI strings
  - Add error messages
  - Add notification messages

### 1.2 Data Models

- [x] **TASK-005**: Create JSON-RPC data models in `server/models/`
  - `JsonRpcRequest.kt` - Request data class with serialization
  - `JsonRpcResponse.kt` - Response data class with serialization
  - `JsonRpcError.kt` - Error data class
  - `JsonRpcErrorCodes.kt` - Error code constants

- [x] **TASK-006**: Create MCP protocol models in `server/models/`
  - `ToolDefinition.kt` - Tool metadata for tools/list
  - `ToolCallResult.kt` - Tool execution result
  - `ContentBlock.kt` - Sealed class for text/image content
  - `ResourceDefinition.kt` - Resource metadata
  - `ResourceContent.kt` - Resource read result

- [x] **TASK-007**: Create command history models in `history/`
  - `CommandEntry.kt` - History entry data class
  - `CommandStatus.kt` - Status enum (PENDING, SUCCESS, ERROR)
  - `CommandFilter.kt` - Filter criteria data class

- [x] **TASK-008**: Create tool input/output models in `tools/models/`
  - `PositionInput.kt` - Common file/line/column input
  - `UsageLocation.kt` - Usage search result
  - `FindUsagesResult.kt` - find_usages output
  - `DefinitionResult.kt` - go_to_definition output
  - `SymbolInfoResult.kt` - get_symbol_info output

### 1.3 McpRequestHandler (HTTP+SSE Transport)

- [x] **TASK-009**: Implement `McpRequestHandler.kt`
  - Extend `HttpRequestHandler` from IntelliJ Platform
  - Implement `isSupported()` to match `/index-mcp` and `/index-mcp/sse` paths
  - Implement `process()` to handle GET (SSE) and POST (JSON-RPC) requests
  - Inject `JsonRpcHandler` for request processing

- [x] **TASK-010**: Implement HTTP+SSE transport
  - Handle GET `/index-mcp/sse` - open SSE stream, send `endpoint` event
  - Handle POST `/index-mcp` - parse body, call JsonRpcHandler, return JSON response
  - Set correct content-type headers (`text/event-stream` for SSE, `application/json` for POST)
  - Handle errors gracefully with proper HTTP status codes

- [x] **TASK-011**: Write unit tests for McpRequestHandler
  - Test GET SSE stream establishment
  - Test SSE endpoint event format
  - Test POST JSON-RPC request handling
  - Test error responses
  - Test unsupported paths return false

### 1.4 JSON-RPC Handler

- [x] **TASK-012**: Implement `JsonRpcHandler.kt`
  - Implement `handleRequest()` - main entry point
  - Implement request parsing with error handling
  - Implement method routing
  - **ADDED**: Multi-project resolution with `project_path` parameter

- [x] **TASK-013**: Implement MCP method handlers
  - Implement `processInitialize()` - return server info
  - Implement `processToolsList()` - return tool definitions
  - Implement `processToolCall()` - dispatch to tool
  - Implement `processResourcesList()` - return resource definitions
  - Implement `processResourceRead()` - read resource

- [x] **TASK-014**: Implement error response helpers
  - `createParseErrorResponse()`
  - `createInvalidRequestResponse()`
  - `createMethodNotFoundResponse()`
  - `createInvalidParamsResponse()`
  - `createInternalErrorResponse()`

- [x] **TASK-015**: Write unit tests for JsonRpcHandler
  - Test initialize response
  - Test tools/list response
  - Test tools/call routing
  - Test error responses
  - **ADDED**: Multi-project resolution tests

### 1.5 Tool Infrastructure

- [x] **TASK-016**: Create `McpTool.kt` interface
  - Define `name` property
  - Define `description` property
  - Define `inputSchema` property (JsonObject)
  - Define `execute()` suspend function

- [x] **TASK-017**: Create `AbstractMcpTool.kt` base class
  - Implement `requireSmartMode()` helper
  - Implement `readAction()` wrapper
  - Implement `writeAction()` wrapper
  - Implement `resolveFile()` helper
  - Implement `findPsiElement()` helper
  - Implement `createSuccessResult()` helper
  - Implement `createErrorResult()` helper
  - Implement `createJsonResult()` helper

- [x] **TASK-018**: Implement `ToolRegistry.kt`
  - Implement `register()` method
  - Implement `unregister()` method
  - Implement `getTool()` method
  - Implement `getAllTools()` method
  - Implement `getToolDefinitions()` method
  - Implement `registerBuiltInTools()` method

- [x] **TASK-019**: Write unit tests for tool infrastructure
  - Test tool registration
  - Test tool lookup
  - Test tool definition generation

### 1.6 Navigation Tools (Phase 1)

- [x] **TASK-020**: Implement `FindUsagesTool.kt`
  - Define input schema (file, line, column, project_path)
  - Implement position resolution
  - Implement `ReferencesSearch` usage
  - Map results to `UsageLocation` list
  - Classify usage types (METHOD_CALL, REFERENCE, etc.)

- [x] **TASK-021**: Write integration tests for FindUsagesTool
  - Test Java method usage finding
  - Test Kotlin function usage finding
  - Test field usage finding
  - Test class usage finding
  - Test no usages case

- [x] **TASK-022**: Implement `GoToDefinitionTool.kt`
  - Define input schema (file, line, column, project_path)
  - Implement reference resolution
  - Navigate to declaration element
  - Extract file, line, column from target
  - Generate code preview

- [x] **TASK-023**: Write integration tests for GoToDefinitionTool
  - Test method definition navigation
  - Test class definition navigation
  - Test field definition navigation
  - Test external library navigation

- [x] **TASK-024**: Implement `GetSymbolInfoTool.kt`
  - Define input schema (file, line, column, project_path)
  - Resolve element at position
  - Extract symbol name
  - Extract symbol type
  - Extract documentation (from JavaDoc/KDoc)

- [x] **TASK-025**: Write integration tests for GetSymbolInfoTool
  - Test method info extraction
  - Test class info extraction
  - Test field info extraction
  - Test documentation extraction

### 1.7 MCP Server Service

- [x] **TASK-026**: Implement `McpServerService.kt`
  - Create as application-level service
  - Initialize ToolRegistry with built-in tools
  - Initialize ResourceRegistry
  - Initialize JsonRpcHandler
  - Implement `getServerUrl(project)` method using `BuiltInServerManager`
  - Implement `Disposable` for cleanup

- [x] **TASK-027**: Write unit tests for McpServerService
  - Test service creation
  - Test tool registry initialization
  - Test server URL generation

### 1.8 Command History Service (Basic)

- [x] **TASK-028**: Implement `CommandHistoryService.kt`
  - Create as project-level service
  - Maintain history list (thread-safe)
  - Implement `recordCommand()` method
  - Implement `updateCommandStatus()` method
  - Implement `clearHistory()` method
  - Implement history size limiting

- [x] **TASK-029**: Implement listener pattern
  - Define `CommandHistoryListener` interface
  - Implement `addListener()` method
  - Implement `removeListener()` method
  - Implement `notifyListeners()` private method

- [x] **TASK-030**: Write unit tests for CommandHistoryService
  - Test command recording
  - Test status updates
  - Test history limiting
  - Test listener notifications

### 1.9 Basic GUI

- [x] **TASK-031**: Create `McpToolWindowFactory.kt`
  - Implement `ToolWindowFactory` interface
  - Create tool window content
  - Configure toolbar actions

- [x] **TASK-032**: Create `McpToolWindowPanel.kt`
  - Create main panel layout
  - Add server status section
  - Add placeholder for command history

- [x] **TASK-033**: Create `ServerStatusPanel.kt`
  - Display running/stopped status with icon
  - Display server URL when running
  - Display project name
  - Add "Copy URL" button functionality

- [x] **TASK-034**: Register tool window in plugin.xml
  - Configure anchor (bottom)
  - Configure icon
  - Configure factory class

### 1.10 Startup Activity

- [x] **TASK-035**: Implement `McpServerStartupActivity.kt`
  - Implement `ProjectActivity` interface
  - Auto-start server on project open
  - Handle startup errors gracefully
  - Show notification on successful start

### 1.11 Utility Classes

- [x] **TASK-036**: Implement `PsiUtils.kt`
  - `findElementAtPosition()` helper
  - `getContainingClass()` helper
  - `getContainingMethod()` helper
  - `extractDocumentation()` helper

- [x] **TASK-037**: Implement `ProjectUtils.kt`
  - `getRelativePath()` helper
  - `resolveProjectFile()` helper
  - `getProjectBasePath()` helper

- [x] **TASK-038**: Implement `ThreadingUtils.kt`
  - `readActionSuspend()` helper
  - `writeActionSuspend()` helper
  - `runOnEdt()` helper
  - `runWhenSmart()` helper

### 1.12 Exception Handling

- [x] **TASK-039**: Create exception hierarchy in `exceptions/`
  - `McpException` sealed base class
  - `ParseErrorException`
  - `InvalidRequestException`
  - `MethodNotFoundException`
  - `InvalidParamsException`
  - `InternalErrorException`
  - `IndexNotReadyException`
  - `FileNotFoundException`
  - `SymbolNotFoundException`

- [x] **TASK-040**: Implement `ErrorResponseBuilder.kt`
  - Map exceptions to JSON-RPC error responses
  - Include error codes and messages

---

## Phase 2: Navigation & Intelligence

**Goal**: Complete navigation tools, add code intelligence, implement full command history UI.

### 2.1 Additional Navigation Tools

- [ ] **TASK-041**: Implement `TypeHierarchyTool.kt`
  - Define input schema (file, line, column, optional className)
  - Resolve class element
  - Use `HierarchyProvider` to get type hierarchy
  - Extract supertypes and subtypes
  - Return `TypeHierarchyResult`

- [ ] **TASK-042**: Write integration tests for TypeHierarchyTool
  - Test class hierarchy extraction
  - Test interface hierarchy
  - Test with multiple inheritance levels

- [ ] **TASK-043**: Implement `CallHierarchyTool.kt`
  - Define input schema (file, line, column, direction)
  - Resolve method element
  - Use `CallerMethodsTreeStructure` for callers
  - Use `CalleeMethodsTreeStructure` for callees
  - Return tree structure as `CallHierarchyResult`

- [ ] **TASK-044**: Write integration tests for CallHierarchyTool
  - Test caller hierarchy
  - Test callee hierarchy
  - Test with recursive calls

- [ ] **TASK-045**: Implement `FindImplementationsTool.kt`
  - Define input schema (file, line, column)
  - Resolve interface/abstract class/method
  - Use `DefinitionsScopedSearch` or `OverridingMethodsSearch`
  - Return list of implementations

- [ ] **TASK-046**: Write integration tests for FindImplementationsTool
  - Test interface implementations
  - Test abstract method implementations
  - Test overriding methods

### 2.2 Code Intelligence Tools

- [ ] **TASK-047**: Implement `GetCompletionsTool.kt`
  - Define input schema (file, line, column)
  - Use `CompletionService` API
  - Extract completion items
  - Return list of suggestions with type info

- [ ] **TASK-048**: Write integration tests for GetCompletionsTool
  - Test method completion
  - Test variable completion
  - Test import completion

- [ ] **TASK-049**: Implement `GetInspectionsTool.kt`
  - Define input schema (file, optional startLine, endLine)
  - Use `InspectionManager` to run inspections
  - Extract problems with severity
  - Return list of problems

- [ ] **TASK-050**: Write integration tests for GetInspectionsTool
  - Test error detection
  - Test warning detection
  - Test range-limited inspection

- [ ] **TASK-051**: Implement `GetQuickFixesTool.kt`
  - Define input schema (file, line, column)
  - Resolve problems at position
  - Extract available quick fixes
  - Generate unique fix IDs
  - Return list of applicable fixes

- [ ] **TASK-052**: Write integration tests for GetQuickFixesTool
  - Test quick fix discovery
  - Test fix ID generation

- [ ] **TASK-053**: Implement `ApplyQuickFixTool.kt`
  - Define input schema (file, line, column, fixId)
  - Locate the quick fix by ID
  - Apply fix using `WriteCommandAction`
  - Return modified code or success message

- [ ] **TASK-054**: Write integration tests for ApplyQuickFixTool
  - Test fix application
  - Test invalid fix ID handling

### 2.3 Project Structure Tools

- [ ] **TASK-055**: Implement `GetFileStructureTool.kt`
  - Define input schema (file)
  - Parse PSI tree of file
  - Extract classes, methods, fields
  - Return hierarchical structure

- [ ] **TASK-056**: Write integration tests for GetFileStructureTool
  - Test Java file structure
  - Test Kotlin file structure
  - Test nested class extraction

- [ ] **TASK-057**: Implement `GetProjectStructureTool.kt`
  - Use `ModuleManager` to get modules
  - Extract source roots for each module
  - Return project structure tree

- [ ] **TASK-058**: Implement `GetDependenciesTool.kt`
  - Use `OrderEnumerator` to get libraries
  - Extract library names and versions
  - Return dependency list

- [x] **TASK-059**: Implement `GetIndexStatusTool.kt`
  - Check `DumbService.isDumb()`
  - Return dumb/smart mode status

### 2.4 Resource Providers

- [x] **TASK-060**: Create `McpResource.kt` interface
  - Define `uri`, `name`, `description`, `mimeType` properties
  - Define `read()` suspend function

- [x] **TASK-061**: Implement `ResourceRegistry.kt`
  - Implement `register()` method
  - Implement `getResource()` method
  - Implement `getAllResources()` method
  - Implement `getResourceDefinitions()` method

- [x] **TASK-062**: Implement `ProjectStructureResource.kt`
  - URI: `project://structure`
  - Return project module tree as JSON

- [ ] **TASK-063**: Implement `FileContentResource.kt`
  - URI pattern: `file://content/{path}`
  - Read file content
  - Return with metadata

- [ ] **TASK-064**: Implement `SymbolInfoResource.kt`
  - URI pattern: `symbol://info/{fqn}`
  - Resolve symbol by fully qualified name
  - Return symbol information

- [x] **TASK-065**: Implement `IndexStatusResource.kt`
  - URI: `index://status`
  - Return indexing status

- [x] **TASK-066**: Write unit tests for resource providers
  - Test each resource read operation

### 2.5 Full Command History UI

- [ ] **TASK-067**: Implement `CommandHistoryPanel.kt`
  - Create JBList with custom model
  - Implement `CommandHistoryListener`
  - Handle selection events
  - Implement auto-scroll behavior

- [ ] **TASK-068**: Implement `CommandListCellRenderer.kt`
  - Display timestamp
  - Display tool name (bold)
  - Display status with color coding
  - Support expand/collapse indicator

- [ ] **TASK-069**: Implement `CommandDetailPanel.kt`
  - Create split panel layout
  - Add JSON viewer for request
  - Add JSON viewer for response
  - Display execution duration
  - Display affected files (if any)

- [ ] **TASK-070**: Implement `FilterToolbar.kt`
  - Add tool name filter (ComboBox)
  - Add status filter (ComboBox)
  - Add search field
  - Wire filters to CommandHistoryService

- [x] **TASK-071**: Update `CommandHistoryService.kt` with filtering
  - Implement `getFilteredHistory()` method
  - Support tool name filter
  - Support status filter
  - Support text search

- [x] **TASK-072**: Implement export functionality
  - Implement `exportToJson()` method
  - Implement `exportToCsv()` method

- [x] **TASK-073**: Create `ExportHistoryAction.kt`
  - Show file chooser dialog
  - Support JSON and CSV formats
  - Write export file

- [x] **TASK-074**: Create `ClearHistoryAction.kt`
  - Show confirmation dialog
  - Clear history on confirm

- [ ] **TASK-075**: Update McpToolWindowPanel with full UI
  - Integrate ServerStatusPanel
  - Integrate FilterToolbar
  - Integrate CommandHistoryPanel
  - Integrate CommandDetailPanel with splitter

### 2.6 Integration Tests

- [ ] **TASK-076**: Create `McpServerIntegrationTest.kt`
  - Test full server startup
  - Test tools/list endpoint
  - Test tools/call endpoint
  - Test resources/list endpoint

- [ ] **TASK-077**: Create `ToolExecutionIntegrationTest.kt`
  - Test each navigation tool end-to-end
  - Test each intelligence tool end-to-end
  - Test error scenarios

---

## Phase 3: Refactoring Operations

**Goal**: Implement all refactoring tools with proper undo support and user confirmation.

### 3.1 Refactoring Infrastructure

- [ ] **TASK-078**: Create `RefactoringResult.kt` model
  - success flag
  - affected files list
  - changes count
  - message

- [ ] **TASK-079**: Create `AbstractRefactoringTool.kt` base class
  - Extend `AbstractMcpTool`
  - Add confirmation check logic
  - Add undo group management
  - Add affected files tracking

### 3.2 Rename Tool

- [ ] **TASK-080**: Implement `RenameSymbolTool.kt`
  - Define input schema (file, line, column, newName)
  - Resolve element at position
  - Use `RenameProcessor` for rename
  - Track affected files
  - Return `RefactoringResult`

- [ ] **TASK-081**: Write integration tests for RenameSymbolTool
  - Test method rename
  - Test class rename
  - Test variable rename
  - Test rename with references in multiple files

### 3.3 Extract Method Tool

- [ ] **TASK-082**: Implement `ExtractMethodTool.kt`
  - Define input schema (file, startLine, endLine, methodName)
  - Select code range
  - Use `ExtractMethodProcessor`
  - Return new method location

- [ ] **TASK-083**: Write integration tests for ExtractMethodTool
  - Test simple extraction
  - Test extraction with parameters
  - Test extraction with return value

### 3.4 Extract Variable Tool

- [ ] **TASK-084**: Implement `ExtractVariableTool.kt`
  - Define input schema (file, line, column, variableName)
  - Resolve expression at position
  - Use `IntroduceVariableHandler`
  - Return variable declaration location

- [ ] **TASK-085**: Write integration tests for ExtractVariableTool
  - Test expression extraction
  - Test with multiple occurrences

### 3.5 Inline Tool

- [ ] **TASK-086**: Implement `InlineTool.kt`
  - Define input schema (file, line, column)
  - Resolve variable/method at position
  - Use `InlineHandler`
  - Return success/failure

- [ ] **TASK-087**: Write integration tests for InlineTool
  - Test variable inline
  - Test method inline

### 3.6 Safe Delete Tool

- [ ] **TASK-088**: Implement `SafeDeleteTool.kt`
  - Define input schema (file, line, column)
  - Resolve element at position
  - Check for usages using `SafeDeleteProcessor`
  - If usages exist, return blocking usages
  - If no usages, perform delete
  - Return result

- [ ] **TASK-089**: Write integration tests for SafeDeleteTool
  - Test deletion with no usages
  - Test deletion blocked by usages

### 3.7 Move Element Tool

- [ ] **TASK-090**: Implement `MoveElementTool.kt`
  - Define input schema (file, line, column, targetFile)
  - Resolve element at position
  - Use `MoveHandler`
  - Return new location

- [ ] **TASK-091**: Write integration tests for MoveElementTool
  - Test class move
  - Test method move

### 3.8 User Confirmation Dialog

- [ ] **TASK-092**: Implement `RefactoringConfirmationDialog.kt`
  - Show affected files preview
  - Show changes summary
  - Confirm/Cancel buttons
  - "Don't ask again" checkbox

- [ ] **TASK-093**: Integrate confirmation with refactoring tools
  - Check settings for confirmation required
  - Show dialog before executing
  - Proceed or abort based on user choice

### 3.9 Undo/Redo Integration

- [ ] **TASK-094**: Implement undo group management
  - Use `CommandProcessor` for command grouping
  - Set descriptive command names
  - Ensure atomic undo for multi-file changes

- [ ] **TASK-095**: Write tests for undo functionality
  - Test undo after rename
  - Test undo after extract method

### 3.10 Refactoring Preview

- [ ] **TASK-096**: Implement `RefactoringPreviewPanel.kt`
  - Show diff for each affected file
  - Highlight changes
  - Allow accept/reject

- [ ] **TASK-097**: Integrate preview with refactoring tools
  - Generate preview data
  - Show preview dialog
  - Apply or cancel based on user choice

---

## Phase 4: Polish & Extensions

**Goal**: Performance optimization, extension point, documentation, and marketplace preparation.

### 4.1 Performance Optimization

- [ ] **TASK-098**: Profile tool execution times
  - Add timing instrumentation
  - Identify slow operations
  - Document baseline performance

- [ ] **TASK-099**: Optimize PSI operations
  - Use stub indexes where possible
  - Cache frequently accessed data
  - Minimize read action scope

- [ ] **TASK-100**: Optimize JSON serialization
  - Use streaming where appropriate
  - Reduce object allocations
  - Profile and optimize hot paths

- [ ] **TASK-101**: Add request throttling
  - Implement request queue
  - Add configurable rate limiting
  - Handle burst requests gracefully

### 4.2 Extension Point

- [ ] **TASK-102**: Define `mcpTool` extension point in plugin.xml
  - Configure interface
  - Set dynamic=true

- [ ] **TASK-103**: Update ToolRegistry to load extensions
  - Implement `registerExtensionTools()`
  - Handle extension point listener for dynamic registration

- [ ] **TASK-104**: Create extension point documentation
  - Document interface requirements
  - Provide example implementation
  - Document registration process

- [ ] **TASK-105**: Write tests for extension point
  - Test dynamic tool registration
  - Test tool execution from extension

### 4.3 Client Configuration Generator

- [ ] **TASK-106**: Create `ClientConfigGenerator.kt`
  - Generate Claude Desktop config
  - Generate Cursor config
  - Generate VS Code config (generic MCP)

- [ ] **TASK-107**: Add "Copy Config" action to tool window
  - Show config type selector
  - Generate and copy to clipboard
  - Show success notification

### 4.4 Documentation

- [ ] **TASK-108**: Update README.md
  - Add feature overview
  - Add installation instructions
  - Add usage guide
  - Add configuration guide

- [ ] **TASK-109**: Create USAGE.md
  - Document each tool with examples
  - Document each resource
  - Document error codes

- [ ] **TASK-110**: Create EXTENSION.md
  - Document extension point
  - Provide example plugin
  - Document best practices

- [ ] **TASK-111**: Add inline documentation
  - KDoc for public APIs
  - Usage examples in KDoc

### 4.5 Marketplace Preparation

- [ ] **TASK-112**: Create plugin icon (pluginIcon.svg)
  - Design 40x40 icon
  - Follow JetBrains guidelines

- [ ] **TASK-113**: Write plugin description for marketplace
  - Feature highlights
  - Screenshots
  - Compatibility info

- [ ] **TASK-114**: Update CHANGELOG.md
  - Document all features
  - Version history

- [ ] **TASK-115**: Configure plugin signing
  - Set up certificate
  - Configure signing in build.gradle.kts

- [ ] **TASK-116**: Run plugin verifier
  - Fix any compatibility issues
  - Ensure API compatibility

- [ ] **TASK-117**: Prepare release workflow
  - Configure GitHub Actions for release
  - Set up marketplace publishing

### 4.6 Final Testing

- [ ] **TASK-118**: End-to-end testing with Claude Desktop
  - Test all tools manually
  - Verify error handling
  - Test multi-instance support

- [ ] **TASK-119**: End-to-end testing with Cursor
  - Test tool discovery
  - Test tool execution
  - Verify compatibility

- [ ] **TASK-120**: Performance testing
  - Test with large projects
  - Test concurrent requests
  - Measure response times

- [ ] **TASK-121**: Stress testing
  - Rapid request sequences
  - Memory usage monitoring
  - Connection handling

---

## Summary

### Task Count by Phase

| Phase | Task Count | Completed | Status |
|-------|------------|-----------|--------|
| Phase 1: Foundation | 40 tasks | 40 | **Complete** |
| Phase 2: Navigation & Intelligence | 37 tasks | 10 | In Progress |
| Phase 3: Refactoring Operations | 20 tasks | 0 | Not Started |
| Phase 4: Polish & Extensions | 24 tasks | 0 | Not Started |
| **Total** | **121 tasks** | **50** | |

### Critical Path

The following tasks are on the critical path and block other work:

1. TASK-001 → TASK-002 (Dependencies must be added first)
2. TASK-005 → TASK-012 (Data models needed for JSON-RPC handler)
3. TASK-009 → TASK-012 (McpRequestHandler needed for JSON-RPC integration)
4. TASK-016 → TASK-020 (Tool interface needed for tools)
5. TASK-026 → TASK-035 (Server service needed for startup activity)

### Milestone Definitions

| Milestone | Completion Criteria |
|-----------|---------------------|
| M1: Infrastructure | TASK-001 through TASK-019 complete |
| M2: MVP Navigation | TASK-020 through TASK-030 complete |
| M3: MVP GUI | TASK-031 through TASK-040 complete |
| M4: Full Navigation | TASK-041 through TASK-066 complete |
| M5: Full GUI | TASK-067 through TASK-077 complete |
| M6: Refactoring | TASK-078 through TASK-097 complete |
| M7: Release Ready | All tasks complete |

---

## Appendix: Task Dependencies

```
TASK-001 (gradle deps)
    └── TASK-002 (build.gradle)
            └── TASK-003 (plugin.xml)

TASK-005 (JSON-RPC models)
    └── TASK-012 (JsonRpcHandler)
            └── TASK-013 (MCP methods)

TASK-009 (McpRequestHandler)
    └── TASK-012 (JsonRpcHandler)
            └── TASK-026 (McpServerService)
                    └── TASK-035 (StartupActivity)

TASK-016 (McpTool interface)
    └── TASK-017 (AbstractMcpTool)
            └── TASK-020, TASK-022, TASK-024 (Navigation tools)

TASK-018 (ToolRegistry)
    └── TASK-026 (McpServerService)

TASK-028 (CommandHistoryService)
    └── TASK-067 (CommandHistoryPanel)
            └── TASK-075 (Full GUI)
```

---

**Document Version**: 1.1
**Updated**: 2025-01-26

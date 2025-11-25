# IntelliJ Index MCP Plugin - Task Breakdown

**Document Version**: 1.0
**Based on**: design.md v1.0, requirements.md v1.0

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

**Goal**: Establish core infrastructure with working HTTP transport and basic navigation tools.

### 1.1 Project Setup & Dependencies

- [ ] **TASK-001**: Update `gradle/libs.versions.toml` with new dependencies
  - Add MCP Kotlin SDK version
  - Add Ktor versions (server-core, server-netty, content-negotiation, serialization)
  - Add kotlinx-serialization-json version
  - Add kotlinx-coroutines-core version
  - Add MockK version for testing

- [ ] **TASK-002**: Update `build.gradle.kts` with dependencies
  - Add MCP SDK implementation dependency
  - Add Ktor server dependencies
  - Add serialization dependencies
  - Add coroutines dependencies
  - Add test dependencies

- [ ] **TASK-003**: Update `plugin.xml` with basic configuration
  - Update plugin ID and name
  - Add `com.intellij.modules.platform` dependency
  - Add optional Java and Kotlin dependencies
  - Register project services
  - Register notification group

- [ ] **TASK-004**: Create resource bundle `McpBundle.kt` and `McpBundle.properties`
  - Define message keys for UI strings
  - Add error messages
  - Add notification messages

### 1.2 Data Models

- [ ] **TASK-005**: Create JSON-RPC data models in `server/models/`
  - `JsonRpcRequest.kt` - Request data class with serialization
  - `JsonRpcResponse.kt` - Response data class with serialization
  - `JsonRpcError.kt` - Error data class
  - `JsonRpcErrorCodes.kt` - Error code constants

- [ ] **TASK-006**: Create MCP protocol models in `server/models/`
  - `ToolDefinition.kt` - Tool metadata for tools/list
  - `ToolCallResult.kt` - Tool execution result
  - `ContentBlock.kt` - Sealed class for text/image content
  - `ResourceDefinition.kt` - Resource metadata
  - `ResourceContent.kt` - Resource read result

- [ ] **TASK-007**: Create port registry models in `server/`
  - `PortRegistry.kt` - Registry container
  - `PortRegistryEntry.kt` - Individual entry

- [ ] **TASK-008**: Create command history models in `history/`
  - `CommandEntry.kt` - History entry data class
  - `CommandStatus.kt` - Status enum (PENDING, SUCCESS, ERROR)
  - `CommandFilter.kt` - Filter criteria data class

- [ ] **TASK-009**: Create tool input/output models in `tools/models/`
  - `PositionInput.kt` - Common file/line/column input
  - `UsageLocation.kt` - Usage search result
  - `FindUsagesResult.kt` - find_usages output
  - `DefinitionResult.kt` - go_to_definition output
  - `SymbolInfoResult.kt` - get_symbol_info output

### 1.3 Port Manager

- [ ] **TASK-010**: Implement `PortManager.kt`
  - Define port range constants (8080-8180)
  - Define registry file path (`~/.mcp-jetbrains/ports.json`)
  - Implement `allocatePort()` method
  - Implement `releasePort()` method
  - Implement `isPortAvailable()` method using socket check
  - Implement `getRegisteredInstances()` method

- [ ] **TASK-011**: Implement port registry file operations
  - Implement `readRegistry()` - read and parse JSON file
  - Implement `writeRegistry()` - serialize and write JSON file
  - Implement `cleanStaleEntries()` - remove entries for dead processes
  - Handle file creation if not exists
  - Handle concurrent access with file locking

- [ ] **TASK-012**: Write unit tests for PortManager
  - Test port allocation success
  - Test port allocation when all ports busy
  - Test port release
  - Test stale entry cleanup
  - Test concurrent allocation

### 1.4 HTTP Transport

- [ ] **TASK-013**: Implement `HttpTransport.kt` with Ktor
  - Create Ktor application configuration
  - Configure Netty engine with allocated port
  - Implement `start()` suspend function
  - Implement `stop()` suspend function
  - Track running state and server URL

- [ ] **TASK-014**: Implement HTTP routing
  - Configure POST `/mcp` endpoint for JSON-RPC requests
  - Configure GET `/mcp` endpoint for server info/health
  - Add content-type handling (application/json)
  - Add error handling middleware

- [ ] **TASK-015**: Write unit tests for HttpTransport
  - Test server start/stop
  - Test request handling
  - Test error responses

### 1.5 JSON-RPC Handler

- [ ] **TASK-016**: Implement `JsonRpcHandler.kt`
  - Implement `handleRequest()` - main entry point
  - Implement request parsing with error handling
  - Implement method routing

- [ ] **TASK-017**: Implement MCP method handlers
  - Implement `processInitialize()` - return server info
  - Implement `processToolsList()` - return tool definitions
  - Implement `processToolCall()` - dispatch to tool
  - Implement `processResourcesList()` - return resource definitions
  - Implement `processResourceRead()` - read resource

- [ ] **TASK-018**: Implement error response helpers
  - `createParseErrorResponse()`
  - `createInvalidRequestResponse()`
  - `createMethodNotFoundResponse()`
  - `createInvalidParamsResponse()`
  - `createInternalErrorResponse()`

- [ ] **TASK-019**: Write unit tests for JsonRpcHandler
  - Test initialize response
  - Test tools/list response
  - Test tools/call routing
  - Test error responses

### 1.6 Tool Infrastructure

- [ ] **TASK-020**: Create `McpTool.kt` interface
  - Define `name` property
  - Define `description` property
  - Define `inputSchema` property (JsonObject)
  - Define `execute()` suspend function

- [ ] **TASK-021**: Create `AbstractMcpTool.kt` base class
  - Implement `requireSmartMode()` helper
  - Implement `readAction()` wrapper
  - Implement `writeAction()` wrapper
  - Implement `resolveFile()` helper
  - Implement `findPsiElement()` helper
  - Implement `createSuccessResult()` helper
  - Implement `createErrorResult()` helper
  - Implement `createJsonResult()` helper

- [ ] **TASK-022**: Implement `ToolRegistry.kt`
  - Implement `register()` method
  - Implement `unregister()` method
  - Implement `getTool()` method
  - Implement `getAllTools()` method
  - Implement `getToolDefinitions()` method
  - Implement `registerBuiltInTools()` method

- [ ] **TASK-023**: Write unit tests for tool infrastructure
  - Test tool registration
  - Test tool lookup
  - Test tool definition generation

### 1.7 Navigation Tools (Phase 1)

- [ ] **TASK-024**: Implement `FindUsagesTool.kt`
  - Define input schema (file, line, column)
  - Implement position resolution
  - Implement `ReferencesSearch` usage
  - Map results to `UsageLocation` list
  - Classify usage types (METHOD_CALL, REFERENCE, etc.)

- [ ] **TASK-025**: Write integration tests for FindUsagesTool
  - Test Java method usage finding
  - Test Kotlin function usage finding
  - Test field usage finding
  - Test class usage finding
  - Test no usages case

- [ ] **TASK-026**: Implement `GoToDefinitionTool.kt`
  - Define input schema (file, line, column)
  - Implement reference resolution
  - Navigate to declaration element
  - Extract file, line, column from target
  - Generate code preview

- [ ] **TASK-027**: Write integration tests for GoToDefinitionTool
  - Test method definition navigation
  - Test class definition navigation
  - Test field definition navigation
  - Test external library navigation

- [ ] **TASK-028**: Implement `GetSymbolInfoTool.kt`
  - Define input schema (file, line, column)
  - Resolve element at position
  - Extract symbol name
  - Extract symbol type
  - Extract documentation (from JavaDoc/KDoc)

- [ ] **TASK-029**: Write integration tests for GetSymbolInfoTool
  - Test method info extraction
  - Test class info extraction
  - Test field info extraction
  - Test documentation extraction

### 1.8 MCP Server Service

- [ ] **TASK-030**: Implement `McpServerService.kt`
  - Create as project-level service
  - Initialize ToolRegistry and JsonRpcHandler
  - Initialize PortManager and HttpTransport
  - Implement `start()` method
  - Implement `stop()` method
  - Implement `restart()` method
  - Implement `Disposable` for cleanup

- [ ] **TASK-031**: Implement server lifecycle management
  - Register port on start
  - Release port on stop
  - Handle startup errors with notifications
  - Handle port conflicts

- [ ] **TASK-032**: Write unit tests for McpServerService
  - Test service creation
  - Test start/stop lifecycle
  - Test restart behavior

### 1.9 Command History Service (Basic)

- [ ] **TASK-033**: Implement `CommandHistoryService.kt`
  - Create as project-level service
  - Maintain history list (thread-safe)
  - Implement `recordCommand()` method
  - Implement `updateCommandStatus()` method
  - Implement `clearHistory()` method
  - Implement history size limiting

- [ ] **TASK-034**: Implement listener pattern
  - Define `CommandHistoryListener` interface
  - Implement `addListener()` method
  - Implement `removeListener()` method
  - Implement `notifyListeners()` private method

- [ ] **TASK-035**: Write unit tests for CommandHistoryService
  - Test command recording
  - Test status updates
  - Test history limiting
  - Test listener notifications

### 1.10 Basic GUI

- [ ] **TASK-036**: Create `McpToolWindowFactory.kt`
  - Implement `ToolWindowFactory` interface
  - Create tool window content
  - Configure toolbar actions

- [ ] **TASK-037**: Create `McpToolWindowPanel.kt`
  - Create main panel layout
  - Add server status section
  - Add placeholder for command history

- [ ] **TASK-038**: Create `ServerStatusPanel.kt`
  - Display running/stopped status with icon
  - Display server URL when running
  - Display project name
  - Add "Copy URL" button functionality

- [ ] **TASK-039**: Register tool window in plugin.xml
  - Configure anchor (bottom)
  - Configure icon
  - Configure factory class

### 1.11 Startup Activity

- [ ] **TASK-040**: Implement `McpServerStartupActivity.kt`
  - Implement `ProjectActivity` interface
  - Auto-start server on project open
  - Handle startup errors gracefully
  - Show notification on successful start

### 1.12 Utility Classes

- [ ] **TASK-041**: Implement `PsiUtils.kt`
  - `findElementAtPosition()` helper
  - `getContainingClass()` helper
  - `getContainingMethod()` helper
  - `extractDocumentation()` helper

- [ ] **TASK-042**: Implement `ProjectUtils.kt`
  - `getRelativePath()` helper
  - `resolveProjectFile()` helper
  - `getProjectBasePath()` helper

- [ ] **TASK-043**: Implement `ThreadingUtils.kt`
  - `readActionSuspend()` helper
  - `writeActionSuspend()` helper
  - `runOnEdt()` helper
  - `runWhenSmart()` helper

### 1.13 Exception Handling

- [ ] **TASK-044**: Create exception hierarchy in `exceptions/`
  - `McpException` sealed base class
  - `ParseErrorException`
  - `InvalidRequestException`
  - `MethodNotFoundException`
  - `InvalidParamsException`
  - `InternalErrorException`
  - `IndexNotReadyException`
  - `FileNotFoundException`
  - `SymbolNotFoundException`

- [ ] **TASK-045**: Implement `ErrorResponseBuilder.kt`
  - Map exceptions to JSON-RPC error responses
  - Include error codes and messages

---

## Phase 2: Navigation & Intelligence

**Goal**: Complete navigation tools, add code intelligence, implement full command history UI.

### 2.1 Additional Navigation Tools

- [ ] **TASK-046**: Implement `TypeHierarchyTool.kt`
  - Define input schema (file, line, column, optional className)
  - Resolve class element
  - Use `HierarchyProvider` to get type hierarchy
  - Extract supertypes and subtypes
  - Return `TypeHierarchyResult`

- [ ] **TASK-047**: Write integration tests for TypeHierarchyTool
  - Test class hierarchy extraction
  - Test interface hierarchy
  - Test with multiple inheritance levels

- [ ] **TASK-048**: Implement `CallHierarchyTool.kt`
  - Define input schema (file, line, column, direction)
  - Resolve method element
  - Use `CallerMethodsTreeStructure` for callers
  - Use `CalleeMethodsTreeStructure` for callees
  - Return tree structure as `CallHierarchyResult`

- [ ] **TASK-049**: Write integration tests for CallHierarchyTool
  - Test caller hierarchy
  - Test callee hierarchy
  - Test with recursive calls

- [ ] **TASK-050**: Implement `FindImplementationsTool.kt`
  - Define input schema (file, line, column)
  - Resolve interface/abstract class/method
  - Use `DefinitionsScopedSearch` or `OverridingMethodsSearch`
  - Return list of implementations

- [ ] **TASK-051**: Write integration tests for FindImplementationsTool
  - Test interface implementations
  - Test abstract method implementations
  - Test overriding methods

### 2.2 Code Intelligence Tools

- [ ] **TASK-052**: Implement `GetCompletionsTool.kt`
  - Define input schema (file, line, column)
  - Use `CompletionService` API
  - Extract completion items
  - Return list of suggestions with type info

- [ ] **TASK-053**: Write integration tests for GetCompletionsTool
  - Test method completion
  - Test variable completion
  - Test import completion

- [ ] **TASK-054**: Implement `GetInspectionsTool.kt`
  - Define input schema (file, optional startLine, endLine)
  - Use `InspectionManager` to run inspections
  - Extract problems with severity
  - Return list of problems

- [ ] **TASK-055**: Write integration tests for GetInspectionsTool
  - Test error detection
  - Test warning detection
  - Test range-limited inspection

- [ ] **TASK-056**: Implement `GetQuickFixesTool.kt`
  - Define input schema (file, line, column)
  - Resolve problems at position
  - Extract available quick fixes
  - Generate unique fix IDs
  - Return list of applicable fixes

- [ ] **TASK-057**: Write integration tests for GetQuickFixesTool
  - Test quick fix discovery
  - Test fix ID generation

- [ ] **TASK-058**: Implement `ApplyQuickFixTool.kt`
  - Define input schema (file, line, column, fixId)
  - Locate the quick fix by ID
  - Apply fix using `WriteCommandAction`
  - Return modified code or success message

- [ ] **TASK-059**: Write integration tests for ApplyQuickFixTool
  - Test fix application
  - Test invalid fix ID handling

### 2.3 Project Structure Tools

- [ ] **TASK-060**: Implement `GetFileStructureTool.kt`
  - Define input schema (file)
  - Parse PSI tree of file
  - Extract classes, methods, fields
  - Return hierarchical structure

- [ ] **TASK-061**: Write integration tests for GetFileStructureTool
  - Test Java file structure
  - Test Kotlin file structure
  - Test nested class extraction

- [ ] **TASK-062**: Implement `GetProjectStructureTool.kt`
  - Use `ModuleManager` to get modules
  - Extract source roots for each module
  - Return project structure tree

- [ ] **TASK-063**: Implement `GetDependenciesTool.kt`
  - Use `OrderEnumerator` to get libraries
  - Extract library names and versions
  - Return dependency list

- [ ] **TASK-064**: Implement `GetIndexStatusTool.kt`
  - Check `DumbService.isDumb()`
  - Return dumb/smart mode status

### 2.4 Resource Providers

- [ ] **TASK-065**: Create `McpResource.kt` interface
  - Define `uri`, `name`, `description`, `mimeType` properties
  - Define `read()` suspend function

- [ ] **TASK-066**: Implement `ResourceRegistry.kt`
  - Implement `register()` method
  - Implement `getResource()` method
  - Implement `getAllResources()` method
  - Implement `getResourceDefinitions()` method

- [ ] **TASK-067**: Implement `ProjectStructureResource.kt`
  - URI: `project://structure`
  - Return project module tree as JSON

- [ ] **TASK-068**: Implement `FileContentResource.kt`
  - URI pattern: `file://content/{path}`
  - Read file content
  - Return with metadata

- [ ] **TASK-069**: Implement `SymbolInfoResource.kt`
  - URI pattern: `symbol://info/{fqn}`
  - Resolve symbol by fully qualified name
  - Return symbol information

- [ ] **TASK-070**: Implement `IndexStatusResource.kt`
  - URI: `index://status`
  - Return indexing status

- [ ] **TASK-071**: Write unit tests for resource providers
  - Test each resource read operation

### 2.5 Full Command History UI

- [ ] **TASK-072**: Implement `CommandHistoryPanel.kt`
  - Create JBList with custom model
  - Implement `CommandHistoryListener`
  - Handle selection events
  - Implement auto-scroll behavior

- [ ] **TASK-073**: Implement `CommandListCellRenderer.kt`
  - Display timestamp
  - Display tool name (bold)
  - Display status with color coding
  - Support expand/collapse indicator

- [ ] **TASK-074**: Implement `CommandDetailPanel.kt`
  - Create split panel layout
  - Add JSON viewer for request
  - Add JSON viewer for response
  - Display execution duration
  - Display affected files (if any)

- [ ] **TASK-075**: Implement `FilterToolbar.kt`
  - Add tool name filter (ComboBox)
  - Add status filter (ComboBox)
  - Add search field
  - Wire filters to CommandHistoryService

- [ ] **TASK-076**: Update `CommandHistoryService.kt` with filtering
  - Implement `getFilteredHistory()` method
  - Support tool name filter
  - Support status filter
  - Support text search

- [ ] **TASK-077**: Implement export functionality
  - Implement `exportToJson()` method
  - Implement `exportToCsv()` method

- [ ] **TASK-078**: Create `ExportHistoryAction.kt`
  - Show file chooser dialog
  - Support JSON and CSV formats
  - Write export file

- [ ] **TASK-079**: Create `ClearHistoryAction.kt`
  - Show confirmation dialog
  - Clear history on confirm

- [ ] **TASK-080**: Update McpToolWindowPanel with full UI
  - Integrate ServerStatusPanel
  - Integrate FilterToolbar
  - Integrate CommandHistoryPanel
  - Integrate CommandDetailPanel with splitter

### 2.6 Integration Tests

- [ ] **TASK-081**: Create `McpServerIntegrationTest.kt`
  - Test full server startup
  - Test tools/list endpoint
  - Test tools/call endpoint
  - Test resources/list endpoint

- [ ] **TASK-082**: Create `ToolExecutionIntegrationTest.kt`
  - Test each navigation tool end-to-end
  - Test each intelligence tool end-to-end
  - Test error scenarios

---

## Phase 3: Refactoring Operations

**Goal**: Implement all refactoring tools with proper undo support and user confirmation.

### 3.1 Refactoring Infrastructure

- [ ] **TASK-083**: Create `RefactoringResult.kt` model
  - success flag
  - affected files list
  - changes count
  - message

- [ ] **TASK-084**: Create `AbstractRefactoringTool.kt` base class
  - Extend `AbstractMcpTool`
  - Add confirmation check logic
  - Add undo group management
  - Add affected files tracking

### 3.2 Rename Tool

- [ ] **TASK-085**: Implement `RenameSymbolTool.kt`
  - Define input schema (file, line, column, newName)
  - Resolve element at position
  - Use `RenameProcessor` for rename
  - Track affected files
  - Return `RefactoringResult`

- [ ] **TASK-086**: Write integration tests for RenameSymbolTool
  - Test method rename
  - Test class rename
  - Test variable rename
  - Test rename with references in multiple files

### 3.3 Extract Method Tool

- [ ] **TASK-087**: Implement `ExtractMethodTool.kt`
  - Define input schema (file, startLine, endLine, methodName)
  - Select code range
  - Use `ExtractMethodProcessor`
  - Return new method location

- [ ] **TASK-088**: Write integration tests for ExtractMethodTool
  - Test simple extraction
  - Test extraction with parameters
  - Test extraction with return value

### 3.4 Extract Variable Tool

- [ ] **TASK-089**: Implement `ExtractVariableTool.kt`
  - Define input schema (file, line, column, variableName)
  - Resolve expression at position
  - Use `IntroduceVariableHandler`
  - Return variable declaration location

- [ ] **TASK-090**: Write integration tests for ExtractVariableTool
  - Test expression extraction
  - Test with multiple occurrences

### 3.5 Inline Tool

- [ ] **TASK-091**: Implement `InlineTool.kt`
  - Define input schema (file, line, column)
  - Resolve variable/method at position
  - Use `InlineHandler`
  - Return success/failure

- [ ] **TASK-092**: Write integration tests for InlineTool
  - Test variable inline
  - Test method inline

### 3.6 Safe Delete Tool

- [ ] **TASK-093**: Implement `SafeDeleteTool.kt`
  - Define input schema (file, line, column)
  - Resolve element at position
  - Check for usages using `SafeDeleteProcessor`
  - If usages exist, return blocking usages
  - If no usages, perform delete
  - Return result

- [ ] **TASK-094**: Write integration tests for SafeDeleteTool
  - Test deletion with no usages
  - Test deletion blocked by usages

### 3.7 Move Element Tool

- [ ] **TASK-095**: Implement `MoveElementTool.kt`
  - Define input schema (file, line, column, targetFile)
  - Resolve element at position
  - Use `MoveHandler`
  - Return new location

- [ ] **TASK-096**: Write integration tests for MoveElementTool
  - Test class move
  - Test method move

### 3.8 User Confirmation Dialog

- [ ] **TASK-097**: Implement `RefactoringConfirmationDialog.kt`
  - Show affected files preview
  - Show changes summary
  - Confirm/Cancel buttons
  - "Don't ask again" checkbox

- [ ] **TASK-098**: Integrate confirmation with refactoring tools
  - Check settings for confirmation required
  - Show dialog before executing
  - Proceed or abort based on user choice

### 3.9 Undo/Redo Integration

- [ ] **TASK-099**: Implement undo group management
  - Use `CommandProcessor` for command grouping
  - Set descriptive command names
  - Ensure atomic undo for multi-file changes

- [ ] **TASK-100**: Write tests for undo functionality
  - Test undo after rename
  - Test undo after extract method

### 3.10 Refactoring Preview

- [ ] **TASK-101**: Implement `RefactoringPreviewPanel.kt`
  - Show diff for each affected file
  - Highlight changes
  - Allow accept/reject

- [ ] **TASK-102**: Integrate preview with refactoring tools
  - Generate preview data
  - Show preview dialog
  - Apply or cancel based on user choice

---

## Phase 4: Polish & Extensions

**Goal**: Performance optimization, extension point, documentation, and marketplace preparation.

### 4.1 Performance Optimization

- [ ] **TASK-103**: Profile tool execution times
  - Add timing instrumentation
  - Identify slow operations
  - Document baseline performance

- [ ] **TASK-104**: Optimize PSI operations
  - Use stub indexes where possible
  - Cache frequently accessed data
  - Minimize read action scope

- [ ] **TASK-105**: Optimize JSON serialization
  - Use streaming where appropriate
  - Reduce object allocations
  - Profile and optimize hot paths

- [ ] **TASK-106**: Add request throttling
  - Implement request queue
  - Add configurable rate limiting
  - Handle burst requests gracefully

### 4.2 Extension Point

- [ ] **TASK-107**: Define `mcpTool` extension point in plugin.xml
  - Configure interface
  - Set dynamic=true

- [ ] **TASK-108**: Update ToolRegistry to load extensions
  - Implement `registerExtensionTools()`
  - Handle extension point listener for dynamic registration

- [ ] **TASK-109**: Create extension point documentation
  - Document interface requirements
  - Provide example implementation
  - Document registration process

- [ ] **TASK-110**: Write tests for extension point
  - Test dynamic tool registration
  - Test tool execution from extension

### 4.3 Client Configuration Generator

- [ ] **TASK-111**: Create `ClientConfigGenerator.kt`
  - Generate Claude Desktop config
  - Generate Cursor config
  - Generate VS Code config (generic MCP)

- [ ] **TASK-112**: Add "Copy Config" action to tool window
  - Show config type selector
  - Generate and copy to clipboard
  - Show success notification

### 4.4 Documentation

- [ ] **TASK-113**: Update README.md
  - Add feature overview
  - Add installation instructions
  - Add usage guide
  - Add configuration guide

- [ ] **TASK-114**: Create USAGE.md
  - Document each tool with examples
  - Document each resource
  - Document error codes

- [ ] **TASK-115**: Create EXTENSION.md
  - Document extension point
  - Provide example plugin
  - Document best practices

- [ ] **TASK-116**: Add inline documentation
  - KDoc for public APIs
  - Usage examples in KDoc

### 4.5 Marketplace Preparation

- [ ] **TASK-117**: Create plugin icon (pluginIcon.svg)
  - Design 40x40 icon
  - Follow JetBrains guidelines

- [ ] **TASK-118**: Write plugin description for marketplace
  - Feature highlights
  - Screenshots
  - Compatibility info

- [ ] **TASK-119**: Update CHANGELOG.md
  - Document all features
  - Version history

- [ ] **TASK-120**: Configure plugin signing
  - Set up certificate
  - Configure signing in build.gradle.kts

- [ ] **TASK-121**: Run plugin verifier
  - Fix any compatibility issues
  - Ensure API compatibility

- [ ] **TASK-122**: Prepare release workflow
  - Configure GitHub Actions for release
  - Set up marketplace publishing

### 4.6 Final Testing

- [ ] **TASK-123**: End-to-end testing with Claude Desktop
  - Test all tools manually
  - Verify error handling
  - Test multi-instance support

- [ ] **TASK-124**: End-to-end testing with Cursor
  - Test tool discovery
  - Test tool execution
  - Verify compatibility

- [ ] **TASK-125**: Performance testing
  - Test with large projects
  - Test concurrent requests
  - Measure response times

- [ ] **TASK-126**: Stress testing
  - Rapid request sequences
  - Memory usage monitoring
  - Connection handling

---

## Summary

### Task Count by Phase

| Phase | Task Count | Status |
|-------|------------|--------|
| Phase 1: Foundation | 45 tasks | Not Started |
| Phase 2: Navigation & Intelligence | 37 tasks | Not Started |
| Phase 3: Refactoring Operations | 20 tasks | Not Started |
| Phase 4: Polish & Extensions | 24 tasks | Not Started |
| **Total** | **126 tasks** | |

### Critical Path

The following tasks are on the critical path and block other work:

1. TASK-001 → TASK-002 (Dependencies must be added first)
2. TASK-005 → TASK-016 (Data models needed for JSON-RPC handler)
3. TASK-010 → TASK-013 (Port manager needed for HTTP transport)
4. TASK-020 → TASK-024 (Tool interface needed for tools)
5. TASK-030 → TASK-040 (Server service needed for startup activity)

### Milestone Definitions

| Milestone | Completion Criteria |
|-----------|---------------------|
| M1: Infrastructure | TASK-001 through TASK-023 complete |
| M2: MVP Navigation | TASK-024 through TASK-035 complete |
| M3: MVP GUI | TASK-036 through TASK-045 complete |
| M4: Full Navigation | TASK-046 through TASK-071 complete |
| M5: Full GUI | TASK-072 through TASK-082 complete |
| M6: Refactoring | TASK-083 through TASK-102 complete |
| M7: Release Ready | All tasks complete |

---

## Appendix: Task Dependencies

```
TASK-001 (gradle deps)
    └── TASK-002 (build.gradle)
            └── TASK-003 (plugin.xml)

TASK-005 (JSON-RPC models)
    └── TASK-016 (JsonRpcHandler)
            └── TASK-017 (MCP methods)

TASK-010 (PortManager)
    └── TASK-013 (HttpTransport)
            └── TASK-030 (McpServerService)
                    └── TASK-040 (StartupActivity)

TASK-020 (McpTool interface)
    └── TASK-021 (AbstractMcpTool)
            └── TASK-024, 026, 028 (Navigation tools)

TASK-022 (ToolRegistry)
    └── TASK-030 (McpServerService)

TASK-033 (CommandHistoryService)
    └── TASK-072 (CommandHistoryPanel)
            └── TASK-080 (Full GUI)
```

---

**Document Version**: 1.0
**Created**: 2025-01-25

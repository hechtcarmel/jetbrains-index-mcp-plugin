# IntelliJ Index MCP Plugin - Requirements Specification

**Document Version**: 1.0
**Status**: Draft - Pending Approval

---

## 1. Platform Requirements

### 1.1 Runtime Environment

| ID | Requirement |
|----|-------------|
| PLAT-001 | The plugin SHALL support IntelliJ IDEA 2024.3 and later (platformSinceBuild = 243) |
| PLAT-002 | The plugin SHALL require JVM version 21 |
| PLAT-003 | The plugin SHALL be written in Kotlin 2.1+ |
| PLAT-004 | The plugin SHALL be built with Gradle 9.0 |
| PLAT-005 | The plugin SHALL support IntelliJ IDEA Community Edition (IC) |
| PLAT-006 | The plugin SHALL support IntelliJ IDEA Ultimate Edition (IU) |

### 1.2 Protocol Compliance

| ID | Requirement |
|----|-------------|
| PLAT-007 | The plugin SHALL implement MCP specification version 2025-03-26 |
| PLAT-008 | The plugin SHALL use JSON-RPC 2.0 for message format |
| PLAT-009 | The plugin SHALL support Streamable HTTP transport |

---

## 2. MCP Server Requirements

### 2.1 Transport Layer

| ID | Requirement |
|----|-------------|
| SRV-001 | The server SHALL expose a single HTTP endpoint supporting POST and GET requests |
| SRV-002 | The server SHALL auto-assign a port from the range 8080-8180 |
| SRV-003 | The server SHALL persist port assignments to `~/.mcp-jetbrains/ports.json` |
| SRV-004 | The server SHALL support multiple concurrent client connections |
| SRV-005 | The server SHALL handle port conflicts by trying the next available port |
| SRV-006 | The server SHALL notify the user via IDE notification when port conflicts occur |

### 2.2 Port Registry File Format

| ID | Requirement |
|----|-------------|
| SRV-007 | The port registry file SHALL be JSON format |
| SRV-008 | Each registry entry SHALL contain: `port` (integer) |
| SRV-009 | Each registry entry SHALL contain: `projectPath` (string) |
| SRV-010 | Each registry entry SHALL contain: `projectName` (string) |
| SRV-011 | Each registry entry SHALL contain: `pid` (integer) |
| SRV-012 | Each registry entry SHALL contain: `startedAt` (ISO 8601 timestamp) |

### 2.3 Message Types

| ID | Requirement |
|----|-------------|
| SRV-013 | The server SHALL handle Request messages (with unique ID) |
| SRV-014 | The server SHALL send Response messages (matching request ID) |
| SRV-015 | The server SHALL handle Notification messages (no response expected) |

---

## 3. MCP Tool Requirements

### 3.1 Navigation Tools

#### 3.1.1 find_usages

| ID | Requirement |
|----|-------------|
| NAV-001 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-002 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-003 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-004 | The tool SHALL return a list of usage locations |
| NAV-005 | Each usage location SHALL contain: `file` (string) |
| NAV-006 | Each usage location SHALL contain: `line` (integer) |
| NAV-007 | Each usage location SHALL contain: `column` (integer) |
| NAV-008 | Each usage location SHALL contain: `context` (string, code snippet) |
| NAV-009 | Each usage location SHALL contain: `type` (string, e.g., METHOD_CALL) |

#### 3.1.2 go_to_definition

| ID | Requirement |
|----|-------------|
| NAV-010 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-011 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-012 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-013 | The tool SHALL return the definition location with file path |
| NAV-014 | The tool SHALL return the definition location with line number |
| NAV-015 | The tool SHALL return a code preview of the definition |

#### 3.1.3 type_hierarchy

| ID | Requirement |
|----|-------------|
| NAV-016 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-017 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-018 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-019 | The tool MAY accept input: `className` (string, alternative to position) |
| NAV-020 | The tool SHALL return the element name, file, and kind |
| NAV-021 | The tool SHALL return a list of supertypes with name and file |
| NAV-022 | The tool SHALL return a list of subtypes with name and file |

#### 3.1.4 call_hierarchy

| ID | Requirement |
|----|-------------|
| NAV-023 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-024 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-025 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-026 | The tool SHALL accept input: `direction` (string, "callers" or "callees") |
| NAV-027 | The tool SHALL return a tree structure of calling/called methods |

#### 3.1.5 find_implementations

| ID | Requirement |
|----|-------------|
| NAV-028 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-029 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-030 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-031 | The tool SHALL return a list of implementing classes/methods |

### 3.2 Refactoring Tools

#### 3.2.1 rename_symbol

| ID | Requirement |
|----|-------------|
| REF-001 | The tool SHALL accept input: `file` (string, path relative to project root) |
| REF-002 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| REF-003 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| REF-004 | The tool SHALL accept input: `newName` (string) |
| REF-005 | The tool SHALL return success/failure status |
| REF-006 | The tool SHALL return a list of affected files |
| REF-007 | The tool SHALL return the count of changes made |
| REF-008 | The tool SHALL update all references to the renamed symbol |

#### 3.2.2 extract_method

| ID | Requirement |
|----|-------------|
| REF-009 | The tool SHALL accept input: `file` (string, path relative to project root) |
| REF-010 | The tool SHALL accept input: `startLine` (integer, 1-based) |
| REF-011 | The tool SHALL accept input: `endLine` (integer, 1-based) |
| REF-012 | The tool SHALL accept input: `methodName` (string) |
| REF-013 | The tool SHALL return the location of the new method |

#### 3.2.3 extract_variable

| ID | Requirement |
|----|-------------|
| REF-014 | The tool SHALL accept input: `file` (string, path relative to project root) |
| REF-015 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| REF-016 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| REF-017 | The tool SHALL accept input: `variableName` (string) |
| REF-018 | The tool SHALL return the variable declaration location |

#### 3.2.4 inline

| ID | Requirement |
|----|-------------|
| REF-019 | The tool SHALL accept input: `file` (string, path relative to project root) |
| REF-020 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| REF-021 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| REF-022 | The tool SHALL return success/failure status |

#### 3.2.5 safe_delete

| ID | Requirement |
|----|-------------|
| REF-023 | The tool SHALL accept input: `file` (string, path relative to project root) |
| REF-024 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| REF-025 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| REF-026 | The tool SHALL return success status or list of usages blocking deletion |

#### 3.2.6 move_element

| ID | Requirement |
|----|-------------|
| REF-027 | The tool SHALL accept input: `file` (string, path relative to project root) |
| REF-028 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| REF-029 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| REF-030 | The tool SHALL accept input: `targetFile` (string, destination path) |
| REF-031 | The tool SHALL return the new location |

### 3.3 Code Intelligence Tools

#### 3.3.1 get_completions

| ID | Requirement |
|----|-------------|
| INT-001 | The tool SHALL accept input: `file` (string, path relative to project root) |
| INT-002 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| INT-003 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| INT-004 | The tool SHALL return a list of completion items |

#### 3.3.2 get_inspections

| ID | Requirement |
|----|-------------|
| INT-005 | The tool SHALL accept input: `file` (string, path relative to project root) |
| INT-006 | The tool MAY accept input: `startLine` (integer, optional) |
| INT-007 | The tool MAY accept input: `endLine` (integer, optional) |
| INT-008 | The tool SHALL return a list of problems with severity |

#### 3.3.3 get_quick_fixes

| ID | Requirement |
|----|-------------|
| INT-009 | The tool SHALL accept input: `file` (string, path relative to project root) |
| INT-010 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| INT-011 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| INT-012 | The tool SHALL return a list of applicable fixes with unique IDs |

#### 3.3.4 apply_quick_fix

| ID | Requirement |
|----|-------------|
| INT-013 | The tool SHALL accept input: `file` (string, path relative to project root) |
| INT-014 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| INT-015 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| INT-016 | The tool SHALL accept input: `fixId` (string) |
| INT-017 | The tool SHALL return the modified code |

#### 3.3.5 get_symbol_info

| ID | Requirement |
|----|-------------|
| INT-018 | The tool SHALL accept input: `file` (string, path relative to project root) |
| INT-019 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| INT-020 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| INT-021 | The tool SHALL return symbol name |
| INT-022 | The tool SHALL return symbol type |
| INT-023 | The tool SHALL return symbol documentation |

### 3.4 Project Structure Tools

#### 3.4.1 get_project_structure

| ID | Requirement |
|----|-------------|
| PRJ-001 | The tool SHALL return the module tree with source roots |

#### 3.4.2 get_file_structure

| ID | Requirement |
|----|-------------|
| PRJ-002 | The tool SHALL accept input: `file` (string, path relative to project root) |
| PRJ-003 | The tool SHALL return a tree of classes, methods, and fields |

#### 3.4.3 get_dependencies

| ID | Requirement |
|----|-------------|
| PRJ-004 | The tool SHALL return a list of libraries and versions |

#### 3.4.4 get_index_status

| ID | Requirement |
|----|-------------|
| PRJ-005 | The tool SHALL return whether the IDE is in dumb mode or smart mode |

---

## 4. MCP Resource Requirements

| ID | Requirement |
|----|-------------|
| RES-001 | The server SHALL expose resource: `project://structure` |
| RES-002 | The server SHALL expose resource: `file://content/{path}` |
| RES-003 | The server SHALL expose resource: `symbol://info/{fqn}` |
| RES-004 | The server SHALL expose resource: `index://status` |

---

## 5. GUI Requirements

### 5.1 Tool Window

| ID | Requirement |
|----|-------------|
| GUI-001 | The plugin SHALL provide a tool window titled "MCP Server" |
| GUI-002 | The tool window SHALL display server running status |
| GUI-003 | The tool window SHALL display the server URL |
| GUI-004 | The tool window SHALL provide a "Copy URL" button |
| GUI-005 | The tool window SHALL display the project name |
| GUI-006 | The tool window SHALL provide settings access via gear icon |
| GUI-007 | The tool window SHALL provide a refresh button |

### 5.2 Command History Panel

| ID | Requirement |
|----|-------------|
| GUI-008 | The tool window SHALL display a command history list |
| GUI-009 | The command list SHALL be sorted chronologically (newest first) |
| GUI-010 | Each command entry SHALL display a timestamp |
| GUI-011 | Each command entry SHALL display the tool name |
| GUI-012 | Each command entry SHALL display a status indicator (SUCCESS/ERROR) |
| GUI-013 | Each command entry SHALL be collapsible/expandable |
| GUI-014 | Expanded entries SHALL show parameters |
| GUI-015 | Expanded entries SHALL show results or error messages |
| GUI-016 | Success status SHALL be indicated with green color |
| GUI-017 | Error status SHALL be indicated with red color |
| GUI-018 | Pending status SHALL be indicated with yellow color |
| GUI-019 | The tool window SHALL provide a "Clear History" button |

### 5.3 Command Details

| ID | Requirement |
|----|-------------|
| GUI-020 | The command details SHALL show full JSON request |
| GUI-021 | The command details SHALL show full JSON response |
| GUI-022 | The command details SHALL show affected files list (for refactoring) |
| GUI-023 | The command details SHALL show execution duration |
| GUI-024 | The command details SHALL provide copy-to-clipboard functionality |

### 5.4 Filtering and Search

| ID | Requirement |
|----|-------------|
| GUI-025 | The command history SHALL be filterable by tool name |
| GUI-026 | The command history SHALL be filterable by status |
| GUI-027 | The command history SHALL be searchable within parameters/results |

### 5.5 Actions

| ID | Requirement |
|----|-------------|
| GUI-028 | The tool window SHALL support exporting history to JSON format |
| GUI-029 | The tool window SHALL support exporting history to CSV format |
| GUI-030 | The tool window SHALL support re-executing a command |

---

## 6. Settings Requirements

| ID | Requirement |
|----|-------------|
| SET-001 | The plugin SHALL provide a setting: Max History Size (default: 100) |
| SET-002 | The plugin SHALL provide a setting: Auto-scroll (default: true) |
| SET-003 | The plugin SHALL provide a setting: Show Timestamps (default: true) |
| SET-004 | The plugin SHALL provide a setting: Confirmation Required for write operations (default: true) |
| SET-005 | The plugin SHALL provide a setting: Log to File (default: false) |

---

## 7. Error Handling Requirements

### 7.1 Protocol Errors

| ID | Requirement |
|----|-------------|
| ERR-001 | The server SHALL return error code -32700 for parse errors |
| ERR-002 | The server SHALL return error code -32600 for invalid requests |
| ERR-003 | The server SHALL return error code -32601 for method not found |
| ERR-004 | The server SHALL return error code -32602 for invalid params |
| ERR-005 | The server SHALL return error code -32603 for internal errors |

### 7.2 Tool Errors

| ID | Requirement |
|----|-------------|
| ERR-006 | Tool errors SHALL return result with `isError: true` |
| ERR-007 | The server SHALL return appropriate error when index is not ready (dumb mode) |
| ERR-008 | The server SHALL return appropriate error when file is not found |
| ERR-009 | The server SHALL return appropriate error when symbol is not found |
| ERR-010 | The server SHALL return appropriate error for refactoring conflicts |

### 7.3 Recovery

| ID | Requirement |
|----|-------------|
| ERR-011 | All write operations SHALL be atomic |
| ERR-012 | All write operations SHALL support undo |

---

## 8. Threading Requirements

| ID | Requirement |
|----|-------------|
| THR-001 | PSI read operations SHALL use ReadAction |
| THR-002 | PSI write operations SHALL use WriteCommandAction on EDT |
| THR-003 | Index access SHALL check DumbService.isDumb() before execution |
| THR-004 | GUI updates SHALL use ApplicationManager.getApplication().invokeLater() |
| THR-005 | MCP communication SHALL run on background threads (non-blocking) |

---

## 9. Extension Point Requirements

| ID | Requirement |
|----|-------------|
| EXT-001 | The plugin SHALL provide an extension point named `mcpTool` |
| EXT-002 | The extension point SHALL allow third-party plugins to register custom MCP tools |
| EXT-003 | The extension point interface SHALL require: `name` (String) |
| EXT-004 | The extension point interface SHALL require: `description` (String) |
| EXT-005 | The extension point interface SHALL require: `inputSchema` (JsonSchema) |
| EXT-006 | The extension point interface SHALL require: `execute(project, arguments)` method |

---

## 10. Dependencies

| ID | Requirement |
|----|-------------|
| DEP-001 | The plugin SHALL use MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk`) |
| DEP-002 | The plugin SHALL use Ktor for HTTP transport |
| DEP-003 | The plugin SHALL use kotlinx-serialization-json for JSON processing |
| DEP-004 | The plugin SHALL use kotlinx-coroutines-core for async operations |

---

## 11. IntelliJ Platform Dependencies

| ID | Requirement |
|----|-------------|
| DEP-005 | The plugin SHALL depend on `com.intellij.modules.platform` |
| DEP-006 | The plugin MAY optionally depend on `com.intellij.modules.java` |
| DEP-007 | The plugin MAY optionally depend on `org.jetbrains.kotlin` |

---

## 12. Implementation Phases

### Phase 1: Foundation (MVP)

| ID | Requirement |
|----|-------------|
| PH1-001 | Phase 1 SHALL deliver: Streamable HTTP transport |
| PH1-002 | Phase 1 SHALL deliver: Port manager with auto-assignment and registry |
| PH1-003 | Phase 1 SHALL deliver: Tool registry and JSON-RPC routing |
| PH1-004 | Phase 1 SHALL deliver: `find_usages` tool |
| PH1-005 | Phase 1 SHALL deliver: `go_to_definition` tool |
| PH1-006 | Phase 1 SHALL deliver: `get_symbol_info` tool |
| PH1-007 | Phase 1 SHALL deliver: Basic tool window with server URL and status |
| PH1-008 | Phase 1 SHALL deliver: Unit tests for core functionality |

### Phase 2: Navigation & Intelligence

| ID | Requirement |
|----|-------------|
| PH2-001 | Phase 2 SHALL deliver: `type_hierarchy` tool |
| PH2-002 | Phase 2 SHALL deliver: `call_hierarchy` tool |
| PH2-003 | Phase 2 SHALL deliver: `find_implementations` tool |
| PH2-004 | Phase 2 SHALL deliver: `get_completions` tool |
| PH2-005 | Phase 2 SHALL deliver: `get_quick_fixes` tool |
| PH2-006 | Phase 2 SHALL deliver: `apply_quick_fix` tool |
| PH2-007 | Phase 2 SHALL deliver: `get_file_structure` tool |
| PH2-008 | Phase 2 SHALL deliver: Full command history UI with filtering and search |
| PH2-009 | Phase 2 SHALL deliver: Resource providers |
| PH2-010 | Phase 2 SHALL deliver: Integration tests |

### Phase 3: Refactoring Operations

| ID | Requirement |
|----|-------------|
| PH3-001 | Phase 3 SHALL deliver: `extract_method` tool |
| PH3-002 | Phase 3 SHALL deliver: `extract_variable` tool |
| PH3-003 | Phase 3 SHALL deliver: `inline` tool |
| PH3-004 | Phase 3 SHALL deliver: `safe_delete` tool |
| PH3-005 | Phase 3 SHALL deliver: `move_element` tool |
| PH3-006 | Phase 3 SHALL deliver: `rename_symbol` tool |
| PH3-007 | Phase 3 SHALL deliver: User confirmation dialog for write operations |
| PH3-008 | Phase 3 SHALL deliver: Undo/redo integration |
| PH3-009 | Phase 3 SHALL deliver: Refactoring preview functionality |

### Phase 4: Polish & Extensions

| ID | Requirement |
|----|-------------|
| PH4-001 | Phase 4 SHALL deliver: Performance optimization |
| PH4-002 | Phase 4 SHALL deliver: `mcpTool` extension point |
| PH4-003 | Phase 4 SHALL deliver: Documentation and examples |
| PH4-004 | Phase 4 SHALL deliver: Client configuration generator (for Claude, Cursor, VS Code) |
| PH4-005 | Phase 4 SHALL deliver: Marketplace preparation |

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| MCP | Model Context Protocol |
| PSI | Program Structure Interface |
| EDT | Event Dispatch Thread |
| Dumb Mode | IDE state during indexing when index-based features are unavailable |
| Smart Mode | IDE state when indexing is complete and all features are available |

---

## Appendix B: Document History

| Version | Date | Description |
|---------|------|-------------|
| 1.0 | 2025-01-25 | Initial requirements extraction from init.md |

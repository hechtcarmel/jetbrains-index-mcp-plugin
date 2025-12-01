# IntelliJ Index MCP Plugin - Requirements Specification

**Document Version**: 1.0
**Status**: Draft - Pending Approval

---

## 1. Platform Requirements

### 1.1 Runtime Environment

| ID | Requirement |
|----|-------------|
| PLAT-001 | The plugin SHALL support IntelliJ Platform 2024.3 and later (platformSinceBuild = 243) |
| PLAT-002 | The plugin SHALL require JVM version 21 |
| PLAT-003 | The plugin SHALL be written in Kotlin 2.1+ |
| PLAT-004 | The plugin SHALL be built with Gradle 9.0 |
| PLAT-005 | The plugin SHALL support all JetBrains IDEs based on IntelliJ Platform |
| PLAT-006 | Universal tools SHALL work in all JetBrains IDEs |
| PLAT-007 | Navigation tools SHALL activate based on available language plugins (Java, Python, JavaScript/TypeScript) |
| PLAT-008 | Refactoring tools SHALL only be available when Java plugin is present |

### 1.2 Supported IDEs

| IDE | Universal Tools | Navigation Tools | Refactoring Tools |
|-----|-----------------|------------------|-------------------|
| IntelliJ IDEA Community/Ultimate | Yes | Yes (Java/Kotlin) | Yes |
| Android Studio | Yes | Yes (Java/Kotlin) | Yes |
| PyCharm Community/Professional | Yes | Yes (Python) | No |
| WebStorm | Yes | Yes (JS/TS) | No |
| GoLand | Yes | No | No |
| PhpStorm | Yes | No | No |
| RubyMine | Yes | No | No |
| CLion | Yes | No | No |
| Rider | Yes | No | No |
| DataGrip | Yes | No | No |

### 1.3 Protocol Compliance

| ID | Requirement |
|----|-------------|
| PLAT-008 | The plugin SHALL implement MCP specification version 2024-11-05 |
| PLAT-009 | The plugin SHALL use JSON-RPC 2.0 for message format |
| PLAT-010 | The plugin SHALL support HTTP+SSE transport |

---

## 2. MCP Server Requirements

### 2.1 Transport Layer (HTTP+SSE)

| ID | Requirement |
|----|-------------|
| SRV-001 | The server SHALL register an HttpRequestHandler on the IDE's built-in web server |
| SRV-002 | The server SHALL expose SSE endpoint at path `/index-mcp/sse` |
| SRV-003 | The server SHALL expose JSON-RPC POST endpoint at path `/index-mcp` |
| SRV-004 | The SSE endpoint SHALL send an `endpoint` event with the POST URL on connection |
| SRV-005 | The server SHALL support multiple concurrent client connections |

### 2.2 Message Types

| ID | Requirement |
|----|-------------|
| SRV-006 | The server SHALL handle Request messages (with unique ID) |
| SRV-007 | The server SHALL send Response messages (matching request ID) |
| SRV-008 | The server SHALL handle Notification messages (no response expected) |

### 2.3 Multi-Project Support

| ID | Requirement |
|----|-------------|
| SRV-009 | All tool calls SHALL accept an optional `project_path` parameter (absolute path to project root) |
| SRV-010 | If `project_path` is provided, the server SHALL use that project for the operation |
| SRV-011 | If `project_path` is NOT provided and only ONE project is open, the server SHALL use that project |
| SRV-012 | If `project_path` is NOT provided and MULTIPLE projects are open, the server SHALL return an error with a list of available projects |
| SRV-013 | The error response for multiple projects SHALL include `available_projects` array with each project's `name` and `path` |
| SRV-014 | The error response SHALL include a clear message instructing the client to specify `project_path` |
| SRV-015 | If `project_path` is provided but no matching project is found, the server SHALL return a "project not found" error |

---

## 3. MCP Tool Requirements

### 3.0 Tool Availability

| ID | Requirement |
|----|-------------|
| TOOL-001 | Universal tools SHALL be registered in all JetBrains IDEs |
| TOOL-002 | Navigation tools SHALL be registered when any supported language plugin is available (Java, Python, JavaScript/TypeScript) |
| TOOL-003 | Refactoring tools SHALL only be registered when Java plugin is available |
| TOOL-004 | Tool availability SHALL be determined at startup using cached detection |
| TOOL-005 | Language-specific handlers SHALL be loaded via reflection to prevent class loading errors |
| TOOL-006 | Navigation tools SHALL delegate to appropriate language handlers based on the file type |

**Universal Tools (All IDEs):**
- `ide_find_references`
- `ide_find_definition`
- `ide_diagnostics`
- `ide_index_status`

**Navigation Tools (Language-Aware):**

Available when Java, Python, or JavaScript/TypeScript plugin is present:
- `ide_type_hierarchy`
- `ide_call_hierarchy`
- `ide_find_implementations`
- `ide_find_symbol`
- `ide_find_super_methods`

**Refactoring Tools (Java/Kotlin Only):**
- `ide_refactor_rename`
- `ide_refactor_safe_delete`

### 3.1 Universal Navigation Tools

#### 3.1.1 ide_find_references (formerly find_usages)

| ID | Requirement |
|----|-------------|
| NAV-001 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-002 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-003 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-003a | The tool MAY accept input: `maxResults` (integer, default: 100, max: 500) |
| NAV-004 | The tool SHALL return a list of usage locations |
| NAV-005 | Each usage location SHALL contain: `file` (string) |
| NAV-006 | Each usage location SHALL contain: `line` (integer) |
| NAV-007 | Each usage location SHALL contain: `column` (integer) |
| NAV-008 | Each usage location SHALL contain: `context` (string, code snippet) |
| NAV-009 | Each usage location SHALL contain: `type` (string, e.g., METHOD_CALL) |

#### 3.1.2 ide_find_definition

| ID | Requirement |
|----|-------------|
| NAV-010 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-011 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-012 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-013 | The tool SHALL return the definition location with file path |
| NAV-014 | The tool SHALL return the definition location with line number |
| NAV-015 | The tool SHALL return a code preview of the definition |

#### 3.1.3 ide_type_hierarchy (formerly type_hierarchy)

| ID | Requirement |
|----|-------------|
| NAV-016 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-017 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-018 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-019 | The tool MAY accept input: `className` (string, alternative to position) |
| NAV-020 | The tool SHALL return the element name, file, and kind |
| NAV-021 | The tool SHALL return a list of supertypes with name and file |
| NAV-022 | The tool SHALL return a list of subtypes with name and file |

#### 3.1.4 ide_call_hierarchy (formerly call_hierarchy)

| ID | Requirement |
|----|-------------|
| NAV-023 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-024 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-025 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-026 | The tool SHALL accept input: `direction` (string, "callers" or "callees") |
| NAV-027 | The tool SHALL return a tree structure of calling/called methods |

#### 3.1.5 ide_find_implementations (formerly find_implementations)

| ID | Requirement |
|----|-------------|
| NAV-028 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-029 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| NAV-030 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| NAV-031 | The tool SHALL return a list of implementing classes/methods |

#### 3.1.6 ide_find_symbol

| ID | Requirement |
|----|-------------|
| NAV-032 | The tool SHALL accept input: `query` (string, search pattern) |
| NAV-033 | The tool MAY accept input: `includeLibraries` (boolean, default: false) |
| NAV-034 | The tool MAY accept input: `limit` (integer, default: 25, max: 100) |
| NAV-035 | The tool SHALL support substring matching (case-insensitive) |
| NAV-036 | The tool SHALL support camelCase matching (e.g., "USvc" matches "UserService") |
| NAV-037 | The tool SHALL return a list of matching symbols (classes, methods, fields) |
| NAV-038 | Each symbol SHALL contain: name, qualifiedName, kind, file, line, containerName |

#### 3.1.7 ide_find_super_methods

| ID | Requirement |
|----|-------------|
| NAV-039 | The tool SHALL accept input: `file` (string, path relative to project root) |
| NAV-040 | The tool SHALL accept input: `line` (integer, 1-based line number, any line within method) |
| NAV-041 | The tool SHALL accept input: `column` (integer, 1-based column number, any position within method) |
| NAV-042 | The tool SHALL return the full inheritance hierarchy of overridden/implemented methods |
| NAV-043 | Each super method SHALL contain: name, signature, containingClass, containingClassKind, file, line, isInterface, depth |
| NAV-044 | The hierarchy SHALL be ordered from immediate parent (depth=1) to root |

### 3.2 Refactoring Tools

#### 3.2.1 ide_refactor_rename (formerly rename_symbol)

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

#### 3.2.2 ide_refactor_safe_delete (formerly safe_delete)

| ID | Requirement |
|----|-------------|
| REF-009 | The tool SHALL accept input: `file` (string, path relative to project root) |
| REF-010 | The tool SHALL accept input: `line` (integer, 1-based line number) |
| REF-011 | The tool SHALL accept input: `column` (integer, 1-based column number) |
| REF-012 | The tool SHALL return success status or list of usages blocking deletion |

### 3.3 Code Intelligence Tools

#### 3.3.1 ide_diagnostics

| ID | Requirement |
|----|-------------|
| INT-001 | The tool SHALL accept input: `file` (string, path relative to project root) |
| INT-002 | The tool MAY accept input: `line` (integer, 1-based line number for intention lookup, default: 1) |
| INT-003 | The tool MAY accept input: `column` (integer, 1-based column number for intention lookup, default: 1) |
| INT-004 | The tool MAY accept input: `startLine` (integer, optional filter for problems) |
| INT-005 | The tool MAY accept input: `endLine` (integer, optional filter for problems) |
| INT-006 | The tool SHALL return a list of problems with severity (ERROR, WARNING, WEAK_WARNING, INFO) |
| INT-007 | The tool SHALL return a list of available intentions at the specified position |
| INT-008 | The tool SHALL return problemCount and intentionCount totals |

### 3.4 Project Structure Tools

#### 3.4.1 ide_index_status

| ID | Requirement |
|----|-------------|
| PRJ-001 | The tool SHALL return whether the IDE is in dumb mode or smart mode |
| PRJ-002 | The tool SHALL return the current indexing status |

---

## 4. GUI Requirements

### 4.1 Tool Window

| ID | Requirement |
|----|-------------|
| GUI-001 | The plugin SHALL provide a tool window titled "MCP Server" |
| GUI-002 | The tool window SHALL display server running status |
| GUI-003 | The tool window SHALL display the server URL |
| GUI-004 | The tool window SHALL provide a "Copy URL" button |
| GUI-005 | The tool window SHALL display the project name |
| GUI-006 | The tool window SHALL provide settings access via gear icon |
| GUI-007 | The tool window SHALL provide a refresh button |

### 4.2 Command History Panel

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

### 4.3 Command Details

| ID | Requirement |
|----|-------------|
| GUI-020 | The command details SHALL show full JSON request |
| GUI-021 | The command details SHALL show full JSON response |
| GUI-022 | The command details SHALL show affected files list (for refactoring) |
| GUI-023 | The command details SHALL show execution duration |
| GUI-024 | The command details SHALL provide copy-to-clipboard functionality |

### 4.4 Filtering and Search

| ID | Requirement |
|----|-------------|
| GUI-025 | The command history SHALL be filterable by tool name |
| GUI-026 | The command history SHALL be filterable by status |
| GUI-027 | The command history SHALL be searchable within parameters/results |

### 4.5 Actions

| ID | Requirement |
|----|-------------|
| GUI-028 | The tool window SHALL support exporting history to JSON format |
| GUI-029 | The tool window SHALL support exporting history to CSV format |
| GUI-030 | The tool window SHALL support re-executing a command |

---

## 5. Settings Requirements

| ID | Requirement |
|----|-------------|
| SET-001 | The plugin SHALL provide a setting: Max History Size (default: 100) |
| SET-002 | The plugin SHALL provide a setting: Auto-scroll (default: true) |
| SET-003 | The plugin SHALL provide a setting: Sync External Changes (default: false) |

---

## 6. Error Handling Requirements

### 6.1 Protocol Errors

| ID | Requirement |
|----|-------------|
| ERR-001 | The server SHALL return error code -32700 for parse errors |
| ERR-002 | The server SHALL return error code -32600 for invalid requests |
| ERR-003 | The server SHALL return error code -32601 for method not found |
| ERR-004 | The server SHALL return error code -32602 for invalid params |
| ERR-005 | The server SHALL return error code -32603 for internal errors |

### 6.2 Tool Errors

| ID | Requirement |
|----|-------------|
| ERR-006 | Tool errors SHALL return result with `isError: true` |
| ERR-007 | The server SHALL return appropriate error when index is not ready (dumb mode) |
| ERR-008 | The server SHALL return appropriate error when file is not found |
| ERR-009 | The server SHALL return appropriate error when symbol is not found |
| ERR-010 | The server SHALL return appropriate error for refactoring conflicts |

### 6.3 Recovery

| ID | Requirement |
|----|-------------|
| ERR-011 | All write operations SHALL be atomic |
| ERR-012 | All write operations SHALL support undo |

---

## 7. Threading Requirements

| ID | Requirement |
|----|-------------|
| THR-001 | PSI read operations SHALL use ReadAction |
| THR-002 | PSI write operations SHALL use WriteCommandAction on EDT |
| THR-003 | Index access SHALL check DumbService.isDumb() before execution |
| THR-004 | GUI updates SHALL use ApplicationManager.getApplication().invokeLater() |
| THR-005 | MCP communication SHALL run on background threads (non-blocking) |

---

## 8. Dependencies

| ID | Requirement |
|----|-------------|
| DEP-001 | The plugin SHALL use MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk`) |
| DEP-002 | The plugin SHALL use Ktor for HTTP transport |
| DEP-003 | The plugin SHALL use kotlinx-serialization-json for JSON processing |
| DEP-004 | The plugin SHALL use kotlinx-coroutines-core for async operations |

---

## 9. IntelliJ Platform Dependencies

| ID | Requirement |
|----|-------------|
| DEP-005 | The plugin SHALL depend on `com.intellij.modules.platform` |
| DEP-006 | The plugin MAY optionally depend on `com.intellij.modules.java` |
| DEP-007 | The plugin MAY optionally depend on `org.jetbrains.kotlin` |

---

## 10. Implementation Phases

### Phase 1: Foundation (MVP)

| ID | Requirement |
|----|-------------|
| PH1-001 | Phase 1 SHALL deliver: McpRequestHandler on IDE built-in web server at `/index-mcp` |
| PH1-002 | Phase 1 SHALL deliver: Tool registry and JSON-RPC routing |
| PH1-003 | Phase 1 SHALL deliver: `ide_find_references` tool |
| PH1-004 | Phase 1 SHALL deliver: `ide_find_definition` tool |
| PH1-005 | Phase 1 SHALL deliver: `ide_index_status` tool |
| PH1-006 | Phase 1 SHALL deliver: Basic tool window with server URL and status |
| PH1-007 | Phase 1 SHALL deliver: Unit tests for core functionality |

### Phase 2: Navigation & Intelligence

| ID | Requirement |
|----|-------------|
| PH2-001 | Phase 2 SHALL deliver: `ide_type_hierarchy` tool |
| PH2-002 | Phase 2 SHALL deliver: `ide_call_hierarchy` tool |
| PH2-003 | Phase 2 SHALL deliver: `ide_find_implementations` tool |
| PH2-004 | Phase 2 SHALL deliver: `ide_diagnostics` tool (problems + intentions) |
| PH2-005 | Phase 2 SHALL deliver: Full command history UI with filtering and search |
| PH2-006 | Phase 2 SHALL deliver: Integration tests |

### Phase 3: Refactoring Operations

| ID | Requirement |
|----|-------------|
| PH3-001 | Phase 3 SHALL deliver: `ide_refactor_rename` tool |
| PH3-002 | Phase 3 SHALL deliver: `ide_refactor_safe_delete` tool |
| PH3-003 | Phase 3 SHALL deliver: Undo/redo integration |

### Phase 4: Polish & Release

| ID | Requirement |
|----|-------------|
| PH4-001 | Phase 4 SHALL deliver: Documentation and examples |
| PH4-002 | Phase 4 SHALL deliver: Client configuration generator (for Claude, Cursor, VS Code) |
| PH4-003 | Phase 4 SHALL deliver: Marketplace preparation |

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
| 1.1 | 2025-11-27 | Reduced tool count from 20 to 13; removed 8 tools, added ide_diagnostics |
| 1.2 | 2025-11-28 | Reduced tool count from 13 to 9; removed extract_method, extract_variable, inline, move tools |
| 1.3 | 2025-11-28 | Added ide_find_symbol and ide_find_super_methods navigation tools (11 tools total) |
| 1.4 | 2025-11-28 | Removed MCP Resources (Section 4); resources functionality deprecated |
| 1.5 | 2025-11-29 | Added multi-IDE support; tools now categorized as Universal (4) and Extended (7) |
| 1.6 | 2025-11-29 | Multi-language support: Navigation tools now support Java, Python, and JavaScript/TypeScript via language handlers; Refactoring remains Java-only |
| 1.7 | 2025-12-01 | Added optional `maxResults` parameter to ide_find_references (NAV-003a) |

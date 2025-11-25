# IntelliJ Index MCP Plugin - Requirements Specification

## Table of Contents

1. [Overview](#overview)
2. [Goals and Objectives](#goals-and-objectives)
3. [Background Research](#background-research)
4. [Architecture](#architecture)
5. [MCP Server Features](#mcp-server-features)
6. [GUI Requirements](#gui-requirements)
7. [Technical Requirements](#technical-requirements)
8. [Implementation Phases](#implementation-phases)
9. [References](#references)

---

## Overview

This document outlines the requirements for developing an IntelliJ Platform plugin that exposes an MCP (Model Context Protocol) server. The plugin enables AI coding assistants (such as Claude, Cursor, etc.) to leverage the IDE's powerful indexing and refactoring capabilities.

### What is MCP?

The Model Context Protocol (MCP) is an open protocol specification released by Anthropic that standardizes how AI applications communicate with external tools and data sources. It uses JSON-RPC 2.0 over stdio or HTTP-based transports.

### Why This Plugin?

While JetBrains has integrated MCP server capabilities into IntelliJ IDEA 2025.2+, this plugin aims to:

1. **Extend capabilities** beyond the built-in tools
2. **Provide advanced index-based features** like type hierarchy and call hierarchy
3. **Offer a visual command history** for monitoring AI agent interactions
4. **Support custom tool extensions** for organization-specific workflows

---

## Goals and Objectives

### Primary Goals

1. **Expose IDE Intelligence via MCP**: Allow AI coding agents to leverage IntelliJ's indexing, PSI (Program Structure Interface), and refactoring APIs through standardized MCP tools.

2. **Enhanced Code Navigation**: Provide tools for type hierarchy, call hierarchy, find usages, and go-to-definition that AI agents can use programmatically.

3. **Safe Refactoring Operations**: Enable AI agents to perform refactoring operations (rename, extract, inline, safe delete) with full IDE support for reference updating.

4. **Command History GUI**: Provide a visual interface showing recent MCP commands executed, their parameters, and results.

### Non-Goals

- Replacing the built-in JetBrains MCP server
- Providing AI/LLM capabilities within the plugin itself
- Supporting non-IntelliJ IDEs

---

## Background Research

### Model Context Protocol (MCP)

**Protocol Version**: 2025-03-26 (latest)

**Key Concepts**:

| Concept | Description |
|---------|-------------|
| **Tools** | Operations that AI agents can invoke (e.g., `rename_symbol`, `find_usages`) |
| **Resources** | Read-only data sources (e.g., project structure, file contents) |
| **Prompts** | Pre-defined interaction templates (optional) |

**Transport Mechanisms**:

| Transport | Description | Use Case |
|-----------|-------------|----------|
| **Streamable HTTP** | Single HTTP endpoint supporting POST/GET with optional SSE streaming | **Primary choice** - supports multiple IDE instances, cloud deployment, enterprise networks |
| **STDIO** | Client launches server as subprocess; stdin/stdout communication | Local single-instance only; problematic with multiple IDEs |
| **WebSocket** | Bidirectional persistent connection | Real-time bidirectional needs |

#### Why Streamable HTTP Over STDIO?

**STDIO Limitations:**
- Each STDIO connection requires a **separate process per client**
- Running multiple IntelliJ instances creates **port/process conflicts**
- No way for multiple agents to connect to the **same IDE instance**
- Process management complexity for client applications

**Streamable HTTP Advantages:**
- Single HTTP endpoint handles **multiple concurrent connections**
- Each IDE instance runs on a **unique port** (auto-assigned or configurable)
- Supports **session management** via `Mcp-Session-Id` header
- Works with enterprise **proxies and firewalls**
- Compatible with **serverless/cloud deployment** (AWS Lambda, etc.)
- **Progressive message delivery** via chunked transfer encoding

**Streamable HTTP Details** (from [MCP Spec 2025-03-26](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)):
- Server provides single endpoint supporting POST (requests) and GET (SSE stream)
- Session IDs are globally unique and cryptographically secure (UUID, JWT, or hash)
- Replaces the deprecated SSE-only transport from 2024-11-05

**Message Format**: JSON-RPC 2.0 with three message types:
- Requests (with unique ID)
- Responses (matching request ID)
- Notifications (no response expected)

### IntelliJ Platform SDK Capabilities

**PSI (Program Structure Interface)**:
- Core abstraction for parsing and representing code structure
- Key classes: `PsiFile`, `PsiElement`, `PsiClass`, `PsiMethod`, `PsiNamedElement`
- Enables code analysis, navigation, and modification

**Indexing System**:
- **File-Based Indexes**: Map file content to data
- **Stub Indexes**: Lightweight PSI representations for fast lookups
- **DumbService**: Manages index availability (dumb mode vs. smart mode)

**Refactoring APIs**:
- `RenameProcessor` / `PsiNamedElement.setName()`: Rename refactoring
- `RefactoringSupportProvider`: Extension point for refactoring support
- `SafeDeleteProcessorDelegate`: Safe delete customization
- `FindUsagesProvider`: Usage search implementation

**Navigation APIs**:
- `CallerMethodsTreeStructure` / `CalleeMethodsTreeStructure`: Call hierarchy
- `HierarchyProvider`: Type hierarchy support
- `ReferencesSearch`: Find references to elements

### Existing Implementations

#### JetBrains Built-in MCP Server (IntelliJ 2025.2+)

The built-in MCP server provides **20 tools** organized into categories:

##### File Operations (7 tools)

| Tool | Description |
|------|-------------|
| `get_file_text_by_path` | Retrieves file content using project-relative paths, with truncation options for large files |
| `create_new_file` | Creates new files within the project, auto-creating parent directories |
| `replace_text_in_file` | Flexible find-and-replace with regex, case-sensitivity, and replace-all options |
| `reformat_file` | Applies IDE code formatting rules to specified files |
| `open_file_in_editor` | Opens specified files in the IDE editor |
| `get_all_open_file_paths` | Returns paths of all currently open editor files |
| `list_directory_tree` | Provides tree-format directory representation (like `tree` command) |

##### Search & Navigation (4 tools)

| Tool | Description |
|------|-------------|
| `find_files_by_glob` | Searches files matching glob patterns recursively |
| `find_files_by_name_keyword` | Fast file search by name substring (index-based) |
| `search_in_files_by_text` | Full-text search across project files using IntelliJ's engine |
| `search_in_files_by_regex` | Regex pattern search across project files |

##### Code Intelligence (2 tools)

| Tool | Description |
|------|-------------|
| `get_symbol_info` | Retrieves symbol documentation and declaration at position |
| `get_file_problems` | Analyzes file for errors/warnings using IntelliJ inspections |

##### Refactoring (1 tool)

| Tool | Description |
|------|-------------|
| `rename_refactoring` | Context-aware symbol renaming that updates all project references |

##### Project Structure (3 tools)

| Tool | Description |
|------|-------------|
| `get_project_dependencies` | Returns all project library dependencies |
| `get_project_modules` | Lists all modules with their types |
| `get_repositories` | Retrieves VCS roots for multi-repo projects |

##### Execution (3 tools)

| Tool | Description |
|------|-------------|
| `execute_run_configuration` | Runs IDE run configurations with timeout control |
| `get_run_configurations` | Lists available run configurations with details |
| `execute_terminal_command` | Executes shell commands in integrated terminal |

---

#### Gap Analysis: What's Missing from Built-in MCP

| Category | Missing Capability | Value for AI Agents |
|----------|-------------------|---------------------|
| **Navigation** | `find_usages` - Semantic usage search | Find all references to a symbol (not just text matches) |
| **Navigation** | `go_to_definition` - Jump to declaration | Navigate to where symbol is defined |
| **Navigation** | `type_hierarchy` - Class inheritance tree | Understand class relationships |
| **Navigation** | `call_hierarchy` - Method call graph | Trace method callers/callees |
| **Navigation** | `find_implementations` - Interface implementors | Find concrete implementations |
| **Refactoring** | `extract_method` - Extract code to method | Safe code extraction with parameter detection |
| **Refactoring** | `extract_variable` - Extract to variable | Introduce explaining variables |
| **Refactoring** | `inline` - Inline variable/method | Remove indirection |
| **Refactoring** | `safe_delete` - Delete with usage check | Ensure nothing breaks |
| **Refactoring** | `move_element` - Move class/method | Reorganize code structure |
| **Intelligence** | `get_completions` - Code completion | Get IDE's completion suggestions |
| **Intelligence** | `get_quick_fixes` - Available fixes | Get suggested fixes for problems |
| **Intelligence** | `apply_quick_fix` - Apply a fix | Automatically apply IDE suggestions |
| **Structure** | `get_file_structure` - PSI tree | Classes, methods, fields in a file |

**Key Insight**: The built-in MCP provides excellent **file-level** operations but lacks **semantic code intelligence** that leverages IntelliJ's indexes.

---

#### Official Kotlin SDK ([modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk))

- Kotlin Multiplatform implementation (JVM, WebAssembly, native)
- Supports **STDIO, SSE, and WebSocket** transports
- Maven Central: `io.modelcontextprotocol:kotlin-sdk`
- Requires Kotlin 2.2+, JVM 11+
- Ktor integration for HTTP transports

---

## Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     AI Coding Agents                             │
│         (Claude, Cursor, VS Code, Windsurf, etc.)               │
└───────────┬─────────────────────────────────┬───────────────────┘
            │                                 │
            │ HTTP POST/GET                   │ HTTP POST/GET
            │ (JSON-RPC 2.0)                  │ (JSON-RPC 2.0)
            ▼                                 ▼
┌───────────────────────┐         ┌───────────────────────────────┐
│   IntelliJ Instance 1 │         │      IntelliJ Instance 2      │
│   (port 8081)         │         │      (port 8082)              │
└───────────┬───────────┘         └───────────────┬───────────────┘
            │                                     │
            ▼                                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                    MCP Server Layer (per IDE instance)           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              MCP Transport Handler                        │   │
│  │    Primary: Streamable HTTP (port per instance)          │   │
│  │    Fallback: STDIO (single-instance only)                │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Tool Registry & Router                       │   │
│  │    (Dispatches tool calls to appropriate handlers)        │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Command History Service                      │   │
│  │        (Logs all commands for GUI display)               │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Tool Handlers                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │ Navigation   │  │ Refactoring  │  │ Code Intelligence    │   │
│  │ Tools        │  │ Tools        │  │ Tools                │   │
│  │              │  │              │  │                      │   │
│  │ - find_usages│  │ - rename     │  │ - get_completions    │   │
│  │ - go_to_def  │  │ - extract    │  │ - get_inspections    │   │
│  │ - type_hier  │  │ - inline     │  │ - get_quick_fixes    │   │
│  │ - call_hier  │  │ - safe_delete│  │ - get_symbol_info    │   │
│  └──────────────┘  └──────────────┘  └──────────────────────┘   │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                IntelliJ Platform APIs                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │ PSI          │  │ Indexes      │  │ Refactoring          │   │
│  │ (Code Model) │  │ (Fast Search)│  │ (Safe Transforms)    │   │
│  └──────────────┘  └──────────────┘  └──────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|---------------|
| **MCP Transport Handler** | Manages Streamable HTTP server (Ktor/Netty), session management, JSON-RPC parsing |
| **Port Manager** | Auto-assigns unique ports per IDE instance, persists port registry, handles conflicts |
| **Tool Registry** | Maintains list of available tools, routes requests to handlers |
| **Command History Service** | Logs all MCP commands with timestamps, parameters, and results |
| **Tool Handlers** | Implement specific IDE operations using IntelliJ Platform APIs |
| **GUI Tool Window** | Displays command history, server status, and connection URL |

### Multi-Instance Support

Each IntelliJ IDE instance runs its own MCP server:

| Aspect | Implementation |
|--------|----------------|
| **Port Assignment** | Auto-assign from range 8080-8180; persist in `~/.mcp-jetbrains/ports.json` |
| **Port Discovery** | Clients read port registry file or use IDE-specific env var |
| **Session Management** | Each connection gets unique `Mcp-Session-Id` (UUID) |
| **Instance Identification** | Server returns project name/path in `initialize` response |
| **Conflict Resolution** | If port in use, try next available; notify user via notification |

**Port Registry File** (`~/.mcp-jetbrains/ports.json`):
```json
{
  "instances": [
    {
      "port": 8081,
      "projectPath": "/Users/dev/my-project",
      "projectName": "my-project",
      "pid": 12345,
      "startedAt": "2025-01-24T10:30:00Z"
    },
    {
      "port": 8082,
      "projectPath": "/Users/dev/other-project",
      "projectName": "other-project",
      "pid": 12346,
      "startedAt": "2025-01-24T11:00:00Z"
    }
  ]
}
```

### Threading Model

- **MCP Server**: Runs on background threads; must use `ReadAction` for PSI access
- **Tool Execution**: Must check `DumbService.isDumb()` before accessing indexes
- **GUI Updates**: Must use `ApplicationManager.getApplication().invokeLater()` for EDT
- **Write Operations**: Must use `WriteCommandAction.runWriteCommandAction()` for PSI modifications

---

## MCP Server Features

### Tool Categories

#### 1. Navigation Tools

| Tool | Description | Input | Output |
|------|-------------|-------|--------|
| `find_usages` | Find all usages of a symbol | `file`, `line`, `column` or `symbolName` | List of usage locations with context |
| `go_to_definition` | Navigate to symbol definition | `file`, `line`, `column` | Definition location with preview |
| `type_hierarchy` | Get type hierarchy for a class | `file`, `line`, `column` or `className` | Tree of supertypes and subtypes |
| `call_hierarchy` | Get call hierarchy for a method | `file`, `line`, `column`, `direction` (callers/callees) | Tree of calling/called methods |
| `find_implementations` | Find implementations of interface/abstract | `file`, `line`, `column` | List of implementing classes/methods |

#### 2. Refactoring Tools

| Tool | Description | Input | Output |
|------|-------------|-------|--------|
| `rename_symbol` | Rename a symbol across the project | `file`, `line`, `column`, `newName` | Success/failure with affected files |
| `extract_method` | Extract selection to new method | `file`, `startLine`, `endLine`, `methodName` | New method location |
| `extract_variable` | Extract expression to variable | `file`, `line`, `column`, `variableName` | Variable declaration location |
| `inline` | Inline variable or method | `file`, `line`, `column` | Success/failure |
| `safe_delete` | Safely delete unused element | `file`, `line`, `column` | Usages blocking deletion or success |
| `move_element` | Move class/method to new location | `file`, `line`, `column`, `targetFile` | New location |

#### 3. Code Intelligence Tools

| Tool | Description | Input | Output |
|------|-------------|-------|--------|
| `get_completions` | Get code completions at position | `file`, `line`, `column` | List of completion items |
| `get_inspections` | Run inspections on file/selection | `file`, `startLine?`, `endLine?` | List of problems with severity |
| `get_quick_fixes` | Get available quick fixes | `file`, `line`, `column` | List of applicable fixes |
| `apply_quick_fix` | Apply a specific quick fix | `file`, `line`, `column`, `fixId` | Modified code |
| `get_symbol_info` | Get documentation for symbol | `file`, `line`, `column` | Symbol info, type, documentation |

#### 4. Project Structure Tools

| Tool | Description | Input | Output |
|------|-------------|-------|--------|
| `get_project_structure` | Get project module structure | - | Module tree with source roots |
| `get_file_structure` | Get PSI structure of file | `file` | Tree of classes, methods, fields |
| `get_dependencies` | Get project dependencies | - | List of libraries and versions |
| `get_index_status` | Check if indexes are ready | - | Dumb mode status |

### Resource Providers

| Resource | URI Pattern | Description |
|----------|-------------|-------------|
| `project://structure` | `project://structure` | Current project module structure |
| `file://content/{path}` | `file://content/src/Main.kt` | File content with metadata |
| `symbol://info/{fqn}` | `symbol://info/com.example.MyClass` | Symbol information |
| `index://status` | `index://status` | Indexing status |

### Tool Definition Schema

Each tool follows the MCP tool definition format:

```json
{
  "name": "find_usages",
  "description": "Find all usages of a symbol in the project using IntelliJ's index",
  "inputSchema": {
    "type": "object",
    "properties": {
      "file": {
        "type": "string",
        "description": "Path to the file (relative to project root)"
      },
      "line": {
        "type": "integer",
        "description": "1-based line number"
      },
      "column": {
        "type": "integer",
        "description": "1-based column number"
      }
    },
    "required": ["file", "line", "column"]
  }
}
```

---

## GUI Requirements

### Command History Tool Window

#### Purpose
Provide visibility into MCP commands executed by AI agents, enabling developers to:
- Monitor agent activity
- Debug agent behavior
- Review changes before they're made
- Audit refactoring operations

#### UI Components

```
┌─────────────────────────────────────────────────────────────────┐
│ MCP Server                                          [⚙] [↻] [📋] │
├─────────────────────────────────────────────────────────────────┤
│ Status: ● Running                                                │
│ URL: http://localhost:8081/mcp                      [Copy URL]   │
│ Project: my-project | Sessions: 2 active                        │
├─────────────────────────────────────────────────────────────────┤
│ Command History                                    [🗑 Clear]    │
├─────────────────────────────────────────────────────────────────┤
│ ▼ 14:32:15 | find_usages                              [SUCCESS] │
│   └─ Parameters: file=src/Main.kt, line=42, column=12           │
│   └─ Result: 5 usages found                                     │
├─────────────────────────────────────────────────────────────────┤
│ ▼ 14:32:18 | rename_symbol                            [SUCCESS] │
│   └─ Parameters: file=src/Main.kt, line=42, newName=fetchData   │
│   └─ Result: Renamed 'getData' to 'fetchData' in 3 files        │
├─────────────────────────────────────────────────────────────────┤
│ ▶ 14:32:20 | type_hierarchy                           [SUCCESS] │
├─────────────────────────────────────────────────────────────────┤
│ ▶ 14:32:25 | get_completions                          [ERROR]   │
│   └─ Error: Index not ready (dumb mode)                         │
└─────────────────────────────────────────────────────────────────┘
```

#### Features

1. **Server Status Indicator**
   - Shows whether MCP server is running
   - Displays transport type and connection info
   - Quick enable/disable toggle

2. **Command List**
   - Chronologically sorted (newest first)
   - Collapsible entries showing parameters and results
   - Color-coded status (success=green, error=red, pending=yellow)
   - Timestamp for each command

3. **Command Details Panel**
   - Full JSON request/response view
   - Affected files list (for refactoring)
   - Execution duration
   - Copy to clipboard functionality

4. **Filtering & Search**
   - Filter by tool name
   - Filter by status (success/error)
   - Search within parameters/results

5. **Actions**
   - Clear history
   - Export to file (JSON/CSV)
   - Re-execute command (for debugging)

#### Settings

Accessible via the settings gear icon:

| Setting | Default | Description |
|---------|---------|-------------|
| Max History Size | 100 | Maximum commands to keep in memory |
| Auto-scroll | true | Scroll to newest command |
| Show Timestamps | true | Display command timestamps |
| Confirmation Required | true | Require confirmation for write operations |
| Log to File | false | Persist history to disk |

---

## Technical Requirements

### Platform Requirements

| Requirement | Value |
|-------------|-------|
| IntelliJ Platform Version | 2024.3+ (platformSinceBuild = 243) |
| JVM Version | 21 |
| Kotlin Version | 2.1+ |
| Gradle Version | 9.0 |

### Dependencies

```kotlin
// build.gradle.kts
dependencies {
    // MCP Kotlin SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpVersion")

    // Ktor (for SSE transport, if needed)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}
```

### IntelliJ Platform Modules

Required `plugin.xml` dependencies:

```xml
<depends>com.intellij.modules.platform</depends>
<depends optional="true" config-file="java-features.xml">com.intellij.modules.java</depends>
<depends optional="true" config-file="kotlin-features.xml">org.jetbrains.kotlin</depends>
```

### Threading Requirements

| Operation Type | Thread Requirement |
|---------------|-------------------|
| PSI Read | `ReadAction.compute { }` or background thread with read lock |
| PSI Write | `WriteCommandAction.runWriteCommandAction()` on EDT |
| Index Access | Check `DumbService.isDumb()` first |
| GUI Update | `ApplicationManager.getApplication().invokeLater()` |
| MCP Communication | Background thread (non-blocking) |

### Error Handling

1. **Protocol Errors**: Return JSON-RPC error codes
   - `-32700`: Parse error
   - `-32600`: Invalid request
   - `-32601`: Method not found
   - `-32602`: Invalid params
   - `-32603`: Internal error

2. **Tool Errors**: Return result with `isError: true`
   - Index not ready (dumb mode)
   - File not found
   - Symbol not found
   - Refactoring conflict

3. **Recovery**: All operations should be atomic or support undo

### Security Considerations

1. **File Access**: Only allow access to project files
2. **Command Validation**: Validate all input parameters
3. **Confirmation Mode**: Require user confirmation for write operations (configurable)
4. **Rate Limiting**: Prevent DoS from rapid requests
5. **Audit Logging**: Log all operations for review

---

## Implementation Phases

### Phase 1: Foundation (MVP)

**Goals**:
- Streamable HTTP transport with multi-instance support
- Core tool framework and registry
- Basic GUI with server status and URL

**Deliverables**:
- [ ] MCP server service with **Streamable HTTP transport** (Ktor/Netty)
- [ ] Port manager with auto-assignment and registry file
- [ ] Session management with `Mcp-Session-Id`
- [ ] Tool registry and JSON-RPC routing
- [ ] 3 navigation tools: `find_usages`, `go_to_definition`, `get_symbol_info`
- [ ] Basic tool window showing server URL and status
- [ ] Unit tests for core functionality

### Phase 2: Navigation & Intelligence

**Goals**:
- Complete navigation tool suite
- Code intelligence tools
- Command history GUI

**Deliverables**:
- [ ] `type_hierarchy`, `call_hierarchy`, `find_implementations` tools
- [ ] `get_completions`, `get_quick_fixes`, `apply_quick_fix` tools
- [ ] `get_file_structure` tool (PSI tree)
- [ ] Full command history UI with filtering and search
- [ ] Resource providers for project structure
- [ ] Integration tests

### Phase 3: Refactoring Operations

**Goals**:
- Safe refactoring tools (beyond built-in rename)
- User confirmation system
- Advanced error handling

**Deliverables**:
- [ ] `extract_method`, `extract_variable` tools
- [ ] `inline`, `safe_delete`, `move_element` tools
- [ ] Confirmation dialog for write operations
- [ ] Undo/redo integration
- [ ] Comprehensive error handling
- [ ] Refactoring preview (show changes before applying)

### Phase 4: Polish & Extensions

**Goals**:
- STDIO fallback for compatibility
- Performance optimization
- Extension point for custom tools

**Deliverables**:
- [ ] STDIO transport option (fallback for single-instance use)
- [ ] Performance profiling and optimization
- [ ] Extension point for third-party tools (`mcpTool` extension point)
- [ ] Documentation and examples
- [ ] Client configuration generator (for Claude, Cursor, VS Code)
- [ ] Marketplace preparation

---

## References

### Model Context Protocol

- [MCP Specification (2025-03-26)](https://modelcontextprotocol.io/specification/2025-03-26)
- [MCP Transports - Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [Why MCP Deprecated SSE for Streamable HTTP](https://blog.fka.dev/blog/2025-06-06-why-mcp-deprecated-sse-and-go-with-streamable-http/)
- [MCP Tools API](https://modelcontextprotocol.io/specification/2024-11-05/server/tools)
- [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [JSON-RPC in MCP](https://milvus.io/ai-quick-reference/how-is-jsonrpc-used-in-the-model-context-protocol)

### IntelliJ Platform SDK

- [IntelliJ Platform SDK Documentation](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [PSI (Program Structure Interface)](https://plugins.jetbrains.com/docs/intellij/psi.html)
- [Indexing and PSI Stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)
- [Rename Refactoring](https://plugins.jetbrains.com/docs/intellij/rename-refactoring.html)
- [Safe Delete Refactoring](https://plugins.jetbrains.com/docs/intellij/safe-delete-refactoring.html)
- [Find Usages](https://plugins.jetbrains.com/docs/intellij/find-usages.html)

### JetBrains MCP Implementation

- [JetBrains MCP Server Plugin](https://github.com/JetBrains/mcp-server-plugin) (deprecated)
- [Built-in MCP Server Documentation](https://www.jetbrains.com/help/idea/mcp-server.html)
- [IntelliJ IDEA 2025.1 MCP Blog Post](https://blog.jetbrains.com/idea/2025/05/intellij-idea-2025-1-model-context-protocol/)

### Related Projects

- [JetBrains MCP Kotlin SDK](https://github.com/JetBrains/mcp-kotlin-sdk)
- [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

---

## Appendix A: Tool Response Examples

### find_usages Response

```json
{
  "content": [
    {
      "type": "text",
      "text": "Found 5 usages of 'getData'"
    }
  ],
  "usages": [
    {
      "file": "src/main/kotlin/UserService.kt",
      "line": 45,
      "column": 12,
      "context": "val result = getData(userId)",
      "type": "METHOD_CALL"
    },
    {
      "file": "src/test/kotlin/UserServiceTest.kt",
      "line": 23,
      "column": 20,
      "context": "verify { getData(any()) }",
      "type": "METHOD_CALL"
    }
  ]
}
```

### type_hierarchy Response

```json
{
  "content": [
    {
      "type": "text",
      "text": "Type hierarchy for 'UserRepository'"
    }
  ],
  "hierarchy": {
    "element": {
      "name": "UserRepository",
      "file": "src/main/kotlin/UserRepository.kt",
      "kind": "CLASS"
    },
    "supertypes": [
      {
        "name": "Repository<User>",
        "file": "src/main/kotlin/Repository.kt"
      },
      {
        "name": "Any",
        "file": null
      }
    ],
    "subtypes": [
      {
        "name": "CachedUserRepository",
        "file": "src/main/kotlin/CachedUserRepository.kt"
      }
    ]
  }
}
```

### rename_symbol Response

```json
{
  "content": [
    {
      "type": "text",
      "text": "Successfully renamed 'getData' to 'fetchData' in 3 files"
    }
  ],
  "isError": false,
  "affectedFiles": [
    "src/main/kotlin/UserService.kt",
    "src/main/kotlin/DataProvider.kt",
    "src/test/kotlin/UserServiceTest.kt"
  ],
  "changesCount": 7
}
```

---

## Appendix B: Extension Point Schema

For third-party plugins to add custom MCP tools:

```xml
<!-- plugin.xml -->
<extensionPoints>
    <extensionPoint
        name="mcpTool"
        interface="com.github.hechtcarmel.jetbrainsindexmcpplugin.mcp.McpToolProvider"
        dynamic="true"/>
</extensionPoints>
```

```kotlin
// McpToolProvider.kt
interface McpToolProvider {
    val name: String
    val description: String
    val inputSchema: JsonSchema

    suspend fun execute(
        project: Project,
        arguments: JsonObject
    ): McpToolResult
}
```

---

*Document Version: 1.1*
*Last Updated: 2025-01-24*
*Author: Generated with Claude Code assistance*

## Changelog

### v1.1
- Changed primary transport from STDIO to **Streamable HTTP** for multi-instance support
- Added comprehensive **Gap Analysis** comparing with built-in JetBrains MCP
- Added **Multi-Instance Support** section with port registry design
- Updated architecture diagram to show multiple IDE instances
- Updated implementation phases to prioritize HTTP transport

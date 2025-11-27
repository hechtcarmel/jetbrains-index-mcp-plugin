<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IntelliJ Index MCP Plugin Changelog

## [1.0.0] - 2025-11-27

### Added

#### MCP Server Infrastructure
- HTTP+SSE transport on IDE's built-in web server
    - SSE endpoint: `GET /index-mcp/sse`
    - JSON-RPC endpoint: `POST /index-mcp`
- JSON-RPC 2.0 protocol implementation
- Multi-project support with automatic project resolution
- `project_path` parameter for explicit project targeting

#### Navigation Tools (5 tools)
- `ide_find_references` - Find all usages of a symbol across the project
- `ide_find_definition` - Navigate to symbol definition location
- `ide_type_hierarchy` - Get class/interface type hierarchy
- `ide_call_hierarchy` - Get method caller/callee hierarchy
- `ide_find_implementations` - Find interface/abstract implementations

#### Refactoring Tools (6 tools)
- `ide_refactor_rename` - Rename symbols with reference updates
- `ide_refactor_extract_method` - Extract code to new method
- `ide_refactor_extract_variable` - Extract expression to variable
- `ide_refactor_inline` - Inline variable or method
- `ide_refactor_safe_delete` - Safely delete unused elements
- `ide_refactor_move` - Move elements to different files

#### Code Intelligence Tools (5 tools)
- `ide_code_completions` - Get code completion suggestions
- `ide_analyze_code` - Run code inspections on file/range
- `ide_list_quick_fixes` - Get available quick fixes at position
- `ide_apply_quick_fix` - Apply a specific quick fix
- `ide_inspect_symbol` - Get symbol information and documentation

#### Project Structure Tools (4 tools)
- `ide_project_structure` - Get project module tree
- `ide_file_structure` - Get file's class/method/field structure
- `ide_list_dependencies` - Get project library dependencies
- `ide_index_status` - Check IDE indexing status (dumb/smart mode)

#### MCP Resources (4 resources)
- `project://structure` - Project structure as JSON
- `file://content/{path}` - File content with metadata
- `symbol://info/{fqn}` - Symbol information by fully qualified name
- `index://status` - IDE indexing status

#### User Interface
- Tool window with server status and URL display
- Command history panel with chronological listing
- Status indicators (success=green, error=red, pending=yellow)
- Filtering by tool name and status
- Search within command history
- JSON viewer for request/response details
- Export history to JSON/CSV formats
- Clear history functionality

#### Client Configuration Generator
- One-click configuration for Claude Code CLI
- Copy-to-clipboard configs for:
    - Claude Desktop
    - Cursor
    - VS Code
    - Windsurf

#### Settings
- Maximum history size (default: 100)
- Auto-scroll toggle (default: enabled)
- Timestamp display toggle (default: enabled)
- Write operation confirmation toggle (default: enabled)

### Technical Details
- **Platform**: IntelliJ IDEA 2024.3+ (build 243+)
- **Language**: Kotlin 2.1+
- **Protocol**: MCP Specification 2024-11-05
- **Runtime**: JVM 21
- **Transport**: HTTP+SSE with JSON-RPC 2.0

---


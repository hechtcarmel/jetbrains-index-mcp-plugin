<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Index MCP Server Changelog

## [Unreleased]

## [1.3.0] - 2025-11-28

### Changed
- **BREAKING**: Reduced tool count from 13 to 9 for a more focused API
- Refactoring tools now limited to rename and safe delete

### Removed
- `ide_refactor_extract_method` - Complex refactoring removed for reliability
- `ide_refactor_extract_variable` - Complex refactoring removed for reliability
- `ide_refactor_inline` - Complex refactoring removed for reliability
- `ide_refactor_move` - Complex refactoring removed for reliability

---

## [1.2.0] - 2025-11-27

### Fixed
- Type hierarchy now shows supertypes even when PSI type resolution fails
- Call hierarchy now finds callers through interface/parent class references
- Call hierarchy handles unresolved method calls and parameter types gracefully

### Changed
- Extracted shared `findClassByName()` utility to `AbstractMcpTool` base class
- Improved error messages to include project name

## [1.1.0] - 2025-11-27

### Changed
- **BREAKING**: Reduced tool count from 20 to 13 for a more focused, reliable API
- Merged `ide_analyze_code` and `ide_list_quick_fixes` into new `ide_diagnostics` tool
  - Returns both code problems and available intentions in a single response
  - More efficient than making two separate calls

### Removed
- `ide_project_structure` - Functionality available through other IDE tools
- `ide_file_structure` - Functionality available through other IDE tools
- `ide_list_dependencies` - Functionality available through other IDE tools
- `ide_inspect_symbol` - Limited usefulness in practice
- `ide_code_completions` - Limited usefulness in practice
- `ide_analyze_code` - Merged into `ide_diagnostics`
- `ide_list_quick_fixes` - Merged into `ide_diagnostics`
- `ide_apply_quick_fix` - Removed due to EDT threading issues

### Added
- `ide_diagnostics` - New unified tool for code analysis
  - Returns problems with severity (ERROR, WARNING, WEAK_WARNING, INFO)
  - Returns available intentions/quick fixes at specified position
  - Supports optional line range filtering for problems

---

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

#### Refactoring Tools (2 tools)
- `ide_refactor_rename` - Rename symbols with reference updates
- `ide_refactor_safe_delete` - Safely delete unused elements

#### Code Intelligence Tools (1 tool)
- `ide_diagnostics` - Analyze code for problems and available intentions

#### Project Structure Tools (1 tool)
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
- **Platform**: IntelliJ IDEA 2025.1+ (build 251+)
- **Language**: Kotlin 2.1+
- **Protocol**: MCP Specification 2024-11-05
- **Runtime**: JVM 21
- **Transport**: HTTP+SSE with JSON-RPC 2.0

---


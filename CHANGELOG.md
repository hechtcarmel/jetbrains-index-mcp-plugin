<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Index MCP Server Changelog

## [Unreleased]

## [3.2.0] - 2026-01-23

### Added
- **New tool: `ide_file_structure`** - Get hierarchical structure of source files (classes, methods, fields)
  - Supports: Java, Kotlin, Python
  - **Note**: Disabled by default - enable in Settings > Index MCP Server when needed

### Changed
- **Enhanced: `ide_find_definition`** - Added `fullElementPreview` parameter for complete PSI element preview

## [3.1.0] - 2026-01-07

### Added
- **Codex CLI install command** - "Install Now" now supports Codex CLI with remove-then-add reinstall flow

## [3.0.1] - 2025-12-28

### Fixed
- **Claude Code install removes legacy server name** - Install command now also removes `jetbrains-index-mcp` (v1.x name) to clean up after upgrades
- **Agent rule uses IDE-specific name** - "Copy rule" now uses the correct IDE-specific server name (e.g., `intellij-index`, `pycharm-index`) instead of hardcoded `jetbrains-index`

## [3.0.0] - 2025-12-23

### Fixed
- **MCP spec compliance** - `notifications/initialized` now handled correctly per MCP specification
  - Method renamed from `initialized` to `notifications/initialized` (per spec)
  - Notifications no longer receive a response (spec: "receiver MUST NOT send a response")

### Breaking
- **Claude Code transport type** - Changed `--transport http` to `--transport sse` in generated install commands

## [2.0.0] - 2025-12-15

### Added
- **Configurable server port** with IDE-specific defaults (e.g., IntelliJ: 29170, PyCharm: 29172)
- **IDE-specific server names** (e.g., `intellij-index`, `pycharm-index`) to run multiple IDEs simultaneously
- **Port conflict detection** with error notification and settings link
- **Settings shortcut** - "Change port, disable tools" link in toolbar

### Changed
- **Breaking**: Migrated to custom Ktor CIO server - update MCP client configs with new port/name
- Server URL no longer depends on IDE's built-in server port (was 63342)

## [1.12.1] - 2025-12-10

### Changed
- Replace `localhost` with `127.0.0.1` in server URLs for improved connection reliability

## [1.12.0] - 2025-12-09

### Added
- **Tool enable/disable settings** - Disable individual MCP tools from Settings > Index MCP Server (disabled tools are not exposed via `tools/list`)
- **Settings button in tool window** - Gear icon in toolbar opens plugin settings directly

## [1.11.0] - 2025-12-07

### Added
- **Rust Language Support** - Full support for RustRover, IntelliJ IDEA Ultimate with Rust plugin, and CLion

### Changed
- **Simplified tool descriptions** - Streamlined descriptions across navigation, refactoring, and intelligence tools for improved clarity and consistency

## [1.10.1] - 2025-12-07

### Removed
- **Auto-scroll setting** - Removed the "Auto-scroll to new commands" setting from plugin preferences

## [1.10.0] - 2025-12-07

### Added
- **PHP Language Support** - Full support for PhpStorm and IntelliJ IDEA with PHP plugin

## [1.9.1] - 2025-12-06

### Changed
- **Rider IDE excluded** - Plugin is now explicitly incompatible with Rider IDE (uses ReSharper backend which is incompatible with IntelliJ PSI APIs)
- **Documentation updated** - Clarified IDE compatibility: fully tested (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Android Studio) vs untested (PhpStorm, RubyMine, CLion, DataGrip)

## [1.9.0] - 2025-12-04

### Added
- **Full SSE transport support** - Responses are now sent via SSE `message` events per MCP spec (2024-11-05)
- **MCP Inspector compatibility** - Works correctly with `npx @modelcontextprotocol/inspector` in SSE mode
- **Dual transport support** - Supports both SSE transport and Streamable HTTP transport simultaneously

## [1.8.0] - 2025-12-03

### Added
- **Gemini CLI Support** - Added configuration generator for Gemini CLI (uses mcp-remote bridge)
- **Generic MCP Configurations** - New "Generic MCP Config" section in install popup
  - **Standard SSE** - For MCP clients with native SSE transport support
  - **Via mcp-remote** - For MCP clients without SSE support (uses npx mcp-remote bridge)
- `generateInstallCommand()` method for clients that support direct CLI installation
- `generateStandardSseConfig()` and `generateMcpRemoteConfig()` utility methods
- `getInstallableClients()` and `getCopyableClients()` methods for flexible client categorization

### Changed
- Renamed "Claude Code (CLI)" to "Claude Code" for consistency
- Install Now section now dynamically loads installable clients (only those with CLI support)
- Client type enum now includes `supportsInstallCommand` flag for extensibility

### Removed
- **VS Code configuration** - Removed VS Code-specific MCP configuration (use Generic MCP Config instead)
- **Windsurf configuration** - Removed Windsurf-specific configuration (use Generic MCP Config instead)

## [1.7.0] - 2025-12-03

### Added
- **Go Language Support** - Support for GoLand and IntelliJ IDEA with Go plugin
  - `ide_type_hierarchy` - Find Go struct/interface hierarchies and interface implementations
  - `ide_call_hierarchy` - Analyze caller/callee relationships for Go functions and methods
  - `ide_find_symbol` - Search for Go types, functions, methods, and fields
  - `ide_find_definition` - Navigate to Go symbol definitions
  - `ide_find_references` - Find all usages of Go symbols
  - `ide_diagnostics` - Detect Go code problems (errors, warnings, style issues)
  - `ide_refactor_rename` - Rename Go symbols with automatic JSON tag updates
  - Uses reflection-based handlers to avoid compile-time Go plugin dependency

### Changed
- **Universal Rename Tool** - `ide_refactor_rename` now works across ALL languages (Python, JavaScript, TypeScript, Go, etc.), not just Java/Kotlin
  - Uses IntelliJ's platform-level `RenameProcessor` which delegates to language-specific handlers
  - Language-specific name validation using `LanguageNamesValidation` (identifier rules, keyword detection)
  - Tool is now registered as a universal tool, available in all JetBrains IDEs
  - **Fully headless operation** - No popups or dialogs, suitable for autonomous AI agents
  - **Automatic related element renaming** - Getters/setters, overriding methods, test classes, constructor parameters ↔ fields, etc. are automatically renamed in a single atomic operation (no dialog)
  - Constructor parameter and matching field are automatically renamed together (no dialog)
  - Conflict detection before rename execution (returns error instead of showing dialog)

### Not Supported for Go
- `ide_find_implementations` - Go uses implicit interfaces (structural typing). Use `ide_type_hierarchy` with file+line+column instead to find types that satisfy an interface.
- `ide_find_super_methods` - Go has no inheritance. Methods don't override parent methods; Go uses composition via struct embedding.

### Removed
- Removed design specification files (`design.md`, `MultiIDEPlan.md`, `requirements.md`) - consolidated into CLAUDE.md

## [1.6.0] - 2025-12-01

### Added
- `maxResults` parameter for `ide_find_references` tool (default: 100, max: 500) - enables efficient searches in large codebases

### Changed
- **Performance: Optimized symbol search** - Introduced `OptimizedSymbolSearch` using IntelliJ's built-in "Go to Symbol" infrastructure with caching, word index, and prefix matching
- **Performance: Processor-based collection** - Replaced inefficient `.findAll()` calls with streaming `Processor` pattern for early termination and reduced memory usage
- **Performance: Non-blocking coroutines** - Refactored IntelliJ actions to use `Dispatchers.EDT` and platform `readAction` for improved UI responsiveness
- Symbol search handlers (Java, Python, JavaScript/TypeScript) now use the optimized platform-based search

### Fixed
- Language detection in Java handlers now correctly identifies Java/Kotlin elements
- Improved handling of large search result sets with proper early termination

---

## [1.5.0] - 2025-11-29

### Added
- **Multi-IDE Support** - Works with JetBrains IDEs: IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, DataGrip, Android Studio
- **Multi-Language Support** - Navigation tools now work with Java/Kotlin, Python, and JavaScript/TypeScript
- Agent rule tip panel with copy-to-clipboard in tool window
- Non-blocking operations for improved responsiveness

### Changed
- Tools reorganized: 4 universal tools (all IDEs), 5 navigation tools (language-dependent), 2 refactoring tools (Java only)

---

## [1.4.0] - 2025-11-28

### Added
- `ide_find_symbol` - New navigation tool to search for symbols (classes, methods, fields) by name
  - Supports substring and CamelCase fuzzy matching
  - Configurable result limit and library inclusion
- `ide_find_super_methods` - New navigation tool to find the full inheritance hierarchy of overridden methods
  - Shows all parent methods from interfaces and abstract classes
  - Returns hierarchy chain ordered by depth
- "Sync External Changes" setting to handle externally modified files
  - Enable when AI tools modify files and searches miss newly created content
- Reinstall command support for Claude Code CLI configuration

### Changed
- **BREAKING**: Server name changed from `intellij-index-mcp` to `jetbrains-index-mcp`
  - Update your client configurations to use the new server name
- Refactoring operations now execute immediately without confirmation dialog
  - Better suited for AI agent workflows
  - All operations still support undo via Ctrl/Cmd+Z
- Tool count increased from 9 to 11 with new navigation capabilities

### Removed
- **BREAKING**: MCP resources framework completely removed
  - `project://structure` - Use file exploration tools instead
  - `file://content/{path}` - Use standard file reading
  - `symbol://info/{fqn}` - Use `ide_find_symbol` or `ide_find_definition`
  - `index://status` - Use `ide_index_status` tool instead

---

## [1.3.0] - 2025-11-28

### Changed
- Reduced tool count from 13 to 9 for a more focused API
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
- Sync external file changes toggle (default: disabled)

### Technical Details
- **Platform**: IntelliJ IDEA 2025.1+ (build 251+)
- **Language**: Kotlin 2.1+
- **Protocol**: MCP Specification 2024-11-05
- **Runtime**: JVM 21
- **Transport**: HTTP+SSE with JSON-RPC 2.0

---

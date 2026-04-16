# IDE Index MCP Server

![Build](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/29174.svg)](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/29174.svg)](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server)

A JetBrains IDE plugin that exposes an **MCP (Model Context Protocol) server**, enabling AI coding assistants like Claude, Codex, Cursor, and Windsurf to leverage the IDE's powerful indexing and refactoring capabilities.

**Fully tested**: IntelliJ IDEA, PyCharm, WebStorm, GoLand, RustRover, Android Studio, PhpStorm
**May work** (untested): RubyMine, CLion, DataGrip

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/hechtcarmel)

<!-- Plugin description -->
**IDE Index MCP Server** provides AI coding assistants with access to the IDE's powerful code intelligence features through the Model Context Protocol (MCP).

### Features

**Multi-Language Support**
Advanced tools work across multiple languages based on available plugins:
- **Java & Kotlin** - IntelliJ IDEA, Android Studio
- **Python** - PyCharm (all editions), IntelliJ with Python plugin
- **JavaScript & TypeScript** - WebStorm, IntelliJ Ultimate, PhpStorm
- **Go** - GoLand, IntelliJ IDEA Ultimate with Go plugin
- **PHP** - PhpStorm, IntelliJ Ultimate with PHP plugin
- **Rust** - RustRover, IntelliJ IDEA Ultimate with Rust plugin, CLion

**Universal Tools (All JetBrains IDEs)**
- **Find References** - Locate all usages of any symbol across the project
- **Go to Definition** - Navigate to symbol declarations
- **Code Diagnostics** - Access errors, warnings, and quick fixes
- **Index Status** - Check if code intelligence is ready
- **Sync Files** - Force sync VFS/PSI cache after external file changes
- **Build Project** - Trigger IDE build with structured error/warning output (disabled by default)
- **Find Class** - Fast class/interface search by name with camelCase matching
- **Find File** - Fast file search by name using IDE's file index
- **Search Text** - Text search using IDE's pre-built word index
- **Read File** - Read file content by path or qualified name, including library sources (disabled by default)
- **Open File** - Open a file in the editor with optional navigation (disabled by default)
- **Get Active File** - Get currently active editor file(s) with cursor position (disabled by default)

**Extended Tools (Language-Aware)**
These tools activate based on installed language plugins:
- **Type Hierarchy** - Explore class inheritance chains
- **Call Hierarchy** - Trace method/function call relationships
- **Find Implementations** - Discover interface/abstract implementations
- **Symbol Search** - Find by name with fuzzy/camelCase matching (disabled by default)
- **Find Super Methods** - Navigate method override hierarchies
- **File Structure** - View hierarchical file structure like IDE's Structure view (disabled by default)

**Refactoring Tools**
- **Rename Refactoring** - Safe renaming with automatic related element renaming (getters/setters, overriding methods) - works across ALL languages, fully headless
- **Reformat Code** - Reformat using project code style with import optimization (disabled by default)
- **Safe Delete** - Remove code with usage checking (Java/Kotlin only)
- **Java to Kotlin Conversion** - Convert Java to Kotlin using Intellij's built-in converter (Java only)

### Why Use This Plugin?

Unlike simple text-based code analysis, this plugin gives AI assistants access to:
- **True semantic understanding** through the IDE's AST and index
- **Cross-project reference resolution** that works across files and modules
- **Multi-language support** - automatically detects and uses language-specific handlers
- **Safe refactoring operations** with automatic reference updates and undo support

Perfect for AI-assisted development workflows where accuracy and safety matter.
<!-- Plugin description end -->

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Client Configuration](#client-configuration)
- [Available Tools](#available-tools)
- [Multi-Project Support](#multi-project-support)
- [Tool Window](#tool-window)
- [Error Codes](#error-codes)
- [Requirements](#requirements)
- [Contributing](#contributing)

## Installation

### Using the IDE built-in plugin system

<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "IDE Index MCP Server"</kbd> > <kbd>Install</kbd>

### Using JetBrains Marketplace

Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server) and install it by clicking the <kbd>Install to ...</kbd> button.

### Manual Installation

Download the [latest release](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server/versions) and install it manually:
<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Quick Start

1. **Install the plugin** and restart your JetBrains IDE
2. **Open a project** - the MCP server starts automatically with IDE-specific defaults:
   - IntelliJ IDEA: `intellij-index` on port **29170**
   - PyCharm: `pycharm-index` on port **29172**
   - WebStorm: `webstorm-index` on port **29173**
   - Other IDEs: See [IDE-Specific Defaults](#ide-specific-defaults)
3. **Configure your AI assistant** using the "Install on Coding Agents" button (easiest) or manually
4. **Use the tool window** (bottom panel: "Index MCP Server") to copy configuration or monitor commands
5. **Change port** (optional): Click "Change port, disable tools" in the toolbar or go to <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Index MCP Server</kbd>

### Using the "Install on Coding Agents" Button

The easiest way to configure your AI assistant:
1. Open the "Index MCP Server" tool window (bottom panel)
2. Click the prominent **"Install on Coding Agents"** button on the right side of the toolbar
3. A popup appears with two sections:
   - **Install Now** - For Claude Code CLI and Codex CLI: Runs the installation command automatically
   - **Copy Configuration** - For other clients: Copies the JSON config to your clipboard
4. For "Copy Configuration" clients, paste the config into the appropriate config file

## Client Configuration

### Claude Code (CLI)

Use the "Install on Coding Agents" button in the tool window, or run this command (adjust name and port for your IDE):

```bash
# IntelliJ IDEA
claude mcp add --transport http intellij-index http://127.0.0.1:29170/index-mcp/streamable-http --scope user

# PyCharm
claude mcp add --transport http pycharm-index http://127.0.0.1:29172/index-mcp/streamable-http --scope user

# WebStorm
claude mcp add --transport http webstorm-index http://127.0.0.1:29173/index-mcp/streamable-http --scope user
```

Options:
- `--scope user` - Adds globally for all projects
- `--scope project` - Adds to current project only

To remove: `claude mcp remove <server-name>` (e.g., `claude mcp remove intellij-index`)

### Codex CLI

Use the "Install on Coding Agents" button in the tool window, or run this command (adjust name and port for your IDE):

```bash
# IntelliJ IDEA
codex mcp add intellij-index --url http://127.0.0.1:29170/index-mcp/streamable-http

# PyCharm
codex mcp add pycharm-index --url http://127.0.0.1:29172/index-mcp/streamable-http

# WebStorm
codex mcp add webstorm-index --url http://127.0.0.1:29173/index-mcp/streamable-http
```

To remove: `codex mcp remove <server-name>` (e.g., `codex mcp remove intellij-index`)

### Cursor

Add to `.cursor/mcp.json` in your project root or `~/.cursor/mcp.json` globally (adjust name and port for your IDE):

```json
{
  "mcpServers": {
    "intellij-index": {
      "url": "http://127.0.0.1:29170/index-mcp/streamable-http"
    }
  }
}
```

### Windsurf

Add to `~/.codeium/windsurf/mcp_config.json` (adjust name and port for your IDE):

```json
{
  "mcpServers": {
    "intellij-index": {
      "serverUrl": "http://127.0.0.1:29170/index-mcp/streamable-http"
    }
  }
}
```

### VS Code (Generic MCP)

```json
{
  "mcp.servers": {
    "intellij-index": {
      "url": "http://127.0.0.1:29170/index-mcp/streamable-http"
    }
  }
}
```

> **Note**: Replace the server name and port with your IDE's defaults. See [IDE-Specific Defaults](#ide-specific-defaults) below.

### IDE-Specific Defaults

Each JetBrains IDE has a unique default port and server name to allow running multiple IDEs simultaneously without conflicts:

| IDE | Server Name | Default Port |
|-----|-------------|--------------|
| IntelliJ IDEA | `intellij-index` | 29170 |
| Android Studio | `android-studio-index` | 29171 |
| PyCharm | `pycharm-index` | 29172 |
| WebStorm | `webstorm-index` | 29173 |
| GoLand | `goland-index` | 29174 |
| PhpStorm | `phpstorm-index` | 29175 |
| RubyMine | `rubymine-index` | 29176 |
| CLion | `clion-index` | 29177 |
| RustRover | `rustrover-index` | 29178 |
| DataGrip | `datagrip-index` | 29179 |

> **Tip**: Use the "Install on Coding Agents" button in the tool window - it automatically uses the correct server name and port for your IDE.

## Available Tools

The plugin provides **21 MCP tools** organized by availability. Tools marked *(disabled by default)* can be enabled in <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Index MCP Server</kbd>.

### Universal Tools

These tools work in all supported JetBrains IDEs.

| Tool | Description |
|------|-------------|
| `ide_find_references` | Find all references to a symbol across the entire project |
| `ide_find_definition` | Find the definition/declaration location of a symbol |
| `ide_find_class` | Search for classes/interfaces by name with camelCase/substring/wildcard matching |
| `ide_find_file` | Search for files by name using IDE's file index |
| `ide_search_text` | Text search using IDE's pre-built word index with context filtering |
| `ide_diagnostics` | Analyze a file for problems (errors, warnings) and available intentions |
| `ide_index_status` | Check if the IDE is in dumb mode or smart mode |
| `ide_sync_files` | Force sync IDE's virtual file system and PSI cache with external file changes |
| `ide_build_project` | Build project using IDE's build system (JPS, Gradle, Maven) with structured errors *(disabled by default)* |
| `ide_read_file` | Read file content by path or qualified name, including library/jar sources *(disabled by default)* |
| `ide_get_active_file` | Get the currently active file(s) in the editor with cursor position *(disabled by default)* |
| `ide_open_file` | Open a file in the editor with optional line/column navigation *(disabled by default)* |
| `ide_refactor_rename` | Rename a symbol and update all references across the project (all languages) |
| `ide_move_file` | Move a file to a new directory with automatic reference, import, and package updates |
| `ide_reformat_code` | Reformat code using project code style with import optimization *(disabled by default)* |

### Extended Tools (Language-Aware)

These tools activate based on available language plugins:

| Tool | Description | Languages |
|------|-------------|-----------|
| `ide_type_hierarchy` | Get the complete type hierarchy (supertypes and subtypes) | Java, Kotlin, Python, JS/TS, Go, PHP, Rust |
| `ide_call_hierarchy` | Analyze method call relationships (callers or callees) | Java, Kotlin, Python, JS/TS, Go, PHP, Rust |
| `ide_find_implementations` | Find all implementations of an interface or abstract method | Java, Kotlin, Python, JS/TS, PHP, Rust |
| `ide_find_symbol` | Search for symbols (classes, methods, fields) by name with fuzzy/camelCase matching *(disabled by default)* | Java, Kotlin, Python, JS/TS, Go, PHP, Rust |
| `ide_find_super_methods` | Find the full inheritance hierarchy of methods that a method overrides/implements | Java, Kotlin, Python, JS/TS, PHP |
| `ide_file_structure` | Get hierarchical file structure (similar to IDE's Structure view) *(disabled by default)* | Java, Kotlin, Python, JS/TS |

### Java-Specific Refactoring Tools

| Tool | Description |
|------|-------------|
| `ide_convert_java_to_kotlin` | Convert Java files to Kotlin using IntelliJ's built-in converter *(disabled by default, requires Java + Kotlin plugins)* |
| `ide_refactor_safe_delete` | Safely delete an element, checking for usages first (Java/Kotlin only) |

> **Note**: Refactoring tools modify source files. All changes support undo via <kbd>Ctrl/Cmd+Z</kbd>.

### Tool Availability by IDE

**Fully Tested:**

| IDE | Universal | Navigation | Refactoring |
|-----|-----------|------------|-------------|
| IntelliJ IDEA | ✓ 14 tools | ✓ 6 tools | ✓ rename + reformat + safe delete + Java→Kotlin |
| Android Studio | ✓ 14 tools | ✓ 6 tools | ✓ rename + reformat + safe delete + Java→Kotlin |
| PyCharm | ✓ 14 tools | ✓ 6 tools | ✓ rename + reformat |
| WebStorm | ✓ 14 tools | ✓ 6 tools | ✓ rename + reformat |
| GoLand | ✓ 14 tools | ✓ 4 tools | ✓ rename + reformat |
| RustRover | ✓ 14 tools | ✓ 4 tools | ✓ rename + reformat |
| PhpStorm | ✓ 14 tools | ✓ 5 tools | ✓ rename + reformat |

**May Work (Untested):**

| IDE | Universal | Navigation | Refactoring |
|-----|-----------|------------|-------------|
| RubyMine | ✓ 14 tools | - | ✓ rename + reformat |
| CLion | ✓ 14 tools | - | ✓ rename + reformat |
| DataGrip | ✓ 14 tools | - | ✓ rename + reformat |

> **Note**: Navigation tools activate when language plugins are present. GoLand and RustRover have 4 navigation tools (no `ide_find_implementations` or `ide_find_super_methods` due to language semantics). PhpStorm has 5 (no `ide_file_structure`). The rename and reformat tools work across all languages. `ide_convert_java_to_kotlin` is available only in IntelliJ IDEA and Android Studio, requires both Java and Kotlin plugins, and is disabled by default.

For detailed tool documentation with parameters and examples, see [USAGE.md](USAGE.md).

## Multi-Project Support

When multiple projects are open in a single IDE window, you must specify which project to use with the `project_path` parameter:

```json
{
  "name": "ide_find_references",
  "arguments": {
    "project_path": "/Users/dev/myproject",
    "file": "src/Main.kt",
    "line": 10,
    "column": 5
  }
}
```

If `project_path` is omitted:
- **Single project open**: That project is used automatically
- **Multiple projects open**: An error is returned with the list of available projects

### Workspace Projects

The plugin supports **workspace projects** where a single IDE window contains multiple sub-projects as modules with separate content roots. The `project_path` parameter accepts:

- The **workspace root** path
- A **sub-project path** (module content root)
- A **subdirectory** of any open project

When an error occurs, the response returns `available_projects`. By default this includes workspace sub-projects so AI agents can discover valid module content roots. If you want smaller error payloads, switch **Project list in error responses** to **Compact** in plugin settings to return only top-level project roots.

## Tool Window

The plugin adds an "Index MCP Server" tool window (bottom panel) that shows:

- **Server Status**: Running indicator with server URL and port
- **Project Name**: Currently active project
- **Command History**: Log of all MCP tool calls with:
  - Timestamp
  - Tool name
  - Status (Success/Error/Pending)
  - Parameters and results (expandable)
  - Execution duration

### Tool Window Actions

| Action | Description |
|--------|-------------|
| Refresh | Refresh server status and command history |
| Copy URL | Copy the MCP server URL to clipboard |
| Clear History | Clear the command history |
| Export History | Export history to JSON or CSV file |
| **Install on Coding Agents** | Install MCP server on AI assistants (prominent button on right) |

## Error Codes

### JSON-RPC Standard Errors

| Code | Name | Description |
|------|------|-------------|
| -32700 | Parse Error | Failed to parse JSON-RPC request |
| -32600 | Invalid Request | Invalid JSON-RPC request format |
| -32601 | Method Not Found | Unknown method name |
| -32602 | Invalid Params | Invalid or missing parameters |
| -32603 | Internal Error | Unexpected internal error |

### Custom MCP Errors

| Code | Name | Description |
|------|------|-------------|
| -32001 | Index Not Ready | IDE is in dumb mode (indexing in progress) |
| -32002 | File Not Found | Specified file does not exist |
| -32003 | Symbol Not Found | No symbol found at the specified position |
| -32004 | Refactoring Conflict | Refactoring cannot be completed (e.g., name conflict) |

## Settings

Configure the plugin at <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Index MCP Server</kbd>:

| Setting | Default | Description |
|---------|---------|-------------|
| Server Port | IDE-specific | MCP server port (range: 1024-65535, auto-restart on change). See [IDE-Specific Defaults](#ide-specific-defaults) |
| Server Host | `127.0.0.1` | Listening host. Change to `0.0.0.0` for remote/WSL access |
| Max History Size | 100 | Maximum number of commands to keep in history |
| Project List in Error Responses | Expanded | Controls `available_projects` detail for invalid/missing `project_path` errors. `Expanded` includes workspace sub-projects; `Compact` returns only top-level project roots |
| Sync External Changes | false | Sync external file changes before operations (**WARNING: significant performance impact**) |
| Disabled Tools | 7 tools | Per-tool enable/disable toggles. Some tools are disabled by default to keep the tool list focused |

## Requirements

- **JetBrains IDE** 2025.1 or later (any IDE based on IntelliJ Platform)
- **JVM** 21 or later
- **MCP Protocol** 2025-03-26 (primary Streamable HTTP), with 2024-11-05 legacy SSE compatibility

### Supported IDEs

**Fully Tested:**
- IntelliJ IDEA (Community/Ultimate)
- Android Studio
- PyCharm (Community/Professional)
- WebStorm
- GoLand
- RustRover
- PhpStorm

**May Work (Untested):**
- RubyMine
- CLion
- DataGrip

> The plugin uses standard IntelliJ Platform APIs and should work on any IntelliJ-based IDE, but has only been tested on the IDEs listed above.

## Architecture

The plugin runs a **custom embedded Ktor CIO HTTP server** with **dual MCP transports**:

### Streamable HTTP Transport (Primary, MCP 2025-03-26)

```
AI Assistant ──────► POST /index-mcp/streamable-http (initialize or request)
                     ◄── JSON-RPC response or HTTP 202 Accepted
             ──────► POST /index-mcp/streamable-http (follow-up requests/notifications)
                     ◄── JSON-RPC response or HTTP 202 Accepted
```

The plugin uses stateless Streamable HTTP for the primary MCP transport. It does not
issue `Mcp-Session-Id` headers, does not require session resumption, and does not
implement or advertise authentication capabilities.

### Legacy SSE Transport (MCP Inspector, older clients)

```
AI Assistant ──────► GET /index-mcp/sse              (establish SSE stream)
                     ◄── event: endpoint             (receive POST URL with sessionId)
             ──────► POST /index-mcp?sessionId=x     (JSON-RPC requests)
                     ◄── HTTP 202 Accepted
                     ◄── event: message              (JSON-RPC response via SSE)
```

This dual approach:
- **Primary MCP transport** - Streamable HTTP per MCP `2025-03-26`
- **MCP Inspector compatible** - Legacy SSE transport per MCP `2024-11-05`
- **Configurable port** - IDE-specific default port, changeable in settings
- Works with any MCP-compatible client
- Single server instance across all open projects

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `./gradlew test`
5. Submit a pull request

### Development Setup

```bash
# Build the plugin
./gradlew build

# Run IDE with plugin installed
./gradlew runIde

# Run tests
./gradlew test

# Run plugin verification
./gradlew runPluginVerifier
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

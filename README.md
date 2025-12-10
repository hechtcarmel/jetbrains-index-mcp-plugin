# IDE Index MCP Server

![Build](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/29174.svg)](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/29174.svg)](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server)

A JetBrains IDE plugin that exposes an **MCP (Model Context Protocol) server**, enabling AI coding assistants like Claude, Cursor, and Windsurf to leverage the IDE's powerful indexing and refactoring capabilities.

**Fully tested**: IntelliJ IDEA, PyCharm, WebStorm, GoLand, RustRover, Android Studio, PhpStorm
**May work** (untested): RubyMine, CLion, DataGrip

<!-- Plugin description -->
**IDE Index MCP Server** provides AI coding assistants with access to the IDE's powerful code intelligence features through the Model Context Protocol (MCP).

### Features

**Multi-Language Support**
Advanced tools work across multiple languages based on available plugins:
- **Java & Kotlin** - IntelliJ IDEA, Android Studio
- **Python** - PyCharm (all editions), IntelliJ with Python plugin
- **JavaScript & TypeScript** - WebStorm, IntelliJ Ultimate, PhpStorm
- **Go** - GoLand, IntelliJ IDEA Ultimate with Go plugin
- **Rust** - RustRover, IntelliJ IDEA Ultimate with Rust plugin, CLion

**Universal Tools (All JetBrains IDEs)**
- **Find References** - Locate all usages of any symbol across the project
- **Go to Definition** - Navigate to symbol declarations
- **Code Diagnostics** - Access errors, warnings, and quick fixes
- **Index Status** - Check if code intelligence is ready

**Extended Tools (Language-Aware)**
These tools activate based on installed language plugins:
- **Type Hierarchy** - Explore class inheritance chains
- **Call Hierarchy** - Trace method/function call relationships
- **Find Implementations** - Discover interface/abstract implementations
- **Symbol Search** - Find by name with fuzzy/camelCase matching
- **Find Super Methods** - Navigate method override hierarchies

**Refactoring Tools**
- **Rename Refactoring** - Safe renaming with automatic related element renaming (getters/setters, overriding methods) - works across ALL languages, fully headless
- **Safe Delete** - Remove code with usage checking (Java/Kotlin only)

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

Download the [latest release](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/releases/latest) and install it manually:
<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Quick Start

1. **Install the plugin** and restart IntelliJ IDEA
2. **Open a project** - the MCP server starts automatically
3. **Find your IDE port**: <kbd>Settings</kbd> > <kbd>Build, Execution, Deployment</kbd> > <kbd>Debugger</kbd> > <kbd>Built-in Server Port</kbd> (default: 63342)
4. **Configure your AI assistant** with the server URL: `http://127.0.0.1:{PORT}/index-mcp/sse`
5. **Use the tool window** (bottom panel: "Index MCP Server") to copy configuration or monitor commands

### Using the "Install on Coding Agents" Button

The easiest way to configure your AI assistant:
1. Open the "Index MCP Server" tool window (bottom panel)
2. Click the prominent **"Install on Coding Agents"** button on the right side of the toolbar
3. A popup appears with two sections:
   - **Install Now** - For Claude Code CLI: Runs the installation command automatically
   - **Copy Configuration** - For other clients: Copies the JSON config to your clipboard
4. For "Copy Configuration" clients, paste the config into the appropriate config file

## Client Configuration

### Claude Code (CLI)

Run this command in your terminal:

```bash
claude mcp add --transport http jetbrains-index http://127.0.0.1:63342/index-mcp/sse --scope user
```

Options:
- `--scope user` - Adds globally for all projects
- `--scope project` - Adds to current project only

To remove: `claude mcp remove jetbrains-index`

### Cursor

Add to `.cursor/mcp.json` in your project root or `~/.cursor/mcp.json` globally:

```json
{
  "mcpServers": {
    "jetbrains-index": {
      "url": "http://127.0.0.1:63342/index-mcp/sse"
    }
  }
}
```

### Windsurf

Add to `~/.codeium/windsurf/mcp_config.json`:

```json
{
  "mcpServers": {
    "jetbrains-index": {
      "serverUrl": "http://127.0.0.1:63342/index-mcp/sse"
    }
  }
}
```

### VS Code (Generic MCP)

```json
{
  "mcp.servers": {
    "jetbrains-index": {
      "transport": "sse",
      "url": "http://127.0.0.1:63342/index-mcp/sse"
    }
  }
}
```

> **Note**: Replace `63342` with your actual IDE port if different. Check <kbd>Settings</kbd> > <kbd>Debugger</kbd> > <kbd>Built-in Server Port</kbd>.

## Available Tools

The plugin provides MCP tools organized by availability:

### Universal Tools

These tools work in all supported JetBrains IDEs.

| Tool | Description |
|------|-------------|
| `ide_find_references` | Find all references to a symbol across the entire project |
| `ide_find_definition` | Find the definition/declaration location of a symbol |
| `ide_diagnostics` | Analyze a file for problems (errors, warnings) and available intentions |
| `ide_index_status` | Check if the IDE is in dumb mode or smart mode |

### Extended Tools (Language-Aware)

These tools activate based on available language plugins:

| Tool | Description | Languages |
|------|-------------|-----------|
| `ide_type_hierarchy` | Get the complete type hierarchy (supertypes and subtypes) | Java, Kotlin, Python, JS/TS, Go, Rust |
| `ide_call_hierarchy` | Analyze method call relationships (callers or callees) | Java, Kotlin, Python, JS/TS, Go, Rust |
| `ide_find_implementations` | Find all implementations of an interface or abstract method | Java, Kotlin, Python, JS/TS, Go, Rust |
| `ide_find_symbol` | Search for symbols (classes, methods, fields) by name with fuzzy/camelCase matching | Java, Kotlin, Python, JS/TS, Go, Rust |
| `ide_find_super_methods` | Find the full inheritance hierarchy of methods that a method overrides/implements | Java, Kotlin, Python, JS/TS, Go, Rust |

### Refactoring Tools

| Tool | Description | Languages |
|------|-------------|-----------|
| `ide_refactor_rename` | Rename a symbol and update all references | All languages |
| `ide_refactor_safe_delete` | Safely delete an element, checking for usages first | Java/Kotlin only |

> **Note**: Refactoring tools modify source files. All changes support undo via <kbd>Ctrl/Cmd+Z</kbd>.

### Tool Availability by IDE

**Fully Tested:**

| IDE | Universal | Navigation | Refactoring |
|-----|-----------|------------|-------------|
| IntelliJ IDEA | ✓ 4 tools | ✓ 5 tools | ✓ 2 tools (rename + safe delete) |
| Android Studio | ✓ 4 tools | ✓ 5 tools | ✓ 2 tools (rename + safe delete) |
| PyCharm | ✓ 4 tools | ✓ 5 tools | ✓ 1 tool (rename) |
| WebStorm | ✓ 4 tools | ✓ 5 tools | ✓ 1 tool (rename) |
| GoLand | ✓ 4 tools | ✓ 5 tools | ✓ 1 tool (rename) |
| RustRover | ✓ 4 tools | ✓ 5 tools | ✓ 1 tool (rename) |
| PhpStorm | ✓ 4 tools | - | ✓ 1 tool (rename) |

**May Work (Untested):**

| IDE | Universal | Navigation | Refactoring |
|-----|-----------|------------|-------------|
| RubyMine | ✓ 4 tools | - | ✓ 1 tool (rename) |
| CLion | ✓ 4 tools | - | ✓ 1 tool (rename) |
| DataGrip | ✓ 4 tools | - | ✓ 1 tool (rename) |

> **Note**: Navigation tools (type hierarchy, call hierarchy, find implementations, symbol search, find super methods) are available when language plugins are present. The rename tool works across all languages.

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
| Max History Size | 100 | Maximum number of commands to keep in history |
| Sync External Changes | false | Sync external file changes before operations |

## Requirements

- **JetBrains IDE** 2025.1 or later (any IDE based on IntelliJ Platform)
- **JVM** 21 or later
- **MCP Protocol** 2024-11-05

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

The plugin supports **dual MCP transports** on the IDE's built-in web server:

### SSE Transport (MCP Inspector, spec-compliant clients)

```
AI Assistant ──────► GET /index-mcp/sse              (establish SSE stream)
                     ◄── event: endpoint             (receive POST URL with sessionId)
             ──────► POST /index-mcp?sessionId=x     (JSON-RPC requests)
                     ◄── HTTP 202 Accepted
                     ◄── event: message              (JSON-RPC response via SSE)
```

### Streamable HTTP Transport (Claude Code, simple clients)

```
AI Assistant ──────► POST /index-mcp                 (JSON-RPC requests)
                     ◄── JSON-RPC response           (immediate HTTP response)
```

This dual approach:
- **MCP Inspector compatible** - Full SSE transport per MCP spec (2024-11-05)
- **Claude Code compatible** - Streamable HTTP for simple request/response
- Requires no additional ports or processes
- Works with any MCP-compatible client
- Automatically adapts to the IDE's server port

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

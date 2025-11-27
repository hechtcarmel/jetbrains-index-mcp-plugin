# IntelliJ Index MCP Server

![Build](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

An IntelliJ Platform plugin that exposes an **MCP (Model Context Protocol) server**, enabling AI coding assistants like Claude, Cursor, and Windsurf to leverage the IDE's powerful indexing and refactoring capabilities.

<!-- Plugin description -->
**IntelliJ Index MCP Server** provides AI coding assistants with access to IntelliJ IDEA's powerful code intelligence features through the Model Context Protocol (MCP).

### Features

- **Semantic Code Navigation** - Find usages, find definition, type/call hierarchies, find implementations using the IDE's semantic index
- **Safe IDE-Powered Refactoring** - Rename symbols, extract methods/variables, inline, move, and safely delete code with full reference updates
- **Code Diagnostics** - Analyze files for problems, warnings, and available code improvements
- **Project Intelligence** - Query indexing status to check if code intelligence is available

### Why Use This Plugin?

Unlike simple text-based code analysis, this plugin gives AI assistants access to:
- **True semantic understanding** through IntelliJ's AST and index
- **Cross-project reference resolution** that works across files and modules
- **Safe refactoring operations** with automatic reference updates and undo support
- **Real-time code intelligence** including completions and quick fixes

Perfect for AI-assisted development workflows where accuracy and safety matter.
<!-- Plugin description end -->

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Client Configuration](#client-configuration)
- [Available Tools](#available-tools)
- [Available Resources](#available-resources)
- [Multi-Project Support](#multi-project-support)
- [Tool Window](#tool-window)
- [Error Codes](#error-codes)
- [Requirements](#requirements)
- [Contributing](#contributing)

## Installation

### Using the IDE built-in plugin system

<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "IntelliJ Index MCP Server"</kbd> > <kbd>Install</kbd>

### Using JetBrains Marketplace

Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button.

### Manual Installation

Download the [latest release](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/releases/latest) and install it manually:
<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Quick Start

1. **Install the plugin** and restart IntelliJ IDEA
2. **Open a project** - the MCP server starts automatically
3. **Find your IDE port**: <kbd>Settings</kbd> > <kbd>Build, Execution, Deployment</kbd> > <kbd>Debugger</kbd> > <kbd>Built-in Server Port</kbd> (default: 63342)
4. **Configure your AI assistant** with the server URL: `http://localhost:{PORT}/index-mcp/sse`
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
claude mcp add --transport http intellij-index http://localhost:63342/index-mcp/sse --scope user
```

Options:
- `--scope user` - Adds globally for all projects
- `--scope project` - Adds to current project only

To remove: `claude mcp remove intellij-index`

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "intellij-index": {
      "url": "http://localhost:63342/index-mcp/sse"
    }
  }
}
```

### Cursor

Add to `.cursor/mcp.json` in your project root or `~/.cursor/mcp.json` globally:

```json
{
  "mcpServers": {
    "intellij-index": {
      "url": "http://localhost:63342/index-mcp/sse"
    }
  }
}
```

### Windsurf

Add to `~/.codeium/windsurf/mcp_config.json`:

```json
{
  "mcpServers": {
    "intellij-index": {
      "serverUrl": "http://localhost:63342/index-mcp/sse"
    }
  }
}
```

### VS Code (Generic MCP)

```json
{
  "mcp.servers": {
    "intellij-index": {
      "transport": "sse",
      "url": "http://localhost:63342/index-mcp/sse"
    }
  }
}
```

> **Note**: Replace `63342` with your actual IDE port if different. Check <kbd>Settings</kbd> > <kbd>Debugger</kbd> > <kbd>Built-in Server Port</kbd>.

## Available Tools

The plugin provides **13 MCP tools** organized into four categories:

### Navigation Tools

| Tool | Description |
|------|-------------|
| `ide_find_references` | Find all references to a symbol across the entire project |
| `ide_find_definition` | Find the definition/declaration location of a symbol |
| `ide_type_hierarchy` | Get the complete type hierarchy (supertypes and subtypes) |
| `ide_call_hierarchy` | Analyze method call relationships (callers or callees) |
| `ide_find_implementations` | Find all implementations of an interface or abstract method |

### Code Intelligence Tools

| Tool | Description |
|------|-------------|
| `ide_diagnostics` | Analyze a file for problems (errors, warnings) and available intentions/code improvements |

### Project Structure Tools

| Tool | Description |
|------|-------------|
| `ide_index_status` | Check if the IDE is in dumb mode or smart mode |

### Refactoring Tools

| Tool | Description |
|------|-------------|
| `ide_refactor_rename` | Rename a symbol and update all references |
| `ide_refactor_extract_method` | Extract selected code into a new method |
| `ide_refactor_extract_variable` | Extract an expression into a variable |
| `ide_refactor_inline` | Inline a variable or method |
| `ide_refactor_safe_delete` | Safely delete an element, checking for usages first |
| `ide_refactor_move` | Move an element to a different file or package |

> **Note**: Refactoring tools modify source files. All changes support undo via <kbd>Ctrl/Cmd+Z</kbd>.

For detailed tool documentation with parameters and examples, see [USAGE.md](USAGE.md).

## Available Resources

The plugin exposes **4 MCP resources** for querying project state:

| Resource URI | Description |
|--------------|-------------|
| `index://status` | IDE indexing status (dumb/smart mode) |
| `project://structure` | Project module tree with source roots |
| `file://content/{path}` | Read file content by path |
| `symbol://info/{fqn}` | Symbol information by fully qualified name |

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
| Auto-scroll | true | Auto-scroll to new commands in history |
| Show Timestamps | true | Display timestamps in command history |
| Confirm Write Operations | true | Show confirmation dialog for refactoring operations |
| Enable File Logging | false | Log commands to a file |

## Requirements

- **IntelliJ IDEA** 2024.3 or later (Community or Ultimate)
- **JVM** 21 or later
- **MCP Protocol** 2024-11-05

## Architecture

The plugin uses HTTP+SSE (Server-Sent Events) transport on the IDE's built-in web server:

```
AI Assistant ──────► GET /index-mcp/sse     (establish SSE stream)
                     ◄── event: endpoint    (receive POST URL)
             ──────► POST /index-mcp        (JSON-RPC requests)
                     ◄── JSON-RPC response
```

This approach:
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

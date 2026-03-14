# IDE Index MCP Server

![Build](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/29174.svg)](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/29174.svg)](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server)

A JetBrains IDE plugin that adds IDE-backed MCP tools to JetBrains' built-in MCP Server.

Tested on IntelliJ IDEA, PyCharm, WebStorm, GoLand, RustRover, Android Studio, and PhpStorm.

[![Buy Me A Coffee](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/hechtcarmel)

<!-- Plugin description -->
**IDE Index MCP Server** extends JetBrains' native MCP Server with code navigation, search, diagnostics, and refactoring tools powered by PSI and IDE indexes.

### Highlights

- **Native MCP integration**: JetBrains owns transport, sessions, host, and port.
- **Real code intelligence**: Tools use PSI, indexes, and refactoring APIs directly.
- **Stable tool contract**: Existing tool names and `project_path` are preserved.
- **Plugin-side observability**: The tool window still shows status, enabled tools, and command history.
- **Multi-language support**: Language-aware tools activate when the corresponding IDE plugin is available.
<!-- Plugin description end -->

## Requirements

- JetBrains IDE based on the 2025.3 platform or newer
- JetBrains built-in MCP Server available in the IDE
- A project opened in the IDE

JetBrains introduced the built-in MCP Server in 2025.2. This plugin currently targets 2025.3 APIs.

## Installation

Install from JetBrains Marketplace or from disk, then restart the IDE.

- Marketplace: [IDE Index MCP Server](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server)
- Manual install: `Settings/Preferences > Plugins > ⚙ > Install plugin from disk...`

## Quick Start

1. Install the plugin and restart the IDE.
2. Open a project.
3. Enable JetBrains MCP Server in the IDE.
4. Open the `Index MCP Server` tool window to verify native server status and registered tools.
5. Use `Open Built-in MCP Setup` in the tool window if you need JetBrains MCP settings or setup help.
6. Copy the MCP URL exposed by JetBrains MCP Server into your client configuration.

This plugin no longer starts its own HTTP server or owns a custom endpoint path.

## Client Configuration

Use the URL shown by JetBrains MCP Server. Do not hardcode the legacy `/index-mcp/streamable-http` endpoint from older plugin versions.

Examples:

### Claude Code

```bash
claude mcp add jetbrains-index --transport sse <jetbrains-mcp-url> --scope user
```

### Codex CLI

```bash
codex mcp add jetbrains-index --url <jetbrains-mcp-url>
```

### Cursor

```json
{
  "mcpServers": {
    "jetbrains-index": {
      "url": "<jetbrains-mcp-url>"
    }
  }
}
```

### VS Code

```json
{
  "mcp.servers": {
    "jetbrains-index": {
      "url": "<jetbrains-mcp-url>"
    }
  }
}
```

If your client uses another field name, reuse the same JetBrains-provided URL in that field.

## Tool Window

The `Index MCP Server` tool window keeps plugin-owned controls and visibility:

- native MCP server status
- native server URL when exposed by the IDE
- registered tool count
- enabled tool count
- command history

Toolbar actions:

- `Copy Server URL`
- `Open Built-in MCP Setup`
- `Settings`
- `Get Companion Skill`

## Settings

The plugin settings page now controls only plugin-owned behavior:

- maximum command history size
- optional PSI sync before tool execution
- tool enable/disable toggles

It does not manage host, port, transport, session lifecycle, or client configuration generation.

## Available Tools

Universal tools:

- `ide_find_references`
- `ide_find_definition`
- `ide_find_class`
- `ide_find_file`
- `ide_search_text`
- `ide_diagnostics`
- `ide_index_status`
- `ide_sync_files`
- `ide_build_project`
- `ide_read_file`
- `ide_open_file`
- `ide_get_active_file`
- `ide_refactor_rename`
- `ide_reformat_code`

Language-aware tools:

- `ide_type_hierarchy`
- `ide_call_hierarchy`
- `ide_find_implementations`
- `ide_find_symbol`
- `ide_find_super_methods`
- `ide_file_structure`

Java/Kotlin-only refactoring:

- `ide_refactor_safe_delete`

Some tools remain disabled by default and can be enabled in plugin settings.

## Multi-Project Support

Workspace-style projects are still supported.

- `project_path` is preserved as the public tool parameter
- `ProjectResolver` still prefers explicit `project_path`
- when `project_path` is absent, execution falls back to the project from the native MCP call context

## Architecture

Current architecture:

- JetBrains built-in MCP Server owns transport and sessions
- `NativeMcpToolsProvider` registers tools through `com.intellij.mcpServer.mcpToolsProvider`
- `NativeMcpToolAdapter` bridges existing plugin tools to JetBrains native MCP descriptors and results
- `McpServerService` is now a lightweight integration facade for registry initialization and UI status
- command history is recorded in the native adapter layer

## Development

```bash
./gradlew build
./gradlew test
./gradlew runIde
./gradlew runPluginVerifier
```

The project now builds against JetBrains 2025.3 APIs and depends on the bundled `com.intellij.mcpServer` plugin.

## Contributing

Every PR should:

1. Bump `pluginVersion` in `gradle.properties` when behavior changes.
2. Update `CHANGELOG.md`.
3. Add or update tests.
4. Update `README.md` and `CLAUDE.md` when architecture or workflow changes.

## License

Apache License 2.0. See [LICENSE](LICENSE).

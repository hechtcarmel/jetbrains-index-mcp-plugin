# IDE Index MCP Server - Development Guide

An IntelliJ Platform plugin that extends JetBrains' built-in MCP Server with IDE-backed tools for code navigation, analysis, and refactoring.

## Project Overview

### Goal

Expose IDE-native capabilities to MCP clients while keeping the public tool contract stable:

- preserve tool names and descriptions
- preserve `project_path`
- preserve plugin-side command history and tool toggles
- delegate transport, sessions, host, and port to JetBrains MCP Server

### Technology Stack

- **Language**: Kotlin (JVM 21)
- **Build system**: Gradle 9.0 with Kotlin DSL
- **Platform target**: IntelliJ Platform 2025.3+
- **Bundled dependency**: `com.intellij.mcpServer`
- **Protocol surface**: MCP tools via JetBrains native provider APIs

## Key Documentation

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [Plugin configuration](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html)
- [PSI](https://plugins.jetbrains.com/docs/intellij/psi.html)
- [Indexing and PSI stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)
- [Rename refactoring](https://plugins.jetbrains.com/docs/intellij/rename-refactoring.html)
- [JetBrains MCP Server help](https://www.jetbrains.com/help/idea/mcp-server.html)
- [JetBrains MCP extension points](https://plugins.jetbrains.com/docs/intellij/intellij-community-plugins-extension-point-list.html#comintellijmcpserver)

## Project Structure

```text
src/
├── main/
│   ├── kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/
│   │   ├── handlers/                   # Language-specific navigation handlers
│   │   ├── history/                    # Command history models and service
│   │   ├── server/
│   │   │   ├── McpServerService.kt     # Native integration facade
│   │   │   ├── NativeMcpToolAdapter.kt # Plugin-tool -> native-tool bridge
│   │   │   ├── NativeMcpToolsProvider.kt # JetBrains MCP tools provider
│   │   │   ├── ProjectResolver.kt      # project_path and workspace resolution
│   │   │   └── models/                 # Shared MCP/tool result models
│   │   ├── settings/                   # Plugin-side settings only
│   │   ├── tools/                      # Existing plugin MCP tool implementations
│   │   ├── ui/                         # Tool window and status UI
│   │   └── util/                       # PSI/project/threading helpers
│   └── resources/
│       ├── META-INF/plugin.xml
│       └── messages/McpBundle.properties
└── test/
    └── kotlin/
```

## Architecture

### Native MCP integration

JetBrains built-in MCP Server is now the only transport owner.

- `plugin.xml` depends on `com.intellij.mcpServer`
- `plugin.xml` registers `NativeMcpToolsProvider` through `com.intellij.mcpServer.mcpToolsProvider`
- `McpServerService` initializes `ToolRegistry` once and exposes status for the plugin UI
- `NativeMcpToolsProvider` returns native wrappers only for enabled plugin tools
- `NativeMcpToolAdapter` converts:
  - plugin JSON schema to `McpToolSchema`
  - plugin `ToolCallResult` to `McpToolCallResult`
  - `project_path` or native project context to the IntelliJ `Project`

### Project resolution

Execution order:

1. If `project_path` is present, resolve it through `ProjectResolver`.
2. Otherwise, use the project attached to the native MCP call context.
3. If neither resolves, fall back to global project discovery.

This preserves the old agent-facing behavior without renaming the parameter to `projectPath`.

### Command history

Command history moved from the deleted JSON-RPC layer into `NativeMcpToolAdapter`.

- every native MCP tool call records a `CommandEntry`
- status and duration are updated after execution
- the tool window remains the main UI for history inspection

### Removed architecture

The plugin no longer owns:

- Ktor server bootstrap
- JSON-RPC request routing
- streamable HTTP endpoints
- legacy SSE transport
- configurable host/port
- client config generation for plugin-owned endpoint URLs

## Settings and UI

The settings page now controls only plugin-managed behavior:

- `maxHistorySize`
- `syncExternalChanges`
- enabled/disabled tools

The tool window now shows:

- native server availability
- native server URL when exposed by the IDE
- registered and enabled tool counts
- command history

`CopyClientConfigAction` now opens JetBrains MCP Server settings/help instead of generating custom client configuration.

## Tool Development Guidelines

- Keep comments and user-facing messages in English.
- Preserve the public tool contract when possible.
- Use `SchemaBuilder` for tool schemas.
- Extend `AbstractMcpTool` unless the tool explicitly does not need PSI synchronization.
- Keep `project_path` in schemas for agent compatibility.

## Testing

Test split:

- `*UnitTest.kt`: serialization, schema translation, provider/adapter behavior, registries
- `*Test.kt`: IntelliJ platform behavior, PSI access, project services

Native migration tests should cover:

- schema translation keeps `project_path`
- native adapter execution uses fallback project correctly
- explicit `project_path` still wins over fallback context
- command history recording now happens in the native adapter
- provider returns native wrappers for enabled tools

Do not keep transport-level tests for the deleted Ktor stack.

## Build and Verification

```bash
./gradlew build
./gradlew test
./gradlew runIde
./gradlew runPluginVerifier
```

Do not run platform tests casually during iteration unless they are required for the specific change.

## Contributing Checklist

1. Bump `pluginVersion` for behavioral changes.
2. Update `CHANGELOG.md`.
3. Update docs when architecture changes.
4. Add or update tests.
5. Keep code comments and user-facing messages in English.

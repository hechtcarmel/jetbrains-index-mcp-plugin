# IntelliJ Index MCP Plugin - Development Guide

An IntelliJ Platform plugin that exposes an MCP (Model Context Protocol) server, enabling coding agents to leverage the IDE's powerful indexing and refactoring capabilities.

## Project Overview

### Goal
Create an MCP server within an IntelliJ plugin that allows AI coding assistants to:
- Perform refactoring operations (rename, extract, move, etc.)
- Query type hierarchy and call hierarchy
- Access code navigation features (find usages, go to definition)
- Leverage IDE indexes for fast code search and analysis
- Use code completion and inspection APIs

### Technology Stack
- **Language**: Kotlin (JVM 21)
- **Build System**: Gradle 9.0 with Kotlin DSL
- **IDE Platform**: IntelliJ IDEA 2024.3+ (platformType = IC)
- **Protocol**: Model Context Protocol (MCP) 2024-11-05

## Key Documentation

### IntelliJ Platform SDK
- **Main Documentation**: https://plugins.jetbrains.com/docs/intellij/welcome.html
- **PSI (Program Structure Interface)**: https://plugins.jetbrains.com/docs/intellij/psi.html
- **Indexing and PSI Stubs**: https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html
- **Rename Refactoring**: https://plugins.jetbrains.com/docs/intellij/rename-refactoring.html
- **Modifying the PSI**: https://plugins.jetbrains.com/docs/intellij/modifying-psi.html
- **Plugin Configuration**: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html
- **Explore API**: https://plugins.jetbrains.com/docs/intellij/explore-api.html

### Model Context Protocol (MCP)
- **Specification**: https://spec.modelcontextprotocol.io/specification/2024-11-05/
- **Tools API**: https://modelcontextprotocol.io/specification/2024-11-05/server/tools
- **Resources API**: https://modelcontextprotocol.io/specification/2024-11-05/server/resources
- **GitHub**: https://github.com/modelcontextprotocol/modelcontextprotocol

## Project Structure

```
src/
├── main/
│   ├── kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/
│   │   ├── MyBundle.kt                 # Resource bundle accessor
│   │   ├── services/                   # Application/project services
│   │   ├── startup/                    # Startup activities
│   │   └── toolWindow/                 # Tool window UI
│   └── resources/
│       ├── META-INF/plugin.xml         # Plugin configuration
│       └── messages/MyBundle.properties # i18n messages
└── test/
    ├── kotlin/                         # Test sources
    └── testData/                       # Test fixtures
```

## Architecture Concepts

### IntelliJ Platform Key Components

1. **PSI (Program Structure Interface)**
   - Core abstraction for parsing and representing code structure
   - `PsiFile`, `PsiElement`, `PsiClass`, `PsiMethod`, etc.
   - `PsiNamedElement` for elements that can be renamed/referenced

2. **Indexes**
   - `DumbService` - query if IDE is in dumb mode (indexing) vs smart mode
   - File-based indexes for fast lookups
   - PSI stubs for lightweight syntax trees

3. **Refactoring APIs**
   - `RenameHandler` - custom rename UI/workflow
   - `PsiNamedElement.setName()` - rename element
   - `PsiReference.handleElementRename()` - update references

4. **Services**
   - Application-level services (singleton across IDE)
   - Project-level services (one per open project)

### MCP Server Architecture

MCP servers expose:
- **Tools** - Operations that can be invoked (e.g., `rename_symbol`, `find_usages`)
- **Resources** - Data that provides context (e.g., project structure, file contents)
- **Prompts** - Pre-defined interaction templates (optional)

**Transport**: This plugin uses HTTP+SSE transport with JSON-RPC 2.0:
- `GET /index-mcp/sse` → Opens SSE stream, sends `endpoint` event with POST URL
- `POST /index-mcp` → JSON-RPC requests/responses

**Client Configuration** (Cursor, Claude Desktop, etc.):
```json
{
  "mcpServers": {
    "intellij-index": {
      "url": "http://localhost:{IDE_PORT}/index-mcp/sse"
    }
  }
}
```

Find IDE port: Settings → Build, Execution, Deployment → Debugger → Built-in Server Port (default: 63342)

## Development Guidelines

### Kotlin Standards
- Use Kotlin idioms (data classes, extension functions, coroutines where appropriate)
- Leverage null safety features
- Use `@RequiresBackgroundThread` / `@RequiresReadLock` annotations where needed

### IntelliJ Platform Best Practices
- Always check `DumbService.isDumb()` before accessing indexes
- Use `ReadAction` / `WriteAction` for PSI modifications
- Register extensions in `plugin.xml`, not programmatically
- Use `ApplicationManager.getApplication().invokeLater()` for UI updates
- Handle threading correctly (read actions on background threads, write actions on EDT)

### Code Style
- Follow Kotlin coding conventions
- Use meaningful variable names
- Keep functions small and focused
- Extract reusable logic to utility classes

## Building and Running

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

### Run Configurations (in `.run/`)
- **Run Plugin** - Launch IDE with plugin for manual testing
- **Run Tests** - Execute unit tests
- **Run Verifications** - Run compatibility checks

## Plugin Configuration

Key files:
- `gradle.properties` - Plugin metadata (version, IDs, platform version)
- `plugin.xml` - Extension points and dependencies
- `build.gradle.kts` - Build configuration

### Adding Dependencies
1. Add to `gradle/libs.versions.toml` for version catalog
2. Reference in `build.gradle.kts` using `libs.xxx` syntax

### Adding Extension Points
Register in `plugin.xml`:
```xml
<extensions defaultExtensionNs="com.intellij">
    <your.extension implementation="com.your.ImplementationClass"/>
</extensions>
```

## Testing

### Test Architecture

Tests are split into two categories to optimize execution time:

1. **Unit Tests (`*UnitTest.kt`)** - Extend `junit.framework.TestCase`
   - Fast, no IntelliJ Platform initialization required
   - Use for: serialization, schema validation, data classes, registries, pure logic
   - Run with: `./gradlew test --tests "*UnitTest*"`

2. **Platform Tests (`*Test.kt`)** - Extend `BasePlatformTestCase`
   - Slower, requires full IntelliJ Platform with indexing
   - Use for: tests needing `project`, PSI operations, tool execution, resource reads
   - Run with: `./gradlew test --tests "*Test" --tests "!*UnitTest*"`

### Test File Conventions

| Test Class | Base Class | Purpose |
|------------|------------|---------|
| `McpPluginUnitTest` | `TestCase` | JSON-RPC serialization, error codes, registry |
| `McpPluginTest` | `BasePlatformTestCase` | Platform availability |
| `ToolsUnitTest` | `TestCase` | Tool schemas, registry, definitions |
| `ToolsTest` | `BasePlatformTestCase` | Tool execution with project |
| `JsonRpcHandlerUnitTest` | `TestCase` | JSON-RPC protocol, error handling |
| `JsonRpcHandlerTest` | `BasePlatformTestCase` | Tool calls requiring project |
| `ResourcesUnitTest` | `TestCase` | Resource registry, metadata |
| `ResourcesTest` | `BasePlatformTestCase` | Resource reads with project |
| `CommandHistoryUnitTest` | `TestCase` | Data classes, filters |
| `CommandHistoryServiceTest` | `BasePlatformTestCase` | Service with project |

### When to Use Each Base Class

**Use `TestCase` (unit test) when:**
- Testing serialization/deserialization
- Validating schemas and definitions
- Testing data classes and their properties
- Testing registries without executing tools
- No `project` instance is needed

**Use `BasePlatformTestCase` (platform test) when:**
- Test needs `project` instance
- Test executes tools against a project
- Test reads resources that require project context
- Test uses project-level services (e.g., `CommandHistoryService`)
- Test needs PSI or index access

### Running Tests

```bash
# Run all tests
./gradlew test

# Run only fast unit tests (recommended for quick feedback)
./gradlew test --tests "*UnitTest*"

# Run only platform tests
./gradlew test --tests "*Test" --tests "!*UnitTest*"

# Run specific test class
./gradlew test --tests "McpPluginUnitTest"
```

### Test Data
- Place test fixtures in `src/test/testData/`
- Test both smart mode and dumb mode scenarios for platform tests

## MCP Implementation Notes

### Tools to Implement
1. **Refactoring**
   - `rename_symbol` - Rename a symbol across the project
   - `extract_method` - Extract selection to new method
   - `inline` - Inline variable/method

2. **Navigation**
   - `find_usages` - Find all usages of a symbol
   - `go_to_definition` - Navigate to symbol definition
   - `type_hierarchy` - Get type hierarchy for a class
   - `call_hierarchy` - Get call hierarchy for a method

3. **Code Intelligence**
   - `get_completions` - Get code completions at position
   - `get_quick_fixes` - Get available quick fixes
   - `run_inspection` - Run code inspection

### Resources to Expose
- Project structure and module information
- Open files and their contents
- Current selection/cursor position
- Index status (dumb/smart mode)

## Useful IntelliJ Platform Classes

```kotlin
// PSI Navigation
PsiTreeUtil           // Tree traversal utilities
PsiUtilCore          // Core PSI utilities
ReferencesSearch     // Find references to element

// Refactoring
RefactoringFactory   // Create refactoring instances
RenameProcessor      // Rename refactoring
RefactoringBundle    // Refactoring messages

// Indexes
DumbService          // Check index status
FileBasedIndex       // Access file indexes
StubIndex            // Access stub indexes

// Project Structure
ProjectRootManager   // Project roots
ModuleManager        // Module access
VirtualFileManager   // Virtual file system
```

## Troubleshooting

### Common Issues
1. **IndexNotReadyException** - Accessing indexes in dumb mode
   - Solution: Use `DumbService.getInstance(project).runWhenSmart { ... }`

2. **WriteAction required** - Modifying PSI without write lock
   - Solution: Wrap in `WriteCommandAction.runWriteCommandAction(project) { ... }`

3. **Must be called from EDT** - UI operations on background thread
   - Solution: Use `ApplicationManager.getApplication().invokeLater { ... }`

## Contributing

1. Follow existing code patterns
2. Add tests for new functionality
3. Update this documentation as needed
4. Run `./gradlew runPluginVerifier` before submitting

---

**Template Source**: [JetBrains IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

# IDE Index MCP Server - Development Guide

An IntelliJ Platform plugin that exposes an MCP (Model Context Protocol) server, enabling coding agents to leverage the IDE's powerful indexing and refactoring capabilities.

**Works with JetBrains IDEs**: IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, RustRover, DataGrip, and Android Studio.

## Project Overview

### Goal
Create an MCP server within an IntelliJ plugin that allows AI coding assistants to:
- Perform refactoring operations (rename, extract, move, etc.)
- Query type hierarchy and call hierarchy
- Access code navigation features (find usages, find definition)
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
│   │   ├── handlers/                   # Language-specific handlers
│   │   │   ├── LanguageHandler.kt      # Handler interfaces
│   │   │   ├── LanguageHandlerRegistry.kt # Handler registry
│   │   │   ├── OptimizedSymbolSearch.kt # Optimized symbol search using platform APIs
│   │   │   ├── java/JavaHandlers.kt    # Java/Kotlin handlers
│   │   │   ├── python/PythonHandlers.kt # Python handlers (reflection)
│   │   │   ├── javascript/JavaScriptHandlers.kt # JS/TS handlers (reflection)
│   │   │   ├── go/GoHandlers.kt        # Go handlers (reflection)
│   │   │   ├── php/PhpHandlers.kt      # PHP handlers (reflection)
│   │   │   └── rust/RustHandlers.kt    # Rust handlers (reflection)
│   │   ├── services/                   # Application/project services
│   │   ├── startup/                    # Startup activities
│   │   ├── tools/                      # MCP tool implementations
│   │   │   ├── navigation/             # Navigation tools (multi-language)
│   │   │   ├── refactoring/            # Refactoring tools (Java only)
│   │   │   └── utils/                  # Plugin detectors
│   │   └── toolWindow/                 # Tool window UI
│   └── resources/
│       ├── META-INF/
│       │   ├── plugin.xml              # Plugin configuration
│       │   ├── java-features.xml       # Java-specific extensions
│       │   ├── kotlin-features.xml     # Kotlin-specific extensions
│       │   ├── python-features.xml     # Python-specific extensions
│       │   ├── javascript-features.xml # JS/TS-specific extensions
│       │   ├── go-features.xml         # Go-specific extensions
│       │   ├── php-features.xml        # PHP-specific extensions
│       │   └── rust-features.xml       # Rust-specific extensions
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
- **Prompts** - Pre-defined interaction templates (optional)

**Transport**: This plugin uses HTTP+SSE transport with JSON-RPC 2.0:
- `GET /index-mcp/sse` → Opens SSE stream, sends `endpoint` event with POST URL
- `POST /index-mcp` → JSON-RPC requests/responses

**Client Configuration** (Cursor, Claude Desktop, etc.):
```json
{
  "mcpServers": {
    "jetbrains-index": {
      "url": "http://127.0.0.1:{IDE_PORT}/index-mcp/sse"
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

### PSI-Document Synchronization

The IntelliJ Platform maintains separate Document (text) and PSI (parsed structure) layers.
When files are modified externally (e.g., by AI coding tools), PSI may not immediately reflect
the changes. This can cause search APIs to miss references in newly created files.

**Solution**: `AbstractMcpTool` automatically refreshes the VFS and commits documents
before executing any tool. This ensures PSI is synchronized with external file changes.

**User Setting**: "Sync external file changes before operations" (Settings → MCP Server)
- **Disabled** (default): Best performance, suitable for most use cases
- **Enabled**: **WARNING - SIGNIFICANT PERFORMANCE IMPACT.** Use only when rename/find-usages misses references in files just created externally. Each operation will take seconds instead of milliseconds on large repos.

**For tool developers**:
- Extend `AbstractMcpTool` and implement `doExecute()` (not `execute()`)
- PSI synchronization happens automatically before `doExecute()` is called
- To opt-out (for tools that don't use PSI), override:
  ```kotlin
  override val requiresPsiSync: Boolean = false
  ```

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

### Implemented Tools

Tools are organized by IDE availability.

**Universal Tools (All JetBrains IDEs):**
- `ide_find_references` - Find all usages of a symbol
- `ide_find_definition` - Find symbol definition location
- `ide_diagnostics` - Analyze file for problems and available intentions
- `ide_index_status` - Check indexing status (dumb/smart mode)
- `ide_refactor_rename` - Rename a symbol across the project with automatic related element renaming (getters/setters, overriding methods). Fully headless, works for ALL languages.

**Extended Navigation Tools (Language-Aware):**

These activate based on available language plugins (Java, Python, JavaScript/TypeScript, Go, PHP, Rust):
- `ide_type_hierarchy` - Get type hierarchy for a class
- `ide_call_hierarchy` - Get call hierarchy for a method
- `ide_find_implementations` - Find implementations of interface/method
- `ide_find_symbol` - Search for symbols (classes, methods, fields) by name with fuzzy/camelCase matching
- `ide_find_super_methods` - Find methods that a given method overrides/implements (full hierarchy chain)

**Java/Kotlin-Only Refactoring Tools:**
- `ide_refactor_safe_delete` - Safely delete element (requires Java plugin)

### Multi-Language Architecture

The plugin uses a language handler pattern for multi-IDE support:

**Core Components:**
- `LanguageHandler<T>` - Base interface for language-specific handlers
- `LanguageHandlerRegistry` - Central registry managing all language handlers
- `*PluginDetector` - Cached checks for language plugin availability (runs once at startup)

**Language Handlers (in `handlers/` package):**
- `handlers/java/JavaHandlers.kt` - Direct PSI access for Java/Kotlin
- `handlers/python/PythonHandlers.kt` - Reflection-based Python PSI access
- `handlers/javascript/JavaScriptHandlers.kt` - Reflection-based JS/TS PSI access
- `handlers/go/GoHandlers.kt` - Reflection-based Go PSI access
- `handlers/php/PhpHandlers.kt` - Reflection-based PHP PSI access
- `handlers/rust/RustHandlers.kt` - Reflection-based Rust PSI access

**Handler Types:**
- `TypeHierarchyHandler` - Type hierarchy lookup
- `ImplementationsHandler` - Find implementations
- `CallHierarchyHandler` - Call hierarchy analysis
- `SymbolSearchHandler` - Symbol search by name
- `SuperMethodsHandler` - Method override hierarchy

**Registration Flow:**
1. `LanguageHandlerRegistry.registerHandlers()` - Registers handlers for available language plugins
2. `ToolRegistry.registerUniversalTools()` - Registers universal tools including `ide_refactor_rename`
3. `ToolRegistry.registerLanguageNavigationTools()` - Registers tools if any language handlers available
4. `ToolRegistry.registerJavaRefactoringTools()` - Registers `ide_refactor_safe_delete` if Java plugin available

**Reflection Pattern:** Python, JavaScript, Go, PHP, and Rust handlers use reflection to avoid compile-time dependencies on language-specific plugins. This prevents `NoClassDefFoundError` in IDEs without those plugins.

### Optimized Symbol Search

Symbol search across all languages uses `OptimizedSymbolSearch` (in `handlers/OptimizedSymbolSearch.kt`):
- Leverages IntelliJ's "Go to Symbol" APIs (`ChooseByNameContributor`)
- Uses `MinusculeMatcher` for CamelCase, substring, and typo-tolerant matching
- Supports language filtering (e.g., `languageFilter = setOf("Java", "Kotlin")`)

### Search Collection Pattern (Processor)

All search operations use the `Processor` pattern for efficient streaming and early termination:

```kotlin
// ✗ Inefficient: loads all results into memory
val results = SomeSearch.search(element).findAll().take(100)

// ✓ Efficient: streams results with early termination
val results = mutableListOf<Result>()
SomeSearch.search(element).forEach(Processor { item ->
    results.add(convertToResult(item))
    results.size < 100  // Return false to stop iteration
})
```

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

4. **Search misses newly created files** - PSI not synchronized with document
   - Cause: External tools modified files but PSI tree hasn't been updated
   - Solution: Enable "Sync external file changes" in Settings → MCP Server (WARNING: significant performance impact)
   - For custom code: `PsiDocumentManager.getInstance(project).commitAllDocuments()`

## Contributing

1. Follow existing code patterns
2. Add tests for new functionality
3. Update this documentation as needed
4. Run `./gradlew runPluginVerifier` before submitting

---

**Template Source**: [JetBrains IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Never run platform tests on your own
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
- **IDE Platform**: IntelliJ IDEA 2025.1+ (platformType = IC)
- **HTTP Server**: Ktor CIO 2.3.12 (embedded, configurable port)
- **Protocol**: Model Context Protocol (MCP) 2025-03-26

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
- **Specification**: https://spec.modelcontextprotocol.io/specification/2025-03-26/
- **Tools API**: https://modelcontextprotocol.io/specification/2025-03-26/server/tools
- **Resources API**: https://modelcontextprotocol.io/specification/2025-03-26/server/resources
- **Legacy SSE Transport**: https://spec.modelcontextprotocol.io/specification/2024-11-05/basic/transports/
- **GitHub**: https://github.com/modelcontextprotocol/modelcontextprotocol

## Project Structure

```
src/
├── main/
│   ├── kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/
│   │   ├── MyBundle.kt                 # Resource bundle accessor
│   │   ├── handlers/                   # Language-specific handlers
│   │   │   ├── LanguageHandler.kt      # Handler interfaces & data classes
│   │   │   ├── LanguageHandlerRegistry.kt # Data-driven handler registry
│   │   │   ├── OptimizedSymbolSearch.kt # Symbol search using platform APIs
│   │   │   ├── java/JavaHandlers.kt    # Java/Kotlin handlers
│   │   │   ├── python/PythonHandlers.kt # Python handlers (reflection)
│   │   │   ├── javascript/JavaScriptHandlers.kt # JS/TS handlers (reflection)
│   │   │   ├── go/GoHandlers.kt        # Go handlers (reflection)
│   │   │   ├── php/PhpHandlers.kt      # PHP handlers (reflection)
│   │   │   └── rust/RustHandlers.kt    # Rust handlers (reflection)
│   │   ├── server/                     # MCP server infrastructure
│   │   │   ├── McpServerService.kt     # App-level service managing server lifecycle
│   │   │   ├── JsonRpcHandler.kt       # JSON-RPC 2.0 request routing
│   │   │   ├── ProjectResolver.kt      # Multi-project resolution with workspace support
│   │   │   ├── models/                 # Protocol models (JsonRpc, MCP)
│   │   │   └── transport/              # HTTP+SSE transport layer
│   │   │       ├── KtorMcpServer.kt    # Embedded Ktor CIO server
│   │   │       ├── KtorSseSessionManager.kt # SSE session management
│   │   ├── startup/                    # Startup activities
│   │   ├── tools/                      # MCP tool implementations
│   │   │   ├── McpTool.kt             # Tool interface
│   │   │   ├── AbstractMcpTool.kt     # Base class (PSI sync, threading, helpers)
│   │   │   ├── ToolRegistry.kt        # Data-driven tool registry
│   │   │   ├── schema/                # Tool schema utilities
│   │   │   │   └── SchemaBuilder.kt   # Fluent builder for input schemas
│   │   │   ├── editor/                # Editor interaction tools
│   │   │   ├── navigation/            # Navigation tools (multi-language)
│   │   │   ├── intelligence/          # Code analysis tools
│   │   │   ├── project/               # Project status tools
│   │   │   └── refactoring/           # Refactoring tools
│   │   ├── util/                      # Utilities
│   │   │   ├── PluginDetector.kt      # Generic plugin availability detector
│   │   │   ├── PluginDetectors.kt     # Registry of all language detectors
│   │   │   ├── ClassResolver.kt       # Class lookup by FQN (Java, PHP)
│   │   │   ├── ProjectUtils.kt        # Project/workspace helpers
│   │   │   ├── PsiUtils.kt            # PSI navigation helpers
│   │   │   └── ThreadingUtils.kt      # Threading utilities
│   │   └── ui/                        # Tool window UI
│   └── resources/
│       ├── META-INF/
│       │   ├── plugin.xml              # Plugin configuration
│       │   └── *-features.xml          # Optional language-specific extensions
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

### Workspace / Multi-Module Project Support

The plugin supports workspace projects where a single IDE window contains multiple sub-projects
represented as modules with separate content roots:

- **Project resolution** (`ProjectResolver.resolve`): Checks exact basePath → module content roots → subdirectory match
- **File resolution** (`AbstractMcpTool.resolveFile`): Tries basePath, then module content roots
- **Relative path computation** (`ProjectUtils.getRelativePath`): Strips the matching content root prefix
- **VFS/PSI sync** (`AbstractMcpTool.ensurePsiUpToDate`): Refreshes all content roots, not just basePath
- **Error responses**: `available_projects` detail is configurable. Expanded mode includes workspace sub-projects with their `workspace` parent name; compact mode returns only top-level project roots.

Key utility: `ProjectUtils.getModuleContentRoots(project)` returns all module content root paths.

### MCP Server Architecture

MCP servers expose:
- **Tools** - Operations that can be invoked (e.g., `rename_symbol`, `find_usages`)
- **Prompts** - Pre-defined interaction templates (optional)

**Server Infrastructure:**
- Custom embedded **Ktor CIO** HTTP server (not IntelliJ's built-in server)
- Configurable port with IDE-specific defaults (e.g., IntelliJ: 29170, PyCharm: 29172) via Settings → Index MCP Server → Server Port
- Binds to `127.0.0.1` only (localhost) for security
- Single server instance across all open projects
- Auto-restart on port change

**Key Server Classes:**
- `McpServerService` - Application-level service managing server lifecycle
- `KtorMcpServer` - Embedded Ktor CIO server with CORS support
- `KtorSseSessionManager` - SSE session management using Kotlin channels
- `JsonRpcHandler` - JSON-RPC 2.0 request processing

**Transport**: This plugin supports two transports with JSON-RPC 2.0:

*Streamable HTTP (Primary, MCP 2025-03-26):*
- `POST /index-mcp/streamable-http` → Stateless JSON-RPC requests/responses
- `GET /index-mcp/streamable-http` → 405 Method Not Allowed
- `DELETE /index-mcp/streamable-http` → 405 Method Not Allowed

*Legacy SSE (MCP 2024-11-05):*
- `GET /index-mcp/sse` → Opens SSE stream, sends `endpoint` event with POST URL
- `POST /index-mcp` → JSON-RPC requests/responses

**Client Configuration** (Cursor, Claude Desktop, etc.):
```json
{
  "mcpServers": {
    "intellij-index": {
      "url": "http://127.0.0.1:29170/index-mcp/streamable-http"
    }
  }
}
```
Note: Server name and port are IDE-specific. Use the "Install on Coding Agents" button for automatic configuration.

**Port Configuration**: Settings → Tools → Index MCP Server → Server Port (IDE-specific defaults, range: 1024-65535)

**IDE-Specific Defaults**:
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
| Aqua | `aqua-index` | 29180 |
| DataSpell | `dataspell-index` | 29181 |
| Rider | `rider-index` | 29182 |

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

### Tool Schema Guidelines

All tool input schemas MUST use `SchemaBuilder` (in `tools/schema/SchemaBuilder.kt`). This eliminates boilerplate and ensures consistency:

```kotlin
// ✓ Use SchemaBuilder for all tool schemas
override val inputSchema = SchemaBuilder.tool()
    .projectPath()
    .file()
    .lineAndColumn()
    .intProperty("maxResults", "Maximum results to return. Default: 100, max: 500.")
    .build()

// For enum parameters:
.enumProperty("matchMode", "How to match the query.", listOf("substring", "prefix", "exact"))

// For complex properties that don't fit the builder, use the escape hatch:
.property("target_type", buildJsonObject { /* custom schema */ })
```

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
- `ide_find_references` - Find all usages of a symbol. Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_find_definition` - Find symbol definition location. Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_find_class` - Search for classes/interfaces by name with camelCase/substring/wildcard matching
- `ide_find_file` - Search for files by name using IDE's file index
- `ide_search_text` - Text search using IDE's pre-built word index with context filtering
- `ide_read_file` - Read file content by path or qualified name, including library/jar sources (disabled by default)
- `ide_diagnostics` - Unified diagnostics tool: per-file code analysis (errors, warnings, intentions), build output from last build, and test results from open test run tabs. Supports `includeBuildErrors`, `includeTestResults`, `severity` filter, `testResultFilter`, `maxBuildErrors`, `maxTestResults`. The `file` parameter is now optional.
- `ide_index_status` - Check indexing status (dumb/smart mode)
- `ide_sync_files` - Force sync IDE's virtual file system and PSI cache with external file changes
- `ide_build_project` - Build project using IDE's build system (JPS, Gradle, Maven). Returns structured errors/warnings with file locations when available (null counts = no messages captured, not 0). Uses CompilationStatusListener for JPS builds and BuildProgressListener for Gradle/Maven builds. Supports workspace sub-project targeting via `project_path`. (disabled by default)
- `ide_refactor_rename` - Rename a symbol or file across the project with automatic related element renaming (getters/setters, overriding methods). Fully headless, works for ALL languages. Two modes: **symbol rename** (file + line + column + newName) and **file rename** (file + newName, omit line/column). File rename mode works for all file types including binary files (images, etc.) and is especially useful for Android resource files where it updates all XML references. Supports `relatedRenamingStrategy` parameter to control automatic related renames: `"all"` (default), `"none"`, `"accessors_and_tests"`, or `"ask"`.
- `ide_move_file` - Move a file to a new directory using the IDE's refactoring engine. Automatically updates all references, imports, and package declarations across the project. Supports automatic directory creation and optional reference update toggle.
- `ide_reformat_code` - Reformat code using project code style (.editorconfig, IDE settings). Supports optional import optimization and code rearrangement. (disabled by default)
- `ide_optimize_imports` - Optimize imports (remove unused, organize) without reformatting code. Equivalent to IDE's Ctrl+Alt+O. (disabled by default)
- `ide_get_active_file` - Get the currently active file(s) in the editor (disabled by default)
- `ide_open_file` - Open a file in the editor with optional line/column navigation (disabled by default)

**Extended Navigation Tools (Language-Aware):**

These activate based on available language plugins (Java, Python, JavaScript/TypeScript, Go, PHP, Rust):
- `ide_type_hierarchy` - Get type hierarchy for a class (Java, Kotlin, Python, JS/TS, Go, PHP, Rust)
- `ide_call_hierarchy` - Get call hierarchy for a method (Java, Kotlin, Python, JS/TS, Go, PHP, Rust). Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_find_implementations` - Find implementations of interface/method (Java, Kotlin, Python, JS/TS, PHP, Rust — not Go). Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_find_symbol` - Search for symbols (classes, methods, fields) by name with fuzzy/camelCase matching (disabled by default)
- `ide_find_super_methods` - Find methods that a given method overrides/implements (Java, Kotlin, Python, JS/TS, PHP — not Go, Rust). Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_file_structure` - Get hierarchical file structure similar to IDE's Structure view (Java, Kotlin, Python, JS/TS) (disabled by default)

**Java/Kotlin-Only Refactoring Tools:**
- `ide_refactor_safe_delete` - Safely delete element (requires Java plugin)

**Kotlin Conversion Tools:**
- `ide_convert_java_to_kotlin` - Convert Java files to Kotlin using IntelliJ's built-in J2K converter. Supports full file conversion with automatic formatting and import optimization. Handles classes, interfaces, methods, generics, Java 8+ features (lambdas, streams). Returns list of created .kt files and conversion warnings. Requires both Java and Kotlin plugins. (disabled by default)

### Multi-Language Architecture

The plugin uses a language handler pattern for multi-IDE support:

**Core Components:**
- `LanguageHandler<T>` - Base interface for language-specific handlers
- `LanguageHandlerRegistry` - Central registry managing all language handlers
- `PluginDetectors` - Central registry of language plugin availability detectors (runs once at startup)

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
- `SymbolReferenceHandler` - Resolve fully qualified symbol references (e.g., `com.example.MyClass#method(String)`) to PSI elements
- `SuperMethodsHandler` - Method override hierarchy

**Registration Flow:**
1. `LanguageHandlerRegistry.registerHandlers()` - Registers handlers for available language plugins
2. `ToolRegistry.registerUniversalTools()` - Registers universal tools including `ide_refactor_rename`, `ide_sync_files`
3. `ToolRegistry.registerLanguageNavigationTools()` - Registers tools if any language handlers available
4. `ToolRegistry.registerJavaRefactoringTools()` - Registers `ide_refactor_safe_delete` if Java plugin available

**Reflection Pattern:** Python, JavaScript, Go, PHP, and Rust handlers use reflection to avoid compile-time dependencies on language-specific plugins. This prevents `NoClassDefFoundError` in IDEs without those plugins.

### Optimized Symbol Search

Symbol search across all languages uses `OptimizedSymbolSearch` (in `handlers/OptimizedSymbolSearch.kt`):
- Leverages IntelliJ's "Go to Symbol" APIs (`ChooseByNameContributor`)
- Uses `MinusculeMatcher` for CamelCase, substring, and typo-tolerant matching
- Supports language filtering (e.g., `languageFilter = setOf("Java", "Kotlin")`)

### Pagination

The plugin supports cursor-based pagination for search tools that return flat result lists:
`ide_find_references`, `ide_search_text`, `ide_find_class`, `ide_find_file`, `ide_find_symbol`, `ide_find_implementations`.

**Key components:**
- `PaginationService` (`server/PaginationService.kt`): Application-level light service managing cursor cache
- Cursor tokens are opaque, immutable, base64url-encoded strings containing `{entryId}:{offset}:{pageSize}`
- Same cursor token always returns the same page (idempotent, safe for retries)
- Each response includes `nextCursor` for the next page

**Cache lifecycle:**
- Over-collection: tools collect 500 results internally, serve in configurable page sizes (default varies per tool)
- Inactivity-based TTL: 10 minutes of idle time before cursor expires
- LRU eviction: max 20 active cursors
- Max 5,000 cached results per cursor; beyond this, `hasMore` returns false
- Staleness detection via `PsiModificationTracker` — `stale: true` in response if PSI changed

**Tool integration pattern:**
1. Check for `cursor` parameter → serve from cache via `getPageFromCache()`
2. Fresh search → collect results, create cursor via `PaginationService.createCursor()`, serve first page
3. `searchExtender` lambda enables lazy cache extension when pages are exhausted
4. Each tool has a `buildPaginatedResult()` helper mapping `GetPageResult` to its own result model

**Schema:** All parameters are optional in the schema (no `required` array) because the Anthropic API does not support `anyOf`/`oneOf` at the top level. Validation is done at runtime — if `cursor` is absent, the tool checks for its required search params and returns an error if missing.

**Backward compatibility:** Old `limit`/`maxResults` parameters work as aliases for `pageSize`. Legacy cursors (without embedded pageSize) are still decodable but require an explicit `pageSize` parameter.

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

## Contributing / PR Checklist

Every PR **must** include:

1. **Version bump** — Update `pluginVersion` in `gradle.properties` following [SemVer](https://semver.org):
   - **Patch** (3.x.**Y**): Bug fixes, internal refactoring with no behavior change
   - **Minor** (3.**Y**.0): New features, new tools, protocol improvements
   - **Major** (**Y**.0.0): Breaking changes to tool schemas, transport, or client configuration
2. **CHANGELOG.md update** — Add an entry under `## [Unreleased]` following [Keep a Changelog](https://keepachangelog.com) format. Use sections: `Added`, `Changed`, `Fixed`, `Removed`, `Breaking`
3. Follow existing code patterns and use `SchemaBuilder` for new tool schemas
4. Add tests for new functionality
5. Update this documentation (`CLAUDE.md`) for any structural or architectural changes
6. Run `./gradlew test` to verify all tests pass (do NOT run platform tests yourself)

---

**Template Source**: [JetBrains IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Never run platform tests on your own

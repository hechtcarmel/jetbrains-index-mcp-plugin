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

# Run tests (see the Testing section for the -Ptier split)
./gradlew test

# Run plugin verification
./gradlew verifyPlugin
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

### Running Tests

```bash
# Everything (~40s locally). Safe to run — see "On running platform tests" below.
./gradlew test

# Fast tier only: headless, no IntelliJ Platform (~20s)
./gradlew test -Ptier=unit

# Platform tier only: BasePlatformTestCase fixtures with real indexing
./gradlew test -Ptier=platform

# One class
./gradlew test --tests "ToolManifestContractUnitTest"
```

`-Ptier` exists because Gradle's `--tests` flag has **no negation operator** and OR-combines
repeated occurrences. The command this file used to document —
`--tests "*Test" --tests "!*UnitTest*"` — silently selected the entire suite: `*Test` already
matches every `*UnitTest` class, and `!*UnitTest*` is a literal pattern matching nothing. Do not
reintroduce it.

**On running platform tests locally:** you can. Earlier revisions of this file claimed they
"hang on headless machines"; that was never substantiated. The full suite, platform tests
included, runs locally in about 40 seconds, and CI runs `./gradlew check` on `ubuntu-latest`
with no xvfb and no `DISPLAY`.

### Test Architecture

Three tiers, selected by class-name suffix:

1. **Contract tests** (`contract/*UnitTest.kt`) — extend `junit.framework.TestCase`
   - `ToolManifestContractUnitTest` snapshots each tool's name, description and complete input
     schema into `src/test/resources/contract/tool-manifest.json`. This is the regression net
     for large refactors: one assertion covers every registered tool × every schema property, so
     a dropped `register(...)` call or a mutated parameter type fails here instead of shipping.
     Scope: 47 of the 50 tools in `ToolNames.ALL` (the three needing the Kotlin or Maven plugin
     are covered by set-equality instead), and **inputs only**.
   - `ResultShapeContractUnitTest` snapshots the other half of the client contract — the response
     side — into `src/test/resources/contract/result-shapes.txt`: the wire key set, JSON value
     kind, nullability and optionality of all 61 serializable result models, every enum's wire
     values, and the `UsageTypes` literals. Result models use plain Kotlin property names as their
     wire keys, so renaming `UsageLocation.file` to `.path` is a source-compatible refactor that
     breaks every MCP client; this is what turns red.
   - `TestTierConventionUnitTest` enforces that no `*UnitTest` extends `BasePlatformTestCase`.
   - `PluginDetectorLeakUnitTest` enforces that no test-tree class impersonates a language
     plugin via `PluginDetector`'s `Class.forName` fallback.

2. **Unit tests** (`*UnitTest.kt`) — extend `junit.framework.TestCase`
   - Headless. Serialization decisions, schema semantics, pure logic, registries.

3. **Platform tests** (`*Test.kt`, `*BehaviorTest.kt`, `*IntegrationTest.kt`)
   - Anything needing `project`, PSI, indexes, or end-to-end tool execution.
   - New ones should extend `McpPlatformTestCase`; older ones still extend `BasePlatformTestCase`
     directly and are migrated as they are touched.

**Changing a golden file is sometimes correct, but must always be deliberate:**

```bash
./gradlew test -Ptier=unit --tests "*ToolManifestContractUnitTest" -Dcontract.update=true
./gradlew test -Ptier=unit --tests "*ResultShapeContractUnitTest" -Dcontract.update=true
```

Review the resulting diff as part of the change — for the result shapes it is the list of
breaking changes the release notes owe clients.

Both commands intentionally **exit non-zero** after writing the golden file, so `BUILD FAILED`
there is expected. Re-run without the flag to confirm green.

### Test-only platform dependencies

`build.gradle.kts` declares two things purely so tests can run; neither affects the shipped
plugin:

- `testFramework(TestFrameworkType.Plugin.Java)` — Java test-framework classes.
- `testBundledPlugin("JUnit")` — without it `TestFramework.EXTENSION_NAME.extensionList` is empty
  (the Java plugin declares that extension point but ships no implementations), so
  `ide_list_tests` could only ever answer "No test frameworks are registered" and would be
  untestable.

`gradle.properties` also adds `JavaScript` to `platformBundledPlugins`. That one is *not*
test-only in form — it is a compile/test classpath entry — but it does not change what the plugin
requires at runtime: `plugin.xml` already declared `<depends optional="true">JavaScript</depends>`.
Without it, 30 JS/TS tests silently pass while executing nothing.

### Writing platform tests

Extend `McpPlatformTestCase` (`src/test/kotlin/.../testutil/McpPlatformTestCase.kt`). It provides
`writeProjectFile`, `readProjectFileVfs`, `registerSourceRoot`, `assertToolSucceeded`,
`assertRenamedInFile`, and friends.

**Never use `myFixture.addFileToProject` for a fixture a tool must resolve from a path
argument.** It writes into IntelliJ's in-memory `TempFileSystem` (`temp:///src/...`), but every
production entry point that turns a tool argument into a `VirtualFile` —
`AbstractMcpTool.resolveFile`, `PsiUtils`, the JS/TS symbol resolver — goes through
`LocalFileSystem`, which cannot see `temp://` files. The tool then returns "file not found" and
any assertion looser than an exact-error check passes for the wrong reason. Use
`writeProjectFile`.

`addFileToProject` is still fine — and still used in ~45 places — where the test hands PSI to the
code under test directly rather than routing a path through `resolveFile` (e.g. a
`LanguageHandlerRegistry` handler, a Find-in-Files search, a `ChooseByNameContributor`).

**Call `registerSourceRoot` whenever the assertion depends on index-backed search** —
`ReferencesSearch`, inheritor search, `ChangeSignatureProcessor.findUsages`, JS/TS import
resolution. The default `project_files` scope only covers content roots, so without it a
refactoring silently updates the declaration and no call sites, and the tool still reports
success. That is how `ChangeSignatureBehaviorTest` came to certify non-compiling output.

### Assertion rules

These are enforced by review, and violating them is how this suite previously accumulated ~1,000
tests that could not fail:

- **A test must fail if the production code it covers is deleted or inverted.** If it would still
  pass, delete it.
- **Assert both directions on refactorings.** A rename test that only checks the new name is
  present passes when call-site updating is completely broken. Use `assertRenamedInFile`.
- **Never simulate the system under test with a private helper and assert on the helper.**
- **Conditional skips use `org.junit.Assume.assumeTrue`, never a bare `return`.** A bare return
  reports the test as passed. Gradle is configured to log skipped tests so they stay visible.
- **No asserting on production source text.** Reading a `.kt` file off disk and checking for
  substrings passes on comments and breaks on behavior-preserving refactors.

### Test Data
- Fixtures live in `src/test/testData/`; golden files in `src/test/resources/`
- Cover dumb mode with `DumbModeTestUtils` for any index-backed tool

## MCP Implementation Notes

### Implemented Tools

Tools are organized by IDE availability.

**Universal Tools (All Supported JetBrains IDEs):**
- `ide_find_references` - Find all usages of a symbol. Supports `language`+`symbol` as alternative to `file`+`line`+`column`. Includes generated sources by default (`includeGenerated: true`) so valid runtime references (Dagger/MapStruct/gRPC/serializers) aren't missed; set `includeGenerated: false` to drop generated DI factories/mappers/stubs when they dominate results.
- `ide_find_definition` - Find symbol definition location. Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_find_class` - Search for classes/interfaces by name with camelCase/substring/wildcard matching
- `ide_find_file` - Search for files by name using IDE's file index
- `ide_find_symbol` - Search for symbols (classes, methods, fields, functions) by name with IntelliJ Go to Symbol matching (disabled by default)
- `ide_search_text` - Text search using IntelliJ Find in Files with context filtering (substring matching for plain text, regex matching when enabled)
- `ide_read_file` - Read file content by path or qualified name, including library/jar sources (disabled by default)
- `ide_diagnostics` - Unified diagnostics tool: per-file code analysis (errors, warnings, intentions), build output from last build, and test results from open test run tabs. Supports `includeBuildErrors`, `includeTestResults`, `severity` filter, `testResultFilter`, `maxBuildErrors`, `maxTestResults`. The `file` parameter is now optional.
- `ide_index_status` - Check indexing status (dumb/smart mode)
- `ide_sync_files` - Force sync IDE's virtual file system and PSI cache with external file changes
- `ide_reload_project` - Force-reload the project build model (Maven, Gradle, or both) after modifying build files. Equivalent to "Reload All Maven Projects" / "Reload Gradle Project" in the IDE. Async — returns immediately, resolution happens in background. (disabled by default)
- `ide_import_modules` - Import external Maven project directories as modules into the current IntelliJ window for cross-project code intelligence and refactoring. Already imported module roots are skipped. Requires Maven plugin. (disabled by default)
- `ide_open_workspace` - Scan a root directory for Maven projects and open them all in one IntelliJ window with full cross-project code intelligence, or provide an explicit list of Maven project paths via `modules`. `path` and `modules` are mutually exclusive; `modules` uses SHA-based caching. Creates a temporary aggregator POM with relative module paths. Requires Maven plugin. (disabled by default)
- `ide_build_project` - Build project using IDE's build system (JPS, Gradle, Maven). Returns structured errors/warnings with file locations when available (null counts = no messages captured, not 0). Uses CompilationStatusListener for JPS builds and BuildProgressListener for Gradle/Maven builds. Supports workspace sub-project targeting via `project_path`. (disabled by default)
- `ide_change_signature` - Change method signature (name, return type, visibility, parameters) with automatic caller updates using IntelliJ's Change Signature refactoring. Java only. (disabled by default)
- `ide_create_file` - Create a new source file with content, immediately indexed by IntelliJ. Created through IntelliJ's VFS, instantly available for all IDE tools without needing `ide_sync_files`. Use instead of Write for `.java`, `.kt`, `.ts`, `.tsx`, `.py` files. File must not already exist. (disabled by default)
- `ide_replace_text_in_file` - Find and replace text in a file using IntelliJ's Document API. Plain text or regex replacement through IntelliJ's document model, so changes are immediately visible to index, PSI, and all other IDE tools without needing `ide_sync_files`. (disabled by default)
- `ide_list_tests` - List all test methods/classes discovered by the IDE's test framework extension points (JUnit, TestNG, etc.). Optional `file` parameter limits scan to a single file. Returns entries with className, methodName, framework, file path, and line number. (disabled by default)
- `ide_run_tests` - Run tests via the IDE's run configuration infrastructure. `target` accepts an existing run config name (works for any language/framework) or a Java/Kotlin class/method FQN (`com.example.MyTest` / `com.example.MyTest#testFoo`). **Creating a config from an FQN is Java/Kotlin-only** — for Python/JS/TS/Go/PHP/Rust, pass an existing run-config name. Results are read directly from the IDE's test runner (any Service-Message-based framework: JUnit, TestNG, pytest, Jest, Go test, PHPUnit), returning structured pass/fail/error counts, exit code, and per-test results. (disabled by default)
- `ide_refactor_rename` - Rename a symbol or file across the project with automatic related element renaming (getters/setters, overriding methods). Fully headless, works for ALL languages. Two modes: **symbol rename** (file + line + column + newName) and **file rename** (file + newName, omit line/column). File rename mode works for all file types including binary files (images, etc.) and is especially useful for Android resource files where it updates all XML references. Supports `relatedRenamingStrategy` parameter to control automatic related renames: `"all"` (default), `"none"`, `"accessors_and_tests"`, or `"ask"`.
- `ide_move_file` - Move a file to a new directory using the IDE's refactoring engine. Automatically updates all references, imports, and package declarations across the project. Supports automatic directory creation and optional reference update toggle.
- `ide_reformat_code` - Reformat code using project code style (.editorconfig, IDE settings). Supports optional import optimization and code rearrangement. (disabled by default)
- `ide_optimize_imports` - Optimize imports (remove unused, organize) without reformatting code. Equivalent to IDE's Ctrl+Alt+O. (disabled by default)
- `ide_structural_search_replace` - Pattern-based code search and transformation using IntelliJ's Structural Search and Replace engine. Search-only when `replacePattern` is omitted. Java, Kotlin. (disabled by default)
- `ide_get_active_file` - Get the currently active file(s) in the editor (disabled by default)
- `ide_open_file` - Open a file in the editor with optional line/column navigation (disabled by default)
- `ide_set_power_save_mode` - Enable/disable IDE Power Save Mode (IDE-wide). Suspends background inspections and code analysis while keeping the index and code intelligence operational (disabled by default)
- `ide_close_project` - Close an open project window and free its memory. Non-blocking; refuses to close the last open project so the MCP server keeps a JSON-RPC context (disabled by default)
- `ide_open_project` - Open a project by absolute path and wait until indexing completes (`timeoutSeconds`, default 600). Idempotent for already-open projects (disabled by default)
- `ide_install_plugin` - Install a plugin zip into the IDE, replacing any existing version; auto-detects `build/distributions/*.zip` when no path is given (disabled by default)
- `ide_restart` - Restart the IDE; terminates the MCP connection. Call after `ide_install_plugin` (disabled by default)

**Extended Navigation Tools (Language-Aware):**

These activate based on available language plugins (Java, Python, JavaScript/TypeScript, Go, PHP, Rust, Markdown):
- `ide_type_hierarchy` - Get type hierarchy for a class (Java, Kotlin, Python, JS/TS, Go, PHP, Rust)
- `ide_call_hierarchy` - Get call hierarchy for a method (Java, Kotlin, Python, JS/TS, Go, PHP, Rust). Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_find_implementations` - Find implementations of interface/method (Java, Kotlin, Python, JS/TS, PHP, Rust — not Go). Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_find_super_methods` - Find methods that a given method overrides/implements (Java, Kotlin, Python, JS/TS, PHP — not Go, Rust). Supports `language`+`symbol` as alternative to `file`+`line`+`column`.
- `ide_file_structure` - Get hierarchical file structure similar to IDE's Structure view with start/end line numbers (Java, Kotlin, Python, JS/TS, Markdown) (disabled by default)

**Java/Kotlin-Only Tools:**
- `ide_list_tests` - List all test methods/classes discovered by the IDE's test framework extension points (JUnit, TestNG, etc.). Optional `file` parameter limits scan to a single file. Returns entries with className, methodName, framework, file path, and line number. Requires Java plugin — the `com.intellij.testFramework` extension point is declared by the Java plugin. (disabled by default)
- `ide_edit_member` - Replace an entire member declaration (signature + body) with new content (disabled by default)
- `ide_insert_member` - Insert a new member at a structural position in a class or file (disabled by default)
- `ide_refactor_safe_delete` - Safely delete element (requires Java plugin)
- `ide_replace_member` - Replace method body or field initializer only, preserving the signature (disabled by default)

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

**Every PR — without exception — must comply with [CONTRIBUTING.md](CONTRIBUTING.md).**
Read it before writing a single line of code. It is the authoritative guide for this repo.

Before pushing, run the pre-push validation script to catch common mistakes automatically:

```bash
./scripts/check-pr.sh
```

Quick summary of the non-negotiables:
1. `CHANGELOG.md` — user-visible changes go under `[Unreleased]`; never add `## [x.y.z]` version sections (created at release time)
2. No `.idea/gradle.xml`, no `scripts/build-install.sh`, no `docs/pr-*.md`
3. New tools: registered in `ToolNames`, `ToolRegistry`, and all six doc locations (`README.md`, `USAGE.md`, `CLAUDE.md`, `SKILL.md`, `tools-reference.md`, `ToolNames.ALL` sorted)
4. New opt-in tools: add to `McpSettings.DEFAULT_DISABLED_TOOLS`, bump the settings schema, and add a migration so existing users also get the tool disabled by default
5. No `@Internal` API, no `ModalityState.NON_MODAL` (deprecated)
6. Tests pass: `./gradlew test` — the whole suite, platform tier included. It takes ~40s. Use
   `-Ptier=unit` for a faster inner loop, but do not push on the fast tier alone: 46% of the tests
   live in the platform tier, and that is where tool behavior is actually verified.
7. New tools: regenerate the golden manifest and review its diff (see the Testing section)

---

**Template Source**: [JetBrains IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

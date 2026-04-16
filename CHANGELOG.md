<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Index MCP Server Changelog

## [Unreleased]

## [4.11.0] - 2026-04-15
### Added
- Added a **Project list in error responses** setting with `Expanded` and `Compact` modes. Workspace sub-project/module content roots remain valid `project_path` targets, while compact mode limits invalid/missing `project_path` errors to top-level project roots only.

## [4.10.5] - 2026-04-15
### Changed
- Relaxed IDE compatability requirements to 2025.3+ build, to support Android Studio which doesn't have 2026 yet 

## [4.10.4] - 2026-04-12
### Fixed
- Improved command history stability during concurrent MCP tool calls.

## [4.10.3] - 2026-04-11
### Changed
- **Streamable HTTP is now stateless** — The primary `/index-mcp/streamable-http` transport no longer creates or validates `Mcp-Session-Id` headers. Requests continue working across client reconnects and server restarts without transport reauthentication semantics.

### Fixed
- **Claude Code stale session failure mode** — Removed the transport-level stale session `404` path that Claude Code could surface as a misleading authentication problem, while preserving legacy SSE behavior.

## [4.10.2] - 2026-04-11
### Changed
- **Streamable HTTP is now stateless** — The primary `/index-mcp/streamable-http` transport no longer creates or validates `Mcp-Session-Id` headers. Requests continue working across client reconnects and server restarts without transport reauthentication semantics.

### Fixed
- **Claude Code stale session failure mode** — Removed the transport-level stale session `404` path that Claude Code could surface as a misleading authentication problem, while preserving legacy SSE behavior.
- **`ide_find_references` search failure handling** — Added defensive handling for `LinkageError` / `NoSuchMethodError` failures coming from IDE search infrastructure so affected calls return a structured error with fallback guidance instead of hanging indefinitely.
- **Plugin verification baseline** — Added explicit Plugin Verifier coverage for IntelliJ IDEA Ultimate `2026.1` build `IU-261.22158.277` to keep this release line checked against the exact IDE version reported in [#122](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/122).

## [4.10.1] - 2026-04-07
### Fixed
- **`resolveVirtualFileAnywhere` Windows path handling** — Fixed path comparison failures on Windows caused by backslash path separators and case-insensitive VFS normalization. Uses NIO Path-based `isPathPrefixOf` for case-insensitive library JAR validation, and normalizes paths before comparison. Fixes issues where `Z:/Temp` paths were rejected due to VFS normalizing to `Z:/temp`.

## [4.10.0] - 2026-04-06
### Added
- **Symbol reference resolution for position-based tools** — Five tools now accept `language` + `symbol` as an alternative to `file` + `line` + `column` for identifying the target element. The two parameter groups are mutually exclusive. Unlocks the ability to directly reference symbols from third-party libraries.

  Currently supported for Java; extensible to other languages via `SymbolReferenceHandler`. Symbol format uses JavaDoc-style member references: `com.example.ClassName`, `com.example.ClassName#memberName`, or `com.example.ClassName#method(ParamType1, ParamType2)`.

  - Affected tools: `ide_find_references`, `ide_find_definition`, `ide_call_hierarchy`, `ide_find_implementations`, `ide_find_super_methods`
  - New handler interface: `SymbolReferenceHandler` with `JavaSymbolReferenceHandler` implementation
  - New `resolveElementFromArguments()` helper in `AbstractMcpTool` for unified element resolution
  - New `languageAndSymbol()` builder method in `SchemaBuilder`

## [4.9.3] - 2026-04-04
### Added
- **`ide_refactor_rename` file rename mode** — The `line` and `column` parameters are now optional. Omit them to rename the file itself instead of a symbol within it. Works for all file types including binary files (`.webp`, `.png`, `.jpg`). Especially useful for Android resource files where it updates all resource references across the project. Fixes [#115](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/115).

### Fixed
- **`ide_refactor_rename` file rename correctly handles Android resource naming** — When renaming Android resource files (drawables, mipmaps, etc.), the tool now probes `RenamePsiElementProcessor.prepareRenaming()` to detect element substitution. If the `PsiFile` will be substituted for a resource element, the file extension is stripped from `newName` to match the SDK's resource naming convention. This prevents double extensions (e.g., `app_icon.webp.webp`) on related DPI variants and corrupted `R.drawable` references.
- **`ide_refactor_safe_delete` now detects Android resource references for file deletion** — Previously, deleting Android resource files (e.g., `backup_rules.xml`) did not detect `@xml/` or `@drawable/` references in other XML files, allowing deletion despite active references. The tool now checks three layers: direct file references, resource element references (via `prepareRenaming` probe), and top-level symbol references. This correctly blocks deletion when the file is referenced via the Android resource system.

## [4.9.2] - 2026-04-02
### Fixed
- **`ide_refactor_rename` renamed XML attribute name instead of referenced resource** — When renaming inside an XML attribute value (e.g., `android:id="@+id/XXTVProgress"`), the tool incorrectly renamed the attribute name (`android:id`) instead of the referenced resource ID. Now resolves PSI references before falling back to tree-walking, so the rename correctly targets the referenced declaration. Fixes [#113](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/113).

## [4.9.1] - 2026-04-01
### Fixed
- **Path traversal protection for file operations** — All file-based tools now validate that resolved paths stay within project boundaries, preventing access to files outside the project via relative paths like `../../`.
- **JAR reading restricted to project libraries** — `ide_read_file` now only allows reading JARs that are part of the project's configured library roots, blocking access to arbitrary JARs on the filesystem.

## [4.9.0] - 2026-03-30
### Added
- **Enhanced `ide_diagnostics` with build error and test result sources** — The diagnostics tool now supports three independent sources: per-file code analysis (existing), build output from the last build (new), and test results from open test run tabs (new). New parameters: `includeBuildErrors`, `includeTestResults`, `severity` filter (`all`/`errors`/`warnings`), `testResultFilter` (`failed`/`all`), `maxBuildErrors`, `maxTestResults`. The `file` parameter is now optional — omit it to query only build/test results. Fully backward compatible. Addresses [#104](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/104).
- **`BuildDiagnosticsCacheService`** — Persistent project-level service that captures build errors/warnings from all build sources (Gradle, Maven, JPS) as they happen. Queried by `ide_diagnostics` when `includeBuildErrors` is enabled.

### Changed
- Extracted shared build listener reflection code from `BuildProjectTool` into `BuildListenerUtils` utility for reuse.

## [4.8.0] - 2026-03-30
### Added
- **`ide_find_references` and `ide_find_definition`: `astPath` field** — Returns the chain of named AST ancestors (classes, methods, etc.) enclosing the target element, providing structural context for each result without requiring additional file reads.

## [4.7.0] - 2026-03-26
### Added
- Cursor-based pagination for `ide_find_references`, `ide_search_text`, `ide_find_class`, `ide_find_file`, `ide_find_symbol`, and `ide_find_implementations`

### Fixed
- `ide_search_text` context filter (`context: "comments"`, `"code"`, `"strings"`) returned false positives from non-matching contexts

## [4.6.0] - 2026-03-21
### Added
- **`ide_refactor_rename`: `relatedRenamingStrategy` parameter** — Controls automatic renaming of related symbols (same-named properties on unrelated classes, getters/setters, test classes, variables). Options: `"all"` (default, current behavior), `"none"` (rename only the targeted symbol), `"accessors_and_tests"` (only rename getters/setters and test classes/methods), `"ask"` (show IDE dialog for interactive choice). Fixes [#101](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/101).

## [4.5.0] - 2026-03-21
### Added
- **`ide_move_file` tool** — Move files to a new directory using the IDE's refactoring engine. Enabled by default.

## [4.4.1] - 2026-03-19
### Fixed
- **2026.1 compatability issues** 

## [4.4.0] - 2026-03-18
### Added
- **`ide_convert_java_to_kotlin` tool** — Convert Java files to Kotlin using IntelliJ's built-in J2K (Java-to-Kotlin) converter. Supports full file conversion with automatic formatting, import optimization, and code cleanup. Handles classes, interfaces, enums, methods, fields, generics, and Java 8+ features (lambdas, streams, method references). Returns list of created .kt files with line counts and any conversion warnings. Original Java files are automatically deleted after successful conversion. Requires both Java and Kotlin plugins. Disabled by default — enable in Settings → Tools → Index MCP Server.

## [4.3.0] - 2026-03-17
### Added
- **`ide_optimize_imports` tool** — Optimize imports in a file without reformatting code. Removes unused imports and organizes remaining imports according to project code style. Equivalent to the IDE's "Optimize Imports" action (Ctrl+Alt+O / ⌘⌥O). Disabled by default — enable in Settings → Tools → Index MCP Server. ([#94](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/94))

## [4.2.0] - 2026-03-13
### Added
- **Companion Skill** — New "Get Companion Skill" button in the tool window toolbar. Lets users install or export a companion skill that guides AI coding agents on when and how to use IDE MCP tools effectively. Supports direct installation to `.claude/skills/` for Claude Code projects, or export as `.skill`/`.zip` file for sharing.

## [4.1.0] - 2026-03-13
### Added
- **`ide_build_project` tool** — Trigger project builds via the IDE's build system (JPS, Gradle, Maven). Returns structured error/warning messages with file locations. Supports workspace sub-projects, trusted-project security gate, optional timeout, and optional raw build output. Disabled by default — enable in Settings → Tools → Index MCP Server.
- **Plugin description updates** - Aligned with missing tools and features.

## [4.0.2] - 2026-03-13
### Changed
- **Refactored path resolution in `PsiUtils`** — Extracted `resolveLocalFile`, `resolveAbsolutePath`, `resolveAbsolutePathString`, `expandHome`, and `toPathOrNull` as reusable helpers, eliminating duplicated path normalization logic

### Fixed
- **Integration test used real filesystem** — `ToolExecutionIntegrationTest` now uses `myFixture.addFileToProject()` so test files are properly indexed by the in-memory VFS
- **Path comparison failed on Windows** — `PsiUtilsTest` now normalizes path separators and casing before comparing, fixing test failures on Windows

## [4.0.1] - 2026-03-12

### Fixed
- **Fixed exception introduced in 4.0.0 that could occur when `ide_sync_files` was used after external file changes**
- **Fixed contructor param renaming forcing modal popup**

## [4.0.0] - 2026-03-11

### Added
- **Primary transport changed** — Default server URL now points to Streamable HTTP endpoint (`/index-mcp/streamable-http`). Existing client configurations using the SSE URL continue to work but should be updated.

## [3.14.0] - 2026-03-11

### Added
- **Configurable server host** — Allows the user to configure the listening server host, making it possible to use the MCP server on another machine or WSL (Windows Subsystem for Linux).

## [3.13.0] - 2026-03-03

### Added
- **`ide_reformat_code` tool** — Reformat code files using the IDE's code style settings (`.editorconfig`, project code style). Equivalent to the IDE's "Reformat Code" action (Ctrl+Alt+L / ⌘⌥L). Supports optional import optimization (`optimizeImports`, default: true), code rearrangement (`rearrangeCode`, default: true), and partial file formatting via `startLine`/`endLine`. Disabled by default — enable in Settings → Tools → Index MCP Server. ([#76](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/76))

## [3.12.2] - 2026-03-03

### Fixed
- **Tool filter dropdown in tool window was outdated**

## [3.12.1] - 2026-03-03

### Fixed
- **Server stuck on "Initializing..." if `postStartupActivity` doesn't fire** — The MCP server now self-initializes asynchronously from its service constructor instead of depending solely on `postStartupActivity`. This fixes environments where the startup activity silently fails (e.g., due to plugin conflicts or class-loading errors), leaving the server permanently in "Initializing..." state ([#73](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/73))

## [3.12.0] - 2026-03-01

### Added
- **`overrideStrategy` parameter for `ide_refactor_rename`** — Controls how renaming a method that overrides a base method is handled, enabling fully headless/agent usage without modal dialogs
  - `"rename_base"` (default): Automatically renames the base method and all overrides by resolving to the deepest super method via `PsiMethod.findDeepestSuperMethods()`, bypassing the dialog entirely
  - `"rename_only_current"`: Renames only the current method, leaving the base and other overrides unchanged
  - `"ask"`: Preserves original IDE behavior, showing the dialog for interactive choice

## [3.11.0] - 2026-02-27

### Changed
- **Codebase refactoring overhaul** — Major internal cleanup reducing ~926 lines of duplication
  - **Generic `PluginDetector`** — Replaced 6 nearly identical plugin detector files (640 lines) with a single generic `PluginDetector` class and `PluginDetectors` registry (~80 lines)
  - **`SchemaBuilder` utility** — All 19 tool input schemas now use a fluent `SchemaBuilder` instead of manual JSON construction, eliminating ~460 lines of boilerplate
  - **Data-driven registration** — Handler and tool registration use data-driven loops instead of duplicated reflection blocks
  - **`ClassResolver`** — Extracted class-by-FQN resolution (PHP/Java) from `AbstractMcpTool` into standalone utility
  - **`ProjectResolver`** — Extracted multi-project resolution logic from `JsonRpcHandler` into independently testable class
  - **`server/transport/` package** — Moved `KtorMcpServer` and `KtorSseSessionManager` to dedicated transport sub-package
  - **Consolidated error builders** — Replaced 4 nearly identical JSON-RPC error response methods with single factory

### Fixed
- **JSON-RPC error responses used unsafe string concatenation** — `KtorMcpServer.createJsonRpcError()` now uses proper `kotlinx.serialization` instead of manual string interpolation, preventing malformed JSON from special characters in error messages
- **Streamable HTTP notifications returned no response** — Notifications (e.g., `notifications/initialized`) sent via Streamable HTTP transport now correctly return `202 Accepted` instead of silently dropping the connection
- **No JSON-RPC version validation** — Server now validates that `request.jsonrpc == "2.0"` and returns `INVALID_REQUEST` (-32600) for non-compliant requests

## [3.10.2] - 2026-02-27

### Fixed
- **Tools stop responding when a modal dialog is open** - MCP tool calls (e.g., `ide_sync_files`, `ide_refactor_rename`) no longer hang indefinitely when a modal dialog (Settings, Registry, refactoring preview, etc.) is open in the IDE

## [3.10.1] - 2026-02-27

### Fixed
- **`ide_find_definition` crash in PhpStorm and other non-Java IDEs**

## [3.10.0] - 2026-02-22

### Added
- **`matchMode` parameter for `ide_find_symbol` and `ide_find_class`** - Control how queries match symbol names
  - `"substring"` (default) - matches anywhere in name (backward compatible)
  - `"prefix"` - camelCase-aware prefix matching (e.g., "find" matches "findSymbol")
  - `"exact"` - case-sensitive exact match
- **`language` parameter for `ide_find_symbol` and `ide_find_class`** - Filter results by programming language (e.g., `"Kotlin"`, `"Java"`)
- **`maxPreviewLines` parameter for `ide_find_definition`** - Limit `fullElementPreview` output size (default: 50, max: 500)
- **Glob pattern support for `ide_search_text`** - File type filtering via glob patterns (e.g., `*.kt`, `*.gradle.kts`)
- **Kotlin callee resolution for `ide_call_hierarchy`** - Callees direction now works for Kotlin methods by resolving `KtCallExpression` references via reflection
- **Path-based search fallback for `ide_find_file`** - Falls back to path matching when filename search returns no results
- **`ide_file_structure` for JavaScript and TypeScript** - Previously returned "Language not supported". Now works for `.js`, `.ts`, `.jsx`, `.tsx` files

### Fixed
- **`ide_call_hierarchy` callers for Kotlin `suspend fun`** - `MethodReferencesSearch` misses `suspend fun` call sites because the Kotlin compiler appends a hidden `Continuation<T>` parameter to the JVM signature. Added unconditional `ReferencesSearch.search(navigationElement)` alongside `MethodReferencesSearch` (with deduplication) so callers are always found
- **`ide_call_hierarchy` callers inside `val`/`var` assignments** - `resolveKotlinMethod` was stopping at local `val`/`var` PSI nodes (`KtProperty` with no backing JVM method) and returning `null`, silently dropping every such caller reference. Now continues walking up the PSI tree to find the enclosing named function
- **`ide_call_hierarchy` "unknown" caller names for JSX arrow functions** - `findContainingCallable` was returning unnamed anonymous arrow functions (`const App = () => ...`) instead of the enclosing `JSVariable`. Now skips unnamed `JSFunction` nodes and falls back to the containing `JSVariable` for correct caller name resolution
- **`ide_find_symbol`, `ide_find_class`, `ide_search_text`, `ide_find_file` polluted by excluded paths** - All search tools now use a `DelegatingGlobalSearchScope` subclass (`ExcludedPathScope`) that rejects venv, node_modules, build output, and worktree files at the IntelliJ search-infrastructure level. Excluded files never consume buffer slots, replacing the fragile over-fetch-then-filter approach
- **`ide_find_symbol` and `ide_find_class` polluted by venv/node_modules in subdirectories** - Exclusion filter now matches `.venv/`, `venv/`, `node_modules/`, and `.worktrees/` at any path depth (not only at the project root). Fixes multi-module projects where the virtual environment is inside a subdirectory (e.g. `python-services/.venv/`)
- **`ide_find_symbol` exact `matchMode` was case-insensitive** - Changed from `name.equals(pattern, ignoreCase = true)` to `name == pattern`. `"CalendarService"` with `exact` no longer matches `calendarService` properties
- **`ide_find_references` duplicate entries for JSX components** - Opening and closing JSX tags (`<Foo>` / `</Foo>`) resolved to identical `file:line:column` positions, producing duplicate entries. Results are now deduplicated by position
- **`ide_find_references` `truncated` flag incorrectly true after deduplication** - `truncated` was computed as `totalFound > usages.size`, which fired whenever deduplication removed JSX tag duplicates. Now correctly set to `totalFound > maxResults` — only true when results were actually cut off by the limit
- **`ide_find_class` short/generic queries returning 0 results** - Increased `processNames` collection limit from 75 to 5000. The contributor's `processNames` emits names from broader scope (JDK/libraries) even when searching project scope; short patterns like "Tool" would fill the small buffer with library names before reaching project classes
- **`ide_find_class` language filter** - Filter applied at collection time in `processContributor` instead of post-filtering, preventing generic queries from returning 0 results when language filtering
- **`ide_find_symbol` language filter** - Collects 3x more from handlers when filtering and filters during collection loop
- **`ide_find_definition` on import statements** - Class imports now resolve correctly. Package-segment imports resolve to the package directory via `PsiPackage`/`PsiDirectory` handling instead of returning "Definition file not found"
- **`ide_find_definition` compiled class targets** - `effectiveTarget` now uses `navigationElement` when target resolves to compiled class in JAR
- **`ide_search_text` deduplication and false positives** - Results deduplicated by (file, line) and validated that the search word appears in the matched line
- **`ide_find_references` Processor pattern** - Uses streaming `Processor` with early termination instead of `findAll().take(n)` to avoid loading all results into memory
- **`ide_type_hierarchy` Kotlin language detection** - Uses `navigationElement.language.id` to correctly detect Kotlin types instead of reporting them as Java
- **`ide_find_file` build output duplicates** - Filters `bin/`, `build/`, `out/`, `.gradle/` output directories from results
- **`ide_search_text` returning results from worktrees and node_modules** - Search results were not filtered by excluded paths; now uses scope-based exclusion like all other search tools
- **`ide_file_structure` duplicate constructors for Java classes** - `PsiClass.methods` includes constructors in IntelliJ PSI, causing constructor entries to appear twice (once from `psiClass.constructors` and once from `psiClass.methods`). Now skips constructor entries when iterating methods

## [3.8.0] - 2026-02-19

### Added
- **Tool window footer links** - GitHub, Debugger MCP Server, and Buy Me a Coffee links in the toolbar for quick access
  - "Star/Report Issues" link to the GitHub repository
  - "Try Debugger MCP Server" link to the companion plugin on JetBrains Marketplace
  - "Buy Me a Coffee" link to support the developer

## [3.7.0] - 2026-02-19

### Added
- **New tool: `ide_get_active_file`** - Get the currently active file(s) open in the IDE editor
  - Returns cursor position (line, column), selected text, and language for all visible editors
  - Supports split panes (returns all visible editors)
  - Returns empty list (not error) when no editors are open
- **New tool: `ide_open_file`** - Open a file in the IDE editor with optional navigation
  - Navigate to specific line and column positions
  - Validates parameters (column requires line, line >= 1, column >= 1)

### Disabled by default
- `ide_get_active_file` and `ide_open_file` are disabled by default - enable in Settings > Index MCP Server

## [3.6.0] - 2026-02-18

### Added
- **Column numbers in navigation results for better inter-tool flows integration** - `ide_find_implementations`, `ide_call_hierarchy`, `ide_find_symbol`, and `ide_find_super_methods` now include 1-based `column` numbers in their output, matching the existing behavior of `ide_find_references`, `ide_find_definition`, `ide_diagnostics`, and `ide_search_text`

## [3.5.0] - 2026-02-18

### Added
- **Workspace project support** - All tools now correctly resolve paths when a JetBrains IDE opens a workspace with multiple sub-projects (modules with separate content roots)

### Fixed
- **SLF4J dependency conflict** - Excluded `org.slf4j` from Ktor dependencies to avoid classloader conflicts with the IDE's bundled SLF4J

## [3.4.0] - 2026-02-18

### Added
- **New tool: `ide_sync_files`** - Force the IDE to synchronize its virtual file system and PSI cache with external file changes on-demand
  - Use when files were created, modified, or deleted outside the IDE and other tools report stale results
  - Lightweight alternative to the global "Sync external file changes" setting
  - Optional `paths` parameter to sync specific files/directories instead of the entire project

## [3.3.4] - 2026-02-05

### Added
- **New tool: `ide_read_file`** - Read source file contents from project or library dependencies
  - Supports multiple file path formats: relative, absolute, jar paths (`path/to/lib.jar!/com/example/Class.java`), and jar URLs
  - Can read files by qualified class name (e.g., `java.util.ArrayList`)
  - Supports optional line range extraction with `startLine` and `endLine` parameters (1-based, inclusive)
  - Automatically detects library files and resolves jar file paths
  - Returns file metadata: language ID, line count, and whether it's a library file

### Changed
- **Enhanced library source navigation** - `ide_find_definition` and symbol resolution now prefer source files (`.java`) over compiled files (`.class`) when library sources are attached
  - Added `PsiUtils.getNavigationElement()` utility for consistent navigation element resolution
  - Improves readability when navigating to library code with attached sources

## [3.3.3] - 2026-02-03

### Fixed
- **Symbol navigation resolution** - `ide_find_class` and optimized symbol search now resolve file/line/name via navigation elements for accurate locations.

## [3.3.2] - 2026-02-02

### Fixed
- **Safe delete file protection** - `ide_refactor_safe_delete` no longer accidentally deletes files when positioned on whitespace/comments. Now returns nearby symbol suggestions instead of deleting the file.
- **File deletion mode** - Added explicit `target_type='file'` parameter to safely delete entire files (only succeeds if no external usages exist)

## [3.3.1] - 2026-02-01

### Fixed
- **Kotlin position resolution** - Position-based tools now correctly resolve Kotlin classes and methods when cursor is on a declaration (not just references)
  - Affected tools: `ide_type_hierarchy`, `ide_find_implementations`, `ide_call_hierarchy`, `ide_find_super_methods`
  - Root cause: `PsiTreeUtil.getParentOfType` doesn't match Kotlin PSI types (`KtClass`, `KtNamedFunction`)
  - Solution: Use reflection to find Kotlin PSI elements and convert to light classes

## [3.3.0] - 2026-01-27

### Added
- **New tool: `ide_find_class`** - Class/interface search using CLASS_EP_NAME index
- **New tool: `ide_find_file`** - File search using FILE_EP_NAME index
- **New tool: `ide_search_text`** - Text search using word index with context filtering (code/comments/strings)

### Disabled by default
 - ide_find_symbol

## [3.2.1] - 2026-01-26

### Fixed
- **Performance: Prevent IDE freezes during rapid tool calls** - Switched from blocking `readAction` to yielding `suspendingReadAction` in all tools. This prevents write lock starvation that caused IDE freezes when Claude Code's Explore agent fired many tool calls in succession.

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

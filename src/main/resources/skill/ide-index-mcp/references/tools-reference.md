# IDE Index MCP - Tools Reference

Complete parameter reference for all IDE MCP tools. All tools use JSON-RPC via MCP protocol.

## Common Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `project_path` | string, optional | Absolute path to project root. Required for multi-project workspaces. Omit for single-project setups. |
| `file` | string | For project files, path relative to project root (e.g., `src/main/App.java`). `ide_read_file` and some read-only position-based navigation tools also accept dependency/library paths returned by the plugin as absolute paths or `jar://` URLs; check each tool section because support is tool-specific. |
| `line` | integer | **1-based** line number |
| `column` | integer | **1-based** column number. Place on the symbol name, not whitespace. For dotted expressions like `json.dumps()` or `os.path.join()`, point to the member token (`dumps`, `join`) when targeting the member definition. |
| `language` | string | Language of the symbol (e.g., `"Java"`, `"PHP"`). Required when using `symbol`. |
| `symbol` | string | Fully qualified symbol reference. Java format: `com.example.ClassName`, `com.example.ClassName#memberName`. PHP format: `\\App\\Service\\UserService`, `\\App\\Service\\UserService::method()`, `\\App\\Service\\UserService::CONSTANT`, `\\App\\Service\\UserService::$property`, `\\App\\Service\\StatusEnum::ACTIVE`. PHP properties require the `$property` form; plain `::name` resolves enum cases (on enum types), constants, or methods. |

**Symbol reference:** Some tools accept `language` + `symbol` as an alternative to `file` + `line` + `column`. The two groups are **mutually exclusive**. Supported languages: Java, PHP, JavaScript, TypeScript. Unsupported languages are rejected explicitly; use `file` + `line` + `column` for other languages.

**JavaScript/TypeScript symbol grammar (v1):** Symbols must be module-qualified in one of these forms:
- `modulePath#exportName` — named export (e.g., `src/utils#formatDate`)
- `modulePath#default` — default export (e.g., `src/index#default`)
- `modulePath#ClassName.memberName` — class member (e.g., `src/models#User.validate`)

**Deterministic outcomes for JS/TS symbol resolution:**
- `unsupported_grammar` — symbol does not match accepted forms
- `not_found` — module path resolved but symbol not found in exports/members
- `ambiguous_match` — multiple matching exports/members across candidate files

**Fallback TypeScript cases:** Use `file` + `line` + `column` for local non-exported symbols, local import aliases, npm/package symbols, unresolved barrel/re-export chains, or any target that cannot be represented as a stable module-qualified export.

**Example fallback:**
```json
{
  "file": "src/utils/math.ts",
  "line": 18,
  "column": 12
}
```

**Note:** Module-qualified lookup remains v1 grammar and bounded; unsupported cases should fall back to `file` + `line` + `column`.

## Response Format

All tools return: `{ "content": [{"type": "text", "text": "<JSON>"}], "isError": false|true }`

Parse the `text` field as JSON for structured data.

---

## Navigation Tools

### ide_find_references
Find all usages of a symbol (semantic, not text search).

**Target (mutually exclusive):** `file`+`line`+`column` OR `language`+`symbol`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | no | Include references in generated sources (KSP/Dagger/annotation-processor output). **Default true** — keeps valid runtime references (Dagger/MapStruct/gRPC/serializers). Set false to drop generated call sites when they dominate results on injected symbols. |
| `maxResults` | integer | no | Deprecated alias for `pageSize`. Default 100, max 500 |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 100, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ usages: [{ file, line, column, context, type, astPath }], totalCount, truncated, nextCursor?, hasMore, totalCollected, offset, pageSize, stale }`
**Pagination note**: `truncated` mirrors `hasMore`; when `hasMore` is `true`, pass `nextCursor` to fetch the next page.
**type values**: `METHOD_CALL`, `FIELD_ACCESS`, `IMPORT`, `PARAMETER`, `VARIABLE`, `REFERENCE`

### ide_find_definition
Go to where a symbol is defined.

**Target (mutually exclusive):** `file`+`line`+`column` OR `language`+`symbol`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `fullElementPreview` | boolean | no | Return full element code (default false) |
| `maxPreviewLines` | integer | no | Max lines for full preview (default 50, max 500) |
| `project_path` | string | no | Project root path |

**Returns**: `{ file, line, column, preview, symbolName, astPath }`
Handles: packages, compiled classes, library sources (jar: URLs).

### ide_find_class
Search for classes/interfaces by name using IDE's class index. Equivalent to Ctrl+N / Cmd+O.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | Class name pattern |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `language` | string | no | Filter: "Java", "Kotlin", "Python", etc. |
| `includeGenerated` | boolean | no | Include classes from generated sources (KSP/Dagger/annotation-processor output). Default false |
| `matchMode` | enum | no | `substring` (default), `prefix`, `exact` |
| `limit` | integer | no | Deprecated alias for `pageSize`. Default 25, max 500 |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 25, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ classes: [{name, qualifiedName, file, line, kind, language}], totalCount, query }`
**Path note**: Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.
**Matching**: CamelCase (`USvc` -> `UserService`), substring, wildcard (`User*Impl`).

### ide_find_file
Search for files by name using IDE's file index. Equivalent to Ctrl+Shift+N / Cmd+Shift+O.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | File name pattern |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | no | Include files under generated sources (KSP/Dagger/annotation-processor output). Default false |
| `limit` | integer | no | Deprecated alias for `pageSize`. Default 25, max 500 |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 25, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ files: [{name, path, directory}], totalCount, query }`
**Path note**: Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.

### ide_search_text
Search for text using IDE's pre-built word index for exact searches or IntelliJ Find in Files for regex searches.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | Text to search for; exact word unless `regex` is true |
| `regex` | boolean | no | Treat `query` as a regular expression. Default false |
| `context` | enum | no | `all` (default), `code`, `comments`, `strings` |
| `caseSensitive` | boolean | no | Default true |
| `filePattern` | string | no | IntelliJ file mask, e.g. `*.kt`, `*.java,!*Test.java` |
| `limit` | integer | no | Default 100, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ matches: [{file, line, column, context}], totalCount, query }`

### ide_find_implementations
Find implementations of interfaces, abstract classes, or abstract methods.

**Target (mutually exclusive):** `file`+`line`+`column` OR `language`+`symbol`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. For JS/TS, use module-qualified forms: `modulePath#exportName`, `modulePath#default`, or `modulePath#ClassName.memberName`. Required for symbol-based lookup. |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | no | Include implementations in generated sources (KSP/Dagger/annotation-processor output). Default false |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 100, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ implementations: [{name, file, line, column, kind, language}], totalCount, nextCursor?, hasMore, totalCollected, offset, pageSize, stale }`
**Languages**: Java, Kotlin, Python, JS/TS, PHP, Rust (not Go).

### ide_find_symbol (disabled by default)
Search for any code symbol (classes, methods, fields, functions) by name.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | Symbol name pattern. Matching follows IntelliJ's Go to Symbol popup, including qualified queries like `BasicSolver.run`. |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `language` | string | no | Filter by language |
| `includeGenerated` | boolean | no | Include symbols from generated sources (KSP/Dagger/annotation-processor output). Default false |
| `limit` | integer | no | Deprecated alias for `pageSize`. Default 25, max 500 |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 25, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ symbols: [{name, qualifiedName, file, line, kind, language}], totalCount, query }`
**Languages**: Java, Kotlin, Python, JS/TS, Go, PHP, Rust, plus other IDE-supplied symbol contributors where available.
**Path note**: Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.

### ide_find_super_methods
Find parent methods that a given method overrides or implements.

**Target (mutually exclusive):** `file`+`line`+`column` OR `language`+`symbol`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column (anywhere in method body works). Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. For JS/TS, use module-qualified forms: `modulePath#exportName`, `modulePath#default`, or `modulePath#ClassName.memberName`. Required for symbol-based lookup. |
| `project_path` | string | no | Project root path |

**Returns**: `{ method: {name, class, file, line}, hierarchy: [{name, class, file, line, isInterface}], totalCount }`
**Languages**: Java, Kotlin, Python, JS/TS, PHP (NOT Go, Rust).

### ide_type_hierarchy
Get complete type inheritance hierarchy (supertypes and subtypes).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `className` | string | no | FQN (preferred, faster). E.g., `com.example.MyClass` |
| `file` | string | no | Alternative: project-relative file path. Unlike other read-only navigation tools, `ide_type_hierarchy` file mode does not resolve dependency/library absolute paths or `jar://` URLs. |
| `line` | integer | no | Required with file |
| `column` | integer | no | Required with file |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | no | Include supertypes/subtypes in generated sources (KSP/Dagger/annotation-processor output). Default true — keeps generated types in the hierarchy |
| `project_path` | string | no | Project root path |

**Provide either** `className` **or** `file`+`line`+`column`.
**Returns**: `{ element: {name, file, kind, language, supertypes?}, supertypes: [{name, file, kind, language, supertypes?}], subtypes: [{name, file, kind, language, supertypes?}] }`
**Languages**: Java, Kotlin, Python, JS/TS, PHP, Rust.

### ide_call_hierarchy
Build call tree showing who calls a method or what a method calls.

**Target (mutually exclusive):** `file`+`line`+`column` OR `language`+`symbol`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. For JS/TS, use module-qualified forms: `modulePath#exportName`, `modulePath#default`, or `modulePath#ClassName.memberName`. Required for symbol-based lookup. |
| `direction` | enum | yes | `callers` or `callees` |
| `depth` | integer | no | Recursion depth (default 3, max 5) |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | no | Include callers/callees in generated sources (KSP/Dagger/annotation-processor output). Default true |
| `project_path` | string | no | Project root path |

**Returns**: `{ element: {name, file, line, column, language}, calls: [{name, file, line, column, language, children: [...]}] }`

### ide_file_structure (disabled by default)
Get hierarchical file structure like IDE's Structure panel.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative file path |
| `project_path` | string | no | Project root path |

**Returns**: `{ file, language, structure }` (formatted tree with types, modifiers, signatures, line numbers)
**Languages**: Java, Kotlin, Python, JS/TS, PHP, Markdown.

PHP support requires the PHP plugin and is available in PhpStorm or IntelliJ IDEA Ultimate with the PHP plugin enabled.

### ide_read_file (disabled by default)
Read file content by path or qualified name, including library/jar sources.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | no | File path (relative, absolute, or jar:// URL) |
| `qualifiedName` | string | no | Java/PHP FQN (e.g., `java.util.ArrayList`) |
| `startLine` | integer | no | 1-based start line |
| `endLine` | integer | no | 1-based end line |
| `project_path` | string | no | Project root path |

**Provide either** `file` **or** `qualifiedName`.
**Returns**: `{ file, content, language, lineCount, startLine?, endLine?, isLibraryFile }`

---

## Intelligence Tools

### ide_diagnostics
Analyze a file for errors, warnings, and available quick fixes/intentions.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative file path |
| `line` | integer | no | For intention lookup (default 1) |
| `column` | integer | no | For intention lookup (default 1) |
| `startLine` | integer | no | Filter problems to range |
| `endLine` | integer | no | Filter problems to range |
| `project_path` | string | no | Project root path |

**Returns**: `{ problems: [{message, severity, line, column, source}], intentions: [{name, description, familyName}], problemCount, intentionCount, analysisFresh, analysisTimedOut, analysisMessage }`
**Notes**: Open files use fresh daemon highlights. Closed files use public batch analysis, so `WEAK_WARNING` results and quick-fix intentions may be less complete unless the file is already open in an editor.
**Severity levels**: `ERROR`, `WARNING`, `WEAK_WARNING`

---

## Refactoring Tools

### ide_refactor_rename
Rename a symbol or file and update ALL references (semantic rename, not find-replace). Works across ALL languages.

**Target:** `file` + `targetType="file"` for file rename, or `file` + `targetType="symbol"` + `line` + `column` for symbol rename. Without `targetType`, legacy `null/null => file` and `line`+`column => symbol` behavior remains.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | conditional | Relative file path. Required for position-based lookup. |
| `targetType` | string | no | `symbol` or `file`. When `file`, placeholder `line`/`column` values are ignored. |
| `line` | integer | no | 1-based line for symbol rename. |
| `column` | integer | no | 1-based column for symbol rename. |
| `newName` | string | yes | New name for the symbol |
| `overrideStrategy` | enum | no | `rename_base` (default), `rename_only_current`, `ask` |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, affectedFiles: [paths], changesCount, message }`
**Auto-renames**: getters/setters, overriding methods, constructor params <-> fields, test classes.
**Supports IDE undo** (Ctrl+Z).

### ide_move_file
Move a file to a new directory. Applies language-aware reference, import, and package/namespace updates only when the IDE provides a semantic move backend for that file type.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative path of file to move |
| `destination` | string | yes | Target directory (relative to project root, created if needed) |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, affectedFiles: [paths], changesCount, message }`
**Supports IDE undo** (Ctrl+Z).

### ide_refactor_safe_delete (Java/Kotlin only)
Delete a symbol or file, checking for usages first.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative file path |
| `line` | integer | no | Required for target_type="symbol" |
| `column` | integer | no | Required for target_type="symbol" |
| `target_type` | enum | no | `symbol` (default) or `file` |
| `force` | boolean | no | Force delete even with usages (default false) |
| `project_path` | string | no | Project root path |

**Returns (success)**: `{ success, affectedFiles, changesCount, message }`
**Returns (blocked)**: `{ canDelete: false, elementName, usageCount, blockingUsages: [...], message }`
**Only available in**: IntelliJ IDEA, Android Studio (requires Java plugin).

### ide_reformat_code (disabled by default)
Reformat code per project style (.editorconfig, IDE settings). Equivalent to Ctrl+Alt+L / Cmd+Opt+L.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative file path |
| `startLine` | integer | no | 1-based start (requires endLine) |
| `endLine` | integer | no | 1-based end (requires startLine) |
| `optimizeImports` | boolean | no | Default true |
| `rearrangeCode` | boolean | no | Default true |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, affectedFiles, changesCount, message }`

---

## Project Tools

### ide_index_status
Check if IDE is ready for code intelligence operations.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Project root path |

**Returns**: `{ isDumbMode, isIndexing, indexingProgress? }`
When `isDumbMode: true`, most tools will fail. Wait and retry.

### ide_sync_files
Force sync IDE's virtual file system with external file changes.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `paths` | string[] | no | Relative paths to sync (empty = sync entire project) |
| `project_path` | string | no | Project root path |

**Returns**: `{ syncedPaths, syncedAll, message }`
Call this when files were created/modified outside the IDE and search tools miss them.

### ide_build_project (disabled by default)
Build project using IDE's build system (JPS, Gradle, Maven).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | For workspace sub-projects |
| `rebuild` | boolean | no | Full rebuild (default false = incremental) |
| `includeRawOutput` | boolean | no | Include raw build log (default false) |
| `timeoutSeconds` | integer | no | Build timeout (no timeout if omitted) |

**Returns**: `{ success, aborted, errors?, warnings?, buildMessages: [{message, file, line, column, severity}], truncated, rawOutput?, durationMs }`
Note: `errors`/`warnings` are `null` when no messages were captured (not 0).

### ide_reload_project (disabled by default)
Force-reload the project build model (Maven, Gradle, or both). Use after changing build files so IntelliJ resolves updated dependencies before diagnostics or builds. The reload is asynchronous.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Project root path |

**Returns**: text summary of scheduled Maven/Gradle reloads or skipped unlinked build systems.

### ide_import_modules (disabled by default, Maven plugin only)
Import one or more external Maven project directories as modules into the current IntelliJ project window. Already imported module roots are skipped.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `paths` | string[] | yes | Absolute directory paths to import; each must contain `pom.xml` |
| `project_path` | string | no | Project root path |

**Returns**: text summary of imported, skipped, and failed module paths.

### ide_set_power_save_mode (disabled by default)
Enable or disable IDE Power Save Mode (IDE-wide). Suspends background inspections and code analysis; the index and code intelligence tools stay functional.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `enabled` | boolean | yes | `true` to enable, `false` to disable |
| `project_path` | string | no | Project root path |

**Returns**: text confirmation, e.g. `Power Save Mode enabled (IDE-wide).`

### ide_close_project (disabled by default)
Close an open project window and free its memory. Non-blocking; returns once the close is scheduled. Refuses to close the last open project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Project root path (required when multiple projects are open) |

**Returns**: text confirmation, e.g. `Project 'name' is closing.`

### ide_open_project (disabled by default)
Open a project by absolute path and wait until indexing completes. Idempotent: returns immediately if the project is already open. May require a human to answer the IDE's "Trust project?" dialog for first-time projects.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Absolute path of the project directory |
| `timeoutSeconds` | integer | no | Max seconds to wait for open + indexing (default 600) |
| `project_path` | string | no | JSON-RPC context project when multiple are open |

**Returns**: text confirmation; on indexing timeout returns success with a note to check `ide_index_status`.

---

## Editor Tools

### ide_get_active_file (disabled by default)
Get currently active file(s) in editor with cursor position and selection.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Project root path |

**Returns**: `{ activeFiles: [{file, line, column, selectedText, language}] }`

### ide_open_file (disabled by default)
Open a file in the editor with optional navigation.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative or absolute path |
| `line` | integer | no | 1-based line to navigate to |
| `column` | integer | no | 1-based column (requires line) |
| `project_path` | string | no | Project root path |

**Returns**: `{ file, opened, message }`

### ide_open_workspace (disabled by default)
Scan a root directory for Maven projects in immediate subdirectories and open them all in a single IntelliJ window. Creates a temporary aggregator POM with relative module paths. Uses `MavenProjectAsyncBuilder.commitSync()` to wait for Maven import completion before returning.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Absolute path to root directory containing Maven project subdirectories |
| `timeoutSeconds` | integer | no | Max seconds to wait for opening + indexing (default: 600) |
| `project_path` | string | no | Project root path (routing context) |

**Returns**: text listing all discovered modules and content root count, or error if no Maven projects found.

---

## Plugin Development Tools

### ide_install_plugin (disabled by default)
Install a plugin zip into the IDE, replacing any existing version. Auto-detects the newest `build/distributions/*.zip` of the active project when `path` is omitted. Requires `ide_restart` to load the new version.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | no | Absolute path to the plugin zip (default: auto-detect) |
| `project_path` | string | no | Project root path when `path` is omitted |

**Returns**: text confirmation with the installed plugin id and zip name.

### ide_restart (disabled by default)
Restart the IDE. Terminates the MCP connection immediately — reconnect after the IDE comes back up.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Project root path |

**Returns**: text confirmation; the connection drops right after.

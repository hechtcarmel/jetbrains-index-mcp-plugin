# IDE Index MCP - Tools Reference

Complete parameter reference for all IDE MCP tools. All tools use JSON-RPC via MCP protocol.

## Common Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `project_path` | string, optional | Absolute path to project root. Required for multi-project workspaces. Omit for single-project setups. |
| `file` | string | For project files, path relative to project root (e.g., `src/main/App.java`). `ide_read_file` and some read-only position-based navigation tools also accept dependency/library paths returned by the plugin as absolute paths or `jar://` URLs; check each tool section because support is tool-specific. |
| `line` | integer | **1-based** line number |
| `column` | integer | **1-based** column number. Place on the symbol name, not whitespace. For dotted expressions like `json.dumps()` or `os.path.join()`, point to the member token (`dumps`, `join`) when targeting the member definition. |
| `language` | string | Language of the symbol (e.g., `"Java"`). Required when using `symbol`. |
| `symbol` | string | Fully qualified symbol reference. Format: `com.example.ClassName`, `com.example.ClassName#memberName`. |

**Symbol reference:** Some tools accept `language` + `symbol` as an alternative to `file` + `line` + `column`. The two groups are **mutually exclusive**. Currently supported for Java only. Unsupported languages are rejected explicitly; use `file` + `line` + `column` for other languages.

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
| `limit` | integer | no | Deprecated alias for `pageSize`. Default 25, max 500 |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 25, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ files: [{name, path, directory}], totalCount, query }`
**Path note**: Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.

### ide_search_text
Search for exact words using IDE's pre-built word index. O(1) lookups, not file scanning.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | Exact word (NOT regex/pattern) |
| `context` | enum | no | `all` (default), `code`, `comments`, `strings` |
| `caseSensitive` | boolean | no | Default true |
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
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 100, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ implementations: [{name, file, line, column, kind, language}], totalCount, nextCursor?, hasMore, totalCollected, offset, pageSize, stale }`
**Languages**: Java, Kotlin, Python, JS/TS, PHP, Rust (not Go).

### ide_find_symbol (disabled by default)
Search for any symbol (classes, methods, fields, functions) or Markdown heading by name.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | Symbol name pattern. Matching follows IntelliJ's Go to Symbol popup, including qualified queries like `BasicSolver.run`. |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `language` | string | no | Filter by language |
| `limit` | integer | no | Deprecated alias for `pageSize`. Default 25, max 500 |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 25, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ symbols: [{name, qualifiedName, file, line, kind, language}], totalCount, query }`
**Languages**: Java, Kotlin, Python, JS/TS, Go, PHP, Rust, Markdown (headings).
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
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
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
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `direction` | enum | yes | `callers` or `callees` |
| `depth` | integer | no | Recursion depth (default 3, max 5) |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `project_path` | string | no | Project root path |

**Returns**: `{ element: {name, file, line, column, language}, calls: [{name, file, line, column, language, children: [...]}] }`

### ide_file_structure (disabled by default)
Get hierarchical file structure like IDE's Structure panel.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative file path |
| `project_path` | string | no | Project root path |

**Returns**: `{ file, language, structure }` (formatted tree with types, modifiers, signatures, line numbers)
**Languages**: Java, Kotlin, Python, JS/TS, Markdown.

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
Rename a symbol and update ALL references (semantic rename, not find-replace). Works across ALL languages.

**Target (mutually exclusive):** `file`+`line`+`column` OR `language`+`symbol`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | conditional | Relative file path. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
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

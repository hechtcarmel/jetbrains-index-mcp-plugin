# IDE Index MCP Server - Tool Reference

This document provides detailed documentation for all MCP tools available in the IDE Index MCP Server plugin.

## Tool Availability by IDE

Tools are organized into categories based on IDE compatibility:

### Universal Tools (All Supported JetBrains IDEs)

These tools work in every supported JetBrains IDE:

| Tool | Description | Default |
|------|-------------|---------|
| `ide_find_references` | Find all references to a symbol | Enabled |
| `ide_find_definition` | Find symbol definition location | Enabled |
| `ide_find_class` | Search classes/interfaces by name | Enabled |
| `ide_find_file` | Search files by name | Enabled |
| `ide_find_symbol` | Search code symbols by name *(disabled by default)* | Disabled |
| `ide_search_text` | Text search using word index | Enabled |
| `ide_diagnostics` | Analyze file problems with fresh IDE diagnostics, plus optional build/test results | Enabled |
| `ide_index_status` | Check indexing status | Enabled |
| `ide_sync_files` | Force sync VFS/PSI cache | Enabled |
| `ide_reload_project` | Reload linked Maven/Gradle build models | Disabled |
| `ide_import_modules` | Import external Maven projects as modules | Disabled |
| `ide_open_workspace` | Scan root directory for Maven projects, or open an explicit module list, in one window | Disabled |
| `ide_build_project` | Build project with structured errors | Disabled |
| `ide_run_tests` | Run tests via run configs; structured pass/fail results from the IDE's test runner (any framework). FQN class/method targeting is Java/Kotlin-only; other languages pass an existing run-config name | Disabled |
| `ide_read_file` | Read file content by path or qualified name | Disabled |
| `ide_get_active_file` | Get currently active editor file(s) | Disabled |
| `ide_open_file` | Open file in editor with navigation | Disabled |
| `ide_refactor_rename` | Rename symbol with reference updates (all languages) | Enabled |
| `ide_move_file` | Move file to new directory with IDE-aware move semantics | Enabled |
| `ide_reformat_code` | Reformat code using project code style | Disabled |
| `ide_optimize_imports` | Optimize imports without reformatting code | Disabled |
| `ide_structural_search_replace` | Pattern-based code search and transformation (Java, Kotlin) | Disabled |
| `ide_change_signature` | Change method signature with automatic caller updates (Java) | Disabled |
| `ide_create_file` | Create a new source file with content, immediately indexed by IntelliJ | Disabled |
| `ide_replace_text_in_file` | Find and replace text using IntelliJ's Document API | Disabled |
| `ide_edit_member` | Replace an entire member declaration (signature + body) with new content (Java, Kotlin) | Disabled |
| `ide_insert_member` | Insert a new member at a structural position (Java, Kotlin) | Disabled |
| `ide_replace_member` | Replace method body or field initializer, preserving signature (Java, Kotlin) | Disabled |

## Extended Tools (Language-Aware)

These tools activate based on available language plugins:

| Tool | Description | Languages |
|------|-------------|-----------|
| `ide_type_hierarchy` | Get type inheritance hierarchy | Java, Kotlin, Python, JS/TS, Go, PHP, Rust |
| `ide_call_hierarchy` | Analyze method call relationships | Java, Kotlin, Python, JS/TS, Go, PHP, Rust |
| `ide_find_implementations` | Find interface implementations | Java, Kotlin, Python, JS/TS, PHP, Rust |
| `ide_find_super_methods` | Find overridden methods | Java, Kotlin, Python, JS/TS, PHP |
| `ide_file_structure` | Hierarchical file structure with start/end line numbers *(disabled by default)* | Java, Kotlin, Python, JS/TS, PHP, Markdown |

### Java-Specific Tools

| Tool | Description |
|------|-------------|
| `ide_list_tests` | List all test methods/classes discovered by the IDE's test frameworks *(disabled by default)* |
| `ide_convert_java_to_kotlin` | Convert Java files to Kotlin using the IDE converter *(disabled by default)* |
| `ide_refactor_safe_delete` | Safely delete with usage check |

### Project Lifecycle Management Tools

These tools work in all supported JetBrains IDEs; defaults are listed per tool.

| Tool | Description | Default |
|------|-------------|---------|
| `ide_project_status` | Combined view of all open and managed projects with mode per row | Enabled |
| `ide_set_project_mode` | Set a project's lifecycle mode (active/background/dormant/closed) | Disabled |
| `ide_get_project_modes` | List all managed projects and their current modes | Disabled |
| `ide_set_all_project_modes` | Set all managed open projects to the same mode | Disabled |
| `ide_enroll_all_projects` | Enroll all currently open projects in lifecycle management | Disabled |
| `ide_release_project` | Remove a project from lifecycle management | Disabled |
| `ide_release_all_projects` | Release all managed projects from lifecycle management at once | Disabled |
| `ide_lifecycle_log` | Query recent lifecycle events with trigger reasons | Disabled |
| `ide_set_lifecycle_log_file` | Enable or disable persistent lifecycle log file writes | Disabled |
| `ide_set_power_save_mode` | Toggle Power Save Mode directly | Enabled |
| `ide_close_project` | Close a project window | Enabled |
| `ide_open_project` | Open a project by path and wait for indexing | Enabled |
| `ide_install_plugin` | Install a plugin zip into the IDE | Enabled |
| `ide_restart` | Restart the IDE | Enabled |

---

**Claude Code users:** To enforce IDE tool usage and prevent agents from falling back to grep/sed/Edit,
see [Claude Code Hooks](docs/claude-code-hooks.md) for ready-to-use `PreToolUse` hook scripts.

---

## Table of Contents

- [Common Parameters](#common-parameters)
- [Universal Tools](#universal-tools)
  - [ide_find_references](#ide_find_references)
  - [ide_find_definition](#ide_find_definition)
  - [ide_find_class](#ide_find_class)
  - [ide_find_file](#ide_find_file)
  - [ide_search_text](#ide_search_text)
  - [ide_find_symbol](#ide_find_symbol)
  - [ide_diagnostics](#ide_diagnostics)
  - [ide_index_status](#ide_index_status)
  - [ide_sync_files](#ide_sync_files)
  - [ide_reload_project](#ide_reload_project)
  - [ide_import_modules](#ide_import_modules)
  - [ide_open_workspace](#ide_open_workspace)
  - [ide_build_project](#ide_build_project)
  - [ide_run_tests](#ide_run_tests)
  - [ide_read_file](#ide_read_file)
  - [ide_get_active_file](#ide_get_active_file)
  - [ide_open_file](#ide_open_file)
- [Plugin Development](#plugin-development)
  - [ide_install_plugin](#ide_install_plugin)
  - [ide_restart](#ide_restart)
- [Project Window Management](#project-window-management)
  - [ide_set_power_save_mode](#ide_set_power_save_mode)
  - [ide_close_project](#ide_close_project)
  - [ide_open_project](#ide_open_project)
- [Refactoring Tools](#refactoring-tools)
  - [ide_refactor_rename](#ide_refactor_rename)
  - [ide_move_file](#ide_move_file)
  - [ide_reformat_code](#ide_reformat_code)
  - [ide_optimize_imports](#ide_optimize_imports)
  - [ide_structural_search_replace](#ide_structural_search_replace)
  - [ide_change_signature](#ide_change_signature)
  - [ide_create_file](#ide_create_file)
  - [ide_replace_text_in_file](#ide_replace_text_in_file)
  - [ide_edit_member](#ide_edit_member)
  - [ide_insert_member](#ide_insert_member)
  - [ide_replace_member](#ide_replace_member)
- [Extended Tools (Language-Aware)](#extended-tools-language-aware)
  - [ide_type_hierarchy](#ide_type_hierarchy)
  - [ide_call_hierarchy](#ide_call_hierarchy)
  - [ide_find_implementations](#ide_find_implementations)
  - [ide_find_super_methods](#ide_find_super_methods)
  - [ide_file_structure](#ide_file_structure)
- [Project Lifecycle Management](#project-lifecycle-management)
  - [ide_project_status](#ide_project_status)
  - [ide_set_project_mode](#ide_set_project_mode)
  - [ide_get_project_modes](#ide_get_project_modes)
  - [ide_set_all_project_modes](#ide_set_all_project_modes)
  - [ide_release_project](#ide_release_project)
  - [ide_release_all_projects](#ide_release_all_projects)
  - [ide_enroll_all_projects](#ide_enroll_all_projects)
  - [ide_lifecycle_log](#ide_lifecycle_log)
  - [ide_set_power_save_mode](#ide_set_power_save_mode)
  - [ide_close_project](#ide_close_project)
  - [ide_open_project](#ide_open_project)
  - [ide_install_plugin](#ide_install_plugin)
  - [ide_restart](#ide_restart)
- [Java-Specific Tools](#java-specific-tools)
  - [ide_list_tests](#ide_list_tests)
  - [ide_convert_java_to_kotlin](#ide_convert_java_to_kotlin)
  - [ide_refactor_safe_delete](#ide_refactor_safe_delete)
- [Error Handling](#error-handling)

---

## Common Parameters

All tools accept an optional `project_path` parameter:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Absolute path to the project root. Required when multiple projects are open in the IDE. For workspace projects, use the sub-project path. |

### Position Parameters

Most tools operate on a specific location in code and require these parameters:

| Parameter | Type | Description |
|-----------|------|-------------|
| `file` | string | For project files, path relative to project root (e.g., `src/main/java/MyClass.java`). `ide_read_file` and read-only position-based navigation tools also accept dependency/library paths returned by the plugin as absolute paths or `jar://` URLs. |
| `line` | integer | 1-based line number |
| `column` | integer | 1-based column number. For dotted expressions like `json.dumps()` or `os.path.join()`, point to the member token (`dumps`, `join`) when targeting the member definition. |

### Symbol Reference Parameters

Some tools support identifying the target element by fully qualified symbol reference instead of file position. The following parameters are available as an alternative to `file` + `line` + `column`:

| Parameter | Type | Description |
|-----------|------|-------------|
| `language` | string | Language of the symbol (e.g., `"Java"`). Required when using `symbol`. Unsupported languages are rejected at runtime; use `file` + `line` + `column` for languages without symbol-reference support. |
| `symbol` | string | Fully qualified symbol reference. **Java format:** `com.example.ClassName`, `com.example.ClassName#memberName`. **JS/TS format:** `modulePath#exportName`, `modulePath#default`, or `modulePath#ClassName.memberName`. |

**Important:** The two parameter groups are **mutually exclusive** — provide either `file` + `line` + `column` OR `language` + `symbol`, not both.

**Supported languages:** Java, JS, TS, and Python. Unsupported languages return an explicit error listing the currently supported symbol-reference languages.

**Python symbol grammar:** Symbols must be module-qualified (dotted path with ≥2 segments):
- `pkg.mod.ClassName` — class
- `pkg.mod.function_name` — module-level function
- `pkg.mod.ClassName.method_name` — method (resolved via the function index; a method's qualified name is `pkg.mod.ClassName.method`)
- `pkg.mod.ClassName#member_name` — method, class/instance attribute, or `@property` of the named class

Parameter lists are not supported (Python has no overload-by-signature); bare unqualified names are rejected — use `file` + `line` + `column` for those.

**JS/TS symbol grammar (v1):** Symbols must be module-qualified:
- `modulePath#exportName` — named export (e.g., `src/utils#formatDate`)
- `modulePath#default` — default export (e.g., `src/index#default`)
- `modulePath#ClassName.memberName` — class member (e.g., `src/models#User.validate`)

**Deterministic outcomes for JS/TS symbol resolution:**
- `unsupported_grammar` — symbol does not match accepted forms
- `not_found` — module path resolved but symbol not found in exports/members
- `ambiguous_match` — multiple matching exports/members across candidate files

**Fallback TypeScript cases:** Use `file` + `line` + `column` when targeting local non-exported symbols, local import aliases, npm/package symbols, unresolved barrel/re-export chains, or any case that cannot be expressed as a stable module-qualified export.

**Example fallback request:**
```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_definition",
    "arguments": {
      "file": "src/utils/math.ts",
      "line": 18,
      "column": 12
    }
  }
}
```

**Note:** Module-qualified lookup remains v1 grammar and bounded; unsupported cases should fall back to `file` + `line` + `column`.

**Tools that support symbol references:** `ide_find_references`, `ide_find_definition`, `ide_call_hierarchy`, `ide_find_implementations`, `ide_find_super_methods`.

---

## Universal Tools

These tools work in all JetBrains IDEs (IntelliJ, PyCharm, WebStorm, GoLand, etc.).

### ide_find_references

Finds all references to a symbol across the entire project using IntelliJ's semantic index.

**Use when:**
- Locating where a method, class, variable, or field is called or accessed
- Understanding code dependencies
- Preparing for refactoring

**Target (mutually exclusive):** `file` + `line` + `column` OR `language` + `symbol`

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | Conditional | 1-based line number. Required for position-based lookup. |
| `column` | integer | Conditional | 1-based column number. Required for position-based lookup. |
| `language` | string | Conditional | Language of the symbol (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | Conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `scope` | string | No | Built-in search scope. One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | No | Include references in generated sources (KSP/Dagger/annotation-processor output). **Default: true** — keeps valid runtime references from generated DI factories, MapStruct mappers, gRPC stubs, and serializers. Set `false` to drop generated call sites when they dominate results. |
| `maxResults` | integer | No | Deprecated alias for `pageSize` (default: 100, max: 500) |
| `cursor` | string | No | Pagination cursor from a previous response |
| `pageSize` | integer | No | Number of results per page (default: 100, max: 500) |

**Example Request (position-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_references",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 15,
      "column": 20
    }
  }
}
```

**Example Request (symbol-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_references",
    "arguments": {
      "language": "Java",
      "symbol": "com.example.UserService#findUser(String)",
      "scope": "project_and_libraries"
    }
  }
}
```

**Example Response:**

```json
{
  "usages": [
    {
      "file": "src/main/java/com/example/UserController.java",
      "line": 42,
      "column": 15,
      "context": "userService.findUser(id)",
      "type": "METHOD_CALL",
      "astPath": ["UserController", "getUser"]
    },
    {
      "file": "src/test/java/com/example/UserServiceTest.java",
      "line": 28,
      "column": 10,
      "context": "service.findUser(\"test\")",
      "type": "METHOD_CALL",
      "astPath": ["UserServiceTest", "testFindUser"]
    }
  ],
  "totalCount": 2,
  "truncated": false,
  "nextCursor": null,
  "hasMore": false,
  "totalCollected": 2,
  "offset": 0,
  "pageSize": 100,
  "stale": false
}
```

**Reference Types:**
- `METHOD_CALL` - Method invocation
- `FIELD_ACCESS` - Field read/write
- `REFERENCE` - General reference
- `IMPORT` - Import statement
- `PARAMETER` - Method parameter
- `VARIABLE` - Variable usage

---

### ide_find_definition

Finds the definition/declaration location of a symbol at a given source location.

**Use when:**
- Understanding where a method, class, variable, or field is declared
- Looking up the original definition from a usage site

**Target (mutually exclusive):** `file` + `line` + `column` OR `language` + `symbol`

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | Conditional | 1-based line number. Required for position-based lookup. |
| `column` | integer | Conditional | 1-based column number. Required for position-based lookup. |
| `language` | string | Conditional | Language of the symbol (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | Conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `maxPreviewLines` | integer | No | Limit `fullElementPreview` output size (default: 50, max: 500) |

**Example Request (position-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_definition",
    "arguments": {
      "file": "src/main/java/com/example/App.java",
      "line": 25,
      "column": 12
    }
  }
}
```

**Example Request (symbol-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_definition",
    "arguments": {
      "language": "Java",
      "symbol": "com.example.UserService#findUser(String)"
    }
  }
}
```

**Example Response:**

```json
{
  "file": "src/main/java/com/example/UserService.java",
  "line": 15,
  "column": 17,
  "preview": "14:     \n15:     public User findUser(String id) {\n16:         return userRepository.findById(id);\n17:     }",
  "symbolName": "findUser",
  "astPath": ["UserService"]
}
```

**Path note:** Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.

---

### ide_find_class

Searches for classes and interfaces by name using the IDE's class index.

**Use when:**
- Finding a class by name when you don't know the file path
- Discovering all classes matching a pattern

**Matching modes:**
- Substring: `"Service"` matches `"UserService"`, `"OrderService"`
- CamelCase: `"USvc"` matches `"UserService"`
- Wildcard: `"User*Impl"` matches `"UserServiceImpl"`
- Exact: case-sensitive exact match

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Search pattern |
| `scope` | string | No | Built-in search scope. One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `language` | string | No | Filter by language (e.g., `"Kotlin"`, `"Java"`, `"Python"`). Case-insensitive |
| `includeGenerated` | boolean | No | Include classes from generated sources (KSP/Dagger/annotation-processor output). Default: false |
| `matchMode` | string | No | `"substring"` (default), `"prefix"`, or `"exact"` |
| `limit` | integer | No | Deprecated alias for `pageSize` (default: 25, max: 500) |
| `cursor` | string | No | Pagination cursor from a previous response |
| `pageSize` | integer | No | Number of results per page (default: 25, max: 500) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_class",
    "arguments": {
      "query": "UserService",
      "language": "Kotlin",
      "scope": "project_files"
    }
  }
}
```

**Example Response:**

```json
{
  "classes": [
    {
      "name": "UserService",
      "qualifiedName": "com.example.service.UserService",
      "kind": "INTERFACE",
      "file": "src/main/kotlin/com/example/service/UserService.kt",
      "line": 12,
      "column": 18
    }
  ],
  "totalCount": 1,
  "query": "UserService"
}
```

**Path note:** Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.

---

### ide_find_file

Searches for files by name using the IDE's file index.

**Use when:**
- Finding a file when you know part of its name
- Discovering test files, config files, etc.

**Matching:** CamelCase (`"USJ"` matches `"UserService.java"`), substring, and wildcard (`"*Test.kt"`).

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | File name pattern |
| `scope` | string | No | Built-in search scope. One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | No | Include files under generated sources (KSP/Dagger/annotation-processor output). Default: false |
| `limit` | integer | No | Deprecated alias for `pageSize` (default: 25, max: 500) |
| `cursor` | string | No | Pagination cursor from a previous response |
| `pageSize` | integer | No | Number of results per page (default: 25, max: 500) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_file",
    "arguments": {
      "query": "UserService",
      "scope": "project_and_libraries"
    }
  }
}
```

**Example Response:**

```json
{
  "files": [
    {
      "name": "UserService.kt",
      "path": "src/main/kotlin/com/example/service/UserService.kt",
      "directory": "src/main/kotlin/com/example/service"
    }
  ],
  "totalCount": 1,
  "query": "UserService"
}
```

**Path note:** Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.

---

### ide_search_text

Searches for text using the IDE's pre-built word index. Significantly faster than file scanning.

**Use when:**
- Searching for exact word occurrences across the codebase
- Finding string literals, comments, or code patterns
- Filtering searches by context (code only, comments only, strings only)

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Text to search for; treated as an exact word unless `regex` is `true` |
| `regex` | boolean | No | Treat `query` as a regular expression (default: false) |
| `context` | string | No | Where to search: `"code"`, `"comments"`, `"strings"`, or `"all"` (default) |
| `caseSensitive` | boolean | No | Case sensitive search (default: true) |
| `filePattern` | string | No | IntelliJ file mask to filter files by name (e.g., `"*.kt"`, `"*.gradle.kts"`, `"*.java,!*Test.java"`) |
| `limit` | integer | No | Maximum results (default: 100, max: 500) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_search_text",
    "arguments": {
      "query": "TODO",
      "context": "comments",
      "filePattern": "*.kt"
    }
  }
}
```

Regex example:

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_search_text",
    "arguments": {
      "query": "Runtime\\.getRuntime\\(\\)\\.exec\\(",
      "regex": true,
      "context": "code",
      "filePattern": "*.java"
    }
  }
}
```

**Example Response:**

```json
{
  "matches": [
    {
      "file": "src/main/kotlin/com/example/UserService.kt",
      "line": 42,
      "column": 8,
      "context": "// TODO: add caching",
      "contextType": "COMMENT"
    }
  ],
  "totalCount": 1,
  "query": "TODO"
}
```

---

### ide_diagnostics

> **Availability**: Universal Tool - works in all JetBrains IDEs

Analyzes code diagnostics from three sources:
- fresh per-file IDE analysis for problems (errors, warnings),
- optional build output from the last build,
- optional test results from open test run tabs.

File problems are collected through explicit daemon analysis, so they do not depend on the target project window being active. Intentions/quick fixes are best-effort and require the file to already be open in an editor.

**Use when:**
- Finding code issues in a file
- Checking code quality
- Identifying potential bugs
- Discovering available code improvements
- Reading recent build errors without parsing console output
- Inspecting failing tests from open test run tabs

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | No | 1-based line number for intention lookup (default: 1) |
| `column` | integer | No | 1-based column number for intention lookup (default: 1) |
| `startLine` | integer | No | Filter problems to start from this line |
| `endLine` | integer | No | Filter problems to end at this line |
| `includeBuildErrors` | boolean | No | Include errors/warnings from the last build (default: `false`) |
| `includeTestResults` | boolean | No | Include test results from open test run tabs (default: `false`) |
| `severity` | string | No | Filter diagnostics by `all`, `errors`, or `warnings` (default: `all`) |
| `testResultFilter` | string | No | Filter test results by `failed` or `all` (default: `failed`) |
| `maxBuildErrors` | integer | No | Maximum build messages to return (default: 100, max: 500) |
| `maxTestResults` | integer | No | Maximum test results to return (default: 100, max: 500) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_diagnostics",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java"
    }
  }
}
```

**Example Response:**

```json
{
  "problems": [
    {
      "message": "Cannot resolve symbol 'UnknownType'",
      "severity": "ERROR",
      "file": "src/main/java/com/example/UserService.java",
      "line": 12,
      "column": 9,
      "endLine": 12,
      "endColumn": 20
    }
  ],
  "intentions": [],
  "problemCount": 1,
  "intentionCount": 0,
  "analysisFresh": true,
  "analysisTimedOut": false,
  "analysisMessage": "Intentions are unavailable because the file is not open in an editor."
}
```

**Response Notes:**
- `analysisFresh = true` means the file problems came from a fresh explicit IDE analysis pass instead of cached editor highlights.
- `analysisTimedOut = true` means the file analysis budget was exceeded; build/test sections may still be returned.
- `analysisMessage` explains degraded cases such as timeouts or missing live editor context for intentions.
- `line` and `column` affect intention lookup only; file problems are collected for the whole file, then filtered by `startLine` / `endLine` if provided.

**Severity Values:**
- `ERROR` - Compilation error
- `WARNING` - Potential problem
- `WEAK_WARNING` - Minor issue
- `INFO` - Informational

---

### ide_index_status

> **Availability**: Universal Tool - works in all JetBrains IDEs

Checks if the IDE is in dumb mode (indexing) or smart mode.

**Use when:**
- Checking if index-dependent operations will work
- Waiting for indexing to complete

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | | | No parameters required |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_index_status",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "isDumbMode": false,
  "isSmartMode": true,
  "isIndexing": false,
  "projectName": "my-application"
}
```

---

### ide_sync_files

Force the IDE to synchronize its virtual file system and PSI cache with external file changes.

**Use when:**
- Files were created, modified, or deleted outside the IDE (e.g., by coding agents)
- Other IDE tools report stale results or miss references in recently changed files

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `paths` | array of strings | No | File or directory paths relative to project root to sync. If omitted, syncs the entire project |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_sync_files",
    "arguments": {
      "paths": ["src/main/java/com/example/NewFile.java"]
    }
  }
}
```

**Example Response:**

```json
{
  "syncedPaths": ["src/main/java/com/example/NewFile.java"],
  "syncedAll": false,
  "message": "Synced 1 path(s)"
}
```

---

### ide_reload_project

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Force-reload the project build model (Maven, Gradle, or both). Equivalent to clicking **"Reload All Maven Projects"** or **"Reload Gradle Project"** in the IDE.

Use this after modifying `pom.xml`, `build.gradle`, `build.gradle.kts`, `settings.gradle`, or any dependency configuration file so that IntelliJ resolves the updated dependencies before running diagnostics or builds. The reload is asynchronous — IntelliJ resolves dependencies in the background.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Selects the project when multiple are open |

**Example:**

```json
{ "name": "ide_reload_project", "arguments": {} }
```

**Example Response:**

```
Build model reload scheduled for Maven in 'engine'. IntelliJ is resolving dependencies in the background.
```

---

### ide_import_modules

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server
> **Requires**: Maven plugin

Import one or more external Maven project directories as modules into the current IntelliJ window, enabling cross-project code intelligence and refactoring. Already imported module roots are skipped.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `paths` | array of strings | Yes | Absolute directory paths to import. Each directory must contain a `pom.xml`. |
| `project_path` | string | No | Selects the IntelliJ project window when multiple are open |

**Example:**

```json
{
  "name": "ide_import_modules",
  "arguments": {
    "paths": ["/Users/dev/casehub/drafthouse", "/Users/dev/casehub/worker"]
  }
}
```

**Example Response:**

```
Imported 2 module(s):
  + /Users/dev/casehub/drafthouse
  + /Users/dev/casehub/worker
```

---

### ide_open_workspace

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server
> **Requires**: Maven plugin

Scan a root directory for Maven projects and open them all in one IntelliJ window with full cross-project code intelligence. Alternatively, provide an explicit list of Maven project paths. Creates a temporary aggregator POM with relative module paths.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | No* | Root directory to scan for Maven projects (each must contain a `pom.xml`). Mutually exclusive with `modules`. |
| `modules` | array of strings | No* | Explicit list of absolute paths to Maven project directories. Mutually exclusive with `path`. Uses SHA-based caching so the same module combination reuses the cached workspace. |
| `timeoutSeconds` | integer | No | Timeout in seconds for opening and indexing (default: 600) |
| `project_path` | string | No | Selects the IntelliJ project window when multiple are open |

*Either `path` or `modules` must be provided, but not both.

**Example (scan directory):**

```json
{
  "name": "ide_open_workspace",
  "arguments": {
    "path": "/Users/dev/monorepo"
  }
}
```

**Example (explicit modules):**

```json
{
  "name": "ide_open_workspace",
  "arguments": {
    "modules": [
      "/Users/dev/casehub/platform",
      "/Users/dev/casehub/engine",
      "/Users/dev/casehub/worker"
    ]
  }
}
```

**Example Response:**

```
Workspace opened with 3 Maven projects from /Users/dev/monorepo. IntelliJ is indexing in the background.
```

---

### ide_build_project

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Build the project using the IDE's build system (supports JPS, Gradle, Maven).

**Use when:**
- Checking for compilation errors after code changes
- Verifying that refactoring didn't break anything
- Getting structured error messages with file locations

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `rebuild` | boolean | No | Full rebuild instead of incremental build (default: false) |
| `includeRawOutput` | boolean | No | Include raw build output log (default: false) |
| `timeoutSeconds` | integer | No | Timeout in seconds. No timeout if omitted |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_build_project",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "success": false,
  "aborted": false,
  "errors": 1,
  "warnings": 2,
  "buildMessages": [
    {
      "category": "ERROR",
      "message": "Unresolved reference: fooBar",
      "file": "src/main/kotlin/com/example/App.kt",
      "line": 15,
      "column": 10
    }
  ],
  "truncated": false,
  "durationMs": 3200
}
```

---

### ide_list_tests

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server
> **Availability**: Requires Java plugin — only available in **IntelliJ IDEA** and **Android Studio** (uses the `com.intellij.testFramework` extension point declared by the Java plugin)

List all test methods discovered by the IDE's test framework extension points (JUnit, TestNG, etc.).

**Use when:**
- Discovering what tests exist before running them
- Finding the exact FQN of a test class or method to pass to `ide_run_tests`
- Checking whether a new test file was picked up by the IDE

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Absolute path to the project root (required when multiple projects are open) |
| `file` | string | No | Path to a specific test file relative to project root. If omitted, all test sources are scanned |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_list_tests",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "tests": [
    {
      "framework": "JUnit4",
      "className": "McpPluginUnitTest",
      "methodName": "testToolNamesHaveIdePrefix",
      "displayName": "McpPluginUnitTest.testToolNamesHaveIdePrefix",
      "file": "src/test/kotlin/com/example/McpPluginUnitTest.kt",
      "line": 42
    }
  ],
  "count": 1,
  "truncated": false
}
```

---

### ide_run_tests

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Run tests using the IDE's run configuration infrastructure. Returns structured pass/fail results with per-test error messages.

Results are read directly from the IDE's test runner rather than from report files on disk, so they always reflect this run and work with any Service-Message-based framework (JUnit, TestNG, pytest, Jest, Go test, PHPUnit).

**Language support:** Passing an **existing run configuration name** works for any language/framework. Passing a **class or method FQN** (so the plugin creates the run config for you) is supported **only for Java/Kotlin** — for Python, JS/TS, Go, PHP, or Rust, create/select a run configuration in the IDE and pass its name.

**Use when:**
- Running a specific test class or method after a code change
- Verifying that a fix resolves a test failure
- Getting structured test results without dropping to a terminal

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Absolute path to the project root (required when multiple projects are open) |
| `target` | string | Yes | One of: (1) existing run config name (any language), (2) FQN class `com.example.MyTest`, (3) FQN method `com.example.MyTest#testFoo`. FQN forms (2) and (3) are **Java/Kotlin-only** |
| `timeoutSeconds` | integer | No | Max seconds to wait for test completion (default: 120) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_run_tests",
    "arguments": {
      "target": "com.example.MyTest#testFoo"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "timedOut": false,
  "noTestsFound": false,
  "exitCode": 0,
  "passed": 3,
  "failed": 0,
  "errors": 0,
  "total": 3,
  "tests": [
    { "name": "com.example.MyTest.testFoo", "status": "passed" },
    { "name": "com.example.MyTest.testBar", "status": "passed" },
    { "name": "com.example.MyTest.testBaz", "status": "passed" }
  ]
}
```

---

### ide_read_file

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Read file content by file path or fully qualified class name.

**Use when:**
- Reading library/dependency source code from JARs
- Looking up class source by qualified name (e.g., `java.util.ArrayList`)
- Reading project files with metadata

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | No* | File path (relative, absolute, or jar path with `!/` or `jar://`) |
| `qualifiedName` | string | No* | Fully qualified class name (e.g., `java.util.ArrayList`) |
| `startLine` | integer | No | Starting line (1-based, inclusive) |
| `endLine` | integer | No | Ending line (1-based, inclusive) |

*Either `file` or `qualifiedName` must be provided.

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_read_file",
    "arguments": {
      "qualifiedName": "java.util.ArrayList",
      "startLine": 1,
      "endLine": 50
    }
  }
}
```

**Example Response:**

```json
{
  "file": "jar:///path/to/jdk/src.zip!/java.base/java/util/ArrayList.java",
  "content": "...",
  "language": "JAVA",
  "lineCount": 1750,
  "startLine": 1,
  "endLine": 50,
  "isLibraryFile": true
}
```

---

### ide_get_active_file

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Get the currently active file(s) open in the IDE editor, including split panes.

**Use when:**
- Understanding what the user is currently looking at
- Getting cursor position and selected text

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | | | Only `project_path` if multiple projects are open |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_get_active_file",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "activeFiles": [
    {
      "file": "src/main/kotlin/com/example/UserService.kt",
      "line": 25,
      "column": 10,
      "selectedText": null,
      "hasSelection": false,
      "language": "Kotlin"
    }
  ]
}
```

---

### ide_open_file

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Open a file in the IDE editor with optional line/column navigation.

**Use when:**
- Directing the user's attention to a specific file and location
- Opening a file after finding it via search

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | File path relative to project root, or absolute path |
| `line` | integer | No | 1-based line number to navigate to |
| `column` | integer | No | 1-based column number (requires `line`) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_open_file",
    "arguments": {
      "file": "src/main/kotlin/com/example/UserService.kt",
      "line": 25
    }
  }
}
```

**Example Response:**

```json
{
  "file": "src/main/kotlin/com/example/UserService.kt",
  "opened": true,
  "message": "Opened file at line 25"
}
```

---

### ide_find_symbol

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Searches for code symbols (classes, interfaces, methods, fields, and functions) by name using the IDE's semantic index and IntelliJ's Go to Symbol matching.

**Use when:**
- Finding a class or interface by name (e.g., find "UserService")
- Locating methods across the codebase (e.g., find all "findById" methods)
- Discovering fields or constants by name
- Navigating to code when you know the symbol name but not the file location

**Supports Go to Symbol matching:**
- Substring: "Service" matches "UserService", "OrderService"
- CamelCase: "USvc" matches "UserService", "US" matches "UserService"
- Qualified queries: "BasicSolver.run" matches a method in its containing class or module

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Search pattern. Matching follows IntelliJ's Go to Symbol popup. |
| `scope` | string | No | Built-in search scope. One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `language` | string | No | Filter by language (e.g., `"Kotlin"`, `"Java"`). Case-insensitive |
| `includeGenerated` | boolean | No | Include symbols from generated sources (KSP/Dagger/annotation-processor output). Default: false |
| `limit` | integer | No | Deprecated alias for `pageSize` (default: 25, max: 500) |
| `cursor` | string | No | Pagination cursor from a previous response |
| `pageSize` | integer | No | Number of results per page (default: 25, max: 500) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_symbol",
    "arguments": {
      "query": "UserService"
    }
  }
}
```

**Example Request (camelCase matching):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_symbol",
    "arguments": {
      "query": "USvc",
      "scope": "project_and_libraries",
      "pageSize": 50
    }
  }
}
```

**Example Response:**

```json
{
  "symbols": [
    {
      "name": "UserService",
      "qualifiedName": "com.example.service.UserService",
      "kind": "INTERFACE",
      "file": "src/main/java/com/example/service/UserService.java",
      "line": 12,
      "column": 18,
      "containerName": null
    },
    {
      "name": "UserServiceImpl",
      "qualifiedName": "com.example.service.UserServiceImpl",
      "kind": "CLASS",
      "file": "src/main/java/com/example/service/UserServiceImpl.java",
      "line": 15,
      "column": 14,
      "containerName": null
    },
    {
      "name": "findUser",
      "qualifiedName": "com.example.service.UserService.findUser",
      "kind": "METHOD",
      "file": "src/main/java/com/example/service/UserService.java",
      "line": 18,
      "column": 10,
      "containerName": "UserService"
    }
  ],
  "totalCount": 3,
  "query": "UserService"
}
```

**Path note:** Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.

**Kind Values:**
- `CLASS` - Concrete class
- `ABSTRACT_CLASS` - Abstract class
- `INTERFACE` - Interface
- `ENUM` - Enum type
- `ANNOTATION` - Annotation type
- `RECORD` - Record class (Java 16+)
- `METHOD` - Method
- `FIELD` - Field or constant
- `FUNCTION` - Function
- `SYMBOL` - Generic symbol when the IDE contributor does not expose a more specific kind

For Markdown heading outlines, use `ide_file_structure`.

---

## Plugin Development

> **Note**: Both tools in this section are disabled by default. Enable them in Settings > Tools > Index MCP Server.

### ide_install_plugin

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Install a plugin zip into the IDE, replacing any existing version. When no path is given, auto-detects the most recently modified zip in `build/distributions/` of the active project — the output of `./gradlew buildPlugin`.

A restart is required for the change to take effect. Call `ide_restart` after this tool returns.

**Use when:**
- Installing a freshly built plugin without leaving the MCP session
- Iterating on plugin development: build → install → restart in one flow

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | No | Absolute path to the plugin zip. Defaults to auto-detection from `build/distributions/` |
| `project_path` | string | No | Project path when multiple projects are open and `path` is omitted |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_install_plugin",
    "arguments": {}
  }
}
```

**Example Response:**

```
Plugin 'com.example.my-plugin' installed from my-plugin-1.0.0.zip. Restart the IDE to load the updated plugin (call ide_restart).
```

---

### ide_restart

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Restart the IDE. This terminates the MCP connection — the AI assistant will lose connectivity and must reconnect after the IDE comes back up.

**Use when:**
- Loading a freshly installed plugin after `ide_install_plugin`

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path when multiple projects are open |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_restart",
    "arguments": {}
  }
}
```

> **Note**: The MCP connection drops immediately after this call. Reconnect your AI assistant client before making further tool calls.

---

## Project Window Management

> **Note**: All tools in this section are disabled by default. Enable them in Settings > Tools > Index MCP Server.

### ide_set_power_save_mode

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Enable or disable IDE Power Save Mode. When enabled, background inspections and code analysis are suspended, reducing CPU and memory pressure. The index and all code intelligence operations (find usages, refactoring, navigation) remain fully functional.

The setting is IDE-wide: it affects every open project, regardless of which project serves as the JSON-RPC context.

**Use when:**
- Reducing resource usage on projects open for reference but not actively edited
- Cutting background analysis cost while running searches or refactorings across multiple open projects

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `enabled` | boolean | Yes | `true` to enable Power Save Mode, `false` to disable |
| `project_path` | string | No | Project path when multiple projects are open |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_set_power_save_mode",
    "arguments": {
      "enabled": true
    }
  }
}
```

**Example Response:**

```
Power Save Mode enabled (IDE-wide).
```

---

### ide_close_project

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Close an open project window and free its memory. The project can be reopened later via Recent Projects or `ide_open_project`.

Non-blocking: the tool returns as soon as the close is scheduled. Refuses to close the last open project — the MCP server needs at least one open project to serve requests (including `ide_open_project`).

**Use when:**
- Freeing memory from a project that is no longer needed
- Closing a project window programmatically

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Project path when multiple projects are open |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_close_project",
    "arguments": {
      "project_path": "/Users/dev/myproject"
    }
  }
}
```

**Example Response:**

```
Project 'myproject' is closing.
```

---

### ide_open_project

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Open a project by filesystem path and wait until indexing is complete, so subsequent MCP tool calls against the opened project succeed immediately. If the project is already open, returns successfully right away.

Requires at least one project to already be open (needed as the JSON-RPC context). Opening a project the IDE has not seen before may show the modal "Trust project?" dialog, which only a human can answer; the call fails after `timeoutSeconds` if the project has not opened by then. If the project opens but indexing outlasts the timeout, the tool returns success with a note to check `ide_index_status`.

**Use when:**
- Opening a project that is not currently open in the IDE
- Ensuring a project is indexed before running code intelligence tools

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | Yes | Absolute filesystem path of the project directory to open |
| `timeoutSeconds` | integer | No | Maximum seconds to wait for opening + indexing. Default: 600 |
| `project_path` | string | No | Selects the JSON-RPC context project when multiple are open |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_open_project",
    "arguments": {
      "path": "/Users/dev/myproject"
    }
  }
}
```

**Example Response:**

```
Project 'myproject' is open and ready.
```

---

## Refactoring Tools

> **Note**: All refactoring tools modify source files. Changes can be undone with Ctrl/Cmd+Z.

### ide_refactor_rename

Renames a symbol or file and updates all references across the project. This tool uses IntelliJ's `RenameProcessor` which is language-agnostic and works across **all languages** supported by your IDE.

**Supported Languages:** Java, Kotlin, Python, JavaScript, TypeScript, Go, PHP, Rust, Ruby, and any language with IntelliJ plugin support.

**Features:**
- Language-specific name validation (identifier rules, keyword detection)
- **Fully headless/autonomous operation** (no popups or dialogs)
- **Automatic related element renaming** - getters/setters, overriding methods, test classes are renamed automatically
- Explicit `targetType` mode selection (`symbol` or `file`)
- Conflict detection before rename execution (returns error instead of showing dialog)
- Supports IDE undo for rename changes

**Use when:**
- Renaming identifiers to improve code clarity
- Following naming conventions
- Refactoring code structure

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file containing the symbol |
| `targetType` | string | No | `symbol` (requires 1-based `line` + `column`) or `file` (renames the file itself; placeholder coordinates are ignored) |
| `line` | integer | No | 1-based line number for symbol rename |
| `column` | integer | No | 1-based column number for symbol rename |
| `newName` | string | Yes | The new name for the symbol |
| `overrideStrategy` | string | No | How to handle overriding methods: `"rename_base"` (default), `"rename_only_current"`, or `"ask"` |
| `relatedRenamingStrategy` | string | No | How to handle automatic renaming of related symbols: `"all"` (default), `"none"`, `"accessors_and_tests"`, or `"ask"` |

**Example Request (Java):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_refactor_rename",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 15,
      "column": 17,
      "newName": "findUserById"
    }
  }
}
```

**Example Request (Python):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_refactor_rename",
    "arguments": {
      "file": "src/services/user_service.py",
      "line": 10,
      "column": 5,
      "newName": "fetch_user_data"
    }
  }
}
```

**Example Request (PHP):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_refactor_rename",
    "arguments": {
      "file": "src/Models/User.php",
      "line": 25,
      "column": 21,
      "newName": "getFullName"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "affectedFiles": [
    "src/main/java/com/example/UserService.java",
    "src/main/java/com/example/UserController.java",
    "src/test/java/com/example/UserServiceTest.java"
  ],
  "changesCount": 3,
  "message": "Successfully renamed 'findUser' to 'findUserById' (also renamed 2 related element(s))"
}
```

**Automatic Related Renames:**

Related elements are automatically renamed without any prompts or dialogs:

| Language | What Gets Auto-Renamed |
|----------|------------------------|
| Java/Kotlin | Getters/setters for fields, constructor parameters matching fields, overriding methods in subclasses, test classes |
| All Languages | Method implementations in subclasses, interface method implementations |

Rename changes support IDE undo (Ctrl/Cmd+Z).

---

### ide_move_file

Move a file to a new directory using the IDE's refactoring engine. Applies language-aware reference, import, and namespace/package updates when the IDE provides a semantic move backend for that file type.

**Supported Languages:** All project file types for literal file moves. Semantic updates depend on the active JetBrains language plugin. Java, Kotlin, and Python are known to provide file-move semantics; PHP class files are routed through PhpStorm's higher-level semantic move flow when available.

**Features:**
- Uses the IDE's file move refactoring for literal file relocation
- Applies semantic namespace/package/import updates when the language plugin supports them
- Routes PHP class-file moves through PhpStorm's semantic move dispatcher instead of the plain file-move backend
- Automatically creates destination directory if it doesn't exist
- Detects name conflicts at the destination
- Fails fast for ambiguous PHP semantic moves instead of reporting a false success

**Use when:**
- Reorganizing project structure
- Moving classes to different packages or namespaces when the IDE supports a semantic backend
- Relocating files while preserving IDE-managed references when available

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the source file to move, relative to project root |
| `destination` | string | Yes | Target directory path relative to project root |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_move_file",
    "arguments": {
      "file": "src/main/java/com/old/MyService.java",
      "destination": "src/main/java/com/new/services"
    }
  }
}
```

**Example Request (config file):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_move_file",
    "arguments": {
      "file": "config/old-config.yml",
      "destination": "config/archive"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "affectedFiles": [
    "src/main/java/com/old/MyService.java",
    "src/main/java/com/new/services/MyService.java"
  ],
  "changesCount": 2,
  "message": "Successfully moved 'src/main/java/com/old/MyService.java' to 'src/main/java/com/new/services/MyService.java' using IDE file move semantics"
}
```

---

### ide_reformat_code

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Reformat code according to the project's code style settings. Equivalent to the IDE's "Reformat Code" action (<kbd>Ctrl+Alt+L</kbd> / <kbd>Cmd+Opt+L</kbd>).

**Use when:**
- Applying consistent formatting after code changes
- Organizing imports
- Rearranging code members according to project rules

**Respects:** `.editorconfig`, project code style, language-specific formatting rules.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | File path relative to project root |
| `startLine` | integer | No | Start line for partial formatting (1-based). Requires `endLine` |
| `endLine` | integer | No | End line for partial formatting (1-based). Requires `startLine` |
| `optimizeImports` | boolean | No | Optimize imports (default: true) |
| `rearrangeCode` | boolean | No | Rearrange code members (default: true) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_reformat_code",
    "arguments": {
      "file": "src/main/kotlin/com/example/UserService.kt"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "affectedFiles": ["src/main/kotlin/com/example/UserService.kt"],
  "changesCount": 1,
  "message": "Reformatted code (optimized imports, rearranged code)"
}
```

---

### ide_structural_search_replace

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Pattern-based code search and transformation using IntelliJ's Structural Search and Replace (SSR) engine. Matches code patterns structurally rather than textually — understands types, expressions, statements, and code structure.

**Languages:** Java, Kotlin.

When `replacePattern` is omitted, the tool performs search-only and returns matching locations. When `replacePattern` is provided, the tool replaces all matches.

**Use when:**
- Finding code patterns that text search cannot express (e.g., all calls to a deprecated method with specific argument types)
- Applying systematic code transformations across the project
- Migrating API usage patterns
### ide_edit_member

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Replace an entire member declaration (signature + body) with new content. The tool locates the member by name, optional parameter count, and optional line number, then replaces the complete declaration.

**Languages:** Java, Kotlin.

**Use when:**
- Rewriting a method signature and body together
- Replacing a field declaration with a different type or initializer
- Updating a member where both signature and body need to change

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `searchPattern` | string | Yes | Structural search pattern using IntelliJ SSR syntax |
| `replacePattern` | string | No | Replacement pattern. If omitted, search-only mode |
| `filePattern` | string | No | IntelliJ file mask to filter files (e.g., `"*.java"`, `"*.kt"`) |
| `scope` | string | No | Built-in search scope. One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |

**Example Request (search-only):**
| `file` | string | Yes | Path to the file relative to project root |
| `class` | string | No | Class name to scope the search (required for inner classes or when the member name is ambiguous) |
| `member` | string | Yes | Name of the member to replace |
| `parameterCount` | integer | No | Number of parameters to disambiguate overloaded methods |
| `line` | integer | No | 1-based line number to disambiguate when multiple members share the same name |
| `content` | string | Yes | The full replacement declaration (signature + body) |
| `reformat` | boolean | No | Reformat the replaced code using project code style (default: true) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_structural_search_replace",
    "arguments": {
      "searchPattern": "$Instance$.$MethodCall$($Parameter$)",
      "filePattern": "*.java"
    }
  }
}
```

**Example Request (search and replace):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_structural_search_replace",
    "arguments": {
      "searchPattern": "System.out.println($arg$)",
      "replacePattern": "logger.info($arg$)",
      "filePattern": "*.java",
      "scope": "project_production_files"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "matchCount": 12,
  "replacedCount": 12,
  "matches": null,
  "message": "Replaced 12 of 12 match(es)"
}
```

---

### ide_change_signature

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Change a method's signature — name, return type, visibility, and parameters — with automatic updates to all callers using IntelliJ's Change Signature refactoring. Supports reordering, adding, removing, and renaming parameters.

**Language:** Java.

**Use when:**
- Adding a new parameter to a method and providing a default value for existing callers
- Changing parameter order, types, or names across the project
- Changing method visibility or return type
- Generating a delegate method to preserve binary compatibility

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file containing the method, relative to project root |
| `line` | integer | Yes | 1-based line number of the method |
| `column` | integer | Yes | 1-based column number of the method name |
| `newName` | string | No | New method name (unchanged if omitted) |
| `newReturnType` | string | No | New return type (unchanged if omitted) |
| `newVisibility` | string | No | New visibility: `"public"`, `"protected"`, `"private"`, or `"package-local"` (unchanged if omitted) |
| `newParameters` | array | No | Array of parameter objects defining the new parameter list. Each object: `{ oldIndex, name, type, defaultValue }`. Use `oldIndex: -1` for new parameters. |
| `generateDelegate` | boolean | No | Generate a delegate method with the old signature that calls the new one (default: false) |

**`newParameters` object fields:**

| Field | Type | Description |
|-------|------|-------------|
| `oldIndex` | integer | Index in the original parameter list (0-based), or `-1` for a new parameter |
| `name` | string | Parameter name |
| `type` | string | Parameter type (e.g., `"String"`, `"int"`, `"List<String>"`) |
| `defaultValue` | string | Default value expression used to update existing callers (required for new parameters) |

**Example Request (add parameter):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_change_signature",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 15,
      "column": 17,
      "newParameters": [
        { "oldIndex": 0, "name": "id", "type": "String" },
        { "oldIndex": -1, "name": "includeDeleted", "type": "boolean", "defaultValue": "false" }
      ]
    }
  }
}
```

**Example Request (rename + change visibility):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_change_signature",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 15,
      "column": 17,
      "newName": "findUserById",
      "newVisibility": "public"
    "name": "ide_edit_member",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "member": "findUser",
      "parameterCount": 1,
      "content": "public User findUser(String id) {\n    return userRepository.findById(id).orElseThrow(() -> new NotFoundException(id));\n}"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "file": "src/main/java/com/example/UserService.java",
  "message": "Changed signature of 'findUser' — updated 5 caller(s)",
  "affectedFiles": [
    "src/main/java/com/example/UserService.java",
    "src/main/java/com/example/UserController.java",
    "src/test/java/com/example/UserServiceTest.java"
  ],
  "changesCount": 5
  "message": "Replaced method 'findUser' entirely",
  "startLine": 15,
  "endLine": 18
}
```

---

### ide_create_file

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Create a new source file with content, immediately indexed by IntelliJ. The file is created through IntelliJ's VFS, so it is instantly available for `ide_find_references`, `ide_refactor_rename`, `ide_edit_member`, and all other IDE tools without needing `ide_sync_files`.

Use this instead of the Write tool for creating `.java`, `.kt`, `.ts`, `.tsx`, `.py` files. The file must not already exist.

**Use when:**
- Creating new source files that need to be immediately indexed
- Adding new classes, interfaces, or modules to the project
- Generating files that other IDE tools need to reference right away

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the new file relative to project root. File must not already exist. |
| `content` | string | Yes | The file content to write |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_create_file",
    "arguments": {
      "file": "src/main/java/com/example/NewService.java",
      "content": "package com.example;\n\npublic class NewService {\n}"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "file": "src/main/java/com/example/NewService.java",
  "message": "Created file 'src/main/java/com/example/NewService.java' (immediately indexed)"
}
```

---

### ide_replace_text_in_file

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Find and replace text in a file using IntelliJ's Document API. Performs plain text or regex replacement through IntelliJ's document model, so changes are immediately visible to the index, PSI, and all other IDE tools without needing `ide_sync_files`.

Use this for mechanical text substitutions — e.g., replacing a method call wrapper, updating import paths, or renaming a local pattern. For structural refactoring (renaming symbols across the project), use `ide_refactor_rename` instead.

**Use when:**
- Replacing a deprecated method call pattern across a file
- Updating import paths or string constants
- Applying regex-based text transformations
### ide_insert_member

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Insert a new member (method, field, inner class, etc.) at a structural position within a class or at the top level of a file.

**Languages:** Java, Kotlin.

**Use when:**
- Adding a new method to a class
- Adding a new field or constant
- Inserting a member at a specific position relative to an existing member

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `searchText` | string | Yes | Text to find. Treated as literal unless `regex` is true |
| `replaceText` | string | Yes | Replacement text. Supports regex group references (`$1`, `$2`) when `regex` is true |
| `regex` | boolean | No | Treat `searchText` as a regular expression (default: false) |
| `caseSensitive` | boolean | No | Case-sensitive matching (default: true) |
| `class` | string | No | Class name to insert into (omit for top-level insertion) |
| `content` | string | Yes | The full member declaration to insert |
| `position` | string | No | Where to insert: `before`, `after`, `first`, or `last` (default: `last`) |
| `anchor` | string | No | Name of an existing member to position relative to (required for `before`/`after`) |
| `anchorParameterCount` | integer | No | Number of parameters to disambiguate overloaded anchor methods |
| `anchorLine` | integer | No | 1-based line number to disambiguate the anchor member |
| `reformat` | boolean | No | Reformat the inserted code using project code style (default: true) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_replace_text_in_file",
    "arguments": {
      "file": "src/main/java/com/example/Service.java",
      "searchText": "OldHelper.wrap(",
      "replaceText": "("
    }
  }
}
```

**Example Request (regex):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_replace_text_in_file",
    "arguments": {
      "file": "src/main/java/com/example/Utils.java",
      "searchText": "LOG\\.debug\\((.*)\\)",
      "replaceText": "LOG.trace($1)",
      "regex": true
    "name": "ide_insert_member",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "class": "UserService",
      "content": "public void deleteUser(String id) {\n    userRepository.deleteById(id);\n}",
      "position": "after",
      "anchor": "findUser"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "file": "src/main/java/com/example/Service.java",
  "replacements": 3,
  "message": "Replaced 3 occurrence(s) of 'OldHelper.wrap(' in Service.java"
  "file": "src/main/java/com/example/UserService.java",
  "message": "Inserted member",
  "startLine": 22,
  "endLine": 25
}
```

---

### ide_replace_member

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Replace only the body of a method or the initializer of a field, preserving the existing signature. This is safer than `ide_edit_member` when the signature should remain unchanged.

**Languages:** Java, Kotlin.

**Use when:**
- Changing method implementation without altering the signature
- Updating a field initializer
- Fixing a bug inside a method body

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `class` | string | No | Class name to scope the search (required for inner classes or when the member name is ambiguous) |
| `member` | string | Yes | Name of the member whose body/initializer to replace |
| `parameterCount` | integer | No | Number of parameters to disambiguate overloaded methods |
| `line` | integer | No | 1-based line number to disambiguate when multiple members share the same name |
| `content` | string | Yes | The new method body (without braces) or field initializer (without `=` sign) |
| `reformat` | boolean | No | Reformat the replaced code using project code style (default: true) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_replace_member",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "member": "findUser",
      "parameterCount": 1,
      "content": "    log.info(\"Finding user: {}\", id);\n    return userRepository.findById(id).orElseThrow();"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "file": "src/main/java/com/example/UserService.java",
  "message": "Replaced body of method 'findUser'",
  "startLine": 16,
  "endLine": 18
}
```

---

## Extended Tools (Language-Aware)

These tools activate based on available language plugins:
- **Java/Kotlin** - IntelliJ IDEA, Android Studio
- **Python** - PyCharm (all editions), IntelliJ with Python plugin
- **JavaScript/TypeScript** - WebStorm, IntelliJ Ultimate, PhpStorm
- **Go** - GoLand, IntelliJ Ultimate with Go plugin
- **PHP** - PhpStorm, IntelliJ Ultimate with PHP plugin
- **Rust** - RustRover, IntelliJ Ultimate with Rust plugin, CLion
- **Markdown** - heading outlines in file structure for IDEs with the bundled Markdown plugin

Navigation tools appear according to installed language plugins. PHP file structure requires the PHP plugin and is available in PhpStorm or IntelliJ IDEA Ultimate with the PHP plugin enabled. Markdown file structure can appear even in IDEs without a code-language handler when the bundled Markdown plugin is enabled.

### ide_type_hierarchy

Retrieves the complete type hierarchy for a class or interface.

**Use when:**
- Exploring class inheritance chains
- Understanding polymorphism
- Finding all subclasses or implementations
- Analyzing interface implementations

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | No* | Path to the file relative to project root |
| `line` | integer | No* | 1-based line number |
| `column` | integer | No* | 1-based column number |
| `className` | string | No* | Fully qualified class name (alternative to position) |
| `scope` | string | No | Built-in search scope. One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | No | Include supertypes/subtypes in generated sources (KSP/Dagger/annotation-processor output). Default: true — keeps generated types in the hierarchy |

*Either `file`/`line`/`column` OR `className` must be provided.

**Example Request (by position):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_type_hierarchy",
    "arguments": {
      "file": "src/main/java/com/example/ArrayList.java",
      "line": 5,
      "column": 14
    }
  }
}
```

**Example Request (by class name - Java):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_type_hierarchy",
    "arguments": {
      "className": "java.util.ArrayList",
      "scope": "project_and_libraries"
    }
  }
}
```

**Example Request (by class name - PHP):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_type_hierarchy",
    "arguments": {
      "className": "App\\Models\\User"
    }
  }
}
```

**Example Response:**

```json
{
  "element": {
    "name": "com.example.UserServiceImpl",
    "file": "src/main/java/com/example/UserServiceImpl.java",
    "kind": "CLASS",
    "language": "Java"
  },
  "supertypes": [
    {
      "name": "com.example.UserService",
      "file": "src/main/java/com/example/UserService.java",
      "kind": "INTERFACE",
      "language": "Java"
    },
    {
      "name": "com.example.BaseService",
      "file": "src/main/java/com/example/BaseService.java",
      "kind": "ABSTRACT_CLASS",
      "language": "Java"
    }
  ],
  "subtypes": [
    {
      "name": "com.example.AdminUserServiceImpl",
      "file": "src/main/java/com/example/AdminUserServiceImpl.java",
      "kind": "CLASS",
      "language": "Java"
    }
  ]
}
```

**Kind Values:**
- `CLASS` - Concrete class
- `ABSTRACT_CLASS` - Abstract class
- `INTERFACE` - Interface
- `ENUM` - Enum type
- `ANNOTATION` - Annotation type
- `RECORD` - Record class (Java 16+)

---

### ide_call_hierarchy

Analyzes method call relationships to find callers or callees.

**Use when:**
- Tracing execution flow
- Understanding code dependencies
- Analyzing impact of method changes
- Debugging to understand how a method is reached

**Target (mutually exclusive):** `file` + `line` + `column` OR `language` + `symbol`

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | Conditional | 1-based line number. Required for position-based lookup. |
| `column` | integer | Conditional | 1-based column number. Required for position-based lookup. |
| `language` | string | Conditional | Language of the symbol (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | Conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `direction` | string | Yes | `"callers"` or `"callees"` |
| `depth` | integer | No | How deep to traverse (default: 3, max: 5) |
| `scope` | string | No | Built-in search scope. One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | No | Include callers/callees in generated sources (KSP/Dagger/annotation-processor output). Default: true |

**Example Request (position-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_call_hierarchy",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 20,
      "column": 10,
      "direction": "callers"
    }
  }
}
```

**Example Request (symbol-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_call_hierarchy",
    "arguments": {
      "language": "Java",
      "symbol": "com.example.UserService#validateUser(String)",
      "direction": "callers",
      "scope": "project_and_libraries"
    }
  }
}
```

**Example Response:**

```json
{
  "element": {
    "name": "UserService.validateUser(String)",
    "file": "src/main/java/com/example/UserService.java",
    "line": 20,
    "column": 17,
    "language": "Java"
  },
  "calls": [
    {
      "name": "UserController.createUser(UserRequest)",
      "file": "src/main/java/com/example/UserController.java",
      "line": 45,
      "column": 17,
      "language": "Java"
    },
    {
      "name": "UserController.updateUser(String, UserRequest)",
      "file": "src/main/java/com/example/UserController.java",
      "line": 62,
      "column": 17,
      "language": "Java"
    }
  ]
}
```

---

### ide_find_implementations

Finds all concrete implementations of an interface, abstract class, or abstract method.

**Languages:** Java, Kotlin, Python, JS/TS, PHP, Rust (not Go — Go uses implicit interfaces).

**Use when:**
- Locating classes that implement an interface
- Finding classes that extend an abstract class
- Finding all overriding methods for polymorphic behavior analysis

**Target (mutually exclusive):** `file` + `line` + `column` OR `language` + `symbol`

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | Conditional | 1-based line number. Required for position-based lookup. |
| `column` | integer | Conditional | 1-based column number. Required for position-based lookup. |
| `language` | string | Conditional | Language of the symbol (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | Conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `scope` | string | No | Built-in search scope. One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | No | Include implementations in generated sources (KSP/Dagger/annotation-processor output). Default: false |
| `cursor` | string | No | Pagination cursor from a previous response |
| `pageSize` | integer | No | Number of results per page (default: 100, max: 500) |

**Example Request (position-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_implementations",
    "arguments": {
      "file": "src/main/java/com/example/Repository.java",
      "line": 8,
      "column": 10
    }
  }
}
```

**Example Request (symbol-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_implementations",
    "arguments": {
      "language": "Java",
      "symbol": "com.example.Repository",
      "scope": "project_test_files"
    }
  }
}
```

**Example Response:**

```json
{
  "implementations": [
    {
      "name": "com.example.JpaUserRepository",
      "file": "src/main/java/com/example/JpaUserRepository.java",
      "line": 12,
      "column": 14,
      "kind": "CLASS"
    },
    {
      "name": "com.example.InMemoryUserRepository",
      "file": "src/main/java/com/example/InMemoryUserRepository.java",
      "line": 8,
      "column": 14,
      "kind": "CLASS"
    }
  ],
  "totalCount": 2,
  "nextCursor": null,
  "hasMore": false,
  "totalCollected": 2,
  "offset": 0,
  "pageSize": 100,
  "stale": false
}
```

---

### ide_find_super_methods

Finds the complete inheritance hierarchy for a method - all parent methods it overrides or implements.

**Languages:** Java, Kotlin, Python, JS/TS, PHP (not Go or Rust — they use composition/traits instead of classical inheritance).

**Use when:**
- Finding which interface method an implementation overrides
- Navigating to the original method declaration in a parent class
- Understanding the full inheritance chain for a method with @Override
- Seeing all levels of method overriding (not just immediate parent)

**Position flexibility:** The position (line/column) can be anywhere within the method - on the name, inside the body, or on the @Override annotation. The tool automatically finds the enclosing method.

**Target (mutually exclusive):** `file` + `line` + `column` OR `language` + `symbol`

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | Conditional | 1-based line number (any line within the method). Required for position-based lookup. |
| `column` | integer | Conditional | 1-based column number (any position within the method). Required for position-based lookup. |
| `language` | string | Conditional | Language of the symbol (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | Conditional | Fully qualified symbol reference. Required for symbol-based lookup. |

**Example Request (position-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_super_methods",
    "arguments": {
      "file": "src/main/java/com/example/UserServiceImpl.java",
      "line": 25,
      "column": 10
    }
  }
}
```

**Example Request (symbol-based):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_super_methods",
    "arguments": {
      "language": "Java",
      "symbol": "com.example.UserServiceImpl#findUser(String)"
    }
  }
}
```

**Example Response:**

```json
{
  "method": {
    "name": "findUser",
    "signature": "findUser(String id): User",
    "containingClass": "com.example.UserServiceImpl",
    "file": "src/main/java/com/example/UserServiceImpl.java",
    "line": 25,
    "column": 17
  },
  "hierarchy": [
    {
      "name": "findUser",
      "signature": "findUser(String id): User",
      "containingClass": "com.example.AbstractUserService",
      "containingClassKind": "ABSTRACT_CLASS",
      "file": "src/main/java/com/example/AbstractUserService.java",
      "line": 18,
      "column": 17,
      "isInterface": false,
      "depth": 1
    },
    {
      "name": "findUser",
      "signature": "findUser(String id): User",
      "containingClass": "com.example.UserService",
      "containingClassKind": "INTERFACE",
      "file": "src/main/java/com/example/UserService.java",
      "line": 12,
      "column": 10,
      "isInterface": true,
      "depth": 2
    }
  ],
  "totalCount": 2
}
```

**Depth field:** The `depth` field indicates the level in the hierarchy:
- `depth: 1` = immediate parent (first level up)
- `depth: 2` = grandparent (two levels up)
- And so on...

**containingClassKind Values:**
- `CLASS` - Concrete class
- `ABSTRACT_CLASS` - Abstract class
- `INTERFACE` - Interface

---

### ide_file_structure

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Get the hierarchical structure of a source file, similar to the IDE's Structure view (<kbd>Cmd+7</kbd> / <kbd>Alt+7</kbd>).

**Languages:** Java, Kotlin, Python, JavaScript, TypeScript, PHP, Markdown.

PHP support requires the PHP plugin and is available in PhpStorm or IntelliJ IDEA Ultimate with the PHP plugin enabled.

**Use when:**
- Getting an overview of a file's classes, methods, fields, PHP namespaces/constants/enum cases, or Markdown heading outline
- Understanding code organization without reading the full file
- Navigating large files

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_file_structure",
    "arguments": {
      "file": "src/main/kotlin/com/example/UserService.kt"
    }
  }
}
```

**Example Response:**

```json
{
  "file": "src/main/kotlin/com/example/UserService.kt",
  "language": "Kotlin",
  "structure": "interface UserService (lines 15-18)\n  fun findUser(id: String): User (line 16)\n  fun deleteUser(id: String) (line 17)\n\nclass UserServiceImpl (lines 20-42)\n  val repository: UserRepository (line 21)\n  override fun findUser(id: String): User (lines 23-29)\n  override fun deleteUser(id: String) (lines 30-35)\n  private fun validate(id: String) (lines 37-41)"
}
```

**Note:** Each element in the structure output includes both start and end line numbers (e.g., `(lines 42-65)` for multi-line elements, `(line 42)` for single-line elements), making it easy to identify the full extent of each declaration.

---

## Java-Specific Tools

These tools require the Java plugin and are only available in **IntelliJ IDEA** and **Android Studio**.

`ide_convert_java_to_kotlin` also requires the Kotlin plugin and is disabled by default.

### ide_convert_java_to_kotlin

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Convert one or more Java files to Kotlin using IntelliJ's built-in J2K (Java-to-Kotlin) converter.

**Use when:**
- Migrating Java source files to Kotlin
- Converting a batch of related Java files in one request
- Letting the IDE handle syntax conversion, formatting, and import cleanup

**Features:**
- Supports batch conversion via a `files` array
- Uses the IDE's built-in converter instead of text transformation
- Automatically formats converted Kotlin files and optimizes imports
- Deletes original `.java` files after successful conversion
- Returns per-file results plus a summary of converted, skipped, and failed files

**Requirements:**
- Java plugin available
- Kotlin plugin enabled
- Files must belong to a module with Kotlin support enabled

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `files` | array of strings | Yes | Java file paths relative to project root |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_convert_java_to_kotlin",
    "arguments": {
      "files": [
        "src/main/java/com/example/User.java",
        "src/main/java/com/example/UserService.java"
      ]
    }
  }
}
```

**Example Response:**

```json
{
  "files": [
    {
      "requestedPath": "src/main/java/com/example/User.java",
      "status": "CONVERTED",
      "kotlinFile": "src/main/java/com/example/User.kt",
      "linesConverted": 42,
      "javaFileDeleted": true
    },
    {
      "requestedPath": "src/main/java/com/example/UserService.java",
      "status": "SKIPPED",
      "reason": "Module 'app' does not have Kotlin plugin enabled"
    }
  ],
  "summary": {
    "totalRequested": 2,
    "converted": 1,
    "skipped": 1,
    "failed": 0
  }
}
```

**Status Values:**
- `CONVERTED` - Successfully converted to a new `.kt` file
- `SKIPPED` - File could not be attempted (for example not found, not a Java file, or no Kotlin-enabled module)
- `FAILED` - Conversion was attempted but did not produce a Kotlin file or hit a converter error

**Notes:**
- The tool returns structured per-file results in the same order as the input list
- Duplicate paths are reported separately
- Some advanced Java constructs may still need manual cleanup after conversion

### ide_refactor_safe_delete

Safely deletes an element, first checking for usages.

**Use when:**
- Removing unused code
- Cleaning up dead code
- Safely removing methods or classes

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |
| `force` | boolean | No | Force deletion even if usages exist (default: false) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_refactor_safe_delete",
    "arguments": {
      "file": "src/main/java/com/example/LegacyHelper.java",
      "line": 8,
      "column": 14
    }
  }
}
```

**Example Response (safe to delete):**

```json
{
  "success": true,
  "message": "Successfully deleted 'LegacyHelper'"
}
```

**Example Response (blocked by usages):**

```json
{
  "success": false,
  "message": "Cannot safely delete: 3 usages found",
  "blockingUsages": [
    {
      "file": "src/main/java/com/example/App.java",
      "line": 25,
      "context": "LegacyHelper.convert(data)"
    }
  ]
}
```

---

## Error Handling

### JSON-RPC Standard Errors

| Code | Name | When It Occurs |
|------|------|----------------|
| -32700 | Parse Error | Invalid JSON in request |
| -32600 | Invalid Request | Missing required JSON-RPC fields |
| -32601 | Method Not Found | Unknown tool or method name |
| -32602 | Invalid Params | Missing or invalid parameters |
| -32603 | Internal Error | Unexpected server error |

### Custom MCP Errors

| Code | Name | When It Occurs |
|------|------|----------------|
| -32001 | Index Not Ready | IDE is indexing (dumb mode) |
| -32002 | File Not Found | Specified file doesn't exist |
| -32003 | Symbol Not Found | No symbol at the specified position |
| -32004 | Refactoring Conflict | Refactoring cannot be completed |

### Example Error Response

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32001,
    "message": "IDE is in dumb mode, indexes not available. Please wait for indexing to complete."
  }
}
```

### Handling Dumb Mode

Before calling index-dependent tools, you can check the index status:

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_index_status",
    "arguments": {}
  }
}
```

If `isDumbMode` is `true`, wait and retry later.

---

## Project Lifecycle Management

When working across multiple projects simultaneously, idle ones consume memory unnecessarily. Lifecycle management automatically sleeps and wakes projects based on window focus and MCP activity. Projects enroll on first MCP use and auto-reopen when targeted by an MCP call — existing tools require no changes.

**Lifecycle modes:**

| Mode | Power Save | Editors | PSI Cache | Memory |
|------|-----------|---------|-----------|--------|
| `active` | off | open | loaded | full |
| `background` | on | open | loaded | reduced |
| `dormant` | on | closed | released via GC | low |
| `closed` | — | — | freed | none (auto-reopens on next MCP call) |

---

### ide_project_status

Combined snapshot of every open project and every managed project.

**Parameters:** `project_path` (optional routing hint)

**Returns:** `projects` array (name, path, open, managed, mode per row) and `summary` object (total, open, managed, open_not_managed, managed_closed counts).

```json
{ "name": "ide_project_status", "arguments": {} }
```

---

### ide_set_project_mode

Set a project's lifecycle mode explicitly.

**Parameters:**
- `mode` (required): `active`, `background`, `dormant`, or `closed`
- `project_path` (optional)

```json
{ "name": "ide_set_project_mode", "arguments": { "mode": "background" } }
```

Setting `closed` fully closes the project window. The project auto-reopens on the next MCP call targeting it.

---

### ide_get_project_modes

List all managed projects and their current modes.

**Parameters:** `project_path` (optional)

**Returns:** `managed_projects` array (name, path, mode) and `total` count. Includes projects currently closed by the lifecycle manager.

```json
{ "name": "ide_get_project_modes", "arguments": {} }
```

---

### ide_set_all_project_modes

Set all currently open managed projects to the same mode at once.

**Parameters:**
- `mode` (required): `active`, `background`, or `dormant` — `closed` is not accepted (closing all projects would make MCP unreachable)
- `project_path` (optional)

```json
{ "name": "ide_set_all_project_modes", "arguments": { "mode": "background" } }
```

---

### ide_release_project

Remove a project from lifecycle management, restoring full IDE behaviour (Power Save off, no auto-close).

**Parameters:** `project_path` (optional)

```json
{ "name": "ide_release_project", "arguments": {} }
```

Calling on an unmanaged project succeeds with a "not managed" note — safe to call idempotently.

Accepts an optional `path` parameter to release a closed managed project without needing it to be open.

---

### ide_release_all_projects

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Release every managed project (including closed ones) from lifecycle management at once. Also disables Power Save Mode globally.

**Parameters:** `project_path` (optional routing hint)

```json
{ "name": "ide_release_all_projects", "arguments": {} }
```

---

### ide_enroll_all_projects

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Enroll every currently open project in lifecycle management. Projects already managed are skipped.

**Parameters:** `project_path` (optional routing hint)

```json
{ "name": "ide_enroll_all_projects", "arguments": {} }
```

---

### ide_lifecycle_log

Query recent lifecycle events from the in-memory ring buffer (default 500 entries, configurable in Settings).

**Parameters:**
- `limit` (optional, default 50): how many events to return, newest first
- `project` (optional): path substring filter — only events for matching projects
- `project_path` (optional): routing hint

**Returns:** `events` array, `log_file` path (always), `buffered` count.

```json
{ "name": "ide_lifecycle_log", "arguments": { "limit": 20, "project": "engine" } }
```

**Event fields:** `timestamp`, `project`, `path`, `event`, `from` (mode), `to` (mode), `trigger`.

**Trigger values:**

| Trigger | Meaning |
|---------|---------|
| `focus_gained` | User switched to this project window |
| `focus_lost` | User switched away; focus timer started |
| `timer:focus` | Focus timer fired → background |
| `timer:inactivity` | Inactivity timer fired → dormant |
| `timer:close` | Dormant timer fired → closed |
| `mcp_call` | MCP tool call triggered this |
| `auto_open` | Lifecycle manager reopened a closed project |
| `user` | User action in the IDE |

**Enabling file output:** The ring buffer is always active. File writes (`mcp-lifecycle.log` in the IDE log directory) require either using `ide_set_lifecycle_log_file` (see below) or enabling IntelliJ debug logging. No restart needed. The file is readable directly even when no project is open.

---

### ide_set_lifecycle_log_file

> **Default**: Disabled - enable in Settings > Tools > Index MCP Server

Enable or disable writing lifecycle events to the persistent log file on disk (`mcp-lifecycle.log`, written alongside `idea.log`). The in-memory ring buffer queried by `ide_lifecycle_log` is always active regardless of this setting.

Use this for tail -f monitoring or post-mortem analysis when no MCP connection is available.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `enabled` | boolean | Yes | `true` to start writing to the log file, `false` to stop |
| `project_path` | string | No | Routing hint when multiple projects are open |

**Example:**

```json
{
  "name": "ide_set_lifecycle_log_file",
  "arguments": {
    "enabled": true
  }
}
```

**Example Response:**

```
Lifecycle log file enabled. Events are being written to: /Users/you/Library/Logs/JetBrains/IntelliJIdea2026.1/mcp-lifecycle.log
```

---

### ide_set_power_save_mode

Toggle Power Save Mode directly.

**Parameters:**
- `enabled` (required): `true` or `false`
- `project_path` (optional)

```json
{ "name": "ide_set_power_save_mode", "arguments": { "enabled": true } }
```

Power Save Mode suspends background inspections and code analysis while leaving the index and all MCP operations fully functional.

---

### ide_close_project

Close an open project window and free its memory.

**Parameters:** `project_path` (optional — defaults to the active project)

```json
{ "name": "ide_close_project", "arguments": {} }
```

The project can be reopened via Recent Projects or `ide_open_project`.

---

### ide_open_project

Open a project by filesystem path and block until indexing completes, so subsequent MCP tool calls succeed immediately.

**Parameters:**
- `path` (required): filesystem path of the project directory
- `project_path` (optional): routing hint — requires at least one project already open

```json
{ "name": "ide_open_project", "arguments": { "path": "/Users/dev/myproject" } }
```

---

### ide_install_plugin

Install a plugin zip into the IDE, replacing any existing version. A restart is required to load the updated plugin.

**Parameters:**
- `path` (optional): explicit path to a `.zip` file. If omitted, auto-detects `build/distributions/*.zip` in the current project — useful when developing the plugin itself.
- `project_path` (optional)

```json
{ "name": "ide_install_plugin", "arguments": {} }
```

Typical workflow: `./gradlew buildPlugin` → `ide_install_plugin` → `ide_restart`.

---

### ide_restart

Restart the IDE. Terminates the MCP connection immediately — no further tool calls should be made after calling this.

**Parameters:** `project_path` (optional)

```json
{ "name": "ide_restart", "arguments": {} }
```

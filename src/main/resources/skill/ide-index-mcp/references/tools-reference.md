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
| `symbol` | string | Fully qualified symbol reference. Java format: `com.example.ClassName`, `com.example.ClassName#memberName`. PHP format: `\\App\\Service\\UserService`, `\\App\\Service\\UserService::method()`, `\\App\\Service\\UserService::CONSTANT`, `\\App\\Service\\UserService::$property`, `\\App\\Service\\StatusEnum::ACTIVE`. PHP properties require the `$property` form; plain `::name` resolves enum cases (on enum types), constants, or methods. Python format: see **Python symbol grammar** below. |

**Handle identity:** Definition and metadata lookups preserve the exact stored PSI target.
Declarations without their own source text use a source-context preview. Deleting and recreating
a file at the same path expires its old handles; rediscover it after `SYMBOL_ID_EXPIRED`.

**Symbol reference:** Some tools accept `language` + `symbol` as an alternative to `file` + `line` + `column`. The two groups are **mutually exclusive**. Supported languages: Java, PHP, JavaScript, TypeScript, Python. Unsupported languages are rejected explicitly; use `file` + `line` + `column` for other languages.

**Python symbol grammar:** Symbols must be module-qualified (dotted path with ≥2 segments):
- `pkg.mod.ClassName` — class
- `pkg.mod.function_name` — module-level function
- `pkg.mod.ClassName.method_name` — method (resolved via the function index; a method's qualified name is `pkg.mod.ClassName.method`)
- `pkg.mod.ClassName#member_name` — method (inherited), class/instance attribute, or `@property` of the named class

Parameter lists are not supported (Python has no overload-by-signature); bare unqualified names are rejected — use `file` + `line` + `column` for those.

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

**Target for a fresh search (mutually exclusive):** top-level `symbolId` OR `file`+`line`+`column` OR `language`+`symbol`; this tool also accepts the equivalent nested `target`. Pagination requests pass `cursor` instead.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Exactly one of `symbolId`, `position` (`file`, `line`, `column`), or `qualifiedName` + `language`. Do not combine with top-level selectors. |
| `symbolId` | string | conditional | Exact declaration handle from a previous result. |
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | no | Include references in generated sources (KSP/Dagger/annotation-processor output). **Default true** — keeps valid runtime references (Dagger/MapStruct/gRPC/serializers). Set false to drop generated call sites when they dominate results on injected symbols. |
| `paths` | array | no | Project-relative path globs restricting results, e.g. `["src/main/**", "!**/generated/**"]`. `*` matches within a segment, `**` crosses directories, a plain directory includes everything beneath it, `!` excludes. Composes with `scope`. An include glob whose literal prefix does not exist (or resolves under a different relative name) errors instead of returning zero results. Include globs also drop library/jar hits under `project_and_libraries`; `\` separators are normalized to `/` |
| `maxResults` | integer | no | Deprecated alias for `pageSize`. Default 100, max 500 |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 100, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ usages: [{ file, line, column, context, type, astPath }], totalCount, totalIsExact, resolvedSymbol, truncated, nextCursor?, hasMore, totalCollected, offset, pageSize, stale }`
**Pagination note**: `truncated` mirrors `hasMore`. When `nextCursor` is present, pass it to fetch the next page. `hasMore: true` without `nextCursor` means uncached results may remain, but the snapshot cannot continue safely (for example, it became stale or reached the 5,000-result cache cap); start a narrower fresh search.
**Resolution note**: `resolvedSymbol` includes `symbolId` and echoes the declaration that was actually searched — positions on comments or whitespace snap to the nearest enclosing named element, so check it matches the symbol you intended. When `totalIsExact` is `false`, `totalCount` is a lower bound.
**type values**: `METHOD_CALL`, `FIELD_ACCESS`, `IMPORT`, `PARAMETER`, `VARIABLE`, `REFERENCE`

### ide_find_definition
Go to where a symbol is defined.

**Target (mutually exclusive):** top-level `symbolId` OR `file`+`line`+`column` OR `language`+`symbol`; this tool also accepts the equivalent nested `target`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Exactly one of `symbolId`, `position` (`file`, `line`, `column`), or `qualifiedName` + `language`. Do not combine with top-level selectors. |
| `symbolId` | string | conditional | Opaque handle returned by this tool or `ide_symbol_info`. Pass it alone to resolve the exact target after edits or rename. |
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `fullElementPreview` | boolean | no | Return full element code (default false) |
| `maxPreviewLines` | integer | no | Max lines for full preview (default 50, max 500) |
| `project_path` | string | no | Project root path |

**Returns**: `{ symbolId, file, line, column, preview, symbolName, astPath }`
Handles: packages, compiled classes, library sources (jar: URLs).

### ide_symbol_info (disabled by default)
Resolved signature and documentation of the symbol at a position — the declaration facts
`ide_find_definition` cannot give, because its preview is source text with unresolved short type
names and no doc comment.

**Target (mutually exclusive):** top-level `symbolId` OR `file`+`line`+`column` OR `language`+`symbol`; this tool also accepts the equivalent nested `target`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Exactly one of `symbolId`, `position` (`file`, `line`, `column`), or `qualifiedName` + `language`. Do not combine with top-level selectors. |
| `symbolId` | string | conditional | Opaque handle returned by this tool or `ide_find_definition`. Pass it alone to resolve the exact target after edits or rename. |
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. Required for symbol-based lookup. |
| `includeDoc` | boolean | no | Include the rendered doc comment. Default true |
| `maxDocLength` | integer | no | Truncate documentation beyond this many characters. Default 4000, max 20000 |
| `project_path` | string | no | Project root path |

**Returns**: `{ symbolId, name, kind, qualifiedName, signature, signatureSource, parameters: [{name, type}], returnType, typeParameters, thrownTypes, modifiers, visibility, containingDeclaration, documentation, documentationTruncated, file, line, column, language }`

For both tools, omitting `project_path` lets `symbolId` route to its owning open project. Handles
expire on server restart, project close, deletion, one hour of inactivity, or LRU eviction;
self-navigating synthetic targets also expire after their backing source file changes. Rediscover
after `SYMBOL_ID_EXPIRED`. Handles are non-canonical and must not be compared for equality.

**Type resolution**: `signatureSource` says how far the types were resolved.
- `java_psi` — Java declarations. Parameter and return types are fully qualified
  (`java.util.List<com.example.Request>`), and `parameters` / `returnType` are populated.
- `quick_navigation` — any language with a documentation provider (Kotlin, Python, JS/TS, Go, PHP,
  Rust). The signature is what that language's Quick Documentation renders; type names may be short
  and the structured fields are absent.
- `element_text` — no documentation provider answered; the declaration's own source line.

**Overloads**: address them by position — each overload's own `line`/`column` selects it.

**Chaining**: on the `java_psi` path `qualifiedName` is in this plugin's `symbol` format and can be
passed straight to `ide_find_references`, `ide_call_hierarchy`, or `ide_find_implementations` as
`symbol`. A callable includes its resolved parameter list (`com.example.Service#handle(com.example.Request)`),
because the bare name is rejected as ambiguous once a method is overloaded. On the
`quick_navigation` / `element_text` paths the container is a best-effort dotted AST path, not a
resolved FQN, so it is descriptive rather than round-trippable — address those by position.

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

**Returns**: `{ classes: [{symbolId, name, qualifiedName, file, line, kind, language}], totalCount, query }`
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
Search for text using IntelliJ Find in Files. Plain-text queries do substring matching (e.g. `a_word` finds `a_word_and_another_word`); regex queries use regular expression matching.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | conditional | Text to search for; substring match unless `regex` is true. Required for fresh search, ignored when `cursor` is provided |
| `regex` | boolean | no | Treat `query` as a regular expression. Default false |
| `context` | enum | no | `all` (default), `code`, `comments`, `strings` |
| `caseSensitive` | boolean | no | Default true |
| `wholeWord` | boolean | no | Match whole words only. Default false (substring match) |
| `filePattern` | string | no | IntelliJ file mask, e.g. `*.kt`, `*.java,!*Test.java` |
| `paths` | array | no | Project-relative path globs restricting the search, e.g. `["src/main/kotlin/**/handlers/**", "!**/*Test.kt"]`. `*` matches within a segment, `**` crosses directories, a plain directory includes everything beneath it, `!` excludes. Composes with `filePattern`. An include glob whose literal prefix does not exist (or resolves under a different relative name) errors instead of returning zero matches. Include globs also drop library/jar hits under `project_and_libraries`; `\` separators are normalized to `/` |
| `limit` | integer | no | Deprecated alias for `pageSize`. Default 100, max 500 |
| `cursor` | string | no | Pagination cursor from a previous response. When provided, search parameters are ignored; `project_path` and `pageSize` may still be provided. |
| `pageSize` | integer | no | Results per page. Default 100, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ matches: [{file, line, column, context}], totalCount, query, nextCursor?, hasMore, totalCollected, offset, pageSize, stale }`
**Pagination note**: when `nextCursor` is present, pass it to fetch the next page. `hasMore: true` without `nextCursor` means uncached results may remain, but the snapshot cannot continue safely (for example, it became stale or reached the 5,000-result cache cap); start a narrower fresh search.

### ide_find_implementations
Find implementations of interfaces, abstract classes, or abstract methods.

**Target for a fresh search (mutually exclusive):** top-level `symbolId` OR `file`+`line`+`column` OR `language`+`symbol`; this tool also accepts the equivalent nested `target`. Pagination requests pass `cursor` instead.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Exactly one of `symbolId`, `position` (`file`, `line`, `column`), or `qualifiedName` + `language`. Do not combine with top-level selectors. |
| `symbolId` | string | conditional | Exact type/method handle from a previous result. |
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

**Returns**: `{ implementations: [{symbolId?, name, file, line, column, kind, language}], totalCount, nextCursor?, hasMore, totalCollected, offset, pageSize, stale }`
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

**Returns**: `{ symbols: [{symbolId, name, qualifiedName, file, line, kind, language}], totalCount, query }`
**Languages**: Java, Kotlin, Python, JS/TS, Go, PHP, Rust, plus other IDE-supplied symbol contributors where available.
**Path note**: Project results use relative paths. Dependency/library results may use absolute paths or `jar://` URLs.

### ide_find_super_methods
Find parent methods that a given method overrides or implements.

**Target (mutually exclusive):** top-level `symbolId` OR `file`+`line`+`column` OR `language`+`symbol`; this tool also accepts the equivalent nested `target`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Exactly one of `symbolId`, `position` (`file`, `line`, `column`), or `qualifiedName` + `language`. Do not combine with top-level selectors. |
| `symbolId` | string | conditional | Exact method handle from a previous result. |
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column (anywhere in method body works). Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. For JS/TS, use module-qualified forms: `modulePath#exportName`, `modulePath#default`, or `modulePath#ClassName.memberName`. Required for symbol-based lookup. |
| `project_path` | string | no | Project root path |

**Returns**: `{ method: {symbolId?, name, class, file, line}, hierarchy: [{symbolId?, name, class, file, line, isInterface}], totalCount }`
**Languages**: Java, Kotlin, Python, JS/TS, PHP (NOT Go, Rust).

### ide_type_hierarchy

Get the legacy nested type hierarchy, or bounded BFS pages when `maxNodes` is supplied.
Legacy root/supertypes/subtypes share one handle budget: keep the root's handle and omit optional
IDs above the budget while retaining all nodes. Use bounded pages when every node needs a handle.
A query through a member's ID preserves that input declaration.
When scope filtering hides an intermediate type, eligible descendants remain discoverable under
their nearest included ancestor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `symbolId` | string | no | Exact type handle from a previous result. |
| `className` | string | no | FQN (preferred, faster). E.g., `com.example.MyClass` |
| `file` | string | no | Alternative: project-relative file path. Unlike other read-only navigation tools, `ide_type_hierarchy` file mode does not resolve dependency/library absolute paths or `jar://` URLs. |
| `line` | integer | no | Required with file |
| `column` | integer | no | Required with file |
| `language` | string | no | Language for a qualified semantic target; requires an installed symbol-reference handler |
| `symbol` | string | no | Fully qualified symbol reference used with `language` |
| `maxNodes` | integer | no | Opt into paging with 1–500 nodes; omit for a legacy tree. Cursor default 100 |
| `cursor` | string | no | Opaque continuation from the previous page. Target/search parameters are ignored; `maxNodes` may set the next page size |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | no | Include supertypes/subtypes in generated sources (KSP/Dagger/annotation-processor output). Default true — keeps generated types in the hierarchy |
| `project_path` | string | no | Project root path |

**Provide exactly one target:** one nested `target` variant, or legacy `symbolId`, `className`, `language`+`symbol`, or `file`+`line`+`column`.
**Returns**: `{ element: {symbolId?, name, file, kind, language}, supertypes: [{symbolId?, name, file, kind, language}], subtypes: [{symbolId?, name, file, kind, language}], traversal: [{direction: "supertype"|"subtype", element: {...}}], returnedNodes, truncated, elapsedMs, hasMore, cursor? }`
**Pagination**: Without `maxNodes`/`cursor`, legacy nested trees and limits remain. Explicit pages expose traversal-local `nodeId`, `parentId`, and `depth` for the first-discovery tree; `returnedNodes` excludes the root. Type `traversal` preserves combined BFS order. Follow `cursor` while present. Retention limits return the computed page with `hasMore=true`, no cursor, and `truncationReason`; narrow the query to continue. Cursors are session/project-bound, expire after ten idle minutes, and retain at most 128 snapshots overall and ten per traversal. Handles refresh on every page.
**Languages**: Java, Kotlin, Python, JS/TS, PHP, Rust.

### ide_call_hierarchy

Build call tree showing who calls a method or what a method calls.
Kotlin getter/setter handles identify distinct callable bodies. A source property or parameter
used as input keeps its original ID binding even if the hierarchy selects a related callable.

**Target (mutually exclusive):** `symbolId` OR `file`+`line`+`column` OR `language`+`symbol`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `symbolId` | string | conditional | Exact callable handle from a previous result. |
| `file` | string | conditional | Project-relative file path, or a dependency/library absolute path or `jar://` URL previously returned by the plugin. Required for position-based lookup. |
| `line` | integer | conditional | 1-based line. Required for position-based lookup. |
| `column` | integer | conditional | 1-based column. Required for position-based lookup. |
| `language` | string | conditional | Symbol language (e.g., `"Java"`). Required for symbol-based lookup. |
| `symbol` | string | conditional | Fully qualified symbol reference. For JS/TS, use module-qualified forms: `modulePath#exportName`, `modulePath#default`, or `modulePath#ClassName.memberName`. Required for symbol-based lookup. |
| `direction` | enum | yes | `callers` or `callees` |
| `depth` | integer | no | Traversal depth across all pages (default 3, max 5) |
| `maxNodes` | integer | no | Opt into paging with 1–500 nodes; omit for a legacy tree. Cursor default 100 |
| `cursor` | string | no | Opaque continuation from the previous page. Target/search parameters are ignored; `maxNodes` may set the next page size |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `includeGenerated` | boolean | no | Include callers/callees in generated sources (KSP/Dagger/annotation-processor output). Default true |
| `project_path` | string | no | Project root path |

**Returns**: `{ element: {symbolId?, name, file, line, column, language}, calls: [{symbolId?, name, file, line, column, language}], returnedNodes, truncated, elapsedMs, hasMore, cursor? }`
Large legacy trees retain all nodes but may omit optional handles after the response handle budget;
the root keeps its handle. Use bounded pages to obtain handles for every returned page.
**Pagination**: Without `maxNodes`/`cursor`, legacy nested trees and limits remain. Explicit pages expose traversal-local `nodeId`, `parentId`, and `depth` for the first-discovery tree; `returnedNodes` excludes the root. Type `traversal` preserves combined BFS order. Follow `cursor` while present. Retention limits return the computed page with `hasMore=true`, no cursor, and `truncationReason`; narrow the query to continue. Cursors are session/project-bound, expire after ten idle minutes, and retain at most 128 snapshots overall and ten per traversal. Handles refresh on every page.

### ide_file_structure (disabled by default)

Get hierarchical file structure like IDE's Structure panel. Each element includes both start and end line numbers (e.g., `(lines 42-65)` for multi-line elements, `(line 42)` for single-line elements).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative file path |
| `project_path` | string | no | Project root path |
| `includeNodes` | boolean | no | Include structured declaration nodes. Default: false |
| `includeSymbolIds` | boolean | no | Bind exact handles for nodes; implies `includeNodes`. Default: false |
| `maxSymbolIds` | integer | no | Handle budget when handles are enabled, from 1–100. Default: 100 |

**Returns**: `{ file, language, structure, nodes, symbolIdsTruncated, symbolIdsOmitted }`
`structure` is retained for compatibility. Set `includeNodes=true` to receive the same hierarchy as
structured data; set `includeSymbolIds=true` when exact handles are needed (it implies nodes). IDs
are bound to the exact extracted PSI elements rather than reconstructed from line numbers. Kotlin
node kinds distinguish `INTERFACE`, `CLASS`, `ENUM`, `ANNOTATION`, and `OBJECT`.
Without structured output, `nodes` is empty and the handle fields are `false` and `0`; with handles
enabled, they report the per-response budget outcome.
Handles are limited to 100 per response by default; `maxSymbolIds` can lower that limit to 1–100.
`symbolIdsTruncated` flags this per-response budget and `symbolIdsOmitted` counts budget omissions.
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

Get code diagnostics from multiple sources: one `file` or a small `files` batch, build output from the last build, and test results from open test run tabs. At least one source must be active: provide exactly one of `file`/`files` for code analysis, `includeBuildErrors` for build output, or `includeTestResults` for test results. Sources can be combined.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | no | One project-relative or in-project absolute path. Mutually exclusive with `files` |
| `files` | string[] | no | Up to 100 supplied relative or in-project absolute paths under one shared timeout budget. Aliases/duplicates are tolerated and analyzed once. Mutually exclusive with `file` |
| `line` | integer | no | For intention lookup (default 1, single `file` only) |
| `column` | integer | no | For intention lookup (default 1, single `file` only) |
| `startLine` | integer | no | Filter problems to range (single `file` only) |
| `endLine` | integer | no | Filter problems to range (single `file` only) |
| `includeBuildErrors` | boolean | no | Include errors/warnings from the last build. Default false |
| `includeTestResults` | boolean | no | Include test results from open test run tabs. Default false |
| `severity` | enum | no | Filter by severity across all sources: `all` (default), `errors`, `warnings` |
| `maxProblems` | integer | no | Max code problems returned across the file(s). Default 100, max 500 |
| `testResultFilter` | enum | no | Filter test results: `failed` (default) or `all` |
| `maxBuildErrors` | integer | no | Max build errors to return. Default 100, max 500 |
| `maxTestResults` | integer | no | Max test results to return. Default 100, max 500 |
| `project_path` | string | no | Project root path |

**Returns**: `{ problems: [{message, severity, file, line, column, endLine?, endColumn?}], intentions?, problemCount, problemsTruncated?, intentionCount?, analysisFresh?, analysisTimedOut?, analysisMessage?, analysisMode?, fileAnalyses?: [{file, state, reason?, mode?, problemCount, problemsTruncated}], buildErrors?, buildErrorCount?, buildWarningCount?, buildErrorsTruncated?, buildTimestamp?, testResults?, testResultsTruncated?, testSummary? }`
**Output cap**: At most `maxProblems` code problems across the response. Aggregate/per-file `problemsTruncated` flags known omissions; per-file `problemCount` counts only returned problems. Re-query a truncated path with `file`, narrowing `startLine`/`endLine` if needed.
**Notes**: File paths preserve literal leading/trailing whitespace. Single-file mode keeps legacy top-level metadata and supports intentions/range filters. Multi-file entries use `analyzed`, `timed_out`, `failed`, `skipped` (not eligible), `not_analyzed` (not started before the shared deadline), or `not_found`; path resolution consumes the shared deadline, and aliases/duplicates are resolved once. Open files use fresh daemon highlights; closed files use public batch analysis. The complete single-file operation is timeout-bounded too, including refresh, PSI setup, and analysis-lock waits. A daemon that reports it did not run can fall back to batch analysis within the remaining budget; a daemon that consumes the timeout does not start a second batch budget.
**Severity levels**: `ERROR`, `WARNING`, `WEAK_WARNING`

### ide_project_diagnostics (disabled by default)
Analyze many files — up to the whole project, including files not open in any editor — with fail-closed coverage metadata. Every file in scope gets exactly one coverage state (`analyzed`, `timed_out`, `failed`, `skipped`, `not_analyzed`) and the top-level `complete` flag is true only when every considered file was analyzed, so an empty `problems` list can never be mistaken for a clean project when analysis was partial.

Each call blocks at most `waitSeconds` (default 45) so the MCP client's request timeout is never hit. If analysis is still running when the wait budget ends, the call returns `{"status": "running", "analysisId": "..."}` while analysis continues in the IDE — call the tool again with that `analysisId` to keep waiting. Only one analysis runs per project at a time.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Project root path |
| `paths` | string[] | no | Files or directories relative to the project root; omit to analyze every file in the project's content roots |
| `severity` | enum | no | `all` (default), `errors`, `warnings` |
| `maxFiles` | integer | no | Max files to analyze (default 1000, max 10000). Overflow files are reported `not_analyzed` and `complete` becomes false |
| `maxProblems` | integer | no | Max problems returned across all files (default 1000, max 5000). `problemCount` keeps counting beyond it |
| `timeoutSeconds` | integer | no | Max seconds for the whole analysis (default 600, max 3600). Remaining files are reported `not_analyzed` when it elapses. Ignored with `analysisId` |
| `analysisId` | string | no | `analysisId` from a previous `{"status": "running"}` response — attaches to that analysis and keeps waiting |
| `waitSeconds` | integer | no | Max seconds this call may block before returning results or a `running` status (default 45, max 55) |

**Returns**: `{ complete, status: "completed"|"timed_out", filesConsidered, filesAnalyzed, filesAnalyzedOpenDaemon, filesAnalyzedClosedBatch, filesTimedOut, filesFailed, filesSkipped, filesNotAnalyzed, incompleteFiles: [{file, state, reason?}], incompleteFilesTruncated, problems: [{message, severity, file, line, column, endLine?, endColumn?}], problemCount, errorCount, warningCount, problemsTruncated, durationMs, analysisMessage }`, or while still executing: `{ status: "running", analysisId, elapsedSeconds, filesProcessed, filesConsidered, timeoutSeconds, message }`
**Notes**: Open files are analyzed with fresh daemon highlights (`open_daemon`); closed files use the IDE's public batch analysis (`closed_batch`), which covers errors and warnings but not weak warnings or editor-only annotators. Binary files are excluded from scope. Treat empty `problems` as a clean signal only when `complete` is true.

---

## Refactoring Tools

### ide_refactor_rename

Rename a symbol or file and update ALL references (semantic rename, not find-replace). Works across ALL languages.

**Target:** `file` + `targetType="file"` for file rename; for symbol rename use `symbolId`, or `file` + `targetType="symbol"` + `line` + `column`. Without `targetType`, legacy `null/null => file` and `line`+`column => symbol` behavior remains.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Structured symbol selector containing exactly one of `symbolId`, `position: {file, line, column}`, or `qualifiedName` + `language`. Mutually exclusive with legacy top-level selectors. |
| `symbolId` | string | conditional | Exact symbol handle. Mutually exclusive with `file`/coordinates. |
| `file` | string | conditional | Relative file path. Required for position-based lookup. |
| `language` | string | conditional | Legacy qualified-name selector language; requires `symbol`. |
| `symbol` | string | conditional | Legacy qualified symbol name; requires `language`. |
| `targetType` | string | no | `symbol` or `file`. When `file`, placeholder `line`/`column` values are ignored. |
| `line` | integer | no | 1-based line for symbol rename. |
| `column` | integer | no | 1-based column for symbol rename. |
| `newName` | string | yes | New name for the symbol |
| `overrideStrategy` | enum | no | `rename_base` (default), `rename_only_current`, `ask` |
| `relatedRenamingStrategy` | enum | no | Controls automatic renaming of related symbols (same-named properties, getters/setters, test classes, variables): `all` (default) renames all related symbols, `none` renames only the targeted symbol, `accessors_and_tests` renames only getters/setters and test classes/methods, `ask` shows the IDE dialog for each related rename |
| `dryRun` | boolean | no | Resolve/validate and discover usages/conflicts without writing or saving files. Default false |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, affectedFiles: [paths], changesCount, message, updatedSymbol? }`
**Returns with `dryRun: true`**: common preview shape; rename `plannedChange` contains `{operation: "rename", targetType, from, to, overrideStrategy, relatedRenamingStrategy}`. Constructors resolve to the containing class for preview and apply. Destination conflicts include implicit Java file renames and existing directories.
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

### ide_refactor_safe_delete (Java, Kotlin)

Delete a symbol or file, checking for usages first.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Structured symbol selector containing exactly one of `symbolId`, `position: {file, line, column}`, or `qualifiedName` + `language`. Mutually exclusive with legacy top-level selectors. |
| `symbolId` | string | conditional | Exact symbol handle for `target_type="symbol"`. Mutually exclusive with coordinates. |
| `file` | string | conditional | Relative file path. Required for position-based symbol deletion and file deletion. |
| `language` | string | conditional | Legacy qualified-name selector language; requires `symbol`. |
| `symbol` | string | conditional | Legacy qualified symbol name; requires `language`. |
| `line` | integer | conditional | Required with `file` for position-based symbol deletion |
| `column` | integer | conditional | Required with `file` and `line` for position-based symbol deletion |
| `target_type` | enum | no | `symbol` (default) or `file` |
| `force` | boolean | no | Force delete even with usages (default false) |
| `dryRun` | boolean | no | Resolve target and discover usages/blockers without deleting or saving. Default false |
| `project_path` | string | no | Project root path |

**Returns (success)**: `{ success, affectedFiles, changesCount, message, invalidatedSymbolId? }`
**Returns (blocked)**: `{ canDelete: false, elementName, usageCount, blockingUsages: [...], message }`
**Returns with `dryRun: true`**: common preview shape; `plannedChange` contains `{operation: "safeDelete", targetType, name, force}`. Usages contribute to `conflictCount`; without `force`, they make `canApply` false. A failed usage search is inapplicable unless `force` explicitly overrides it. Files with no discovered top-level declaration retain their existing apply eligibility and report an incomplete-discovery warning. Preview leaves `symbolId` valid. Generated Kotlin JVM methods without a matching standalone source declaration are rejected in preview and apply, including with `force`; select the intended source declaration explicitly.
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

### ide_optimize_imports (disabled by default)
Optimize imports in a file: remove unused imports and organize remaining imports according to project code style. Equivalent to the IDE's "Optimize Imports" action (Ctrl+Alt+O / Cmd+Opt+O). Does NOT reformat code. Supports IDE undo (Ctrl+Z).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative file path |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, affectedFiles, changesCount, message }`

### ide_convert_java_to_kotlin (disabled by default, Java + Kotlin plugins)
Convert Java files to Kotlin using IntelliJ's built-in J2K converter. Handles classes, interfaces, enums, annotations, methods, fields, and Java 8+ features (lambdas, streams). Automatically formats and optimizes imports. Original Java files are deleted after successful conversion; some advanced constructs may need manual adjustment.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `files` | string[] | yes | Java files to convert (relative to project root) |
| `project_path` | string | no | Project root path |

**Returns**: `{ files: [{requestedPath, status, kotlinFile?, linesConverted?, javaFileDeleted?, reason?}], summary: {totalRequested, converted, skipped, failed} }`
**status values**: `CONVERTED`, `SKIPPED`, `FAILED`

### ide_structural_search_replace (disabled by default)
Pattern-based code search and transformation using IntelliJ's Structural Search and Replace engine. Search-only when `replacePattern` is omitted.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `searchPattern` | string | yes | Structural search pattern using IntelliJ SSR syntax |
| `replacePattern` | string | no | Replacement pattern. Omit for search-only |
| `filePattern` | string | no | IntelliJ file mask, e.g. `*.java`, `*.kt` |
| `scope` | enum | no | One of `project_files` (default), `project_and_libraries`, `project_production_files`, `project_test_files` |
| `paths` | array | no | Project-relative path globs restricting matching, e.g. `["src/main/**", "!**/generated/**"]`. `*` matches within a segment, `**` crosses directories, a plain directory includes everything beneath it, `!` excludes. In replace mode only files inside the globs are rewritten. An include glob whose literal prefix does not exist (or resolves under a different relative name) errors instead of returning zero matches. Include globs also drop library/jar hits under `project_and_libraries`; `\` separators are normalized to `/` |
| `project_path` | string | no | Project root path |

**Returns**: `{ matchCount, replacedCount, matches: [{ file, line, matchedText }] }`
**Languages**: Java, Kotlin.

### ide_change_signature (disabled by default)

Change method signature (name, return type, visibility, parameters) with automatic caller updates.

**Languages:** Java methods and Kotlin JVM functions. Select Kotlin functions by source position or
`symbolId`; override changes start at the base declaration and update implementations and callers.
Preview reports the base target without rebinding the original override's handle. After apply,
`updatedSymbol` preserves that handle's declaration when it remains available.

Preview and apply refuse conflicts, incomplete discovery, read-only scope, and missing defaults
for new required caller/delegate arguments. Return-type changes with overriders are conservatively
refused, including cases where narrower overriding types could safely remain unchanged.
Java override targets do not redirect to the base; select the base explicitly for hierarchy-wide
changes to avoid breaking the override contract.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Structured method selector containing exactly one of `symbolId`, `position: {file, line, column}`, or `qualifiedName` + `language`. Mutually exclusive with legacy top-level selectors. |
| `symbolId` | string | conditional | Exact method handle. Mutually exclusive with coordinates. |
| `file` | string | conditional | Relative file path containing the method. Required without `symbolId`. |
| `line` | integer | conditional | 1-based line of the method. Required without `symbolId`. |
| `column` | integer | conditional | 1-based column on the method name. Required without `symbolId`. |
| `language` | string | conditional | Legacy qualified-name selector language; requires `symbol`. |
| `symbol` | string | conditional | Legacy qualified method name; requires `language`. |
| `newName` | string | no | New method name (unchanged if omitted) |
| `newReturnType` | string | no | New return type (unchanged if omitted) |
| `newVisibility` | string | no | `public`, `protected`, `private`, or `package-private` (`package-local` is a legacy alias; unchanged if omitted) |
| `newParameters` | array | no | Array of `{ oldIndex, name, type, defaultValue }`. Use `oldIndex: -1` for new params |
| `generateDelegate` | boolean | no | Generate delegate with old signature (default false); new required parameters need explicit non-blank defaults even with no existing callers |
| `dryRun` | boolean | no | Resolve/validate and discover callers/conflicts without writing or saving files. Default false |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, file, message, affectedFiles, changesCount, updatedSymbol? }`
**Returns with `dryRun: true`**: common preview shape; `plannedChange` contains `{operation: "changeSignature", before: {...}, requested: {...}}`.

### ide_create_file (disabled by default)
Create a new source file with content, immediately indexed by IntelliJ. The file is created through IntelliJ's VFS, so it is instantly available for `ide_find_references`, `ide_refactor_rename`, `ide_edit_member`, and all other IDE tools without needing `ide_sync_files`. Use this instead of the Write tool for creating `.java`, `.kt`, `.ts`, `.tsx`, `.py` files. The file must not already exist.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Path to the new file relative to project root. File must not already exist. |
| `content` | string | yes | The file content to write |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, file, message }`

### ide_replace_text_in_file (disabled by default)
Find and replace text in a file using IntelliJ's Document API. Performs plain text or regex replacement through IntelliJ's document model, so changes are immediately visible to the index, PSI, and all other IDE tools without needing `ide_sync_files`. Use this for mechanical text substitutions — e.g., replacing a method call wrapper, updating import paths, or renaming a local pattern. For structural refactoring (renaming symbols across the project), use `ide_refactor_rename` instead.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Path to the file relative to project root |
| `searchText` | string | yes | Text to find. Treated as literal unless `regex` is true |
| `replaceText` | string | yes | Replacement text. Passed through as-is (no escape processing). Supports regex group references (`$1`, `$2`) when `regex` is true |
| `regex` | boolean | no | Treat `searchText` as a regular expression. Default false |
| `caseSensitive` | boolean | no | Case-sensitive matching. Default true |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, file, replacements, message }`

### ide_edit_member (disabled by default, Java, Kotlin)
Replace an entire member declaration (signature + body) with new content.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Exactly one nested selector: `{symbolId}`, `{position:{file,line,column}}`, or `{qualifiedName,language}`. Mutually exclusive with all legacy target/member selectors (`symbolId`, `language`+`symbol`, `file`, `class`, `member`, `parameterCount`, `line`). |
| `symbolId` | string | conditional | Exact member handle. Mutually exclusive with member selectors. |
| `language` | string | conditional | Legacy semantic lookup language. Required with `symbol`; mutually exclusive with `target`, `symbolId`, and member selectors. |
| `symbol` | string | conditional | Legacy qualified member name. Required with `language`; mutually exclusive with `target`, `symbolId`, and member selectors. |
| `file` | string | conditional | Relative file path. Required only for the legacy file + member selector. |
| `class` | string | no | Class name to scope the search |
| `member` | string | conditional | Name of the member to replace. Required only for the legacy file + member selector. |
| `parameterCount` | integer | no | Parameter count to disambiguate overloads |
| `line` | integer | no | 1-based line to disambiguate same-name members |
| `content` | string | yes | Full replacement declaration (signature + body) |
| `reformat` | boolean | no | Reformat after replacement (default true) |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, file, message, startLine, endLine, updatedSymbol? }`

### ide_insert_member (disabled by default, Java, Kotlin)
Insert a new member at a structural position in a class or file.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | yes | Relative file path |
| `class` | string | no | Class name to insert into (omit for top-level) |
| `content` | string | yes | Full member declaration to insert |
| `position` | enum | no | `before`, `after`, `first`, `last` (default `last`) |
| `anchor` | string | no | Existing member name to position relative to (required for `before`/`after`) |
| `anchorParameterCount` | integer | no | Parameter count to disambiguate anchor overloads |
| `anchorLine` | integer | no | 1-based line to disambiguate anchor |
| `reformat` | boolean | no | Reformat after insertion (default true) |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, file, message, startLine, endLine }`

### ide_replace_member (disabled by default, Java, Kotlin)
Replace a method body or field initializer only, preserving the signature.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `target` | object | conditional | Exactly one nested selector: `{symbolId}`, `{position:{file,line,column}}`, or `{qualifiedName,language}`. Mutually exclusive with all legacy target/member selectors (`symbolId`, `language`+`symbol`, `file`, `class`, `member`, `parameterCount`, `line`). |
| `symbolId` | string | conditional | Exact member handle. Mutually exclusive with member selectors. |
| `language` | string | conditional | Legacy semantic lookup language. Required with `symbol`; mutually exclusive with `target`, `symbolId`, and member selectors. |
| `symbol` | string | conditional | Legacy qualified member name. Required with `language`; mutually exclusive with `target`, `symbolId`, and member selectors. |
| `file` | string | conditional | Relative file path. Required only for the legacy file + member selector. |
| `class` | string | no | Class name to scope the search |
| `member` | string | conditional | Name of the member whose body/initializer to replace. Required only for the legacy file + member selector. |
| `parameterCount` | integer | no | Parameter count to disambiguate overloads |
| `line` | integer | no | 1-based line to disambiguate same-name members |
| `content` | string | yes | New method body (without braces) or field initializer (without `=`) |
| `reformat` | boolean | no | Reformat after replacement (default true) |
| `project_path` | string | no | Project root path |

**Returns**: `{ success, file, message, startLine, endLine, updatedSymbol? }`

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
| `paths` | string[] | no | Paths relative to the selected project/content root, or absolute paths inside any project/content root. Relative paths try the project base then module content roots when `project_path` is omitted or selects the project base; a selected content root confines relative resolution there. Deleted targets refresh their nearest existing parent. Relative traversal and symlink escapes are rejected. Empty/omitted = sync entire selected root |
| `project_path` | string | no | Project root path |

**Returns**: `{ syncedPaths, syncedAll, message, refreshedRoots, deletedPaths }`
`syncedPaths` reports normalized targets, `refreshedRoots` reports absolute system-independent roots actually refreshed, and `deletedPaths` identifies targets absent on disk. Discovery ancestors and deletion parents receive shallow refreshes; only explicitly requested existing targets receive recursive refreshes. A shallow ancestor does not replace a recursive target in `refreshedRoots`. Absolute paths match every allowed root even when another content root is selected. The full batch is validated inside project/content roots before refresh begins, all invalid entries are returned together, and an empty safe-root set is an explicit error.
Call this when files were created/modified outside the IDE and search tools miss them.

### ide_build_project (disabled by default)
Build project using IDE's build system (JPS, Gradle, Maven, CMake (CLion)).

Each call blocks at most `waitSeconds` (default 45) so the MCP client's request timeout is never hit. If the build is still executing when the wait budget ends, the call returns `{"status": "running", "buildId": "..."}` while the build continues in the IDE — call the tool again with that `buildId` to keep waiting.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | For workspace sub-projects |
| `rebuild` | boolean | no | Full rebuild (default false = incremental) |
| `includeRawOutput` | boolean | no | Include raw build log (default false) |
| `timeoutSeconds` | integer | no | Max seconds for the whole build before it is reported timed out, enforced across polls (no limit if omitted). Ignored with `buildId` |
| `buildId` | string | no | `buildId` from a previous `{"status": "running"}` response — attaches to that build and keeps waiting |
| `waitSeconds` | integer | no | Max seconds this call may block before returning results or a `running` status (default 45, max 55) |

**Returns**: `{ success, aborted, errors?, warnings?, buildMessages: [{message, file, line, column, severity}], truncated, rawOutput?, durationMs }`, or while still executing: `{ status: "running", buildId, elapsedSeconds, timeoutSeconds?, message }`
Note: `errors`/`warnings` are `null` when no messages were captured (not 0).

### ide_list_tests (disabled by default)
List all test methods/classes discovered by the IDE's test framework extension points (JUnit, TestNG, etc.).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | For workspace sub-projects |
| `file` | string | no | Relative path to a specific test file; omit to scan all test sources |

**Returns**: `{ tests: [{framework, className, methodName, displayName, file, line}], count, truncated }`

### ide_run_tests (disabled by default)
Run tests via the IDE's run configuration infrastructure. Results are read from the IDE's test runner, so they work with any Service-Message-based framework (JUnit, TestNG, pytest, Jest, Go test, PHPUnit). Targeting by class/method FQN creates a run config for Java/Kotlin only; for other languages pass an existing run-configuration name. Returns structured pass/fail results with per-test console output.

Each call blocks at most `waitSeconds` (default 45) so the MCP client's own request timeout is never hit. If the run is still going when the wait budget ends — whether the IDE is still compiling before the test process starts, or the tests themselves are still executing — the call returns `{"status": "running", "runId": "..."}` while the run continues in the IDE — call the tool again with that `runId` (and no `target`) to keep waiting. The run itself is bounded by `timeoutSeconds`, counted from when the test process starts (build time before that is not billed to the run): when it expires the process is killed and the next poll reports `timedOut: true`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | For workspace sub-projects |
| `target` | string | no* | Existing run config name (any language), or a Java/Kotlin class FQN (`com.example.MyTest`) / method FQN (`com.example.MyTest#testFoo`) — FQN forms are Java/Kotlin-only |
| `runId` | string | no* | `runId` from a previous `{"status": "running"}` response — attaches to that run and keeps waiting instead of starting a new one |
| `timeoutSeconds` | integer | no | Max seconds the whole test run may take before its process is killed, counted from test process start and enforced across polls (default 120). Ignored with `runId` |
| `waitSeconds` | integer | no | Max seconds this call may block before returning results or a `running` status (default 45, max 55). Keep below the MCP client's request timeout |
| `activateToolWindow` | boolean | no | Open the Run tool window for this run (default `false` — the run stays in the background without stealing focus; content is still added to the Run tool window) |

*Exactly one of `target` / `runId` is required.

**Returns**: `{ success, timedOut, noTestsFound, exitCode, passed, failed, errors, total, output?, tests: [{name, status, errorMessage?, stackTrace?, output?}] }`, or while still executing: `{ status: "running", runId, configName, elapsedSeconds, timeoutSeconds, message }`. Each test's `output` is the console output it printed (stdout/stderr merged in print order, ANSI stripped, system messages excluded); the top-level `output` carries output not attributed to any test (framework/suite messages, `@BeforeAll`/`@AfterAll` prints, build-runner log lines, and prints from a test killed mid-run — e.g. at `timeoutSeconds` — which gets no per-test entry). `stackTrace` is set for failed/errored tests; very long traces and outputs are trimmed in the middle (for traces that keeps the throw site and the root cause). On mass failures per-run size budgets apply: earlier failures keep their traces, later entries carry `errorMessage` only, and per-test output stops attaching once its own budget is spent.

### ide_reload_project (disabled by default)
Force-reload the project build model (Maven, Gradle, or both). Use after changing build files so IntelliJ resolves updated dependencies before diagnostics or builds. The reload is asynchronous.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Project root path |

**Returns**: text summary of scheduled Maven/Gradle reloads or skipped unlinked build systems.

### ide_link_build_system (disabled by default)
Link an unlinked Maven or Gradle project so the IDE resolves its dependencies. Use when `ide_reload_project` reports "build file found but project is not linked". Detects the build system automatically from build files in the project directory.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | no | Absolute path of the project directory to link. Defaults to the resolved project's base path. |
| `project_path` | string | no | Project root path |

**Returns**: text confirming link status ("Maven project linked — dependency resolution scheduled.", "already linked", or error).

### ide_import_modules (disabled by default, Maven plugin only)
Import one or more external Maven project directories as modules into the current IntelliJ project window. Already imported module roots are skipped.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `paths` | string[] | yes | Absolute directory paths to import; each must contain `pom.xml` |
| `project_path` | string | no | Project root path |

**Returns**: text summary of imported, skipped, and failed module paths.

### ide_open_workspace (disabled by default, Maven plugin only)
Scan a root directory for Maven projects, or provide an explicit list of Maven project paths, and open them all in one IntelliJ window with full cross-project code intelligence. Creates a temporary aggregator POM with relative module paths.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | no* | Root directory to scan for Maven projects (each must contain `pom.xml`). Mutually exclusive with `modules`. |
| `modules` | string[] | no* | Explicit list of absolute paths to Maven project directories. Mutually exclusive with `path`. Uses SHA-based caching so the same module combination reuses the cached workspace. |
| `timeoutSeconds` | integer | no | Timeout in seconds for opening and indexing (default 600) |
| `project_path` | string | no | Project root path |

*Either `path` or `modules` must be provided, but not both.

**Returns**: text confirmation with count of Maven projects found and indexing status.

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

### ide_create_module (disabled by default)
Add a directory as an IntelliJ module with a content root, enabling code intelligence for non-Maven projects (TypeScript, plain directories, etc.). Supports optional directory exclusions. For Maven projects, use `ide_import_modules` instead.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Absolute directory path to add as a module content root |
| `name` | string | no | Module name (defaults to directory name) |
| `excludes` | string[] | no | Relative paths to exclude from indexing (e.g., `["node_modules", "dist"]`) |
| `project_path` | string | no | Target project when multiple are open |

**Returns**: text confirmation with module name, content root path, module file path, count of excluded directories, and an async-indexing note.

### ide_open_project (disabled by default)
Open a project by absolute path and wait until indexing completes. Idempotent: returns immediately if the project is already open. May require a human to answer the IDE's "Trust project?" dialog for first-time projects.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Absolute path of the project directory |
| `autoLink` | boolean | no | Automatically link an unlinked Maven/Gradle build system after opening. Default: false. |
| `excludeDirectories` | string[] | no | Directory names to exclude from indexing and refactoring scope (e.g. `["wksp", ".claude"]`). Applied after autoLink. Must not be blank or contain `..`. |
| `timeoutSeconds` | integer | no | Max seconds to wait for open + indexing (default 600) |
| `project_path` | string | no | JSON-RPC context project when multiple are open |

**Returns**: text confirmation with setup details (excluded directories, linked build systems); on indexing timeout returns success with a note to check `ide_index_status`.

---

## Lifecycle Tools

Lifecycle management sleeps and wakes open projects based on window focus and MCP activity. Modes: `active` (full IDE), `background` (Power Save on), `dormant` (editor tabs closed until the window regains focus, PSI caches dropped), `closed` (fully unloaded, auto-reopens on next MCP call). Every MCP tool call on a managed project restarts its idle countdown.

It is opt-in and disabled by default — enable "Enable lifecycle management" in Settings > Tools > Index MCP Server. Until then the tools below only report or alter persisted enrollment state; no automatic transitions occur.

### ide_project_status
Report the status of all known projects in one table. Combines open projects (currently loaded in the IDE) and managed projects (enrolled in MCP lifecycle management). Each row includes whether the project is open, whether it is managed, and its current lifecycle mode when managed.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Routing hint, required when multiple projects are open |

**Returns**: `{ projects: [{name, path, open, managed, mode?}], summary: {total, open, managed, open_not_managed, managed_closed, lifecycle_enabled, note?} }`

### ide_get_project_modes (disabled by default)
List all MCP-managed projects and their current lifecycle mode (active, background, dormant, or closed).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Routing hint when multiple projects are open; does not affect which projects are listed — all managed projects are always returned |

**Returns**: `{ managed_projects: [{path, name, mode}], total }`. Plain-text message when no projects are managed.

### ide_set_project_mode (disabled by default)
Set the lifecycle mode for a specific managed project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `mode` | enum | yes | Target mode: `active`, `background`, `dormant`, or `closed` |
| `project_path` | string | no | Required when multiple projects are open |

**Returns**: text confirmation of the mode transition.

### ide_set_all_project_modes (disabled by default)
Set the lifecycle mode for every currently managed open project at once. Closed projects are skipped — use `ide_open_project` or `ide_set_project_mode` on a specific project to bring a closed project back first.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `mode` | enum | yes | Target mode: `active`, `background`, or `dormant` (`closed` is not supported — use `ide_set_project_mode` per project) |
| `project_path` | string | no | Routing hint required when multiple projects are open; does not limit which projects are affected |

**Returns**: text confirmation of the applied transitions.

### ide_enroll_all_projects (disabled by default)
Enroll all currently open projects in MCP lifecycle management. Projects already managed are skipped. Only open projects can be enrolled — closed projects must be opened first (via `ide_open_project` or auto-open).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Routing hint when multiple projects are open |

**Returns**: text summary of enrolled and skipped projects.

### ide_release_project (disabled by default)
Release a project from MCP lifecycle management, returning full control to the user. After release: Power Save Mode is disabled, all lifecycle timers are cancelled, and the project is no longer auto-slept or auto-closed.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | no | Filesystem path of a closed managed project to release. Omit to release the routed project (must be open) |
| `project_path` | string | no | Routing hint when multiple projects are open |

**Returns**: text confirmation of the release.

### ide_release_all_projects (disabled by default)
Release every managed project from MCP lifecycle management at once, including projects currently closed by the lifecycle manager. Power Save Mode is disabled after all releases complete.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | no | Routing hint when multiple projects are open |

**Returns**: text summary of released projects.

### ide_lifecycle_log (disabled by default)
Return recent lifecycle events for all projects (ring buffer, last 500 events). Covers state transitions, open/close, focus changes, timer firings, and MCP-triggered wakes for all IntelliJ projects, not just managed ones. The same log is also written to a file (path returned as `log_file`) when file logging is enabled via `ide_set_lifecycle_log_file` or IDE debug logging.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `limit` | integer | no | Number of recent events to return, newest first (default 50, max 500) |
| `project` | string | no | Optional path filter (substring match against project path) |
| `project_path` | string | no | Routing hint when multiple projects are open |

**Returns**: `{ events: [{timestamp, project, path, event, from?, to?, trigger, detail?}], log_file, buffered }`. Event types: `open`, `closed`, `transition`, `enroll`, `release`, `wake`, `editors_closed`, `editors_restored`. Trigger values: `focus_gained`, `focus_lost`, `timer:focus`, `timer:inactivity`, `timer:close`, `mcp_call`, `auto_open`, `user`. `detail` explains an event where it helps — how long the project had no MCP call when `timer:inactivity` fired, or how many editor tabs a dormant transition closed.

### ide_set_lifecycle_log_file (disabled by default)
Enable or disable writing lifecycle events to the log file on disk. The in-memory ring buffer (queryable via `ide_lifecycle_log`) is always active regardless of this setting; the file allows `tail -f` monitoring and post-mortem analysis even when no MCP connection is available.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `enabled` | boolean | yes | `true` to write events to the log file, `false` to stop |
| `project_path` | string | no | Routing hint when multiple projects are open |

**Returns**: text confirmation (includes the log file path when enabling).

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

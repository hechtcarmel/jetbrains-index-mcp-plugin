# IDE Index MCP Server — Issue Tracker

Comprehensive issue report from systematic testing against a Kotlin codebase (2026-02-20).

## Status Legend

| Status | Meaning |
|--------|---------|
| `[ ]`  | Open — not started |
| `[~]`  | In progress |
| `[x]`  | Fixed |
| `[-]`  | Won't fix / by design |

---

## P0 — Critical

### 1. `ide_call_hierarchy` — Empty results for all Kotlin methods
- **Status:** `[x]`
- **Tool:** `ide_call_hierarchy`
- **Symptom:** Both `callers` and `callees` directions return `calls: []` for every Kotlin method. The method itself is correctly identified (including CPS-transformed JVM signature), but no call relationships are found.
- **Scope:** Affects all method types: `suspend fun`, `private fun`, regular `fun`, simple and complex methods.
- **Root cause hypothesis:** The MCP bridge may query `CallHierarchyBrowser` API which requires `KtNamedFunction` PSI, but the element is being resolved as a decompiled Java `PsiMethod`. The Kotlin plugin may not bridge these for call hierarchy.
- **Workaround:** `ide_find_references` on the method definition (finds callers). For callees, read the method body via Serena.
- **Files to investigate:**
  - `src/main/kotlin/.../handlers/java/JavaHandlers.kt` — `JavaCallHierarchyHandler`
  - Check how the PSI element is resolved and passed to call hierarchy APIs

### 2. `ide_search_text` — Severe deduplication and false positives
- **Status:** `[x]`
- **Tool:** `ide_search_text`
- **Symptom:** Results contain massive duplication (same line returned 5-8x with different columns) and false positives (lines that don't contain the search term at all, e.g., `package` declarations).
- **Example:** Searching `PostCHTerminator` returns 2 real matches, 2 false positives, and 11 duplicates out of 15 results.
- **Root cause hypothesis:** The word index returns file-level token offset tables rather than filtering to lines that actually contain the search term. Multiple token positions per line create duplicates.
- **Impact:** Tool is actively misleading — cannot trust results.
- **Files to investigate:**
  - `src/main/kotlin/.../tools/navigation/` — search text tool implementation
  - Deduplication logic (or lack thereof)

---

## P1 — Moderate

### 3. No language filter on search tools
- **Status:** `[x]`
- **Tools:** `ide_find_class`, `ide_find_symbol`, `ide_find_file`
- **Symptom:** Generic queries (`Exception`, `Service`, `*Test`) are dominated by Python `.venv` files in polyglot projects. No `language` parameter available.
- **Impact:** In polyglot projects, generic queries return zero Kotlin results because Python `.venv` files consume the entire limit.
- **Proposed fix:** Add optional `language` or `scope` parameter to search tools, or default to excluding common dependency directories (`.venv`, `node_modules`).

### 4. `ide_find_file` returns `bin/` build output duplicates
- **Status:** `[x]`
- **Tool:** `ide_find_file`
- **Symptom:** Every Kotlin file appears 2-3 times (`src/` + `bin/` + `.serena/`), wasting result budget.
- **Impact:** Effective result count halved.
- **Proposed fix:** Add `excludeDirectories` parameter, or auto-exclude `bin/`, `build/`, `.gradle/` output directories.

### 5. `ide_type_hierarchy` — Kotlin types reported as `language: "Java"`
- **Status:** `[x]`
- **Tool:** `ide_type_hierarchy`
- **Symptom:** The queried element and supertypes report `language: "Java"` for Kotlin interfaces, abstract classes, and sealed classes. Subtypes correctly report `language: "Kotlin"`.
- **Root cause hypothesis:** Hierarchy resolver reads compiled `.class` for queried element (losing Kotlin origin info) but reads source for subtypes.
- **Note:** `ide_find_class` correctly reports `language: "Kotlin"` for the same classes. Bug is specific to `ide_type_hierarchy`.

### 6. `ide_file_structure` — Fails on `.gradle.kts` and config files
- **Status:** `[x]` (`.gradle.kts` fixed; `.conf` remains unsupported — not a Kotlin/Java file)
- **Tool:** `ide_file_structure`
- **Symptom:** `build.gradle.kts` returns "File is empty or has no parseable structure". `.conf` files return "Language not supported".
- **Impact:** Cannot inspect build file structure — common during project setup/debugging.
- **Root cause:** `.gradle.kts` extension likely not mapped to Kotlin parser in MCP bridge.

### 7. `ide_find_definition` — Cannot follow import statements
- **Status:** `[x]`
- **Tool:** `ide_find_definition`
- **Symptom:** Positioning cursor on a class name in an import statement returns "Definition file not found".
- **Root cause:** MCP bridge doesn't handle `KtImportDirective` PSI elements.
- **Workaround:** Use `ide_find_class` with the class name from the import.

### 8. `fullElementPreview=true` — No size limit
- **Status:** `[x]`
- **Tool:** `ide_find_definition`
- **Symptom:** With `fullElementPreview=true`, returns entire class body without truncation (40KB+ for large classes).
- **Impact:** Context window waste.
- **Proposed fix:** Add `maxPreviewLines` parameter (default: 50), or return class signature + method signatures instead of full body.

---

## P2 — Minor

### 9. `ide_find_implementations` — Cannot navigate inside library JARs
- **Status:** `[-]`
- **Tool:** `ide_find_implementations`
- **Symptom:** JAR source paths returned by `find_class(includeLibraries=true)` cannot be used as input to `find_implementations`. Returns "No element found at position".
- **Note:** Deferred — requires VFS resolution changes to support `jar://` paths in position-based tools. Non-trivial.
- **Workaround:** Use `ide_type_hierarchy` with `className` to find subtypes instead.

### 10. Cascade errors kill parallel sibling tool calls
- **Status:** `[-]`
- **Impact:** When one tool call in a parallel batch fails, ALL sibling calls return "Sibling tool call errored".
- **Note:** Client-side behavior — the JSON-RPC handler processes each request independently. The cascade is caused by the MCP client (Claude/Cursor), not this plugin. Not fixable server-side.

### 11. `ide_diagnostics` — No intentions for Kotlin code
- **Status:** `[-]`
- **Tool:** `ide_diagnostics`
- **Symptom:** All Kotlin files return 0 intentions regardless of cursor position. Python files return intentions correctly.
- **Impact:** Tool useful for compilation errors only, not for discovering available IDE actions.
- **Note:** Deferred — requires investigation into Kotlin plugin's intention registration (may use different API than `IntentionManager.getAvailableIntentions()`). Compilation errors still work correctly.

### 12. `ide_find_definition` — Parameter name vs type name resolution
- **Status:** `[-]`
- **Tool:** `ide_find_definition`
- **Symptom:** Cursor on parameter name resolves to parameter declaration itself (circular). Must position on type name to navigate to type definition.
- **Note:** By design — parameter name correctly resolves to its declaration. Position cursor on the type name instead to navigate to the type definition.

### 13. `ide_find_references` — `totalCount` always equals returned count
- **Status:** `[x]`
- **Tool:** `ide_find_references`
- **Symptom:** `totalCount` reflects returned count, not true total. Cannot tell if truncation occurred.
- **Proposed fix:** Compute true total separately from the returned results (like `ide_refactor_safe_delete` does).

### 14. `ide_find_symbol` — Overly broad substring matching
- **Status:** `[x]`
- **Tool:** `ide_find_symbol`, `ide_find_class`
- **Symptom:** "solve" matches "resolveUUID" and "canResolve". No option for exact-match or word-boundary matching.
- **Fix:** Added `matchMode` parameter to both `ide_find_symbol` and `ide_find_class`: `"substring"` (default, backward compatible), `"prefix"` (camelCase-aware prefix matching), `"exact"` (case-insensitive exact match). Flows through `SymbolSearchHandler` → `OptimizedSymbolSearch`.

---

## Priority Order for Fixes

### High impact, likely tractable
1. **#2 `ide_search_text` dedup/false positives** — Dedup by (file, line) and verify search term appears in returned line
2. **#13 `ide_find_references` totalCount** — Report true count separately
3. **#4 `ide_find_file` bin/ duplicates** — Filter `bin/`, `build/` output directories
4. **#8 `fullElementPreview` size limit** — Add `maxPreviewLines` parameter

### Medium impact, needs investigation
5. **#1 `ide_call_hierarchy`** — Investigate Kotlin PSI resolution in call hierarchy API
6. **#3 Language filter** — Add scope/language parameter to search tools
7. **#5 `ide_type_hierarchy` language field** — Fix language detection for queried element
8. **#7 `ide_find_definition` on imports** — Handle `KtImportDirective` PSI elements

### Lower priority
9. **#6 `ide_file_structure` on .gradle.kts** — Map extension to Kotlin parser
10. **#14 `ide_find_symbol` match modes** — Add `matchMode` parameter
11. **#11 `ide_diagnostics` intentions** — Investigate Kotlin intention provider
12. **#9 `ide_find_implementations` in JARs** — Virtual file resolution for JARs
13. **#12 `ide_find_definition` param name** — Consider resolving to type as fallback
14. **#10 Cascade errors** — May require MCP transport changes

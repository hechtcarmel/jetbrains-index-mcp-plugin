# MCP Plugin Systematic Testing — Bug Tracking

**Date**: 2026-02-21
**Tester**: Claude (cross-validated all results against grep/Serena ground truth)

## Test Environment

| Project | Language | Path | Build |
|---------|----------|------|-------|
| backend-kotlin (edu-planner-kotlin) | Kotlin + Python | `/home/marius/work/mirela/backend-kotlin` | Gradle |
| eduplanner-ui | TypeScript (React) | `/home/marius/work/mirela/eduplanner-ui` | Vite |
| hiiretail-payment-client-adyen | Java | `/home/marius/work/stefanini/extenda/sdd/hiiretail-payment-client-adyen` | Maven |

---

## BUG 1: `ide_find_class("*")` wildcard returns wrong/incomplete results — PARTIALLY FIXED

**Severity**: High
**Affects**: All projects, all languages
**Tool**: `ide_find_class`
**Initial fix**: `a8fd60b` — Excluded root-level venv/node_modules/worktrees from search results
**Full fix**: `ce08ef9` — Extended filtering to match venv/node_modules at any path depth

### Reproduction

```json
// backend-kotlin — returns Python venv classes, NOT Kotlin project classes
{"query": "*", "project_path": "/home/marius/work/mirela/backend-kotlin", "limit": 10}
// Before a8fd60b: ContentLengthWriter, Http10Writer... (from root .venv/)
// Before ce08ef9: same classes from python-services/.venv/ (nested venv)
```

### Fix

Split exclusion into two tiers:
- **Root-only**: `bin/`, `build/`, `out/`, `.gradle/` — only excluded when at project root
- **Any depth**: `.venv/`, `venv/`, `node_modules/`, `.worktrees/` — excluded at any path depth via `path.contains("/$segment")`

This fixes multi-module Python projects where the venv is inside a subdirectory (e.g. `python-services/.venv/`).

---

## BUG 2: `.worktrees/` directory files pollute search results — FIXED

**Severity**: Medium
**Affects**: eduplanner-ui (any project with git worktrees in the project directory)
**Tools**: `ide_find_symbol`, `ide_find_class`
**Fixed in**: `a8fd60b` — Excluded venv/node_modules/worktrees from search results

### Reproduction

```json
{"query": "useAuth", "project_path": "/home/marius/work/mirela/eduplanner-ui"}
```

### Fix

Results from `.worktrees/` directories are now filtered out, eliminating duplicate entries from worktree copies.

---

## BUG 3: `ide_find_symbol` exact matchMode is case-insensitive — FIXED

**Severity**: Low
**Affects**: All projects
**Tool**: `ide_find_symbol`
**Fixed in**: `bca24ad` — Made exact matchMode case-sensitive

### Reproduction

```json
{"query": "CalendarService", "matchMode": "exact", "language": "Kotlin",
 "project_path": "/home/marius/work/mirela/backend-kotlin"}
```

Previously returned both `CalendarService` (CLASS) and `calendarService` (properties).

### Fix

Changed `createNameFilter` in `SearchMatchUtils.kt` from `name.equals(pattern, ignoreCase = true)` to `name == pattern` for exact mode.

---

## BUG 4: `ide_file_structure` doesn't support TypeScript/JavaScript — FIXED

**Severity**: Medium (feature gap)
**Affects**: TypeScript and JavaScript projects
**Tool**: `ide_file_structure`
**Fixed in**: `a8fd60b` — Added JS/TS file structure support

### Reproduction

```json
{"file": "src/providers/AuthProvider.tsx",
 "project_path": "/home/marius/work/mirela/eduplanner-ui"}
```

Previously returned: `Error: Language not supported for file structure. Supported languages: JAVA, kotlin, Python`

### Fix

Added JavaScript/TypeScript file structure handler using reflection-based PSI access, matching the pattern used by Python handlers.

---

## BUG 5: `ide_call_hierarchy` shows "unknown" for JSX component callers — FIXED

**Severity**: Low
**Affects**: TypeScript React projects
**Tool**: `ide_call_hierarchy`
**Initial fix**: `bca24ad` — Added JSVariable fallback when no JSFunction found
**Full fix**: `ce08ef9` — Skip unnamed JSFunction (anonymous arrows) before trying JSVariable

### Reproduction

```json
{"file": "src/providers/AuthProvider.tsx", "line": 272, "column": 17,
 "direction": "callers", "project_path": "/home/marius/work/mirela/eduplanner-ui"}
```

Caller names showed as `"unknown"` for:
- Arrow functions assigned to variables: `const App = () => <Foo />`  *(fixed in bca24ad)*
- References inside nested anonymous lambdas: `const Foo = lazy(() => import(...).then(m => m.Foo))` *(fixed in ce08ef9)*

### Fix

`findContainingCallable()` now skips any unnamed `JSFunction` (anonymous arrow expression) and goes directly to the enclosing `JSVariable`. Previously it would stop at the first (unnamed) JSFunction regardless.

---

## BUG 6: Duplicate JSX reference entries in `ide_find_references` — FIXED

**Severity**: Low
**Affects**: TypeScript React projects
**Tool**: `ide_find_references`
**Initial fix**: `bca24ad` — Deduplicated references by file:line:column
**Follow-up fix**: `ce08ef9` — Fixed misleading `truncated: true` caused by deduplication

### Reproduction

```json
{"file": "src/providers/AuthProvider.tsx", "line": 46, "column": 17,
 "project_path": "/home/marius/work/mirela/eduplanner-ui"}
```

JSX component `<AuthProvider>...</AuthProvider>` generated two identical entries at the same position. After `bca24ad` dedup, the result showed `totalCount: 3`, `truncated: true` with only 2 usages — falsely suggesting results were cut off.

### Fix

- `bca24ad`: Added `.distinctBy { "${it.file}:${it.line}:${it.column}" }` on collected usages.
- `ce08ef9`: Fixed `truncated` to be `total > maxResults` (reflects actual result limit) instead of `total > usagesList.size` (which incorrectly fired when dedup removed JSX tag duplicates).

---

## Summary

| Bug | Tool | Severity | Status | Fixed in |
|-----|------|----------|--------|----------|
| BUG 1 | `ide_find_class` (wildcard) | High | FIXED | `a8fd60b`, `ce08ef9` |
| BUG 2 | `ide_find_symbol` / `ide_find_class` | Medium | FIXED | `a8fd60b` |
| BUG 3 | `ide_find_symbol` (exact mode) | Low | FIXED | `bca24ad` |
| BUG 4 | `ide_file_structure` | Medium | FIXED | `a8fd60b` |
| BUG 5 | `ide_call_hierarchy` | Low | FIXED | `bca24ad`, `ce08ef9` |
| BUG 6 | `ide_find_references` | Low | FIXED | `bca24ad`, `ce08ef9` |

All 6 bugs discovered during systematic testing have been fixed.

### Cross-Validation Methodology

Every tool result was compared against independent ground truth:
- **grep** (bash): Counted all textual references in source files
- **Serena search_for_pattern**: Pattern-based search (limited to active project)
- **File reads**: Manual verification of specific lines/columns

Reference count validation approach:
1. Run MCP tool, record result count
2. Run grep for same symbol
3. Subtract: definition itself, plain comments (non-javadoc), string literals, module paths
4. Compare adjusted counts — must match exactly

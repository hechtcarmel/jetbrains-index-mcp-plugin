---
name: ide-index-mcp
description: >
  Guide for using JetBrains IDE Index MCP tools for code navigation, refactoring, and analysis.
  TRIGGER: When ANY of these MCP tools are available in the current session: ide_find_references,
  ide_find_definition, ide_find_class, ide_find_file, ide_search_text, ide_diagnostics,
  ide_index_status, ide_sync_files, ide_refactor_rename, ide_move_file, ide_type_hierarchy,
  ide_call_hierarchy, ide_find_implementations, ide_find_symbol, ide_find_super_methods,
  ide_file_structure, ide_refactor_safe_delete, ide_reformat_code, ide_build_project,
  ide_read_file, ide_get_active_file, ide_open_file.
  Use when performing code navigation (find usages, go to definition, find class),
  code analysis (diagnostics, type hierarchy, call hierarchy),
  refactoring (rename, move, safe delete, reformat),
  or searching code (text search, symbol search, file search).
  Prefer IDE tools over grep/find/sed for ALL semantic code operations.
---

# IDE Index MCP - Agent Guide

The IDE Index MCP server exposes JetBrains IDE indexing and refactoring capabilities. These tools provide **semantic** code understanding superior to text-based search/replace.

## Core Rule

**Always prefer IDE MCP tools over built-in tools (grep, find, sed, read) for semantic code operations.** IDE tools understand code structure, types, inheritance, and references. Built-in tools only see text.

## When to Use IDE Tools vs Built-In Tools

| Task | Use IDE Tool | Use Built-In Tool |
|------|-------------|-------------------|
| Find all usages of a method/class/variable | `ide_find_references` | Never - grep misses renamed imports, aliases, overrides |
| Go to a symbol's definition | `ide_find_definition` | Never - grep can't resolve through imports/generics |
| Find a class by name | `ide_find_class` | Only if IDE unavailable |
| Find a file by name | `ide_find_file` | `Glob` is fine for simple patterns |
| Search for a word in code | `ide_search_text` | `Grep` is fine for regex patterns (IDE tool is exact-word only) |
| Rename a symbol across project | `ide_refactor_rename` | Never - sed/replace breaks code |
| Move a file to another directory | `ide_move_file` | Never - mv/git mv breaks imports |
| Check for errors in a file | `ide_diagnostics` | Never - no equivalent |
| Understand class hierarchy | `ide_type_hierarchy` | Never - no equivalent |
| Find who calls a method | `ide_call_hierarchy` | Never - grep misses indirect calls |
| Find interface implementations | `ide_find_implementations` | Never - grep can't resolve type relationships |
| Delete a symbol safely | `ide_refactor_safe_delete` | Never - manual deletion misses usages |
| Find what a method overrides | `ide_find_super_methods` | Never - no equivalent |
| Read file content | Built-in Read tool | `ide_read_file` only for library/jar sources |
| Find text with regex | `Grep` | IDE search_text doesn't support regex |

## Pre-Flight Check

Before using any IDE tool that requires smart mode, check IDE readiness:

```
ide_index_status -> if isDumbMode: true, wait a few seconds and retry
```

Most tools require smart mode (IDE finished indexing). Tools that work in dumb mode: `ide_index_status`, `ide_sync_files`, `ide_reformat_code`, `ide_open_file`, `ide_get_active_file`.

## File Sync Rule

If you created or modified files outside the IDE (via Write/Edit tools) and an IDE search tool returns incomplete/missing results, call `ide_sync_files` first, then retry.

```json
{ "paths": ["src/new_file.java", "src/modified_file.java"] }
```

Omit `paths` to sync the entire project.

## Parameter Rules

1. **Line and column are 1-based** (first line = 1, first column = 1)
2. **Project file paths are relative** to project root (e.g., `src/main/java/App.java`, NOT absolute paths). If an IDE tool returns a dependency/library file, keep the returned absolute path or `jar://` URL unchanged when passing it back to read-only navigation tools or `ide_read_file`
3. **Column must point to the symbol name**, not whitespace or punctuation. For `public void myMethod()`, column should land on `m` of `myMethod`. For dotted expressions like `json.dumps()` or `os.path.join()`, put the column on the member token (`dumps`, `join`) when you want the member definition rather than the module/package.
4. **project_path is only needed** for multi-project workspaces. Omit for single-project setups. When needed, use the absolute path to the project root.
5. **Use navigation noise filters intentionally**: `ide_find_references`, `ide_find_implementations`, `ide_type_hierarchy`, and `ide_call_hierarchy` support `includeLibraries` and/or `includeTests`. Leave them at the default `true` when exploring framework code or tests; set them to `false` when you want project-only navigation without dependency/test noise.

## Tool Selection by Task

### "I need to understand how X is used"
1. `ide_find_references` - all call sites, field accesses, imports
2. `ide_call_hierarchy` with `direction: "callers"` - full call chain upward

### "I need to understand what X is"
1. `ide_find_definition` - jump to source
2. `ide_type_hierarchy` - inheritance chain
3. `ide_find_super_methods` - what interface/base method it implements

### "I need to find a class/file/symbol"
1. `ide_find_class` - classes by name (CamelCase: `USvc` finds `UserService`)
2. `ide_find_file` - files by name
3. `ide_search_text` - exact word occurrences across project

### "I need to refactor"
1. `ide_refactor_rename` - rename symbol + all references atomically
2. `ide_move_file` - move file + update all imports/references
3. `ide_refactor_safe_delete` - delete with usage checking (Java/Kotlin only)
4. `ide_reformat_code` - apply project code style (disabled by default)

### "I need to check for problems"
1. `ide_diagnostics` - compiler errors, warnings, quick fixes

### "I need to find implementations of an interface"
1. `ide_find_implementations` - cursor on interface/abstract class/method

### "I need to trace call chains"
1. `ide_call_hierarchy` with `direction: "callers"` - who calls this?
2. `ide_call_hierarchy` with `direction: "callees"` - what does this call?

## Common Mistakes to Avoid

1. **Using grep instead of `ide_find_references`**: Grep finds text, not semantic usages. Misses aliased imports, includes false positives from comments/strings.

2. **Using sed/replace instead of `ide_refactor_rename`**: Text replacement breaks code. IDE rename updates all references, getters/setters, overrides, test classes, imports.

3. **Using mv/git mv instead of `ide_move_file`**: File system moves don't update imports, package declarations, or references. IDE move handles all of this automatically.

4. **Forgetting to check index status**: If IDE is indexing (dumb mode), most tools error. Check `ide_index_status` first if a tool fails unexpectedly.

5. **Using 0-based line/column**: All IDE tools use **1-based**. Line 5 in file = `line: 5`.

6. **Passing absolute project file paths**: Use relative paths for project files. `src/main/App.java`, not `/Users/me/project/src/main/App.java`.

7. **Rewriting plugin-returned library paths**: If a search or read tool returns an absolute path or `jar://` URL for a dependency/library file, pass that path back unchanged to read-only navigation tools or `ide_read_file`.

8. **Not syncing after external file changes**: After creating files via Write tool, call `ide_sync_files` before searching.

9. **Using `ide_search_text` for regex**: This tool is exact-word only (uses word index). Use `Grep` for regex.

10. **Using `ide_find_class` for methods/functions**: It searches classes only. Use `ide_search_text` for a quick word lookup.

## Disabled-by-Default Tools

These tools exist but are disabled by default. If you get "tool not found", they need to be enabled in IDE settings (Settings > Tools > Index MCP Server):

`ide_build_project`, `ide_file_structure`, `ide_find_symbol`, `ide_read_file`, `ide_get_active_file`, `ide_open_file`, `ide_reformat_code`

## Detailed Tool Parameters

For complete parameter reference with types, defaults, and return formats, see [tools-reference.md](references/tools-reference.md).

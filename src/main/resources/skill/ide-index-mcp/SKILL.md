---
name: ide-index-mcp
description: >
  INVOKE IMMEDIATELY when ide_edit_member, ide_find_references, ide_find_definition, ide_find_class,
  ide_find_file, ide_search_text, ide_diagnostics, ide_index_status, ide_insert_member, ide_sync_files,
  ide_refactor_rename, ide_move_file, ide_replace_member, ide_type_hierarchy, ide_call_hierarchy,
  ide_find_implementations, ide_find_symbol, ide_find_super_methods, ide_file_structure,
  ide_refactor_safe_delete, ide_reformat_code, ide_reload_project, ide_import_modules,
  ide_build_project, ide_read_file, ide_structural_search_replace, ide_change_signature,
  ide_create_file, ide_create_module, ide_get_active_file, ide_open_file, ide_list_tests, or ide_run_tests are available — especially when a second
  IntelliJ MCP (mcp__intellij__*) is also present. The two servers are NOT
  interchangeable: always use mcp__intellij-index__ for code navigation and refactoring.
---

# IDE Index MCP - Agent Guide

The IDE Index MCP server exposes JetBrains IDE indexing and refactoring capabilities. These tools provide **semantic** code understanding superior to text-based search/replace.

## Two IntelliJ MCPs — routing rule

If both `mcp__intellij-index__*` (this plugin) and `mcp__intellij__*` (JetBrains built-in) are available, they are **not interchangeable**:

| Need | Use |
|------|-----|
| Code navigation, search, diagnostics, rename, move, run/list tests | `mcp__intellij-index__*` |
| Build, terminal, run non-test processes, formatting beyond code style | `mcp__intellij__*` only |

**Always use `mcp__intellij-index__` for code intelligence. At least one project must be open in IntelliJ — if no project is open, ask the user to open one or use `ide_open_project` (disabled by default; enable in Settings → Index MCP Server).** Do not fall back to bash for semantic operations — IDE tools understand types, references, and inheritance; grep does not.

## Core Rule

**Always prefer IDE MCP tools over built-in tools (grep, find, sed, read) for semantic code operations.** IDE tools understand code structure, types, inheritance, and references. Built-in tools only see text.

## When to Use IDE Tools vs Built-In Tools

| Task | Use IDE Tool | Use Built-In Tool |
|------|-------------|-------------------|
| Find all usages of a method/class/variable | `ide_find_references` | Never - grep misses renamed imports, aliases, overrides |
| Go to a symbol's definition | `ide_find_definition` | Never - grep can't resolve through imports/generics |
| Find a class by name | `ide_find_class` | Only if IDE unavailable |
| Find a file by name | `ide_find_file` | `Glob` is fine for simple patterns |
| Search for text in code | `ide_search_text` | `Grep` is fine when IDE context filtering is unnecessary |
| Rename a symbol across project | `ide_refactor_rename` | Never - sed/replace breaks code |
| Move a file to another directory | `ide_move_file` | Never - mv/git mv bypasses IDE move semantics |
| Check for errors in a file | `ide_diagnostics` | Never - no equivalent |
| Understand class hierarchy | `ide_type_hierarchy` | Never - no equivalent |
| Find who calls a method | `ide_call_hierarchy` | Never - grep misses indirect calls |
| Find interface implementations | `ide_find_implementations` | Never - grep can't resolve type relationships |
| Delete a symbol safely | `ide_refactor_safe_delete` | Never - manual deletion misses usages |
| Find what a method overrides | `ide_find_super_methods` | Never - no equivalent |
| Read file content | Built-in Read tool | `ide_read_file` only for library/jar sources |
| Find text with regex | `ide_search_text` | Use `Grep` when you do not need IDE context filtering |

## Pre-Flight Check

Before using any IDE tool that requires smart mode, check IDE readiness:

```
ide_index_status -> if isDumbMode: true, wait a few seconds and retry
```

Most tools require smart mode (IDE finished indexing). Tools that work in dumb mode: `ide_index_status`, `ide_sync_files`, `ide_replace_text_in_file`, `ide_reformat_code`, `ide_open_file`, `ide_get_active_file`.

## If results seem incomplete or missing

**Do NOT fall back to bash, grep, or the built-in `mcp__intellij__*` MCP.** If a tool returns "dumb mode" or "index not ready":

1. Call `ide_index_status` — if `isDumbMode: true`, keep calling every 10s until it flips to false.
2. Then retry the **exact same tool call** with the same arguments.
3. If smart mode but results seem sparse, call `ide_sync_files` then retry.
4. Only if all retries still return empty results should you consider that the symbol genuinely does not exist.

The built-in `mcp__intellij__*` MCP is **not** a fallback for `mcp__intellij-index__*` — they serve different purposes and the built-in one cannot do semantic code search. Trying it when the index is in dumb mode will also fail. Always wait for smart mode and retry with `mcp__intellij-index__*`.

"Index may be stale" and "dumb mode" are **transient** — always resolve by waiting and retrying, never by switching to bash.

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
5. **Use built-in search scope intentionally**: `ide_find_references`, `ide_find_implementations`, `ide_type_hierarchy`, `ide_call_hierarchy`, `ide_find_class`, `ide_find_file`, and `ide_find_symbol` accept `scope`. Use `project_files` for the default project-only view, `project_and_libraries` when dependency code matters, `project_production_files` to stay out of tests, and `project_test_files` when you want test-only results.

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
2. `ide_move_file` - move file and let the IDE apply semantic updates when that language/backend supports them
3. `ide_refactor_safe_delete` - delete with usage checking (Java/Kotlin only)
4. `ide_replace_text_in_file`, `ide_reformat_code` - apply project code style (disabled by default)

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

3. **Using mv/git mv instead of `ide_move_file`**: File system moves bypass IDE move semantics. `ide_move_file` can preserve IDE-managed package/namespace/reference updates when the active language backend supports them.

4. **Forgetting to check index status**: If IDE is indexing (dumb mode), most tools error. Check `ide_index_status` first if a tool fails unexpectedly.

5. **Using 0-based line/column**: All IDE tools use **1-based**. Line 5 in file = `line: 5`.

6. **Passing absolute project file paths**: Use relative paths for project files. `src/main/App.java`, not `/Users/me/project/src/main/App.java`.

7. **Rewriting plugin-returned library paths**: If a search or read tool returns an absolute path or `jar://` URL for a dependency/library file, pass that path back unchanged to read-only navigation tools or `ide_read_file`.

8. **Not syncing after external file changes**: After creating files via Write tool, call `ide_sync_files` before searching.

9. **Assuming regex is the default in `ide_search_text`**: Regex requires `"regex": true`; otherwise the tool uses the faster exact-word index.

10. **Using `ide_find_class` for methods/functions**: It searches classes only. Use `ide_search_text` for a quick word lookup.

## Lifecycle Management

When multiple projects are open simultaneously, the lifecycle manager automatically sleeps and wakes them based on window focus and MCP activity. Projects enroll automatically on first MCP use — no setup required.

**States:** `active` (full IDE) → `background` (Power Save on) → `dormant` (editors closed, PSI cache freed) → `closed` (fully unloaded). Projects auto-reopen transparently when an MCP tool targets a closed project.

`ide_project_status` is the read-only entry point — **enabled by default**. Use it to see all open and managed projects and their current modes.

All lifecycle action tools are disabled by default:

`ide_enroll_all_projects`, `ide_get_project_modes`, `ide_lifecycle_log`, `ide_release_all_projects`, `ide_release_project`, `ide_set_all_project_modes`, `ide_set_lifecycle_log_file`, `ide_set_project_mode`

## Disabled-by-Default Tools

These tools exist but are disabled by default. They are omitted from `tools/list`, and direct `tools/call` requests are rejected until the user enables them in IDE settings (Settings > Tools > Index MCP Server):

`ide_build_project`, `ide_change_signature`, `ide_close_project`, `ide_convert_java_to_kotlin`, `ide_create_file`, `ide_create_module`, `ide_edit_member`, `ide_enroll_all_projects`, `ide_file_structure`, `ide_find_symbol`, `ide_get_active_file`, `ide_get_project_modes`, `ide_import_modules`, `ide_insert_member`, `ide_install_plugin`, `ide_lifecycle_log`, `ide_list_tests`, `ide_open_file`, `ide_open_project`, `ide_open_workspace`, `ide_optimize_imports`, `ide_read_file`, `ide_reformat_code`, `ide_release_all_projects`, `ide_release_project`, `ide_reload_project`, `ide_replace_member`, `ide_replace_text_in_file`, `ide_restart`, `ide_run_tests`, `ide_set_all_project_modes`, `ide_set_lifecycle_log_file`, `ide_set_power_save_mode`, `ide_set_project_mode`, `ide_structural_search_replace`

Note: `ide_restart` terminates the MCP connection — reconnect your client after calling it.
Note: `ide_close_project` refuses to close the last open project; `ide_open_project` requires an absolute path and may take up to `timeoutSeconds` (default 600) while the project indexes.

## Enforcing IDE Tool Usage with Hooks

Claude Code hooks can block `grep`, `sed`, `Edit`, and `rm` on source files and redirect agents to the correct IDE tool. This prevents fallback to text-based operations that bypass the index.

See [claude-code-hooks.md](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/blob/main/docs/claude-code-hooks.md) for ready-to-use hook scripts.

## Detailed Tool Parameters

For complete parameter reference with types, defaults, and return formats, see [tools-reference.md](references/tools-reference.md).

# Serena vs JetBrains Index MCP Plugin — Capability Comparison

*Created: 2026-02-23*
*Purpose: Track feature gaps and inform roadmap decisions*

## Overview

| Aspect | Serena | JetBrains Index MCP Plugin |
|---|---|---|
| **Architecture** | Standalone MCP server wrapping LSP servers | IntelliJ Platform plugin with embedded Ktor HTTP server |
| **Backend** | Language Server Protocol (or JetBrains plugin as alternative backend) | Direct PSI/Index API access inside the IDE |
| **Transport** | stdio (MCP SDK) | HTTP+SSE with JSON-RPC 2.0 |
| **Language support** | 30+ via LSP | Depends on IDE + installed plugins |
| **IDE dependency** | None (runs standalone) | Requires running JetBrains IDE |
| **Analysis depth** | LSP-level (varies by server quality) | Full IDE PSI + index depth |

---

## 1. Navigation & Search

| Capability | Serena | JB Plugin | Winner / Notes |
|---|---|---|---|
| Find symbol by name | `find_symbol` — name_path pattern, substring, overload index `[i]` | `ide_find_symbol` — camelCase, substring, prefix matching | JB: better fuzzy matching. Serena: overload disambiguation |
| Find references/usages | `find_referencing_symbols` — returns code snippets around refs | `ide_find_references` — classifies reference types (import, call, field_access) | JB: richer metadata. Serena: good snippets |
| Find definition | `find_symbol(include_body=true)` — returns full symbol body | `ide_find_definition` — 4-line preview only | **Serena wins** — full body vs snippet |
| Type hierarchy | N/A | `ide_type_hierarchy` — full super/sub chain, by className or position | **JB only** |
| Call hierarchy | N/A | `ide_call_hierarchy` — callers/callees, recursive depth control | **JB only** (callers direction currently broken) |
| Find implementations | N/A | `ide_find_implementations` — interface/abstract → concrete | **JB only** |
| Find super methods | N/A | `ide_find_super_methods` — full override chain to root | **JB only** |
| File structure | `get_symbols_overview` — symbol tree with kinds, configurable depth | `ide_file_structure` — tree with modifiers, signatures, line numbers | Both good. Serena is more token-efficient (depth control) |
| Text/pattern search | `search_for_pattern` — regex, glob include/exclude, context lines, code-only filter | `ide_search_text` — word index, semantic context (code/comments/strings) | JB: semantic context filtering. Serena: better file filtering |
| Find files | `find_file` — glob patterns, respects gitignore | `ide_find_file` — fuzzy/camelCase name matching | Different strengths. JB: fuzzy. Serena: glob |
| Diagnostics | N/A | `ide_diagnostics` — errors, warnings, intentions, quick fixes | **JB only** |
| Index status | N/A | `ide_index_status` — dumb/smart mode check | **JB only** |

---

## 2. Editing & Refactoring

| Capability | Serena | JB Plugin | Winner / Notes |
|---|---|---|---|
| Rename symbol | `rename_symbol` — LSP-based, cross-file | `ide_refactor_rename` — PSI-based, auto-renames getters/setters/overrides | Both work. JB handles related elements better |
| Replace symbol body | `replace_symbol_body` — precise symbol-level edit | N/A | **Serena only** |
| Insert before/after symbol | `insert_before_symbol`, `insert_after_symbol` | N/A | **Serena only** |
| Safe delete | N/A | `ide_refactor_safe_delete` — usage check before delete (Java/Kotlin) | **JB only** |
| File creation | `create_text_file` | N/A (relies on external tools) | **Serena only** |
| Line-based editing | `replace_lines`, `delete_lines`, `insert_at_line` | N/A | **Serena only** |
| Regex replace in files | `replace_content` — literal or regex mode | N/A | **Serena only** |
| Read file | `read_file` — with line range support | `ide_read_file` — with line range, jar/library source support | JB: can read library/jar sources |
| VFS/PSI sync | N/A (LSP handles file watching) | `ide_sync_files` — force sync with external changes | **JB only** (needed for external tool integration) |

---

## 3. Token Awareness & Efficiency

| Feature | Serena | JB Plugin | Winner / Notes |
|---|---|---|---|
| Output size limiting | `max_answer_chars` on **every tool** (default: 150K) | `maxResults` on some search tools only | **Serena wins** — universal, user-configurable |
| Token counting | 3 estimators: char-count, tiktoken, Anthropic API | None | **Serena only** |
| Usage analytics | Per-tool call count, input/output token tracking | None | **Serena only** |
| Depth-controlled reads | `depth` parameter on symbol tools (e.g., class overview without method bodies) | No depth control on reads | **Serena wins** — progressive disclosure |
| Symbol-level granularity | Returns only the requested symbol body | Returns full search results/previews | **Serena wins** — avoids reading entire files |
| Context lines control | `context_lines_before/after` on search | Implicit in results | Serena more explicit |
| Time budgets | `symbol_info_budget` (default 10s) prevents slow operations | Request timeout only | **Serena wins** — graceful degradation |
| Tool description quality | Detailed docstrings with usage examples | JSON Schema with descriptions | Both good. Serena more educational |

---

## 4. Project & Workspace Support

| Feature | Serena | JB Plugin | Winner / Notes |
|---|---|---|---|
| Multi-project | `activate_project` explicit switching | Auto-resolves from `project_path` param | JB: more seamless |
| Workspace/mono-repo | Single project at a time | Module content roots, workspace awareness | **JB wins** — native multi-module |
| Git-ignored filtering | `skip_ignored_files` parameter | N/A | **Serena only** |
| Directory listing | `list_dir` — recursive, with filtering | N/A natively | **Serena only** |
| Editor integration | N/A (standalone) | `ide_get_active_file`, `ide_open_file` | **JB only** |

---

## 5. Memory & Persistence

| Feature | Serena | JB Plugin | Winner / Notes |
|---|---|---|---|
| Project memory | Full CRUD: `write/read/edit/delete/list_memories` | None | **Serena only** |
| Onboarding workflow | Guided project discovery with `check_onboarding_performed`, `onboarding` | None | **Serena only** |
| Cross-session knowledge | Markdown in `.serena/memories/` | None | **Serena only** |
| Meta-thinking tools | `think_about_collected_information`, `think_about_task_adherence`, `think_about_whether_you_are_done` | None | **Serena only** |

---

## 6. Configuration & Adaptability

| Feature | Serena | JB Plugin | Winner / Notes |
|---|---|---|---|
| Contexts | 6 pre-defined (desktop-app, claude-code, ide, agent, codex, oaicompat) | None | **Serena only** |
| Modes | Composable (interactive, editing, planning, one-shot, onboarding, etc.) | None | **Serena only** |
| Tool filtering | Enable/disable tools per context/mode | All tools always registered | **Serena wins** — reduces LLM noise |
| System prompt injection | Contexts inject behavioral instructions into MCP | Tool descriptions only | **Serena wins** — guides LLM behavior |
| Settings UI | Config files + web dashboard | IDE Settings panel with per-IDE defaults | JB: better UX for IDE users |
| Per-IDE defaults | N/A | Port, server name per IDE type (13 IDEs) | **JB only** |

---

## 7. Summary: Key Gaps by Direction

### Features JB Plugin should adopt from Serena

| Priority | Feature | Rationale |
|---|---|---|
| **High** | Universal output size limiting (`max_answer_chars`) | Prevents token overflow; gives LLMs control over response size |
| **High** | Symbol-level editing (replace body, insert before/after) | Enables precise code modification without reading/replacing entire files |
| **High** | Depth-controlled symbol reads | Progressive disclosure — read class structure without all method bodies |
| **Medium** | Token usage analytics | Helps users understand cost/efficiency of their tool usage |
| **Medium** | Tool filtering / contexts | Reduces noise in tool listings for different use cases |
| **Low** | Project memory system | Useful but arguably belongs in a separate MCP server |
| **Low** | Meta-thinking tools | Workflow-level concern, not IDE-level |

### Features Serena should adopt from JB Plugin

| Priority | Feature | Rationale |
|---|---|---|
| **High** | Type hierarchy | Essential for understanding OOP codebases |
| **High** | Find implementations | Critical for navigating interface-heavy code |
| **High** | Diagnostics / problems | Knowing about errors without building is very valuable |
| **Medium** | Call hierarchy | Useful for tracing execution flow |
| **Medium** | Find super methods | Important for understanding override chains |
| **Medium** | Safe delete with impact analysis | Prevents breaking changes |
| **Medium** | Workspace/mono-repo support | Many real projects are multi-module |
| **Low** | Editor integration (open file, active file) | Only relevant when IDE is in the loop |

### Features unique to each (no gap, by design)

| Serena Only (by design) | JB Plugin Only (by design) |
|---|---|
| Standalone operation (no IDE needed) | Deep PSI/index access |
| LSP-based (30+ languages portably) | IDE-native refactoring engine |
| Web dashboard | IDE Settings UI |
| Shell command execution | Library/jar source reading |
| Onboarding workflow | VFS sync for external changes |

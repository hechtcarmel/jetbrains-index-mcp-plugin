# MCP Output Size Limiting — Ecosystem Patterns

*Created: 2026-02-23*
*Purpose: Justify `max_answer_chars` design in PR #66*

## The Problem `maxResults` Does Not Solve

`maxResults` limits the **count** of results. It gives no control over the **size** of each result.

Examples where `maxResults=100` still overflows context:
- `ide_find_references` on a widely-used utility — 100 references × 10 lines of context each = large output
- `ide_find_definition` with `fullElementPreview=true` on a 500-line class — 1 result, massive output
- `ide_read_symbol` at `depth=0` on any large symbol — 1 result, unbounded size

These are orthogonal dimensions. Both controls are needed.

---

## How the MCP Ecosystem Handles This

### GitHub's Official MCP Server

Uses two mechanisms:
1. **`minimal_output` parameter** per tool — controls data verbosity at the field level
2. **Explicit `[truncated]` suffix** on responses that exceed line/byte limits

From their [releases](https://github.com/github/github-mcp-server/releases):
> "Actions tools no longer fail with extremely long line length in output — single line minified JS and things will now be truncated with an explicit `[truncated]` suffix to help the model understand."

They do **not** rely on `maxResults` alone. Size-based truncation is a separate, required mechanism.

### Serena

`max_answer_chars` on **every tool** (default configurable via `TOOL_DEFAULT_MAX_ANSWER_LENGTH`).

When a response overflows, Serena returns a structured error with hints:
> "use pagination, filtering, or limit parameters to reduce response size"

Real production case — [issue #516](https://github.com/oraios/serena/issues/516): a user's `search_for_pattern` call returned 32K tokens and was blocked. The error message pointed them to `max_answer_chars`. They narrowed their regex. Problem solved without any code changes.

This is the correct behavior: the tool teaches the caller to self-correct.

### Neo4j MCP

Uses three layers:
1. **Timeout** — limits query execution time
2. **Truncation** — cuts responses at a configurable size
3. **Result sanitization** — strips fields that don't serve the LLM

Source: [Preventing Context Overload — Towards Data Science](https://towardsdatascience.com/preventing-context-overload-controlled-neo4j-mcp-cypher-responses-for-llms/)

### mcp-agent (lastmile-ai)

Open feature request [#209](https://github.com/lastmile-ai/mcp-agent/issues/209): "Configurable max tool response size."

Maintainer response:
> "What do you think of adding it as a property to `RequestParams`? And having `AugmentedLLM` enforce it?"

The proposal: reject oversized responses with an error telling the LLM to use a more specific query. Identical to what `max_answer_chars` does here.

### MCP Discussion #629 — Validated at Scale

GitHub's code-first MCP pattern [validated](https://github.com/orgs/modelcontextprotocol/discussions/629) with real production data:
> "Some API calls return 1000+ items. Solution: **truncation with explicit warning objects** — LLM can request filtered queries."

97.3% token reduction in simple queries. The explicit warning is what allows the LLM to adapt. Silent truncation (or no truncation) defeats the purpose.

---

## The Right Truncation Message

Truncation alone is not enough. The message must:
1. State **what was truncated and why** (hit the char limit)
2. **Guide the next step** — what parameter to change, what to narrow

Current PR message:
```
[Response truncated at {limit} chars. Use a more specific query or increase max_answer_chars.]
```

This could be improved to be tool-aware, e.g. for `ide_find_references`:
```
[Response truncated at 100,000 chars — 47 of 312 references returned.
 Try narrowing: add a maxResults limit, filter by file pattern, or increase max_answer_chars.]
```

This pattern — truncate + hint — is what GitHub, Serena, Neo4j, and mcp-agent all converge on.

---

## Context Bloat Is a Real Crisis in 2025

- [EclipseSource analysis](https://eclipsesource.com/blogs/2026/01/22/mcp-context-overload/): GitHub MCP alone (93 tools) consumes 55,000 tokens before the agent looks at code
- One developer's MCP setup consumed 66,000+ tokens of context before the first message
- Tool result bloat compounds tool definition bloat

Output size control is not a nice-to-have. It is a required safety mechanism for any MCP server used in AI coding workflows.

---

## Summary

| MCP Server | Size control | Approach |
|---|---|---|
| GitHub official | ✅ | `minimal_output` + `[truncated]` suffix |
| Serena | ✅ | `max_answer_chars` per tool + global default |
| Neo4j MCP | ✅ | Timeout + truncation + sanitization |
| mcp-agent | 🔄 Requested | Open issue, maintainer onboard |
| **JB plugin (pre-PR)** | ❌ | `maxResults` (count only, not size) |

`max_answer_chars` is not redundant with `maxResults`. It is the missing layer that every serious MCP implementation has converged on independently.

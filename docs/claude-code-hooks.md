# Claude Code Hooks — Enforcing IDE Tool Usage

Claude Code [hooks](https://docs.anthropic.com/en/docs/claude-code/hooks) run
shell commands before or after tool calls. These hooks enforce a simple rule:
**use IDE MCP tools for code operations, not bash fallbacks.**

Without enforcement, AI agents default to `grep`, `sed`, `Edit`, and `rm` for
code operations — tools that work on text, not code structure. They miss renamed
imports, break references, and bypass the IDE index. The hooks below redirect
agents to the semantically correct IDE tool at the point of use.

## Why hooks instead of prompt instructions

Prompt instructions ("prefer IDE tools") are suggestions. Agents comply when
convenient and fall back to bash when the IDE tool requires more effort. Hooks
are enforcement — the bash command or file edit is rejected with a message
naming the correct tool. The agent has no choice but to use it.

Hooks also survive across sessions and work for subagents, which don't inherit
skills or CLAUDE.md instructions.

## Installation

Add these hooks to your Claude Code settings. You can configure them via
`claude mcp` or by editing `~/.claude/settings.json` directly.

In `~/.claude/settings.json`, add to the `hooks` section:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "bash /path/to/intellij-first.sh"
          }
        ]
      },
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "bash /path/to/intellij-first-edit.sh"
          }
        ]
      }
    ]
  }
}
```

Save the hook scripts below to the paths you configured.

---

## Hook 1: Bash tool — redirect to IDE search and refactoring

**File:** `intellij-first.sh`

This hook intercepts bash commands that should use IDE tools instead: grep on
source files, sed replacements, mv/cp/rm on source files, and python bulk
replacement scripts.

```bash
#!/bin/bash
# PreToolUse hook for Bash: block commands that should use IntelliJ MCP.

CMD=$(cat | python3 -c "import json,sys; print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null)

# grep -r on source files → ide_search_text or ide_find_references
if echo "$CMD" | grep -qE 'grep\s+-[a-zA-Z]*r[a-zA-Z]*\s' && \
   echo "$CMD" | grep -qE '\*\.java|\*\.kt|\*\.ts|\*\.tsx|\*\.js|\*\.jsx|\*\.py|src/main|src/test'; then
  echo "BLOCK: Use ide_search_text or ide_find_references instead of grep on source files." >&2
  exit 2
fi

# mvn output parsing → ide_build_project + ide_diagnostics
if echo "$CMD" | grep -qE 'mvn\s+(compile|package|install|verify)' && \
   echo "$CMD" | grep -qE '\|\s*(grep|awk|sed)'; then
  echo "BLOCK: Use ide_build_project + ide_diagnostics instead of parsing mvn output." >&2
  exit 2
fi

# find + grep on source files → ide_search_text
if echo "$CMD" | grep -qE 'find\s' && \
   echo "$CMD" | grep -qE '\.java|\.kt|\.ts|\.tsx|\.js|\.jsx|\.py' && \
   echo "$CMD" | grep -qE 'grep|xargs.*grep'; then
  echo "BLOCK: Use ide_search_text instead of find+grep on source files." >&2
  exit 2
fi

# sed -i on source files → ide_refactor_rename or ide_replace_text_in_file
if echo "$CMD" | grep -qE 'sed\s+-[a-zA-Z]*i' && \
   echo "$CMD" | grep -qE '\.java|\.kt|\.ts|\.tsx|\.js|\.jsx|\.py'; then
  echo "BLOCK: Use ide_refactor_rename or ide_replace_text_in_file instead of sed on source files." >&2
  exit 2
fi

# python3 bulk replacement → ide_replace_text_in_file
# Exception: scripts in /tmp/ are development tooling
if echo "$CMD" | grep -qE 'python3\s' && \
   echo "$CMD" | grep -qE '\.java|\.kt|\.ts|\.tsx|\.js|\.jsx|\.py' && \
   echo "$CMD" | grep -qE 'replace|sub\(|re\.sub'; then
  if echo "$CMD" | grep -qE 'python3\s+/tmp/'; then
    exit 0
  fi
  echo "BLOCK: Use ide_replace_text_in_file instead of Python bulk replacement on source files." >&2
  exit 2
fi

# mv on source files → ide_move_file (exception: mv to /tmp/ is backup)
if echo "$CMD" | grep -qE '\bmv\s' && \
   echo "$CMD" | grep -qE '\.java|\.kt|\.ts|\.tsx|\.js|\.jsx|\.py'; then
  if echo "$CMD" | grep -qE '\s/tmp/'; then
    exit 0
  fi
  echo "BLOCK: Use ide_move_file instead of mv on source files." >&2
  exit 2
fi

# cp on source files → IDE refactoring
if echo "$CMD" | grep -qE '\bcp\s' && \
   echo "$CMD" | grep -qE '\.java|\.kt|\.ts|\.tsx|\.js|\.jsx|\.py'; then
  echo "BLOCK: Use IntelliJ refactoring instead of cp on source files." >&2
  exit 2
fi

# rm on source files → ide_refactor_safe_delete (warn, don't block — delete-to-recreate is valid)
if echo "$CMD" | grep -qE '\brm\s' && \
   echo "$CMD" | grep -qE '\.java|\.kt|\.ts|\.tsx|\.js|\.jsx|\.py'; then
  echo "WARN: Prefer ide_refactor_safe_delete over rm — rm doesn't check for references." >&2
  exit 0
fi

exit 0
```

### What it catches and what it redirects to

| Blocked pattern | IDE alternative |
|----------------|----------------|
| `grep -r "pattern" src/` | `ide_search_text` or `ide_find_references` |
| `find . -name "*.java" \| xargs grep` | `ide_search_text` |
| `sed -i 's/old/new/g' File.java` | `ide_replace_text_in_file` or `ide_refactor_rename` |
| `python3 script.py` (bulk replace) | `ide_replace_text_in_file` |
| `mv File.java newdir/` | `ide_move_file` |
| `cp File.java Copy.java` | IDE refactoring |
| `rm File.java` | `ide_refactor_safe_delete` (warns, doesn't block) |

### Exceptions

- `mv` to `/tmp/` is allowed (backup, not a refactoring)
- `rm` warns but doesn't block (delete-to-recreate is a valid pattern when a file has errors that prevent IDE tools from working)
- Python scripts in `/tmp/` are allowed (development tooling)

---

## Hook 2: Edit/Write tool — redirect to IDE structural editing

**File:** `intellij-first-edit.sh`

This hook intercepts Edit and Write tool calls on source files, redirecting to
the IDE's structural editing tools which maintain the index.

```bash
#!/bin/bash
# PreToolUse hook for Edit/Write: block modifications to source files.
# IntelliJ MCP owns structural editing. Exceptions:
# - Write to new files (fallback when ide_create_file unavailable)
# - Edit with replace_all (find-and-replace — covered by ide_replace_text_in_file
#   but allowed as fallback for sessions without the tool)

INPUT=$(cat)
TOOL=$(echo "$INPUT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('tool_name',''))" 2>/dev/null)
FILE=$(echo "$INPUT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('tool_input',{}).get('file_path',''))" 2>/dev/null)
REPLACE_ALL=$(echo "$INPUT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('tool_input',{}).get('replace_all',False))" 2>/dev/null)

if echo "$FILE" | grep -qE '\.(java|kt|ts|tsx|js|jsx|py)$'; then
  # Allow Write to new files (fallback when ide_create_file unavailable)
  if [ "$TOOL" = "Write" ] && [ ! -f "$FILE" ]; then
    exit 0
  fi
  # Allow Edit with replace_all (find-and-replace fallback)
  if [ "$TOOL" = "Edit" ] && [ "$REPLACE_ALL" = "True" ]; then
    exit 0
  fi
  echo "BLOCK: Use IntelliJ MCP tools instead of Edit/Write on source files. \
EXISTING files: ide_edit_member, ide_replace_member, ide_insert_member, ide_replace_text_in_file. \
NEW files: ide_create_file (preferred) or Write (fallback). \
REFACTOR: ide_refactor_rename, ide_move_file." >&2
  exit 2
fi

exit 0
```

### What it catches and what it redirects to

| Blocked pattern | IDE alternative |
|----------------|----------------|
| `Edit` on existing .java/.kt/.ts file | `ide_edit_member`, `ide_replace_member`, `ide_insert_member` |
| `Write` to existing source file | `ide_edit_member` or `ide_replace_member` |
| `Edit` for find-and-replace | `ide_replace_text_in_file` (or `Edit` with `replace_all: true` as fallback) |

### Exceptions

- `Write` to a file that doesn't exist yet → allowed (new file creation when `ide_create_file` isn't available)
- `Edit` with `replace_all: true` → allowed (find-and-replace within a file)

---

## How the hook enforcement message works

When a hook blocks a tool call, the agent sees the error message which names the
correct IDE tools. The agent then uses the named tool instead. This is more
effective than prompt instructions because:

1. **It fires at the point of use** — not at the start of the session when the agent is planning
2. **It names the exact alternative** — not a general guideline
3. **Subagents see it** — hooks fire for all agents, not just the main session
4. **It can't be ignored** — the blocked tool call fails; the agent must adapt

## Customization

Adjust the file extension patterns to match your project. The examples above
cover Java, Kotlin, TypeScript, JavaScript, and Python. Add or remove extensions
as needed:

```bash
# Add Go and Rust
grep -qE '\.(java|kt|ts|tsx|js|jsx|py|go|rs)$'
```

Remove patterns you don't need. If you don't use Maven, remove the `mvn` output
parsing check. If you don't have `ide_replace_text_in_file` (requires plugin
version with PR #238), the `Edit replace_all` fallback still works.

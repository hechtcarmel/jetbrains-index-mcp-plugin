# MCP Plugin Smoke Test Protocol

Run this after every `ide_install_plugin` + `ide_restart` cycle to verify the new
build works before committing or raising a PR.

---

## Meta-protocol

### How to call the server

Do NOT rely on the Claude session's MCP tool schema — it is captured at session start
and goes stale the moment the plugin restarts. Call the server directly via HTTP:

```bash
curl -s -X POST http://127.0.0.1:29170/index-mcp/streamable-http \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"<tool>","arguments":{...}}}'
```

Port 29170 is IntelliJ IDEA. Other IDEs: see `IdeProductInfo.kt` for the full map.

### The install-restart-verify cycle

1. `./gradlew buildPlugin` — produces `build/distributions/<name>-<version>.zip`
2. Call `ide_install_plugin` with `project_path` pointing to this project
3. Call `ide_restart` — waits for "Restarting IDE." response
4. Poll the server until it responds (typically 15–30s):
   ```bash
   curl -s --max-time 3 http://127.0.0.1:29170/index-mcp/streamable-http \
     -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
   ```
5. Confirm restart actually happened: `restarter.log` in `~/Library/Logs/JetBrains/IntelliJIdea*/`
   must have a new timestamped entry. If not, the restart was intercepted — see fallback below.
6. **Reinstall the companion skill** — click "Get Companion Skill" in the Index MCP Server
   tool window (bottom panel), or run:
   ```bash
   mkdir -p ~/.claude/skills/ide-index-mcp
   cp -r <plugin-project>/src/main/resources/skill/ide-index-mcp/. ~/.claude/skills/ide-index-mcp/
   ```
   The skill content changes with each plugin build. Without this step, AI assistants may
   use the wrong IntelliJ MCP server (`mcp__intellij__*` instead of `mcp__intellij-index__*`)
   because the routing guidance is stale or missing.
7. Run the tests below.

### Diagnosing "old code still running"

The new code is not loaded if error messages exactly match what was seen before the
install. Compare error text — even one word difference means the new code is running.
Check the installed jar timestamp:
```bash
ls -la ~/Library/Application\ Support/JetBrains/IntelliJIdea*/plugins/jetbrains-index-mcp-plugin/lib/*.jar
```

### Fallback when `ide_restart` doesn't fire

`app.restart(true)` is async. If `restarter.log` shows no new entry after 10s:
```bash
pkill -x "idea"; sleep 3; open "/Applications/IntelliJ IDEA.app"
```

### MCP availability guarantees

The lifecycle manager never closes the last open managed project — it stays in `dormant`
to keep MCP reachable. If all projects are somehow closed anyway (e.g., the user manually
closes the last window), calling any tool **without** `project_path` automatically reopens
a managed-closed project. MCP self-heals without user intervention.

If no managed projects were ever enrolled (nothing in `closedProjectPaths`), automatic
recovery cannot happen. In that case open a project manually:
```bash
open -a "IntelliJ IDEA" /path/to/any/project
```
Check the log file directly for events that occurred while all projects were closed:
```bash
cat ~/Library/Logs/JetBrains/IntelliJIdea*/mcp-lifecycle.log
```
File writes require debug logging to be enabled — see F6 below.

---

## Test cases

Use `project_path` on every call — multiple projects are typically open.

```bash
PP=/path/to/jetbrains-index-mcp-plugin   # adjust to actual path
```

---

### 1. Plugin alive — `ide_index_status`

- Call: `ide_index_status` with `project_path`
- PASS: response contains `isDumbMode` field (true or false)
- FAIL: error or no response

---

### 2. `ide_set_power_save_mode` — round trip

- Call with `enabled: true` → PASS: success, message contains "enabled"
- Call with `enabled: false` → PASS: success, message contains "disabled"
- Call with no `enabled` param → PASS: `isError: true`, message contains "enabled"
- FAIL on any: crash, hang, or generic error

---

### 3. `ide_open_project` — validation

- Call with `path: "/nonexistent/path"` and `project_path`
- PASS: `isError: true`, message contains "Failed to open" or the path
- FAIL: HTTP 500 with empty body (unhandled exception), or hangs

---

### 4. `ide_install_plugin` — error paths

**4a. Nonexistent zip:**
- Call with `path: "/nonexistent/plugin.zip"`, `project_path`
- PASS: `isError: true`, message contains "not found"

**4b. Wrong extension:**
- Call with `path: "/some/file.jar"`, `project_path`
- PASS: `isError: true`, message contains ".zip"
- FAIL: message says "not found" — means extension is checked AFTER existence

**4c. No build output:**
- Call with no `path`, `project_path` pointing to a project without `build/distributions/`
- PASS: `isError: true`, message contains "buildPlugin" or "No plugin zip"

---

### 5. `ide_install_plugin` — auto-detect from this project

- Call with no `path`, `project_path` pointing to this project (after `./gradlew buildPlugin`)
- PASS: `isError: false`, message contains "installed from" and a plugin ID
- FAIL (zip format): "Could not read plugin ID from META-INF/plugin.xml" — means
  `readPluginId` is searching the zip root but plugin.xml is inside `lib/<plugin>.jar`
  (Gradle Plugin 2.x format). Fix: update `readPluginId` to walk nested jars.
- FAIL (no zip): "No plugin zip found" — run `./gradlew buildPlugin` first

---

### 6. `ide_restart` — silent restart

- After install, call `ide_restart` with `project_path`
- PASS: response received with "Restarting IDE.", then `restarter.log` gains a new
  timestamped entry within 15s
- FAIL: response received but no `restarter.log` entry — likely a save-dialog prompt
  intercepted the restart. Fix: `saveAll()` before `restart(true)`, called via `edtAction {}`

---

## Fork-only tests

> These tests cover tools added in this fork and are not relevant to the upstream project.

### F1. `ide_get_project_modes`

- Call with `project_path`
- PASS: `isError: false`, response contains `managed_projects` array and `total` count

---

### F2. `ide_set_project_mode` — validation

- Call with `mode: "INVALID_MODE"` → PASS: error listing valid modes (active, background, dormant, closed)
- Call with no `mode` → PASS: error mentioning "mode"
- Call with `mode: "background"` → PASS: success
- Call with `mode: "active"` → PASS: success

---

### F3. `ide_set_all_project_modes` — validation and bulk

- Call with `mode: "closed"` → PASS: error — closed is not valid for this tool
- Call with `mode: "background"` → PASS: success, response reports count of projects changed
- Call with `mode: "active"` → PASS: restores active state

---

### F4. `ide_release_project` — idempotency

- Enroll project first: call `ide_set_project_mode` with `mode: "active"`
- Call `ide_release_project` → PASS: success, response contains project name
- Call `ide_release_project` again → PASS: success, response contains "not managed"
- FAIL on second call: response says "released" again — means `AbstractMcpTool.execute()`
  re-enrolled the project before `doExecute` checked managed state. Fix: set
  `participatesInLifecycle = false` on `ReleaseProjectTool`.

---

### F5. `ide_project_status` — combined view

- Call with `project_path`
- PASS: `isError: false`, response contains `projects` array and `summary` object
- PASS: the project we routed through appears with `"open": true`
- PASS: `summary` contains `open`, `managed`, `open_not_managed`, `managed_closed` keys
- FAIL: `isError: true` when only one project is open — this tool should work with a
  single open project and no `project_path` argument

**No-project-path variant (single project open):**
- Call with no `project_path`
- PASS: same structure as above; the one open project is selected automatically

---

### F6. `ide_lifecycle_log` — ring buffer and log file

**6a. Basic response structure:**
- Call with `project_path`, `limit: 5`
- PASS: `isError: false`, response contains `events` array, `log_file` string, `buffered` integer
- PASS: `log_file` path ends with `mcp-lifecycle.log`
- PASS: `buffered` ≥ 0 (may be 0 immediately after restart before any events)
- PASS: each event in `events` has `timestamp`, `project`, `path`, `event`, `trigger` fields

**6b. Path filter:**
- Call with `project: "nonexistent-xyz"` → PASS: `events` array is empty, not an error
- Call with `project: "<basename of project_path>"` → PASS: only events for that project

**6c. Limit:**
- Call with `limit: 1` → PASS: at most 1 event returned
- Call with `limit: 500` → PASS: up to 500 events, no error

**6d. File gating:**
- Before enabling debug logging, verify no new lines appear in the log file after MCP
  tool calls (the ring buffer fills but the file does not)
- Enable via Help → Diagnostic Tools → Debug Log Settings:
  add `#com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle`
- Make a tool call to generate a lifecycle event
- PASS: new line appears in the log file within seconds, no restart needed
- FAIL: no new line — check that the category was entered correctly (leading `#` required)

---

### F7. Lifecycle state machine — transitions visible in log

This verifies the full enroll → transition → log pipeline end-to-end.

1. Call any code intelligence tool (e.g. `ide_index_status`) with `project_path` to
   trigger auto-enroll if not already managed
2. Call `ide_set_project_mode` with `mode: "dormant"` and `project_path`
3. Call `ide_lifecycle_log` with `project_path`
4. PASS: events include a `transition` event with `"from": "background"` (or `"active"`),
   `"to": "dormant"`, `"trigger": "mcp_call"`
5. Call `ide_index_status` again with `project_path` (any tool call wakes a dormant project)
6. Call `ide_lifecycle_log` again
7. PASS: events include a `wake` event with `"from": "dormant"`, `"to": "background"`,
   `"trigger": "mcp_call"`

**Expected event sequence (newest first):**
```json
{ "event": "wake",       "from": "dormant",     "to": "background", "trigger": "mcp_call" }
{ "event": "transition", "from": "background",   "to": "dormant",    "trigger": "mcp_call" }
{ "event": "enroll",     "trigger": "mcp_call" }
```

---

### F8. Auto-open from CLOSED — end-to-end

This is the core lifecycle feature. It is the slowest test (5–15s for indexing).

1. Call `ide_set_project_mode` with `mode: "closed"` and `project_path`
   - PASS: success; the project window closes
2. Immediately call `ide_index_status` with `project_path` (the now-closed project)
   - The call should block while the project reopens and indexes
   - PASS (after 5–30s): `isError: false`, `isDumbMode: false`
   - FAIL: `isError: true` with `"no_project_open"` — means `resolveOrOpen` is not
     recognising the path as a managed-closed project. Check `wasClosedByUs` returns true
     and that `reopenAndAwaitSmartMode` completes without the HTTP timeout aborting it.
3. Call `ide_lifecycle_log` with `project_path`
4. PASS: events include `"event": "opened"`, `"trigger": "auto_open"` for the project
5. PASS: `ide_project_status` now shows that project as `"open": true`

**Timing note:** If step 2 times out (curl `--max-time` exceeded), the project may
still be opening in the background. Wait 10s and retry the call — it should succeed
immediately if indexing completed. The `NonCancellable` wrapper in `reopenAndAwaitSmartMode`
ensures the open completes even if the first HTTP call timed out.

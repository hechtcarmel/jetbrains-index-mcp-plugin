# Smoke Test

Run this sequence of MCP tool calls after installing a new build of the plugin to verify all tools load and respond correctly. These tests exercise the API surface without performing destructive operations — `ide_restart` is intentionally excluded since it terminates the MCP connection.

Reconnect Claude Code to the IDE after restart before running (`/mcp` → reconnect, or restart Claude Code).

---

## 1. Baseline — index ready

```
ide_index_status
```
**Expect:** `isDumbMode: false`. If true, wait and retry — indexing is still running.

---

## 2. Power Save Mode round-trip

```
ide_set_power_save_mode { "enabled": true }
ide_set_power_save_mode { "enabled": false }
```
**Expect:** Both return success. After the second call, Power Save Mode is off.

---

## 3. Project window management — error paths

```
ide_open_project { "path": "/nonexistent/path" }
```
**Expect:** Error response — project does not exist.

```
ide_close_project
```
**Expect:** Success — schedules close of the active project. Do not run this during active development; it closes the window.

---

## 4. Lifecycle management

```
ide_project_status
```
**Expect:** Success. Response contains `projects` array and `summary` object with open/managed/mode per project.

```
ide_get_project_modes
```
**Expect:** Success. Either "No projects managed" or a list of enrolled projects with their current mode.

```
ide_lifecycle_log { "limit": 10 }
```
**Expect:** Success. Response contains `events` array (may be empty immediately after restart), `log_file` path, and `buffered` count. Each event has `timestamp`, `project`, `path`, `event`, `trigger` fields.

```
ide_set_project_mode { "mode": "dormant" }
```
**Expect:** Success. Project transitions to DORMANT (editors closed, PSI cache freed).

```
ide_lifecycle_log { "limit": 5 }
```
**Expect:** Events include a `transition` entry with `"from": "background"`, `"to": "dormant"`, `"trigger": "mcp_call"`.

```
ide_set_all_project_modes { "mode": "background" }
```
**Expect:** Success. All managed open projects set to BACKGROUND.

```
ide_set_project_mode { "mode": "active" }
```
**Expect:** Success. Project returns to ACTIVE mode (Power Save OFF).

```
ide_release_project
```
**Expect:** Success. Project removed from lifecycle management.

---

## 5. Plugin installation — validation paths

```
ide_install_plugin { "path": "/nonexistent/plugin.zip" }
```
**Expect:** Error — "File not found".

```
ide_install_plugin { "path": "/some/file.jar" }
```
**Expect:** Error — must be a .zip file.

```
ide_install_plugin
```
Run from within a Gradle plugin project that has been built (`./gradlew buildPlugin`).
**Expect:** Success — finds `build/distributions/*.zip`, installs it, reports "restart required".

---

## 6. Self-installation loop (full end-to-end)

This is the primary workflow these tools are designed to enable:

```bash
# 1. Make a code change to the plugin
# 2. Build
./gradlew buildPlugin

# 3. Install via MCP (no path needed)
ide_install_plugin

# 4. Restart (connection drops — this is expected)
ide_restart

# 5. After IntelliJ restarts, reconnect Claude Code and re-run step 1 of this smoke test
```

All steps except the bash build are MCP calls. An AI coding agent can execute this loop without human intervention beyond the initial plugin install.

---

## 7. Monitoring — reading the lifecycle log for anomalies

Enable file output once (no restart needed):

> IntelliJ → Help → Diagnostic Tools → Debug Log Settings → add `#com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle`

Then watch the log:

```bash
tail -f ~/Library/Logs/JetBrains/IntelliJIdea*/mcp-lifecycle.log
```

Or query via MCP:

```
ide_lifecycle_log { "limit": 30 }
```

### Anomaly patterns to watch for

| Pattern in log | What it means |
|----------------|---------------|
| `timer:focus` waking a `dormant` project | Focus alarm fires after inactivity alarm — the two timers are racing. |
| Projects cycling `background→dormant→background` repeatedly | Inactivity and focus timers leapfrogging each other. |
| `auto_open` immediately followed by `timer:inactivity→dormant` | Project auto-opened but no MCP call followed. |
| A project showing `timer:close→closed` you didn't expect | The dormant-to-closed window may be too short for your workflow. |
| `last_project_kept` trigger in the log | The lifecycle manager would have closed a project but held it dormant because it was the last open managed project. Normal and expected. |
| No `auto_open` after routing an MCP call to a managed-closed project | `wasClosedByUs` check not matching — likely a path normalisation mismatch. |

---

## 8. MCP availability — verifying the last-project and auto-recovery guarantees

### 8a. Last-project stays dormant

1. Enroll exactly one project: `ide_set_project_mode { "mode": "background" }`
2. Wait for `timer:inactivity` → dormant, then `timer:close` fires
3. Check the lifecycle log — PASS: log shows `last_project_kept` trigger instead of `dormant→closed`
4. PASS: `ide_project_status` still returns `open: true` for the project in `dormant` mode

### 8b. Auto-recovery when all projects are manually closed

1. Manually close the last IntelliJ project window (File → Close Project)
2. Call any MCP tool **without** `project_path`:
   ```
   ide_index_status
   ```
3. PASS: tool succeeds — a managed-closed project was auto-opened to restore routing
4. FAIL: `no_project_open` error with no recovery — means no managed projects were in `closedProjectPaths`
   (this can happen if the user closed projects that were never enrolled)

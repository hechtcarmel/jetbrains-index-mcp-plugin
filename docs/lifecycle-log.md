# Lifecycle Event Log (`ide_lifecycle_log`)

Adds a new tool and backing service that records every lifecycle state change — mode
transitions, project opens/closes, focus events, timer firings, and MCP-triggered wakes
— into a queryable ring buffer. The goal is to make the lifecycle system observable:
when a project closes unexpectedly, or auto-open is slower than expected, or two timers
are racing, the log shows exactly what happened and why.

## What it adds

**`LifecycleEventLog`** — application-level service, always running. Holds up to N events
(default 500, configurable) in a `synchronized(ArrayDeque)`. Each entry has:
- `timestamp` — ISO-8601 UTC
- `project` / `path` — which project
- `event` — `transition`, `opened`, `closed`, `enroll`, `release`, `wake`, `focus_lost`
- `from` / `to` — mode names for transition and wake events
- `trigger` — why it happened: `timer:focus`, `timer:inactivity`, `timer:close`,
  `focus_gained`, `focus_lost`, `mcp_call`, `auto_open`, `user`

**`ide_lifecycle_log`** tool — returns recent events (newest first) from the ring buffer.
Parameters: `limit` (default 50, max = buffer size), `project` (path substring filter).
Response includes `log_file` (the path to the on-disk log) and `buffered` (current count).

**`ProjectLifecycleListener`** — `ProjectManagerListener` that logs open/close events for
projects _not_ managed by `ProjectModeService`. Managed projects log their own events
directly in `ProjectModeService`. Uses `projectClosing` (not deprecated) for close events;
open events are captured in `ProjectFocusActivity` which already runs for every project.

**Settings** — "Event log buffer size" spinner added to the Lifecycle section in
Settings → Index MCP Server (range 100–10,000, default 500). The buffer size is read on
each `log()` call so changes take effect without restart.

## Design: two-tier logging

The ring buffer is **always populated** — `ide_lifecycle_log` is useful without any
configuration. The on-disk file (`mcp-lifecycle.log` in IntelliJ's log directory,
alongside `idea.log`) is written **only when `LOG.isDebugEnabled` is true**.

This follows IntelliJ's standard pattern for optional verbose logging: users enable it
per-category via Help → Diagnostic Tools → Debug Log Settings by adding:

```
#com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle
```

No restart is required — `isDebugEnabled` is evaluated on each `log()` call. When debug
is off (production default), there is zero I/O overhead: the ring-buffer write is a
single lock acquisition and deque append.

The file is useful for the stuck case: if the lifecycle manager closes all managed
projects, MCP becomes unreachable (no project context to route through). The log file
can still be read directly — `cat mcp-lifecycle.log` — to understand what happened
without needing a live MCP connection.

## Trigger taxonomy

The `trigger` field on every event tells you _why_ something happened:

| Trigger | Meaning |
|---------|---------|
| `focus_gained` | User switched to this project window |
| `focus_lost` | User switched away; focus timer started |
| `timer:focus` | Focus timer expired; project moved to background |
| `timer:inactivity` | Inactivity timer expired; project moved to dormant |
| `timer:close` | Dormant timer expired; project closed |
| `mcp_call` | An MCP tool call triggered this (wake, enroll, explicit mode set) |
| `auto_open` | Lifecycle manager reopened a closed project for an MCP call |
| `user` | User action in the IDE (open/close outside lifecycle manager) |

## Instrumentation points

Every state change in `ProjectModeService` passes a `trigger: String` parameter to
`transition()`. Timer alarm lambdas pass their own trigger string
(`"timer:focus"` / `"timer:inactivity"` / `"timer:close"`). `ProjectFocusActivity`
passes `"focus_gained"`. All other direct calls default to `"mcp_call"`.

`markReopened()` only logs an `"opened"` event when the path was actually in
`closedProjectPaths` (i.e., we actually reopened something, not just called the method
redundantly during startup).

## API notes

None beyond what is noted in the lifecycle management PR. `LOG.isDebugEnabled` is the
standard IntelliJ `Logger` API; no platform internals are used here.

## Testing

- `LifecycleUnitTest` — tool name, schema shape (no required fields), description
  content, `lifecycleLogBufferSize` default and round-trip through `McpSettings`.
- `smoke-tests/mcp-protocol.md` F6 — ring buffer structure, path filter, limit,
  file-gating behaviour (verify no file writes before debug is enabled, then verify
  writes appear immediately after enabling, without restart).
- `smoke-tests/mcp-protocol.md` F7 — state machine end-to-end: enroll via MCP call,
  set dormant, wake via tool call, verify full event sequence in the log.

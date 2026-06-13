# Project Lifecycle Management

Adds automatic sleep/wake management for IntelliJ projects opened as MCP servers. When
multiple projects are open simultaneously as MCP servers — a common pattern when an AI
agent is working across a monorepo — idle projects consume unnecessary memory, keep Power
Save Mode toggled awkwardly, and leave editors open for no reason. This PR adds a
four-state machine that manages that automatically.

## What it adds

**Four lifecycle states per managed project:**

| State | Power Save | Editors | PSI cache | Auto-transition |
|-------|-----------|---------|-----------|-----------------|
| `active` | off | open | loaded | focus lost → background |
| `background` | on | open | loaded | 2 min idle → dormant |
| `dormant` | on | closed | dropped | 10 min idle → closed |
| `closed` | — | — | freed | next MCP call → background |

Projects enroll automatically on their first MCP tool call and are never enrolled
silently — the user sees a balloon notification and can release any project via
`ide_release_project`.

**New tools:**
- `ide_set_project_mode` — set a project's mode explicitly
- `ide_set_all_project_modes` — bulk-set all managed projects (active/background/dormant only — closed requires a live Project object)
- `ide_get_project_modes` — list all managed projects and their current modes
- `ide_project_status` — combined view: all open projects + all managed projects in one table
- `ide_release_project` — unenroll a project, restore full IDE behaviour

**Supporting tools added in the same batch:**
- `ide_set_power_save_mode` — toggle Power Save Mode directly (useful without lifecycle)
- `ide_close_project` — close a project window explicitly
- `ide_open_project` — open a project by path and block until indexing completes
- `ide_install_plugin` / `ide_restart` — plugin development workflow tools

**Settings** (Settings → Index MCP Server → Project Lifecycle Management):
- Master enable/disable toggle
- Configurable timing thresholds for each transition
- Live display of all managed projects and their current states

**IDE actions** (searchable via Cmd+Shift+A):
- "MCP: Open Project" — popup listing managed projects by state
- "MCP: Show Project States" — opens the settings panel

## Design

### MCP availability guarantee

The lifecycle manager never closes the last open managed project. When only one managed
project remains and its close timer fires, it is kept in `dormant` (editors closed,
memory mostly freed) rather than fully closed. This ensures the MCP HTTP server always
has a project to route requests through.

If all projects are nevertheless closed (the user manually closes the last window),
`resolveOrOpen` detects this: when called with `projectPath = null` and `openProjects`
is empty, it picks a project from `closedProjectPaths` and reopens it before routing
the call. This is a last-resort trampoline — subsequent calls with a specific
`project_path` will route to the right project via the existing auto-open path.

### Auto-open from CLOSED

`ProjectResolver.resolveOrOpen()` extends the existing `resolve()` with a recovery path:
when a project_path refers to a project we closed, it reopens it and waits for smart mode
before returning. This means every existing MCP tool transparently handles closed projects
with no changes required in the tool itself.

The open runs under `NonCancellable` so that an HTTP request timeout cannot abort a
half-opened project. Without this, a slow-to-index project would leave IntelliJ in a
state where the project appears "already opened" to subsequent calls but our lifecycle
registry still shows it as closed. The `wasClosedByUs` flag on `ProjectModeService`
tracks which projects we closed so we don't accidentally auto-open user-closed projects.

### Threading

All mode state changes in `ProjectModeService` are synchronous (plain `ConcurrentHashMap`
writes). Side effects (closing editors, dropping PSI caches, closing the project window,
toggling Power Save Mode) are deferred to `invokeLater` because they require the EDT.
This means tests can assert mode state immediately after `transition()` without async
machinery.

Alarms use `Alarm.ThreadToUse.POOLED_THREAD` and are owned by `ProjectModeService`
(which implements `Disposable`) so they are cleaned up correctly on plugin unload.

### `participatesInLifecycle = false`

Several tools set `participatesInLifecycle = false` on `AbstractMcpTool`:

- **`ide_open_project` and `ide_close_project`** — infrastructure tools. Opening a project
  to inspect it should not commit it to lifecycle management; enrollment signals intent to
  do real work. Enrollment happens on the first semantic tool call (find references,
  diagnostics, refactoring) after the project is open.
- **`ProjectStatusTool`, `ReleaseProjectTool`, `LifecycleLogTool`, `GetProjectModesTool`** —
  observer tools. Calling these to check lifecycle state should not auto-enroll the project
  or reset its inactivity timer, as that would contradict their purpose.

## API notes

**`OpenProjectTask` reflection** — `forceOpenInNewFrame` and `showWelcomeScreen` are
constructor parameters marked `@Internal` in the IntelliJ SDK. We set them via
reflection rather than the constructor to avoid binary incompatibility when JetBrains
changes the parameter list across versions. If auto-open breaks after an IntelliJ
upgrade, check `openTask()` in `ProjectResolver` first.

**`projectClosing` vs `projectClosed`** — `ProjectManagerListener.projectClosed` is
deprecated in 2025.x; we use `projectClosing` instead. `projectOpened` is also
deprecated; open events are captured via `ProjectFocusActivity` (a `ProjectActivity`
that runs for every project on open) to avoid the deprecated API entirely.

## Testing

- `LifecycleUnitTest` (extends `TestCase`) — schema validation, enum completeness,
  settings defaults, tool name constants. Runs in milliseconds, no platform required.
- `ProjectModeServiceTest` (extends `BasePlatformTestCase`) — state machine behaviour:
  transitions, alarm scheduling, persistence across `loadState`.
- `LifecycleToolsTest` (extends `BasePlatformTestCase`) — tool execution: mode setting,
  validation errors, auto-enroll, wake-from-dormant, bulk operations.
- `smoke-tests/mcp-protocol.md` — HTTP-level protocol tests covering the install-restart
  cycle and every new tool including the auto-open path (F8).

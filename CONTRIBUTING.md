# Contributing to IDE Index MCP Server

This document is the single source of truth for contributors — human or AI.
Follow every rule here before opening a pull request.

---

## Quick start

### Local feedback loop (run these yourself)

```bash
./gradlew test --tests "*UnitTest*"   # fast unit tests, no IDE needed (< 30 s)
./gradlew runIde                       # launch sandboxed IDE with plugin installed
./scripts/check-pr.sh                  # pre-push validation — run before every push
```

### CI / maintainer-only (do not run locally unless explicitly asked)

```bash
./gradlew test               # includes platform tests — times out on headless machines
./gradlew build              # depends on ./gradlew test, same issue
./gradlew runPluginVerifier  # Marketplace compatibility check — run in CI
```

`./gradlew test` registers `TestFrameworkType.Platform` via `build.gradle.kts`, which means
it includes platform tests that require a full IntelliJ Platform environment. Running it
locally hangs on headless machines. Stick to `--tests "*UnitTest*"` for local iteration.

---

## PR rules

### CHANGELOG.md

- **DO** add an entry under `## [Unreleased]` for every user-visible change.
  Use sections: `Added`, `Changed`, `Fixed`, `Removed`, `Breaking`.
- **DO NOT** add release version entries (`## [x.y.z]`) — the maintainer creates those at merge time.
- **DO NOT** re-list tools that already shipped in a previous release. After a rebase,
  check that already-released tool entries have not bled back into `[Unreleased]`.

### Files that must NOT appear in PRs

| File | Reason |
|------|--------|
| `.idea/gradle.xml` | Contains local JDK name (`gradleJvm`); breaks on other machines |
| `scripts/build-install.sh` | Local build helper; not for upstream |
| `docs/pr-*.md` | Rename to drop the `pr-` prefix before submitting |

### Version bumps

Do **not** change `pluginVersion` in `gradle.properties` unless explicitly requested.
When requested, follow [SemVer](https://semver.org):

| Change type | Version part |
|-------------|-------------|
| Bug fix / internal refactor | Patch (`x.y.Z`) |
| New tool / new feature | Minor (`x.Y.0`) |
| Breaking schema / transport change | Major (`X.0.0`) |

---

## Adding a new tool — complete checklist

Every item is required. CI will catch missing registrations and test count mismatches.

### Implementation

- [ ] Extend `AbstractMcpTool`, implement `doExecute()` (never `execute()`)
- [ ] Set `override val requiresPsiSync = false` unless the tool reads PSI indexes
- [ ] Set `override val participatesInLifecycle = false` for infrastructure / observer tools
  (tools that manage lifecycle state, or bulk-operate across all projects)
- [ ] Use `SchemaBuilder` for `inputSchema` — never construct `JsonObject` manually
- [ ] Add `project_path` via `.projectPath()` on the builder for any multi-project tool

### Registration

- [ ] Add constant to `ToolNames.kt` in the correct group
- [ ] Add constant to `ToolNames.ALL` in **strict alphabetical order by full string value**
  (compare the complete `ide_*` string character by character:
  `ide_release_all_projects` < `ide_release_project` < `ide_restart` because `all` < `project` < `start`
  lexicographically at position 12). A sort test in `ConstantsUnitTest` will fail if the order is wrong.
- [ ] Add tool name string to `McpSettings.State.disabledTools` — all new tools are opt-in
- [ ] Register in `ToolRegistry.registerUniversalTools()` (or the appropriate method)

### Documentation

- [ ] Add a row to the **`README.md` universal tools table** and update the tool count in the
  "The plugin provides **N MCP tools**" sentence
- [ ] Add a section to `USAGE.md` with a parameters table and a request/response example
- [ ] Add one-line entry to the tool inventory in `CLAUDE.md`
- [ ] Add tool name to `SKILL.md` trigger list (frontmatter description) and disabled-tools section,
  both **in alphabetical order**
- [ ] Add the tool to `src/main/resources/skill/ide-index-mcp/references/tools-reference.md`
  (the bundled detailed parameter/return reference linked from SKILL.md)

**Quick consistency check** — after adding the tool, grep its name across all six locations and
confirm each has an entry: `README.md`, `USAGE.md`, `CLAUDE.md`, `SKILL.md`,
`tools-reference.md`, `ToolNames.kt` / `McpSettings.kt` / `ToolRegistry.kt`, and tests.

### Tests

- [ ] Add unit tests in the appropriate `*UnitTest.kt` file:
  - tool name matches the `ToolNames` constant
  - required fields are present / absent as expected
  - tool appears in `McpSettings.State.disabledTools`
- [ ] Update `ConstantsUnitTest.testToolNamesAllContainsEveryConstant` — add the new constant
  and verify `ToolNames.ALL.size` still matches
- [ ] Update `ToolExecutionIntegrationTest.testAllToolsRegistered` — add the new constant
  in the same alphabetical position as in `ToolNames.ALL`
- [ ] For opt-in features with a toggle (e.g. `lifecycleEnabled`): tests that exercise
  opt-in behaviour must enable the flag in `setUp()` and restore it in `tearDown()`

### Test quality rules

- Assertions must **actually fail** if the implementation is deleted (no vacuous tests)
- Do not simulate the system under test with a private helper and then assert on the helper
- Do not leave placeholder tests with comments referencing non-existent code
- `McpServerWatchdogTest`-style tests (simulate stop-handler locally, assert on local simulation)
  should be deleted; the real integration test (`KtorMcpServerWatchdogTest`) is the one that counts

---

## API compliance — the plugin verifier enforces these; CI fails if violated

### Internal APIs

- **NEVER** use `@ApiStatus.Internal` / `@Internal` classes or methods, even via reflection.
  `getDeclaredField` on an internal class still references it and is flagged.
- Use the public builder API: `OpenProjectTask.build().withForceOpenInNewFrame(true)`
  not `getDeclaredField("forceOpenInNewFrame")`.
- Cast-guarded access (`as? SomeInternalClass`) is still an internal reference.

### Deprecated APIs

- Use `ModalityState.nonModal()` — **not** `ModalityState.NON_MODAL` (deprecated field)
- Use `ModalityState.any()` — **not** `ModalityState.ANY` (deprecated field)

### Service registration

- `@Service(Service.Level.APP)` on the class **and** `<applicationService>` in `plugin.xml`
  is a duplicate — use one or the other, not both. The annotation alone is sufficient for
  light services.
- `@Storage` for services that persist machine-specific paths (absolute filesystem paths,
  project root paths) **must** include `roamingType = RoamingType.DISABLED`.
  Without it, Settings Sync will copy local paths to other machines.

---

## Threading — IntelliJ's threading model

### The two locks

- **Read lock** — required to access PSI. Acquire with `ReadAction.compute { }` or
  `readAction { }`. Any background thread can acquire a read lock.
- **Write lock** — required to modify PSI. Acquire with `WriteCommandAction.runWriteCommandAction`.
  Must run on the EDT.

### EDT rules

| Operation | Thread | How |
|-----------|--------|-----|
| PSI read (search, navigate) | Any background thread | `ReadAction.compute { }` |
| PSI write (rename, reformat) | EDT | `WriteCommandAction.runWriteCommandAction` |
| UI updates, `DumbService.runWhenSmart` | EDT | `invokeLater` / `edtAction { }` |
| `PowerSaveMode.setEnabled()` | EDT | `edtAction { }` |
| `PowerSaveMode.isEnabled()` | Any | direct call |

### Modal dialog safety

`ModalityState.nonModal()` is the **safe default** for PSI, VFS, project-model, and
write-action work. The IntelliJ SDK modality system exists precisely to prevent these
operations from running while a modal dialog is waiting for user input — using
`ModalityState.any()` here risks corrupting state the dialog depends on.

`ModalityState.any()` is appropriate **only** for pure UI/status notifications where running
during a modal is intentional and no PSI/VFS/project-model mutation occurs.

If `nonModal()` blocks a long-running MCP wait (e.g. `DumbService.runWhenSmart` hanging
because a modal dialog is open), the correct fix is a **timeout**, not a modality switch:

```kotlin
withTimeoutOrNull(120_000L) {
    suspendCancellableCoroutine { continuation ->
        ApplicationManager.getApplication().invokeLater({
            DumbService.getInstance(project).runWhenSmart { continuation.resume(Unit) }
        }, ModalityState.nonModal())
    }
}
```

If you need a modal-tolerant EDT helper for a specific case, create a distinctly named
function (e.g. `uiNotifyAction { }`) and document explicitly that it must not mutate
PSI, VFS, or the project model. Do not change the shared `edtAction { }` helper.

### Concurrent collections

Sets mutated from multiple threads (Ktor coroutines, Alarm callbacks, EDT) must use
`ConcurrentHashMap.newKeySet()`, not `mutableSetOf()` (which is `LinkedHashSet` — not thread-safe).

### Alarm lifecycle

`Alarm.cancelAllRequests()` stops pending callbacks but does **not** free the `Alarm` object.
On permanent project release or close, call `Disposer.dispose(alarm)` and remove it from any
map it lives in. Abandoned `Alarm` objects accumulate silently.

---

## Error messages — make them actionable

Every error a tool returns should tell the caller exactly what to do next.

| Situation | Required guidance in error message |
|-----------|----------------------------------|
| IDE in dumb mode (indexing) | DO NOT fall back to bash/grep; call `ide_index_status` until `isDumbMode` is false; retry the same call |
| Outdated stub in index (PSI cache stale) | Call `ide_sync_files`; retry |
| Build system not linked in IDE | Explain how to link it (Maven/Gradle tool window → Import) |
| Project not open | What to do to open it |

Never return a raw Java stack trace as the error message. Catch known exception types
and convert them to user-facing messages.

---

## Memory — avoid accumulation

- **History result strings**: tool responses stored in `CommandHistoryService` must be
  truncated before storage (current cap: 4 KB). `ide_find_references` on a popular class
  can return 100 KB+; at 100 entries × 10 projects this becomes significant.
- **Alarm objects**: dispose AND remove from maps on permanent release — do not only cancel.
- **PSI references**: do not store `PsiElement`, `PsiFile`, or `VirtualFile` references
  in long-lived services. They become invalid when projects close and prevent GC.

---

## Detecting linked build systems

When a tool needs to know whether a project uses Maven or Gradle, check whether the
build system is **actually linked in IntelliJ** — not just whether a build file exists on disk.

```kotlin
// Correct: checks IntelliJ's project model
val linked = ProjectDataManager.getInstance().getExternalProjectsData(project, systemId)
val isLinked = linked.isNotEmpty()

// Wrong: only checks filesystem
val hasPom = File(project.basePath ?: "", "pom.xml").exists()
```

If a build file exists but the project is not linked, tell the user how to link it rather
than claiming success.

---

## Smoke test protocol

After any `./gradlew buildPlugin` → install → restart cycle, run the smoke test at
`docs/smoke-test-protocol.md` when changes touch:

- HTTP transport (`KtorMcpServer.kt`, `JsonRpcHandler.kt`)
- Tool registration (`ToolRegistry.kt`, `McpServerService.kt`)
- Any tool covered by the protocol

Skip for documentation-only, test-only, or version-bump changes.

---

## Project structure quick reference

```
src/main/kotlin/.../
├── constants/          ToolNames.kt — all tool name constants + ALL list
├── history/            Per-project command history (bounded ring buffer)
├── lifecycle/          ProjectModeService, LifecycleEventLog, focus tracking
├── server/             JsonRpcHandler, ProjectResolver, McpServerService, Ktor transport
├── settings/           McpSettings (app-level persisted state)
├── tools/
│   ├── AbstractMcpTool.kt   — extend this, implement doExecute()
│   ├── ToolRegistry.kt      — register new tools here
│   ├── schema/SchemaBuilder.kt  — always use for inputSchema
│   ├── editor/         ide_get_active_file, ide_open_file
│   ├── intelligence/   ide_diagnostics
│   ├── lifecycle/      lifecycle management tools
│   ├── navigation/     ide_find_*, ide_search_text, ide_*_hierarchy
│   ├── project/        ide_index_status, ide_sync_files, ide_build_project
│   └── refactoring/    ide_refactor_*, ide_reformat_code
└── util/               Threading helpers, PSI utilities
```

---

## Adding dependencies

1. Add version to `gradle/libs.versions.toml`
2. Reference in `build.gradle.kts` via `libs.<name>`
3. If the dependency conflicts with IntelliJ's bundled coroutines or slf4j,
   add `exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")`
   (see existing Ktor entries for the pattern)

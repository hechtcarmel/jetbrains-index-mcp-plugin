<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Index MCP Server Changelog

## [Unreleased]

### Fixed

- **`ide_create_file`** — `project_path` outside every known project root or content root now returns a clear error instead of silently falling through to `basePath` and creating the file in the wrong location. Traversal paths (`../..`) are also rejected.

## [5.2.0] - 2026-07-31

### Added

- **`ide_create_module`** — add a directory as an IntelliJ module with a content root, enabling code intelligence for non-Maven projects (TypeScript, plain directories, etc.). Supports optional directory exclusions (e.g., `node_modules`, `dist`). For Maven projects, use `ide_import_modules` instead. *(disabled by default)*

## [5.1.0] - 2026-07-30

### Fixed

- **Write tools now reject read-only files with a clear error** instead of failing deep inside the refactoring engine. Affected tools: `ide_replace_text_in_file`, `ide_edit_member`, `ide_insert_member`, `ide_replace_member`, `ide_move_file`, `ide_change_signature`, `ide_reformat_code`, `ide_optimize_imports`, `ide_refactor_rename` (both symbol and file modes), and `ide_refactor_safe_delete` (both symbol and file modes).
- **`ide_open_project`** — pre-trusts the target directory before opening, preventing the modal "Trust and Open Project?" dialog from freezing headless MCP sessions. The project is trusted per-path, not blanket.
- **The settings page now appears under Settings → Tools → Index MCP Server**, where all documentation always said it was — it was previously registered as a top-level settings entry.
- **Documentation corrections across README, USAGE, CLAUDE.md and the bundled skill**: the license is MIT (README claimed Apache 2.0), the minimum IDE version is 2025.3 (docs claimed 2025.1), lifecycle management is documented as opt-in (its master toggle defaults to off), stale tool counts/parameters were reconciled with the real tool schemas, and the pre-5.0 custom JSON-RPC error-code tables were replaced with the current `isError: true` behavior.
- **MCP server lifecycle hardening.** Concurrent restarts (settings apply racing the watchdog) could orphan a bound Ktor engine, permanently occupying its port for the IDE session — server start/stop is now atomic. A failed start (e.g. port still in use at IDE startup) disarmed the watchdog and never retried, leaving the server down until an IDE restart — non-success starts now stop the failed instance and re-arm the watchdog, engine-side bind cancellations no longer kill the startup coroutine silently, and repeated failures no longer spam duplicate error balloons.
- **Changing the server port in Settings no longer freezes the IDE UI** while the old server drains in-flight MCP calls — the restart runs off the EDT, with the result reported via notification.
- **`project_path` pointing inside a workspace sub-project's content root** (e.g. a source directory under an `ide_open_workspace` module) now resolves to the open workspace instead of returning `project_not_found` with misleading advice.
- **`ide_find_class` and `ide_find_file` no longer swallow cancellation or mid-search dumb-mode transitions** — they return the standard "IDE is indexing, retry" error instead of silently reporting a truncated result set as complete. The same fix applies to the Java hierarchy/implementations handlers (`ide_type_hierarchy`, `ide_find_implementations`, `ide_call_hierarchy`, `ide_find_super_methods`), and to FQN class lookup (`ide_read_file` `qualifiedName`, `ide_run_tests`), which previously misreported "Class not found" while indexing.
- **`ide_search_text` with `context: "code"` no longer drops matches inside numeric, char, or boolean literals**, and `contextType` now labels non-string literals as `CODE` instead of `STRING_LITERAL`.
- **`ide_find_definition` default preview now includes the file's last line** — definitions on the last line (including one-line files) previously produced an empty or truncated preview.
- **`ide_edit_member`/`ide_insert_member`/`ide_replace_member` no longer crash with a threading assertion** after applying the edit when called over HTTP — the crash left the change unsaved on disk and prompted duplicate-insert retries.
- **`ide_insert_member` with `position: "first"` at Kotlin file scope** now inserts after the package declaration and imports instead of corrupting the file by inserting before them.
- **`ide_refactor_rename` no longer reports success when the platform silently aborts the rename** (conflicts, read-only files). Conflicts are auto-resolved headlessly and surfaced as warnings; a provably unapplied rename returns an error.
- **`ide_refactor_safe_delete` no longer counts references inside the deleted symbol itself** (recursive calls, the class's own factory methods) as blocking usages requiring `force: true`.
- **`ide_replace_text_in_file`'s identity pre-flight compares what is actually written** — it unescapes `replaceText` before comparing to `searchText`, so replacements that differ only by escaping are no longer rejected as identical. The `\n`/`\t`/`\\` unescaping convention is now documented in the tool schema and docs.
- **Kotlin call sites and type references resolve to the referenced declaration** in `ide_call_hierarchy`, `ide_find_implementations`, `ide_find_super_methods`, and `ide_type_hierarchy` — previously the cursor on a Kotlin call resolved to the enclosing function or class instead of the callee.
- **`ide_type_hierarchy` on a Java interface no longer lists extended superinterfaces twice**, and on a Rust struct/enum no longer reports traits from unrelated `impl` blocks that merely mention the type in their bodies.
- **`ide_diagnostics` test results now include file/line locations** — location lookup ran without a read action on the MCP server thread and silently returned null in production.
- **Releasing the last lifecycle-managed project (`ide_release_project`) now disables Power Save Mode** instead of leaving it permanently enabled.
- **A vetoed project close is no longer recorded as closed** — lifecycle state now follows the actual close outcome, and vetoed closes retry via the pending-close queue.
- **`ide_close_project` re-checks the last-open-project guard on the EDT at close time**, so concurrent close requests can no longer leave the IDE with zero open projects and an unreachable MCP server.
- **The MCP tool window panel is disposed with its content**, releasing the application-level server-status and command-history listeners (previously leaked the Project after closing it), and its Refresh button actually refreshes again (was a silent no-op).
- **Settings can be applied while the configured port is occupied by another process** as long as host/port are unchanged, and applying with Enter while the Server Host field has focus no longer fails with a perpetual "Validating server host" error.

### Security

- **`ide_install_plugin` rejects archives with zip-slip entries** (`../` traversal or absolute paths) that would write outside the IDE plugins directory. The whole archive is validated before the existing installation is removed, so a rejected archive leaves the current plugin intact, and extraction independently re-checks every normalized destination path.
- **`ide_read_file` no longer reads local files outside the project.** Plain filesystem paths (including `~`-expanded ones) are now checked against the project's content roots and registered libraries; jar/library-source reads are unaffected.
- **The local server's trust model is now documented** in the README (and therefore the Marketplace description) and in `SECURITY.md`: the server binds to `127.0.0.1` with no authentication, so any process on the machine can call its tools with the IDE user's file access.

## [5.0.1] - 2026-07-27

### Added

- **`ide_find_references` echoes the resolved symbol.** Positions on comments or whitespace silently snap to the nearest enclosing named element; the new optional `resolvedSymbol` field (name, kind, container, file, line) lets clients verify which declaration was actually searched. A new `totalIsExact` field distinguishes an exact `totalCount` from a lower bound when collection hit the internal cap.
- **`ide_replace_text_in_file` returns `affectedLines`** — the 1-based line numbers touched by replacements (capped at 100), which its description had always promised.

### Fixed

- **`ide_create_file` no longer triggers the IDE's "Add File to Git" confirmation.** Files were created through direct VFS calls, which the VCS listener processes under the "When files are created" setting — an app-modal dialog when set to Ask (freezing the EDT and hanging every in-flight MCP call until someone clicks it), or a silent `git add` when set to Add silently (the staging surprise from the field report). Files are now written to disk and imported via a synchronous VFS refresh, which the VCS listener ignores by design — still indexed in the same call, but never prompted for and never auto-staged. The tool description now states this; run `git add` yourself when you want the file tracked.
- **`ide_find_file` no longer silently drops results on broad queries.** Candidate-name enumeration was capped at 1500 names over a scope-blind index stream (the filename index enumerates library and JDK keys too), so queries like `Dao`, `*.java` or `*.properties` filled the window with out-of-scope names and returned a fraction of the real matches — with `hasMore: false` claiming completeness. Enumeration is now uncapped (matching the IDE's own Goto File), candidates are resolved best-match-first, and cancellation is no longer swallowed as a silently-truncated result.
- **`ide_find_symbol` no longer drops override implementations that share a name with their super method.** IntelliJ's Go to Symbol popup suppresses a method when any super signature matches the same unqualified query; the search now complements those suppressed methods via `PsiShortNamesCache`, so querying `getAllPublishers` returns both `TRCDao.getAllPublishers` and `TRCDaoImpl.getAllPublishers`.
- **`ide_diagnostics` no longer reports a false clean bill on open files when the highlighting daemon is inactive.** With Power Save Mode on (or the daemon otherwise idle), the open-editor path returned an empty highlight set stamped `analysisFresh: true, problemCount: 0` for files with real compile errors. Power Save Mode now routes open files to batch analysis directly, and an empty daemon result that cannot be proven to come from a completed pass falls back to batch analysis, with distinct `analysisMessage` texts for "daemon did not run" vs "timed out".
- **`ide_refactor_rename` with `targetType: "file"` no longer corrupts Java sources.** Renaming `Foo.java` renamed the file but not the public class inside, producing guaranteed-uncompilable code reported as `success: true`. When the file's top-level class name matches the filename, the rename is now retargeted to the class (which renames the file and updates all references); non-matching and non-Java files keep plain file rename.
- **`ide_find_class` reports real `kind` values.** Interfaces, enums, annotations and records all reported `CLASS` because kind was inferred from the PSI implementation class name (`PsiClassImpl` for every Java declaration form). Kind is now determined by semantic probes (`isInterface`/`isEnum`/`isAnnotationType`/`isRecord` via reflection, working for Java, Kotlin, PHP and JS/TS), with the name heuristic as fallback.
- **`ide_project_status` and `ide_get_project_modes` now agree on what "managed" means.** `ide_project_status` zeroed the managed list whenever lifecycle automation was globally disabled, while `ide_get_project_modes` read the persisted enrollment registry — the same project could report `managed: 0` and 10 managed projects simultaneously. `ide_project_status` now reports persisted enrollment unconditionally, plus a `lifecycle_enabled` flag and an explanatory note when automation is off.
- **`ide_find_implementations` no longer silently truncates at 100 results.** Every language handler capped implementations at 100 with `hasMore: false` presented as completeness; the caps now match the pagination cache bound (5000) so the "cache holds the complete set" invariant actually holds.
- **`ide_refactor_safe_delete` no longer misreports outcomes.** A stale PSI element detected inside the write action silently skipped the delete but still reported "Successfully deleted"; that is now an explicit retry error. A failed usage search (index churn, stale stubs) was swallowed and treated as "no usages", letting the delete proceed as safe; the tool now refuses with an explicit error (dumb-mode/cancellation propagate to the standard retry handling).
- **`ide_change_signature` no longer reports success on a silently aborted refactoring** (read-only file, unwritable elements). A post-run check verifies at least one requested aspect actually applied before reporting success.
- **`ide_move_file` surfaces move conflicts as `warnings`** instead of discarding them — a forced headless move (e.g. a package-private class moved away from its users) previously reported clean success.
- **`ide_insert_member` re-validates its insertion point inside the write action** — a document modified between preparation and write previously threw a raw out-of-bounds error or silently inserted at the wrong position.
- **`ide_read_file` rejects `startLine` beyond end-of-file with an error naming the real line count**, and echoes the effective (clamped) `endLine` instead of the requested one, so an overhanging range no longer masquerades as fulfilled.
- **Refactoring conflict messages no longer leak IDE-dialog HTML.** Rename conflict errors carried raw `<b><code>…</code></b>` markup and XML entities from the IDE's conflicts dialog; messages are now sanitized to plain text.
- The bundled skill doc described `ide_search_text` as "exact word occurrences"; it is a substring search with optional regex, and the doc now says so.

## [5.0.0] - 2026-07-26

### Breaking

- **MCP protocol handling now uses the official [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)** instead of a hand-written JSON-RPC/SSE implementation. All 50 tools, their names, input schemas and response payloads are unchanged — the golden `tool-manifest.json` and `result-shapes.txt` snapshots are byte-identical across the migration. Four client-visible behaviours did change:
  - **`Accept: application/json, text/event-stream` is now required** on `POST /index-mcp/streamable-http`, per the Streamable HTTP spec. `Accept: application/json` alone returns `406 Not Acceptable`, and a non-JSON `Content-Type` returns `415`. Every real MCP client (Claude Code, Cursor, the official SDKs) already sends both; only hand-written `curl` needs updating.
  - **Tool failures are reported as `isError: true` results instead of JSON-RPC errors.** Dumb-mode (`-32001`), unknown tool (`-32601`) and disabled tool (`-32602`) previously came back as protocol errors, which clients surface as hard transport failures. The MCP spec puts tool-execution errors in the result so the model can read and act on them — which is what messages like "call `ide_index_status` until indexing finishes" were written for. The message text is unchanged.
  - **`initialize` requires spec-complete params.** `capabilities` is mandatory per the MCP schema and is now validated.
  - **`serverInfo.description` moved to `instructions`.** MCP's `Implementation` object has no `description` field; `instructions` is the spec's slot for "how to use this server" text, and clients feed it to the model.
- **`initialize` negotiates the protocol version with the client** across `2024-11-05`, `2025-03-26`, `2025-06-18` and `2025-11-25`, rather than answering with a fixed version per endpoint. A client asking for `2024-11-05` still gets `2024-11-05`.

### Added

- **Host header validation** on all MCP endpoints, alongside the existing Origin check — DNS-rebinding protection. Applies only when the server is bound to loopback, which is where that attack lives: it tricks a browser into reaching a server on the user's own machine through an attacker-controlled name. The port is ignored. A server deliberately bound to `0.0.0.0` or a LAN address is reached under whatever name or IP routes to it, so no allow-list is enforced there and such setups keep working exactly as before.

### Changed

- Malformed JSON-RPC batches are processed per message rather than rejected wholesale, and a `DELETE` on the Streamable HTTP endpoint no longer sends an `Allow` header with its `405`.
- The plugin no longer bundles `kotlin-stdlib`, `kotlinx-coroutines`, `kotlin-reflect` or a second copy of Ktor. It previously shipped **two major Ktor versions** (2.3.12 in use, plus a dead 3.0.2 tree pulled in by an MCP SDK dependency that no source file imported), along with artifacts the IntelliJ Platform forbids plugins from bundling.

### Fixed

- **`initialize` reported a stale server version.** `serverInfo.version` was hardcoded to `4.10.4` while the plugin shipped 4.31.x; the build now stamps the real version into a resource the plugin reads at runtime. (Reading it off the plugin descriptor is not an option — every platform API that exposes it is `@ApiStatus.Internal` as of 2026.2, which the plugin verifier fails the build on.)
- **`ide_structural_search_replace`** — replace mode failed with `Must not change PSI outside command or undo-transparent action` on every invocation that matched at least one element in a project file, applying no edits. `Replacer.replaceAll` opens its own write action but no command, which `PomModelImpl` requires for changes to physical files; replacements are now wrapped in a command, matching the IDE's own Replace All. Search-only mode was unaffected.
- **`ide_find_definition`, `ide_find_references`, `ide_call_hierarchy`, `ide_find_implementations`, `ide_find_super_methods`** — symbol mode (`language` + `symbol`) returned `not_found` for `module#default` when the file used `export default function f() {}` or `export default class C {}`, the two most common default-export forms. Default-export detection probed a non-existent `isDefaultExport()` accessor; it now uses `isExportedWithDefault()`, which is where the modifier actually lives when there is no `ES6ExportDefault*` wrapper node. Affects both JavaScript and TypeScript.
- **TypeScript overload resolution** — TypeScript functions were never recognised as function-like, because detection matched on the class name containing `JSFunction` and `TypeScriptFunctionImpl` does not. As a result, resolving an overloaded exported TypeScript function by symbol returned `ambiguous_match`, and `ide_call_hierarchy` seeded from an overload signature reported the empty declaration (no callees, under-reported callers) instead of normalising to the implementation. Detection now tests against the `JSFunction` interface.

## [4.31.0] - 2026-07-25

### Changed

- **`ide_search_text`** — plain-text queries now use `FindInProjectUtil` (same engine as IDE's Find in Files), enabling true substring matching. Previously, whole-word token matching caused queries like `"a_word"` to miss results in identifiers like `"a_word_and_another_word"`. A new `wholeWord` boolean parameter (default `false`) restores whole-word matching when needed.

### Fixed

- **`ide_optimize_imports` / `ide_reformat_code`** — refresh the file from disk before resolving PSI, preventing "Outdated stub in index" errors when files were modified by external tools.

## [4.30.0] - 2026-07-19

### Added

- **`ide_edit_member`** — replace an entire class member declaration (signature + body) by structural name, not text match. Targets by file, class, and member name with optional overload disambiguation. Auto-reformats after editing. Supports Java and Kotlin. *(disabled by default)*
- **`ide_insert_member`** — insert a new member (method, field, inner class) at a structural position relative to an anchor member or at the start/end of a class body. Auto-reformats after insertion. Supports Java and Kotlin. *(disabled by default)*
- **`ide_replace_member`** — replace the body of a method/function or the initializer of a field/property, preserving the member's signature. Targets by structural name. Auto-reformats after editing. Supports Java and Kotlin. *(disabled by default)*
- **`ide_file_structure` now includes `endLine`** — each member in the structure tree now reports its end line alongside the start line, enabling precise line-range reads via `ide_read_file(startLine, endLine)`.

## [4.29.0] - 2026-07-19

### Added

- **`ide_list_tests`** — List all test methods/classes discovered by the IDE's test framework extension points (JUnit, TestNG, etc.) *(disabled by default)*
- **`ide_run_tests`** — Run a test class or method by FQN using the IDE's run configuration infrastructure; returns structured pass/fail results *(disabled by default)*

## [4.28.0] - 2026-07-18

### Added

- **`ide_open_workspace`** — scan a root directory for Maven projects and open them all in one IntelliJ window with full cross-project code intelligence. Creates a temporary Maven aggregator POM with relative module paths. Also accepts an explicit `modules` array of absolute paths for ad-hoc workspaces — same module combination (in any order) reuses the cached workspace via SHA-based naming. Only available when the Maven plugin is installed. *(disabled by default)*
- **`ide_structural_search_replace`** — Pattern-based code search and transformation using IntelliJ's Structural Search and Replace engine. Accepts a `searchPattern` with optional `replacePattern` for search-only or search-and-replace operations. Supports `filePattern` and `scope` filtering. Returns match count, replaced count, and match list. *(disabled by default)* — Java, Kotlin.
- **`ide_change_signature`** — Change a method's signature (name, return type, visibility, parameters) with automatic caller updates using IntelliJ's Change Signature refactoring. Accepts `file` + `line` + `column` to identify the method, plus optional `newName`, `newReturnType`, `newVisibility`, and `newParameters` array. Supports `generateDelegate` to preserve binary compatibility. *(disabled by default)* — Java.
- **`ide_replace_text_in_file`** — Find and replace text (literal or regex) in a file through IntelliJ's Document API. Changes are immediately indexed. Use for mechanical text substitutions that don't need structural refactoring. *(disabled by default)*
- **`ide_create_file`** — Create a new source file through IntelliJ's VFS, immediately indexed and available for all IDE tools without needing `ide_sync_files`. Use instead of the Write tool for `.java`, `.kt`, `.ts`, `.tsx` files. *(disabled by default)*

## [4.27.0] - 2026-07-02

### Added

- **Python symbol lookup for definition and reference tools** — `ide_find_definition` and `ide_find_references` now accept `language: "Python"` with fully qualified symbols such as `pkg.mod.ClassName`, `pkg.mod.function_name`, and `pkg.mod.ClassName.method_name`, so agents can jump to Python definitions and usages without file/line coordinates.

## [4.26.0] - 2026-07-02

### Fixed

- MCP server now starts correctly in JetBrains remote development and headless IDE sessions, so coding agents can connect without a full local UI. Lifecycle management remains available in those environments while skipping UI-only focus handling.

## [4.25.0] - 2026-06-27

### Added

- **`ide_import_modules`** — import external project directories as Maven modules into the current IntelliJ window for cross-project code intelligence and refactoring. Only available when the Maven plugin is installed. *(disabled by default)*

### Changed

- Disabled tools are now rejected at `tools/call` time with a clear error message, not just hidden from `tools/list`.

### Fixed

- New tools that are disabled by default now stay disabled after plugin upgrades and settings changes, while preserving existing tool choices from older saved settings.
- The bundled agent tool reference now includes the project reload and Maven module import tools.

## [4.24.0] - 2026-06-25

### Added

- **`ide_reload_project`** refreshes linked Maven and Gradle build models after build-file edits, so diagnostics and builds can see updated dependencies without a manual IDE reload. The tool is disabled by default and reports clearly when no linked build system can be refreshed.

### Changed

- JavaScript and TypeScript navigation in WebStorm is more accurate for overloads, barrels/re-exports, type aliases, default exports, and class/member lookups. Resolution now uses IntelliJ PSI instead of fragile source-text matching, reducing false positives in comments, strings, and unrelated files.
- **`ide_refactor_rename`** now supports explicit file-vs-symbol targets for JS/TS and keeps file rename imports in sync during headless renames. If some import retargeting cannot be completed automatically, the tool returns partial success with warnings instead of failing the whole rename.

### Fixed

- Lifecycle auto-open now skips closed projects when the IDE heap has less than 10% free memory, avoiding OOM-prone wakeups under pressure.
- **`ide_find_references`** now ignores lone placeholder `language` or `symbol` fields when a complete file/line/column target is present, so valid position-based requests do not fail as mixed lookup modes.

## [4.23.2] - 2026-06-14

### Fixed

- Build failures that only report compiler output now return diagnostics instead of an empty error list when possible.
- Plugin install/update now requires an IDE restart and verifier-only headless runs no longer start the MCP server.

## [4.23.0] - 2026-06-13

### Added — Project lifecycle management

- **`ide_set_project_mode`** — explicitly set a managed project's lifecycle mode (`active`, `background`, `dormant`, `closed`).
- **`ide_get_project_modes`** — list all MCP-managed projects and their current modes, including those we closed.
- **`ide_project_status`** — combined snapshot: every open project and every managed project in one table, with open/managed/mode per row.
- **`ide_release_project`** — unenroll a project, restoring full IDE behaviour and disabling Power Save Mode. Accepts an optional `path` argument to release a closed managed project without needing it to be open.
- **`ide_set_all_project_modes`** — set all managed projects to the same mode at once (active, background, or dormant).
- **`ide_enroll_all_projects`** — enroll every currently open project in lifecycle management at once; already-managed projects are skipped.
- **`ide_release_all_projects`** — release every managed project (including closed ones) from lifecycle management at once.
- **`ide_set_lifecycle_log_file`** — enable or disable writing lifecycle events to the persistent log file on disk (`mcp-lifecycle.log`, written alongside `idea.log`). The in-memory ring buffer is always active regardless. *(disabled by default)*
- **Lifecycle settings** — configurable timing thresholds (focus→background, background→dormant, dormant→closed) and a master enable/disable toggle in Settings → Index MCP Server.
- **Interactive project list in Settings** — the lifecycle settings panel now shows all known projects (open and closed managed) with a checkbox per project (checked = enrolled), an X button to release individual projects, and "Enroll All Open" / "Release All" buttons. Changes take effect immediately without clicking Apply.
- **"MCP: Open Project" action** — searchable popup (Cmd+Shift+A) listing managed projects by state; selecting one opens or wakes it.
- **"MCP: Show Project States" action** — opens the lifecycle settings panel from the keyboard.

### Added — Lifecycle event log

- **`ide_lifecycle_log`** — query recent lifecycle events from an in-memory ring buffer. Records every state transition, project open/close, focus change, timer firing, and MCP-triggered wake for all IntelliJ projects (not just managed ones). Each event includes a `trigger` field that identifies the cause: `timer:focus`, `timer:inactivity`, `timer:close`, `focus_gained`, `focus_lost`, `mcp_call`, `auto_open`, or `user`. Parameters: `limit` (default 50), `project` (path substring filter). Response includes `log_file` — the path to a persistent log file. No restart required.
- **Event log buffer size** — configurable in Settings → Index MCP Server → Lifecycle (default 500, range 100–10,000).

### Fixed

- `IndexNotReadyException` from `ide_diagnostics` and other tools now logged at DEBUG instead of ERROR when the IDE index is not ready.
- Rethrow `ProcessCanceledException` instead of logging as ERROR when a project is disposed mid-call.
- MCP server watchdog restarts the server if it stops unexpectedly between tool calls.
- Detect compiled elements before rename to avoid assertion crash in `ide_refactor_rename`.

## [4.22.0] - 2026-06-12

### Added

- **`ide_set_power_save_mode`** — enable or disable IDE Power Save Mode (IDE-wide). Suspends background inspections and on-the-fly code analysis to cut CPU/memory usage while the index and all code intelligence operations (find usages, refactoring, navigation) remain fully functional.
- **`ide_close_project`** — close an open project window and free its memory. Non-blocking; the project can be reopened via Recent Projects or `ide_open_project`. Refuses to close the last open project so the MCP server always keeps a JSON-RPC context project.
- **`ide_open_project`** — open a project by absolute filesystem path and wait until indexing completes (`timeoutSeconds`, default 600), so follow-up tool calls succeed immediately. Idempotent for already-open projects; reports partial success if the project opens but indexing exceeds the timeout.

## [4.21.1] - 2026-06-11

### Fixed

- Fixed JetBrains Marketplace compatibility issue (internal IntelliJ API usage in `ide_restart`).

## [4.21.0] - 2026-06-10

### Added

- **`ide_install_plugin`** — install a plugin zip into the IDE, replacing any existing version. Auto-detects the output of `./gradlew buildPlugin` (`build/distributions/*.zip`) when no path is supplied; accepts an explicit path for any plugin zip. A restart is required to load the updated plugin.
- **`ide_restart`** — restart the IDE. Terminates the MCP connection immediately; no further tool calls should be made after invoking this. Typical use: `ide_install_plugin` followed by `ide_restart`.

## [4.20.0] - 2026-06-07

### Added

- Added `includeGenerated` controls so generated code can be included when it matters and filtered when it adds noise.

## [4.19.3] - 2026-06-05

### Fixed

- Replaced internal IntelliJ `PluginManager.findEnabledPlugin` usage with public plugin-state checks for Marketplace approval.
- Fixed `ide_file_structure` for Lombok/augmented Java classes by skipping generated PSI members without real source offsets. Fixes [#201](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/201).

## [4.19.1] - 2026-05-26

### Fixed

- Fixed `ide_search_text` regex search and `filePattern` filtering for [#190](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/190).

## [4.19.0] - 2026-05-26

### Added

- Added `regex` support to `ide_search_text` through IntelliJ's Find in Files path, including existing `context` filtering and pagination.

### Fixed

- Wired `ide_search_text`'s documented `filePattern` filter into the schema and search execution path using IntelliJ file mask semantics. Fixes [#190](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/190).

## [4.18.0] - 2026-05-24

### Added

- **PHP symbol reference handler** — PHP now supports `language`+`symbol` parameter mode for `ide_find_references`, `ide_find_definition`, `ide_call_hierarchy`, `ide_find_implementations`, and `ide_find_super_methods`. Accepts symbol formats with PHP namespaces (e.g., `\\App\\Service\\UserService`, `\\App\\Service\\UserService::find()`, `\\App\\Service\\UserService::$property`). Fixes [#179](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/179).
- **PHP symbol reference member lookup** — Inherited and case-insensitive PHP methods resolve through PhpStorm's `findMethodByName(CharSequence)` API, field/constant lookup uses the matching `findFieldByName(CharSequence, boolean)` signature, and plain `Class::name` symbols do not fall back to properties without the documented `$property` syntax.
- **PHP enum case resolution** — `EnumType::CASE` now resolves to enum case PSI elements via PhpStorm's `getEnumCases()` API. Enum case lookup runs before class constant lookup so `::CASE` correctly targets enum cases on enum types. `::CASE()` still resolves as a method call.

## [4.17.3] - 2026-05-21

### Fixed

- Fixed `ide_move_file` PHP namespace inference for monorepos where `composer.json` is nested below the opened project root. The PHP semantic move now discovers the nearest ancestor Composer PSR-4 mapping and resolves it relative to that Composer file, so moves under nested source roots can update namespaces correctly. Fixes [#185](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/185).

## [4.17.2] - 2026-05-18

### Fixed

- De-duplicated PHP interface and trait names in `ide_file_structure` class signatures.

## [4.17.1] - 2026-05-17

### Fixed

- minor issues with - PHP support to `ide_file_structure`

## [4.17.0] - 2026-05-17

### Added

- Added PHP support to `ide_file_structure` using the IDE Structure View API. Works in PhpStorm and IntelliJ IDEA Ultimate with the PHP plugin enabled.
- PHP structure output includes namespace containers, constructor-promoted property modifiers, enum cases, constants, and includes while filtering implicit PHP runtime details from enums.
- PHP structure output renders interface inheritance as `extends`, labels global namespace blocks, and filters synthetic `final` modifiers from properties/constants in final classes.

## [4.16.3] - 2026-05-06

### Fixed

- **`ide_refactor_rename` no longer fails while committing documents from MCP requests** — Document commits now switch to a write-safe non-modal EDT context instead of using deprecated synchronous transaction submission from the request coroutine's write-unsafe modality. Fixes [#172](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/172).

## [4.16.2] - 2026-05-03

### Fixed

- **Blank pagination cursors now start fresh searches** — Search/navigation tools now treat missing, null, blank, and whitespace-only `cursor` values as absent, so clients that send `"cursor": ""` no longer get invalid-cursor errors.
- **Blank symbol/position lookup arguments are ignored consistently** — Shared lookup resolution now treats blank `file`, `language`, and `symbol` values as absent, preventing false symbol-vs-position conflicts and improving missing-parameter errors.
- **Blank required file arguments now fail clearly** — File-based editor and refactoring tools now reject blank required path arguments with `Missing required parameter` errors instead of attempting to resolve empty paths.

## [4.16.1] - 2026-04-30

### Fixed

- **Install on Coding Agents now works on Windows for Codex CLI and Claude Code** — Direct installation no longer assumes a POSIX `sh` shell. Windows installs now run through `cmd.exe` with Windows-compatible command separators and null-output redirection, and copied Windows install commands are wrapped for terminal paste. Fixes [#165](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/165).

## [4.16.0] - 2026-04-24

### Fixed

- **`ide_find_symbol` ordering, missing/extra results, and qualified-query handling now match IntelliJ's Go to Symbol popup.** The tool previously ran the popup search separately for each registered language handler and concatenated results in handler-iteration order, which destroyed cross-language ranking. Symbol search now issues a single popup-backed call.

### Added

- **`ide_find_symbol` is now available in every compatible JetBrains IDE**, including RubyMine, CLion, DataGrip, Aqua, and DataSpell. Result quality depends on IDE-supplied `ChooseByNameContributor` extensions; `kind` and `qualifiedName` may fall back to generic values for languages the plugin doesn't special-case.

### Changed

- Internal: removed the `SymbolSearchHandler` interface and its nine language implementations (including the Markdown symbol-search handler added in 4.15.0); symbol search is now centralised in `OptimizedSymbolSearch` + `PopupFaithfulSymbolSearch`. Markdown heading navigation remains available through `ide_file_structure`, not `ide_find_symbol`.

## [4.15.0] - 2026-04-24

### Added

- Added Markdown heading support for `ide_find_symbol` and `ide_file_structure`, backed by the bundled JetBrains Markdown PSI/indexes. Fixes [#149](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/149).

## [4.14.1] - 2026-04-22

### Fixed

- Reworked `ide_diagnostics` to use Marketplace-safe public IntelliJ APIs instead of internal highlighting APIs, resolving the JetBrains Marketplace internal API rejection.
- Preserved fresh diagnostics for open editor files while falling back to public batch analysis for closed files, with updated tool messaging that explains the weaker closed-file `WEAK_WARNING` and intention coverage.

## [4.14.0] - 2026-04-21

### Added

- Added a settings toggle to return structured MCP tool payloads as either JSON or TOON.

## [4.13.2] - 2026-04-21

### Fixed

- **Qualified symbol search in `ide_find_symbol` now behaves much closer to IntelliJ's Go to Symbol popup** — queries like `BasicSolver.run` and `test.BasicSolver.run` now resolve the intended symbol instead of being treated like a plain symbol name. Fixes [#144](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/144).

## [4.13.1] - 2026-04-21

### Fixed

- Moving files now works more reliably, especially in PHP projects.

## [4.13.0] - 2026-04-18

### Changed

- **Breaking: covered navigation and adjacent search tools now use built-in `scope` instead of `includeLibraries` / `includeTests`** — `ide_find_references`, `ide_find_implementations`, `ide_call_hierarchy`, `ide_type_hierarchy`, `ide_find_class`, `ide_find_file`, and `ide_find_symbol` now accept `scope` with the built-in values `project_files`, `project_and_libraries`, `project_production_files`, and `project_test_files`. The old boolean parameters are no longer part of the public contract.

### Fixed

- **Covered search/navigation tools now honor the requested built-in scope end-to-end** — library, production-only, and test-only searches now use explicit scoped IntelliJ searches instead of collapsing back to legacy boolean behavior in tool, handler, or contributor fallback paths.

## [4.12.0] - 2026-04-18

### Added

- **Optional library/test filters for navigation tools** — `ide_find_implementations`, `ide_call_hierarchy`, `ide_type_hierarchy`, and `ide_find_references` now accept `includeLibraries` and `includeTests`, both defaulting to `true`, so agents can suppress dependency noise and test-only results when narrowing navigation queries. Addresses [#138](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/138).

### Fixed

- **`includeLibraries=true` now widens hierarchy search scopes correctly** — call-hierarchy and related language-specific navigation searches no longer stay pinned to project-only scope when library results are requested, so callers/implementations from dependency sources can be returned again for library-backed targets.
- **Navigation library/test filtering uses IntelliJ file-index classification** — project files are no longer misclassified as dependencies when filtering results, which preserves project implementations while still excluding actual library/test nodes.

## [4.11.3] - 2026-04-17

### Changed

- Completely reworked `ide_diagnostics` for better reliability and multi-project support.

## [4.11.2] - 2026-04-17

### Fixed

- **External library path round-tripping in read-only navigation tools** — Search results now preserve dependency/library paths, and read-only position-based navigation tools accept those returned absolute paths or `jar://` URLs. Fixes [#135](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/135).
- **Python dotted member definition resolution** — Position-based navigation now prefers the Python callable/member target for dotted expressions like `json.dumps`, `os.path.join`, and `datetime.datetime.now()` when the caret is on the member token, instead of incorrectly jumping to a module/package directory.
- **Python supertypes and super-method hierarchies** — `ide_type_hierarchy` now returns Python supertypes again, and `ide_find_super_methods` now returns inherited Python override chains instead of empty hierarchies.

## [4.11.1] - 2026-04-16

### Fixed

- **`ide_call_hierarchy` callers for Python functions in PyCharm** — Replaced the generic `ReferencesSearch`-based incoming call path with PyCharm's own Python call hierarchy API (`PyStaticCallHierarchyUtil.getCallers()`), so Python caller results now match the IDE's native behavior. Fixes [#133](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/133).
- **Python caller hierarchy compatibility failures are now explicit** — If the required PyCharm Python call hierarchy API is missing or incompatible in the current IDE/Python plugin build, the tool now returns a clear error instead of silently degrading to potentially incorrect results.

## [4.11.0] - 2026-04-15

### Added

- Added a **Project list in error responses** setting with `Expanded` and `Compact` modes. Workspace sub-project/module content roots remain valid `project_path` targets, while compact mode limits invalid/missing `project_path` errors to top-level project roots only.

## [4.10.5] - 2026-04-15

### Changed

- Relaxed IDE compatability requirements to 2025.3+ build, to support Android Studio which doesn't have 2026 yet

## [4.10.4] - 2026-04-12

### Fixed

- Improved command history stability during concurrent MCP tool calls.

## [4.10.3] - 2026-04-11

### Changed

- **Streamable HTTP is now stateless** — The primary `/index-mcp/streamable-http` transport no longer creates or validates `Mcp-Session-Id` headers. Requests continue working across client reconnects and server restarts without transport reauthentication semantics.

### Fixed

- **Claude Code stale session failure mode** — Removed the transport-level stale session `404` path that Claude Code could surface as a misleading authentication problem, while preserving legacy SSE behavior.

## [4.10.2] - 2026-04-11

### Changed

- **Streamable HTTP is now stateless** — The primary `/index-mcp/streamable-http` transport no longer creates or validates `Mcp-Session-Id` headers. Requests continue working across client reconnects and server restarts without transport reauthentication semantics.

### Fixed

- **Claude Code stale session failure mode** — Removed the transport-level stale session `404` path that Claude Code could surface as a misleading authentication problem, while preserving legacy SSE behavior.
- **`ide_find_references` search failure handling** — Added defensive handling for `LinkageError` / `NoSuchMethodError` failures coming from IDE search infrastructure so affected calls return a structured error with fallback guidance instead of hanging indefinitely.
- **Plugin verification baseline** — Added explicit Plugin Verifier coverage for IntelliJ IDEA Ultimate `2026.1` build `IU-261.22158.277` to keep this release line checked against the exact IDE version reported in [#122](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/122).

## [4.10.1] - 2026-04-07

### Fixed

- **`resolveVirtualFileAnywhere` Windows path handling** — Fixed path comparison failures on Windows caused by backslash path separators and case-insensitive VFS normalization. Uses NIO Path-based `isPathPrefixOf` for case-insensitive library JAR validation, and normalizes paths before comparison. Fixes issues where `Z:/Temp` paths were rejected due to VFS normalizing to `Z:/temp`.

## [4.10.0] - 2026-04-06

### Added

- **Symbol reference resolution for position-based tools** — Five tools now accept `language` + `symbol` as an alternative to `file` + `line` + `column` for identifying the target element. The two parameter groups are mutually exclusive. Unlocks the ability to directly reference symbols from third-party libraries.

  Currently supported for Java; extensible to other languages via `SymbolReferenceHandler`. Symbol format uses JavaDoc-style member references: `com.example.ClassName`, `com.example.ClassName#memberName`, or `com.example.ClassName#method(ParamType1, ParamType2)`.

  - Affected tools: `ide_find_references`, `ide_find_definition`, `ide_call_hierarchy`, `ide_find_implementations`, `ide_find_super_methods`
  - New handler interface: `SymbolReferenceHandler` with `JavaSymbolReferenceHandler` implementation
  - New `resolveElementFromArguments()` helper in `AbstractMcpTool` for unified element resolution
  - New `languageAndSymbol()` builder method in `SchemaBuilder`

## [4.9.3] - 2026-04-04

### Added

- **`ide_refactor_rename` file rename mode** — The `line` and `column` parameters are now optional. Omit them to rename the file itself instead of a symbol within it. Works for all file types including binary files (`.webp`, `.png`, `.jpg`). Especially useful for Android resource files where it updates all resource references across the project. Fixes [#115](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/115).

### Fixed

- **`ide_refactor_rename` file rename correctly handles Android resource naming** — When renaming Android resource files (drawables, mipmaps, etc.), the tool now probes `RenamePsiElementProcessor.prepareRenaming()` to detect element substitution. If the `PsiFile` will be substituted for a resource element, the file extension is stripped from `newName` to match the SDK's resource naming convention. This prevents double extensions (e.g., `app_icon.webp.webp`) on related DPI variants and corrupted `R.drawable` references.
- **`ide_refactor_safe_delete` now detects Android resource references for file deletion** — Previously, deleting Android resource files (e.g., `backup_rules.xml`) did not detect `@xml/` or `@drawable/` references in other XML files, allowing deletion despite active references. The tool now checks three layers: direct file references, resource element references (via `prepareRenaming` probe), and top-level symbol references. This correctly blocks deletion when the file is referenced via the Android resource system.

## [4.9.2] - 2026-04-02

### Fixed

- **`ide_refactor_rename` renamed XML attribute name instead of referenced resource** — When renaming inside an XML attribute value (e.g., `android:id="@+id/XXTVProgress"`), the tool incorrectly renamed the attribute name (`android:id`) instead of the referenced resource ID. Now resolves PSI references before falling back to tree-walking, so the rename correctly targets the referenced declaration. Fixes [#113](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/113).

## [4.9.1] - 2026-04-01

### Fixed

- **Path traversal protection for file operations** — All file-based tools now validate that resolved paths stay within project boundaries, preventing access to files outside the project via relative paths like `../../`.
- **JAR reading restricted to project libraries** — `ide_read_file` now only allows reading JARs that are part of the project's configured library roots, blocking access to arbitrary JARs on the filesystem.

## [4.9.0] - 2026-03-30

### Added

- **Enhanced `ide_diagnostics` with build error and test result sources** — The diagnostics tool now supports three independent sources: per-file code analysis (existing), build output from the last build (new), and test results from open test run tabs (new). New parameters: `includeBuildErrors`, `includeTestResults`, `severity` filter (`all`/`errors`/`warnings`), `testResultFilter` (`failed`/`all`), `maxBuildErrors`, `maxTestResults`. The `file` parameter is now optional — omit it to query only build/test results. Fully backward compatible. Addresses [#104](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/104).
- **`BuildDiagnosticsCacheService`** — Persistent project-level service that captures build errors/warnings from all build sources (Gradle, Maven, JPS) as they happen. Queried by `ide_diagnostics` when `includeBuildErrors` is enabled.

### Changed

- Extracted shared build listener reflection code from `BuildProjectTool` into `BuildListenerUtils` utility for reuse.

## [4.8.0] - 2026-03-30

### Added

- **`ide_find_references` and `ide_find_definition`: `astPath` field** — Returns the chain of named AST ancestors (classes, methods, etc.) enclosing the target element, providing structural context for each result without requiring additional file reads.

## [4.7.0] - 2026-03-26

### Added

- Cursor-based pagination for `ide_find_references`, `ide_search_text`, `ide_find_class`, `ide_find_file`, `ide_find_symbol`, and `ide_find_implementations`

### Fixed

- `ide_search_text` context filter (`context: "comments"`, `"code"`, `"strings"`) returned false positives from non-matching contexts

## [4.6.0] - 2026-03-21

### Added

- **`ide_refactor_rename`: `relatedRenamingStrategy` parameter** — Controls automatic renaming of related symbols (same-named properties on unrelated classes, getters/setters, test classes, variables). Options: `"all"` (default, current behavior), `"none"` (rename only the targeted symbol), `"accessors_and_tests"` (only rename getters/setters and test classes/methods), `"ask"` (show IDE dialog for interactive choice). Fixes [#101](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/101).

## [4.5.0] - 2026-03-21

### Added

- **`ide_move_file` tool** — Move files to a new directory using the IDE's refactoring engine. Enabled by default.

## [4.4.1] - 2026-03-19

### Fixed

- **2026.1 compatability issues**

## [4.4.0] - 2026-03-18

### Added

- **`ide_convert_java_to_kotlin` tool** — Convert Java files to Kotlin using IntelliJ's built-in J2K (Java-to-Kotlin) converter. Supports full file conversion with automatic formatting, import optimization, and code cleanup. Handles classes, interfaces, enums, methods, fields, generics, and Java 8+ features (lambdas, streams, method references). Returns list of created .kt files with line counts and any conversion warnings. Original Java files are automatically deleted after successful conversion. Requires both Java and Kotlin plugins. Disabled by default — enable in Settings → Tools → Index MCP Server.

## [4.3.0] - 2026-03-17

### Added

- **`ide_optimize_imports` tool** — Optimize imports in a file without reformatting code. Removes unused imports and organizes remaining imports according to project code style. Equivalent to the IDE's "Optimize Imports" action (Ctrl+Alt+O / ⌘⌥O). Disabled by default — enable in Settings → Tools → Index MCP Server. ([#94](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/94))

## [4.2.0] - 2026-03-13

### Added

- **Companion Skill** — New "Get Companion Skill" button in the tool window toolbar. Lets users install or export a companion skill that guides AI coding agents on when and how to use IDE MCP tools effectively. Supports direct installation to `.claude/skills/` for Claude Code projects, or export as `.skill`/`.zip` file for sharing.

## [4.1.0] - 2026-03-13

### Added

- **`ide_build_project` tool** — Trigger project builds via the IDE's build system (JPS, Gradle, Maven). Returns structured error/warning messages with file locations. Supports workspace sub-projects, trusted-project security gate, optional timeout, and optional raw build output. Disabled by default — enable in Settings → Tools → Index MCP Server.
- **Plugin description updates** - Aligned with missing tools and features.

## [4.0.2] - 2026-03-13

### Changed

- **Refactored path resolution in `PsiUtils`** — Extracted `resolveLocalFile`, `resolveAbsolutePath`, `resolveAbsolutePathString`, `expandHome`, and `toPathOrNull` as reusable helpers, eliminating duplicated path normalization logic

### Fixed

- **Integration test used real filesystem** — `ToolExecutionIntegrationTest` now uses `myFixture.addFileToProject()` so test files are properly indexed by the in-memory VFS
- **Path comparison failed on Windows** — `PsiUtilsTest` now normalizes path separators and casing before comparing, fixing test failures on Windows

## [4.0.1] - 2026-03-12

### Fixed

- **Fixed exception introduced in 4.0.0 that could occur when `ide_sync_files` was used after external file changes**
- **Fixed contructor param renaming forcing modal popup**

## [4.0.0] - 2026-03-11

### Added

- **Primary transport changed** — Default server URL now points to Streamable HTTP endpoint (`/index-mcp/streamable-http`). Existing client configurations using the SSE URL continue to work but should be updated.

## [3.14.0] - 2026-03-11

### Added

- **Configurable server host** — Allows the user to configure the listening server host, making it possible to use the MCP server on another machine or WSL (Windows Subsystem for Linux).

## [3.13.0] - 2026-03-03

### Added

- **`ide_reformat_code` tool** — Reformat code files using the IDE's code style settings (`.editorconfig`, project code style). Equivalent to the IDE's "Reformat Code" action (Ctrl+Alt+L / ⌘⌥L). Supports optional import optimization (`optimizeImports`, default: true), code rearrangement (`rearrangeCode`, default: true), and partial file formatting via `startLine`/`endLine`. Disabled by default — enable in Settings → Tools → Index MCP Server. ([#76](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/76))

## [3.12.2] - 2026-03-03

### Fixed

- **Tool filter dropdown in tool window was outdated**

## [3.12.1] - 2026-03-03

### Fixed

- **Server stuck on "Initializing..." if `postStartupActivity` doesn't fire** — The MCP server now self-initializes asynchronously from its service constructor instead of depending solely on `postStartupActivity`. This fixes environments where the startup activity silently fails (e.g., due to plugin conflicts or class-loading errors), leaving the server permanently in "Initializing..." state ([#73](https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/issues/73))

## [3.12.0] - 2026-03-01

### Added

- **`overrideStrategy` parameter for `ide_refactor_rename`** — Controls how renaming a method that overrides a base method is handled, enabling fully headless/agent usage without modal dialogs
  - `"rename_base"` (default): Automatically renames the base method and all overrides by resolving to the deepest super method via `PsiMethod.findDeepestSuperMethods()`, bypassing the dialog entirely
  - `"rename_only_current"`: Renames only the current method, leaving the base and other overrides unchanged
  - `"ask"`: Preserves original IDE behavior, showing the dialog for interactive choice

## [3.11.0] - 2026-02-27

### Changed

- **Codebase refactoring overhaul** — Major internal cleanup reducing ~926 lines of duplication
  - **Generic `PluginDetector`** — Replaced 6 nearly identical plugin detector files (640 lines) with a single generic `PluginDetector` class and `PluginDetectors` registry (~80 lines)
  - **`SchemaBuilder` utility** — All 19 tool input schemas now use a fluent `SchemaBuilder` instead of manual JSON construction, eliminating ~460 lines of boilerplate
  - **Data-driven registration** — Handler and tool registration use data-driven loops instead of duplicated reflection blocks
  - **`ClassResolver`** — Extracted class-by-FQN resolution (PHP/Java) from `AbstractMcpTool` into standalone utility
  - **`ProjectResolver`** — Extracted multi-project resolution logic from `JsonRpcHandler` into independently testable class
  - **`server/transport/` package** — Moved `KtorMcpServer` and `KtorSseSessionManager` to dedicated transport sub-package
  - **Consolidated error builders** — Replaced 4 nearly identical JSON-RPC error response methods with single factory

### Fixed

- **JSON-RPC error responses used unsafe string concatenation** — `KtorMcpServer.createJsonRpcError()` now uses proper `kotlinx.serialization` instead of manual string interpolation, preventing malformed JSON from special characters in error messages
- **Streamable HTTP notifications returned no response** — Notifications (e.g., `notifications/initialized`) sent via Streamable HTTP transport now correctly return `202 Accepted` instead of silently dropping the connection
- **No JSON-RPC version validation** — Server now validates that `request.jsonrpc == "2.0"` and returns `INVALID_REQUEST` (-32600) for non-compliant requests

## [3.10.2] - 2026-02-27

### Fixed

- **Tools stop responding when a modal dialog is open** - MCP tool calls (e.g., `ide_sync_files`, `ide_refactor_rename`) no longer hang indefinitely when a modal dialog (Settings, Registry, refactoring preview, etc.) is open in the IDE

## [3.10.1] - 2026-02-27

### Fixed

- **`ide_find_definition` crash in PhpStorm and other non-Java IDEs**

## [3.10.0] - 2026-02-22

### Added

- **`matchMode` parameter for `ide_find_symbol` and `ide_find_class`** - Control how queries match symbol names
  - `"substring"` (default) - matches anywhere in name (backward compatible)
  - `"prefix"` - camelCase-aware prefix matching (e.g., "find" matches "findSymbol")
  - `"exact"` - case-sensitive exact match
- **`language` parameter for `ide_find_symbol` and `ide_find_class`** - Filter results by programming language (e.g., `"Kotlin"`, `"Java"`)
- **`maxPreviewLines` parameter for `ide_find_definition`** - Limit `fullElementPreview` output size (default: 50, max: 500)
- **Glob pattern support for `ide_search_text`** - File type filtering via glob patterns (e.g., `*.kt`, `*.gradle.kts`)
- **Kotlin callee resolution for `ide_call_hierarchy`** - Callees direction now works for Kotlin methods by resolving `KtCallExpression` references via reflection
- **Path-based search fallback for `ide_find_file`** - Falls back to path matching when filename search returns no results
- **`ide_file_structure` for JavaScript and TypeScript** - Previously returned "Language not supported". Now works for `.js`, `.ts`, `.jsx`, `.tsx` files

### Fixed

- **`ide_call_hierarchy` callers for Kotlin `suspend fun`** - `MethodReferencesSearch` misses `suspend fun` call sites because the Kotlin compiler appends a hidden `Continuation<T>` parameter to the JVM signature. Added unconditional `ReferencesSearch.search(navigationElement)` alongside `MethodReferencesSearch` (with deduplication) so callers are always found
- **`ide_call_hierarchy` callers inside `val`/`var` assignments** - `resolveKotlinMethod` was stopping at local `val`/`var` PSI nodes (`KtProperty` with no backing JVM method) and returning `null`, silently dropping every such caller reference. Now continues walking up the PSI tree to find the enclosing named function
- **`ide_call_hierarchy` "unknown" caller names for JSX arrow functions** - `findContainingCallable` was returning unnamed anonymous arrow functions (`const App = () => ...`) instead of the enclosing `JSVariable`. Now skips unnamed `JSFunction` nodes and falls back to the containing `JSVariable` for correct caller name resolution
- **`ide_find_symbol`, `ide_find_class`, `ide_search_text`, `ide_find_file` polluted by excluded paths** - All search tools now use a `DelegatingGlobalSearchScope` subclass (`ExcludedPathScope`) that rejects venv, node_modules, build output, and worktree files at the IntelliJ search-infrastructure level. Excluded files never consume buffer slots, replacing the fragile over-fetch-then-filter approach
- **`ide_find_symbol` and `ide_find_class` polluted by venv/node_modules in subdirectories** - Exclusion filter now matches `.venv/`, `venv/`, `node_modules/`, and `.worktrees/` at any path depth (not only at the project root). Fixes multi-module projects where the virtual environment is inside a subdirectory (e.g. `python-services/.venv/`)
- **`ide_find_symbol` exact `matchMode` was case-insensitive** - Changed from `name.equals(pattern, ignoreCase = true)` to `name == pattern`. `"CalendarService"` with `exact` no longer matches `calendarService` properties
- **`ide_find_references` duplicate entries for JSX components** - Opening and closing JSX tags (`<Foo>` / `</Foo>`) resolved to identical `file:line:column` positions, producing duplicate entries. Results are now deduplicated by position
- **`ide_find_references` `truncated` flag incorrectly true after deduplication** - `truncated` was computed as `totalFound > usages.size`, which fired whenever deduplication removed JSX tag duplicates. Now correctly set to `totalFound > maxResults` — only true when results were actually cut off by the limit
- **`ide_find_class` short/generic queries returning 0 results** - Increased `processNames` collection limit from 75 to 5000. The contributor's `processNames` emits names from broader scope (JDK/libraries) even when searching project scope; short patterns like "Tool" would fill the small buffer with library names before reaching project classes
- **`ide_find_class` language filter** - Filter applied at collection time in `processContributor` instead of post-filtering, preventing generic queries from returning 0 results when language filtering
- **`ide_find_symbol` language filter** - Collects 3x more from handlers when filtering and filters during collection loop
- **`ide_find_definition` on import statements** - Class imports now resolve correctly. Package-segment imports resolve to the package directory via `PsiPackage`/`PsiDirectory` handling instead of returning "Definition file not found"
- **`ide_find_definition` compiled class targets** - `effectiveTarget` now uses `navigationElement` when target resolves to compiled class in JAR
- **`ide_search_text` deduplication and false positives** - Results deduplicated by (file, line) and validated that the search word appears in the matched line
- **`ide_find_references` Processor pattern** - Uses streaming `Processor` with early termination instead of `findAll().take(n)` to avoid loading all results into memory
- **`ide_type_hierarchy` Kotlin language detection** - Uses `navigationElement.language.id` to correctly detect Kotlin types instead of reporting them as Java
- **`ide_find_file` build output duplicates** - Filters `bin/`, `build/`, `out/`, `.gradle/` output directories from results
- **`ide_search_text` returning results from worktrees and node_modules** - Search results were not filtered by excluded paths; now uses scope-based exclusion like all other search tools
- **`ide_file_structure` duplicate constructors for Java classes** - `PsiClass.methods` includes constructors in IntelliJ PSI, causing constructor entries to appear twice (once from `psiClass.constructors` and once from `psiClass.methods`). Now skips constructor entries when iterating methods

## [3.8.0] - 2026-02-19

### Added

- **Tool window footer links** - GitHub, Debugger MCP Server, and Buy Me a Coffee links in the toolbar for quick access
  - "Star/Report Issues" link to the GitHub repository
  - "Try Debugger MCP Server" link to the companion plugin on JetBrains Marketplace
  - "Buy Me a Coffee" link to support the developer

## [3.7.0] - 2026-02-19

### Added

- **New tool: `ide_get_active_file`** - Get the currently active file(s) open in the IDE editor
  - Returns cursor position (line, column), selected text, and language for all visible editors
  - Supports split panes (returns all visible editors)
  - Returns empty list (not error) when no editors are open
- **New tool: `ide_open_file`** - Open a file in the IDE editor with optional navigation
  - Navigate to specific line and column positions
  - Validates parameters (column requires line, line >= 1, column >= 1)

### Disabled by default

- `ide_get_active_file` and `ide_open_file` are disabled by default - enable in Settings > Index MCP Server

## [3.6.0] - 2026-02-18

### Added

- **Column numbers in navigation results for better inter-tool flows integration** - `ide_find_implementations`, `ide_call_hierarchy`, `ide_find_symbol`, and `ide_find_super_methods` now include 1-based `column` numbers in their output, matching the existing behavior of `ide_find_references`, `ide_find_definition`, `ide_diagnostics`, and `ide_search_text`

## [3.5.0] - 2026-02-18

### Added

- **Workspace project support** - All tools now correctly resolve paths when a JetBrains IDE opens a workspace with multiple sub-projects (modules with separate content roots)

### Fixed

- **SLF4J dependency conflict** - Excluded `org.slf4j` from Ktor dependencies to avoid classloader conflicts with the IDE's bundled SLF4J

## [3.4.0] - 2026-02-18

### Added

- **New tool: `ide_sync_files`** - Force the IDE to synchronize its virtual file system and PSI cache with external file changes on-demand
  - Use when files were created, modified, or deleted outside the IDE and other tools report stale results
  - Lightweight alternative to the global "Sync external file changes" setting
  - Optional `paths` parameter to sync specific files/directories instead of the entire project

## [3.3.4] - 2026-02-05

### Added

- **New tool: `ide_read_file`** - Read source file contents from project or library dependencies
  - Supports multiple file path formats: relative, absolute, jar paths (`path/to/lib.jar!/com/example/Class.java`), and jar URLs
  - Can read files by qualified class name (e.g., `java.util.ArrayList`)
  - Supports optional line range extraction with `startLine` and `endLine` parameters (1-based, inclusive)
  - Automatically detects library files and resolves jar file paths
  - Returns file metadata: language ID, line count, and whether it's a library file

### Changed

- **Enhanced library source navigation** - `ide_find_definition` and symbol resolution now prefer source files (`.java`) over compiled files (`.class`) when library sources are attached
  - Added `PsiUtils.getNavigationElement()` utility for consistent navigation element resolution
  - Improves readability when navigating to library code with attached sources

## [3.3.3] - 2026-02-03

### Fixed

- **Symbol navigation resolution** - `ide_find_class` and optimized symbol search now resolve file/line/name via navigation elements for accurate locations.

## [3.3.2] - 2026-02-02

### Fixed

- **Safe delete file protection** - `ide_refactor_safe_delete` no longer accidentally deletes files when positioned on whitespace/comments. Now returns nearby symbol suggestions instead of deleting the file.
- **File deletion mode** - Added explicit `target_type='file'` parameter to safely delete entire files (only succeeds if no external usages exist)

## [3.3.1] - 2026-02-01

### Fixed

- **Kotlin position resolution** - Position-based tools now correctly resolve Kotlin classes and methods when cursor is on a declaration (not just references)
  - Affected tools: `ide_type_hierarchy`, `ide_find_implementations`, `ide_call_hierarchy`, `ide_find_super_methods`
  - Root cause: `PsiTreeUtil.getParentOfType` doesn't match Kotlin PSI types (`KtClass`, `KtNamedFunction`)
  - Solution: Use reflection to find Kotlin PSI elements and convert to light classes

## [3.3.0] - 2026-01-27

### Added

- **New tool: `ide_find_class`** - Class/interface search using CLASS_EP_NAME index
- **New tool: `ide_find_file`** - File search using FILE_EP_NAME index
- **New tool: `ide_search_text`** - Text search using word index with context filtering (code/comments/strings)

### Disabled by default

- ide_find_symbol

## [3.2.1] - 2026-01-26

### Fixed

- **Performance: Prevent IDE freezes during rapid tool calls** - Switched from blocking `readAction` to yielding `suspendingReadAction` in all tools. This prevents write lock starvation that caused IDE freezes when Claude Code's Explore agent fired many tool calls in succession.

## [3.2.0] - 2026-01-23

### Added

- **New tool: `ide_file_structure`** - Get hierarchical structure of source files (classes, methods, fields)
  - Supports: Java, Kotlin, Python
  - **Note**: Disabled by default - enable in Settings > Index MCP Server when needed

### Changed

- **Enhanced: `ide_find_definition`** - Added `fullElementPreview` parameter for complete PSI element preview

## [3.1.0] - 2026-01-07

### Added

- **Codex CLI install command** - "Install Now" now supports Codex CLI with remove-then-add reinstall flow

## [3.0.1] - 2025-12-28

### Fixed

- **Claude Code install removes legacy server name** - Install command now also removes `jetbrains-index-mcp` (v1.x name) to clean up after upgrades
- **Agent rule uses IDE-specific name** - "Copy rule" now uses the correct IDE-specific server name (e.g., `intellij-index`, `pycharm-index`) instead of hardcoded `jetbrains-index`

## [3.0.0] - 2025-12-23

### Fixed

- **MCP spec compliance** - `notifications/initialized` now handled correctly per MCP specification
  - Method renamed from `initialized` to `notifications/initialized` (per spec)
  - Notifications no longer receive a response (spec: "receiver MUST NOT send a response")

### Breaking

- **Claude Code transport type** - Changed `--transport http` to `--transport sse` in generated install commands

## [2.0.0] - 2025-12-15

### Added

- **Configurable server port** with IDE-specific defaults (e.g., IntelliJ: 29170, PyCharm: 29172)
- **IDE-specific server names** (e.g., `intellij-index`, `pycharm-index`) to run multiple IDEs simultaneously
- **Port conflict detection** with error notification and settings link
- **Settings shortcut** - "Change port, disable tools" link in toolbar

### Changed

- **Breaking**: Migrated to custom Ktor CIO server - update MCP client configs with new port/name
- Server URL no longer depends on IDE's built-in server port (was 63342)

## [1.12.1] - 2025-12-10

### Changed

- Replace `localhost` with `127.0.0.1` in server URLs for improved connection reliability

## [1.12.0] - 2025-12-09

### Added

- **Tool enable/disable settings** - Disable individual MCP tools from Settings > Index MCP Server (disabled tools are not exposed via `tools/list`)
- **Settings button in tool window** - Gear icon in toolbar opens plugin settings directly

## [1.11.0] - 2025-12-07

### Added

- **Rust Language Support** - Full support for RustRover, IntelliJ IDEA Ultimate with Rust plugin, and CLion

### Changed

- **Simplified tool descriptions** - Streamlined descriptions across navigation, refactoring, and intelligence tools for improved clarity and consistency

## [1.10.1] - 2025-12-07

### Removed

- **Auto-scroll setting** - Removed the "Auto-scroll to new commands" setting from plugin preferences

## [1.10.0] - 2025-12-07

### Added

- **PHP Language Support** - Full support for PhpStorm and IntelliJ IDEA with PHP plugin

## [1.9.1] - 2025-12-06

### Changed

- **Rider IDE excluded** - Plugin is now explicitly incompatible with Rider IDE (uses ReSharper backend which is incompatible with IntelliJ PSI APIs)
- **Documentation updated** - Clarified IDE compatibility: fully tested (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Android Studio) vs untested (PhpStorm, RubyMine, CLion, DataGrip)

## [1.9.0] - 2025-12-04

### Added

- **Full SSE transport support** - Responses are now sent via SSE `message` events per MCP spec (2024-11-05)
- **MCP Inspector compatibility** - Works correctly with `npx @modelcontextprotocol/inspector` in SSE mode
- **Dual transport support** - Supports both SSE transport and Streamable HTTP transport simultaneously

## [1.8.0] - 2025-12-03

### Added

- **Gemini CLI Support** - Added configuration generator for Gemini CLI (uses mcp-remote bridge)
- **Generic MCP Configurations** - New "Generic MCP Config" section in install popup
  - **Standard SSE** - For MCP clients with native SSE transport support
  - **Via mcp-remote** - For MCP clients without SSE support (uses npx mcp-remote bridge)
- `generateInstallCommand()` method for clients that support direct CLI installation
- `generateStandardSseConfig()` and `generateMcpRemoteConfig()` utility methods
- `getInstallableClients()` and `getCopyableClients()` methods for flexible client categorization

### Changed

- Renamed "Claude Code (CLI)" to "Claude Code" for consistency
- Install Now section now dynamically loads installable clients (only those with CLI support)
- Client type enum now includes `supportsInstallCommand` flag for extensibility

### Removed

- **VS Code configuration** - Removed VS Code-specific MCP configuration (use Generic MCP Config instead)
- **Windsurf configuration** - Removed Windsurf-specific configuration (use Generic MCP Config instead)

## [1.7.0] - 2025-12-03

### Added

- **Go Language Support** - Support for GoLand and IntelliJ IDEA with Go plugin
  - `ide_type_hierarchy` - Find Go struct/interface hierarchies and interface implementations
  - `ide_call_hierarchy` - Analyze caller/callee relationships for Go functions and methods
  - `ide_find_symbol` - Search for Go types, functions, methods, and fields
  - `ide_find_definition` - Navigate to Go symbol definitions
  - `ide_find_references` - Find all usages of Go symbols
  - `ide_diagnostics` - Detect Go code problems (errors, warnings, style issues)
  - `ide_refactor_rename` - Rename Go symbols with automatic JSON tag updates
  - Uses reflection-based handlers to avoid compile-time Go plugin dependency

### Changed

- **Universal Rename Tool** - `ide_refactor_rename` now works across ALL languages (Python, JavaScript, TypeScript, Go, etc.), not just Java/Kotlin
  - Uses IntelliJ's platform-level `RenameProcessor` which delegates to language-specific handlers
  - Language-specific name validation using `LanguageNamesValidation` (identifier rules, keyword detection)
  - Tool is now registered as a universal tool, available in all JetBrains IDEs
  - **Fully headless operation** - No popups or dialogs, suitable for autonomous AI agents
  - **Automatic related element renaming** - Getters/setters, overriding methods, test classes, constructor parameters ↔ fields, etc. are automatically renamed in a single atomic operation (no dialog)
  - Constructor parameter and matching field are automatically renamed together (no dialog)
  - Conflict detection before rename execution (returns error instead of showing dialog)

### Not Supported for Go

- `ide_find_implementations` - Go uses implicit interfaces (structural typing). Use `ide_type_hierarchy` with file+line+column instead to find types that satisfy an interface.
- `ide_find_super_methods` - Go has no inheritance. Methods don't override parent methods; Go uses composition via struct embedding.

### Removed

- Removed design specification files (`design.md`, `MultiIDEPlan.md`, `requirements.md`) - consolidated into CLAUDE.md

## [1.6.0] - 2025-12-01

### Added

- `maxResults` parameter for `ide_find_references` tool (default: 100, max: 500) - enables efficient searches in large codebases

### Changed

- **Performance: Optimized symbol search** - Introduced `OptimizedSymbolSearch` using IntelliJ's built-in "Go to Symbol" infrastructure with caching, word index, and prefix matching
- **Performance: Processor-based collection** - Replaced inefficient `.findAll()` calls with streaming `Processor` pattern for early termination and reduced memory usage
- **Performance: Non-blocking coroutines** - Refactored IntelliJ actions to use `Dispatchers.EDT` and platform `readAction` for improved UI responsiveness
- Symbol search handlers (Java, Python, JavaScript/TypeScript) now use the optimized platform-based search

### Fixed

- Language detection in Java handlers now correctly identifies Java/Kotlin elements
- Improved handling of large search result sets with proper early termination

## [1.5.0] - 2025-11-29

### Added

- **Multi-IDE Support** - Works with JetBrains IDEs: IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, DataGrip, Android Studio
- **Multi-Language Support** - Navigation tools now work with Java/Kotlin, Python, and JavaScript/TypeScript
- Agent rule tip panel with copy-to-clipboard in tool window
- Non-blocking operations for improved responsiveness

### Changed

- Tools reorganized: 4 universal tools (all IDEs), 5 navigation tools (language-dependent), 2 refactoring tools (Java only)

## [1.4.0] - 2025-11-28

### Added

- `ide_find_symbol` - New navigation tool to search for symbols (classes, methods, fields) by name
  - Supports substring and CamelCase fuzzy matching
  - Configurable result limit and library inclusion
- `ide_find_super_methods` - New navigation tool to find the full inheritance hierarchy of overridden methods
  - Shows all parent methods from interfaces and abstract classes
  - Returns hierarchy chain ordered by depth
- "Sync External Changes" setting to handle externally modified files
  - Enable when AI tools modify files and searches miss newly created content
- Reinstall command support for Claude Code CLI configuration

### Changed

- **BREAKING**: Server name changed from `intellij-index-mcp` to `jetbrains-index-mcp`
  - Update your client configurations to use the new server name
- Refactoring operations now execute immediately without confirmation dialog
  - Better suited for AI agent workflows
  - All operations still support undo via Ctrl/Cmd+Z
- Tool count increased from 9 to 11 with new navigation capabilities

### Removed

- **BREAKING**: MCP resources framework completely removed
  - `project://structure` - Use file exploration tools instead
  - `file://content/{path}` - Use standard file reading
  - `symbol://info/{fqn}` - Use `ide_find_symbol` or `ide_find_definition`
  - `index://status` - Use `ide_index_status` tool instead

## [1.3.0] - 2025-11-28

### Changed

- Reduced tool count from 13 to 9 for a more focused API
- Refactoring tools now limited to rename and safe delete

### Removed

- `ide_refactor_extract_method` - Complex refactoring removed for reliability
- `ide_refactor_extract_variable` - Complex refactoring removed for reliability
- `ide_refactor_inline` - Complex refactoring removed for reliability
- `ide_refactor_move` - Complex refactoring removed for reliability

## [1.2.0] - 2025-11-27

### Fixed

- Type hierarchy now shows supertypes even when PSI type resolution fails
- Call hierarchy now finds callers through interface/parent class references
- Call hierarchy handles unresolved method calls and parameter types gracefully

### Changed

- Extracted shared `findClassByName()` utility to `AbstractMcpTool` base class
- Improved error messages to include project name

## [1.1.0] - 2025-11-27

### Changed

- **BREAKING**: Reduced tool count from 20 to 13 for a more focused, reliable API
- Merged `ide_analyze_code` and `ide_list_quick_fixes` into new `ide_diagnostics` tool
  - Returns both code problems and available intentions in a single response
  - More efficient than making two separate calls

### Removed

- `ide_project_structure` - Functionality available through other IDE tools
- `ide_file_structure` - Functionality available through other IDE tools
- `ide_list_dependencies` - Functionality available through other IDE tools
- `ide_inspect_symbol` - Limited usefulness in practice
- `ide_code_completions` - Limited usefulness in practice
- `ide_analyze_code` - Merged into `ide_diagnostics`
- `ide_list_quick_fixes` - Merged into `ide_diagnostics`
- `ide_apply_quick_fix` - Removed due to EDT threading issues

### Added

- `ide_diagnostics` - New unified tool for code analysis
  - Returns problems with severity (ERROR, WARNING, WEAK_WARNING, INFO)
  - Returns available intentions/quick fixes at specified position
  - Supports optional line range filtering for problems

## [1.0.0] - 2025-11-27

### Added

- HTTP+SSE transport on IDE's built-in web server
    - SSE endpoint: `GET /index-mcp/sse`
    - JSON-RPC endpoint: `POST /index-mcp`
- JSON-RPC 2.0 protocol implementation
- Multi-project support with automatic project resolution
- `project_path` parameter for explicit project targeting

### Technical Details

- **Platform**: IntelliJ IDEA 2025.1+ (build 251+)
- **Language**: Kotlin 2.1+
- **Protocol**: MCP Specification 2024-11-05
- **Runtime**: JVM 21
- **Transport**: HTTP+SSE with JSON-RPC 2.0

[Unreleased]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v5.2.0...HEAD
[5.2.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v5.1.0...v5.2.0
[5.1.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v5.0.1...v5.1.0
[5.0.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v5.0.0...v5.0.1
[5.0.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.31.0...v5.0.0
[4.31.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.30.0...v4.31.0
[4.30.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.29.0...v4.30.0
[4.29.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.28.0...v4.29.0
[4.28.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.27.0...v4.28.0
[4.27.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.26.0...v4.27.0
[4.26.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.25.0...v4.26.0
[4.25.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.24.0...v4.25.0
[4.24.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.23.2...v4.24.0
[4.23.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.23.0...v4.23.2
[4.23.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.22.0...v4.23.0
[4.22.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.21.1...v4.22.0
[4.21.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.21.0...v4.21.1
[4.21.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.20.0...v4.21.0
[4.20.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.19.3...v4.20.0
[4.19.3]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.19.1...v4.19.3
[4.19.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.19.0...v4.19.1
[4.19.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.18.0...v4.19.0
[4.18.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.17.3...v4.18.0
[4.17.3]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.17.2...v4.17.3
[4.17.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.17.1...v4.17.2
[4.17.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.17.0...v4.17.1
[4.17.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.16.3...v4.17.0
[4.16.3]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.16.2...v4.16.3
[4.16.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.16.1...v4.16.2
[4.16.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.16.0...v4.16.1
[4.16.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.15.0...v4.16.0
[4.15.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.14.1...v4.15.0
[4.14.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.14.0...v4.14.1
[4.14.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.13.2...v4.14.0
[4.13.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.13.1...v4.13.2
[4.13.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.13.0...v4.13.1
[4.13.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.12.0...v4.13.0
[4.12.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.11.3...v4.12.0
[4.11.3]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.11.2...v4.11.3
[4.11.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.11.1...v4.11.2
[4.11.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.11.0...v4.11.1
[4.11.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.10.5...v4.11.0
[4.10.5]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.10.4...v4.10.5
[4.10.4]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.10.3...v4.10.4
[4.10.3]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.10.2...v4.10.3
[4.10.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.10.1...v4.10.2
[4.10.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.10.0...v4.10.1
[4.10.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.9.3...v4.10.0
[4.9.3]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.9.2...v4.9.3
[4.9.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.9.1...v4.9.2
[4.9.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.9.0...v4.9.1
[4.9.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.8.0...v4.9.0
[4.8.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.7.0...v4.8.0
[4.7.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.6.0...v4.7.0
[4.6.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.5.0...v4.6.0
[4.5.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.4.1...v4.5.0
[4.4.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.4.0...v4.4.1
[4.4.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.3.0...v4.4.0
[4.3.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.2.0...v4.3.0
[4.2.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.1.0...v4.2.0
[4.1.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.0.2...v4.1.0
[4.0.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.0.1...v4.0.2
[4.0.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v4.0.0...v4.0.1
[4.0.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.14.0...v4.0.0
[3.14.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.13.0...v3.14.0
[3.13.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.12.2...v3.13.0
[3.12.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.12.1...v3.12.2
[3.12.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.12.0...v3.12.1
[3.12.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.11.0...v3.12.0
[3.11.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.10.2...v3.11.0
[3.10.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.10.1...v3.10.2
[3.10.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.10.0...v3.10.1
[3.10.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.8.0...v3.10.0
[3.8.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.7.0...v3.8.0
[3.7.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.6.0...v3.7.0
[3.6.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.5.0...v3.6.0
[3.5.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.4.0...v3.5.0
[3.4.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.3.4...v3.4.0
[3.3.4]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.3.3...v3.3.4
[3.3.3]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.3.2...v3.3.3
[3.3.2]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.3.1...v3.3.2
[3.3.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.3.0...v3.3.1
[3.3.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.2.1...v3.3.0
[3.2.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.2.0...v3.2.1
[3.2.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.1.0...v3.2.0
[3.1.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.0.1...v3.1.0
[3.0.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v3.0.0...v3.0.1
[3.0.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v2.0.0...v3.0.0
[2.0.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.12.1...v2.0.0
[1.12.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.12.0...v1.12.1
[1.12.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.11.0...v1.12.0
[1.11.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.10.1...v1.11.0
[1.10.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.10.0...v1.10.1
[1.10.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.9.1...v1.10.0
[1.9.1]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.9.0...v1.9.1
[1.9.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.5.0...v1.6.0
[1.5.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/commits/v1.0.0

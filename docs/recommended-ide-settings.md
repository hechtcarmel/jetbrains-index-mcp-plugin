# Recommended IDE Settings for MCP Agent Workflows

When AI coding agents drive the IDE through MCP while a human is also using it (or while it runs unattended), several default IDE settings can cause modal dialogs or prompts that block the agent indefinitely. The plugin handles many of these automatically, but some are IDE-wide user preferences that only you can change.

These settings are **optional** — the plugin works without them. But each one prevents a specific class of interruption that can stall an agent session.

## IDE Settings

Open **Settings** (Ctrl+Alt+S / Cmd+,) and apply each change.

### Sync external file changes automatically

**Settings > Appearance & Behavior > System Settings > Synchronization**

- [x] Synchronize external changes when switching to the IDE window

When agents modify files externally (via `Write`, `Edit`, shell commands), the IDE needs to pick up those changes. With this off, switching to the IDE window may show a prompt instead of silently reloading. With it on, files are refreshed automatically on focus.

> **Why not built into the plugin?** This is an IDE-wide preference. Users on network mounts or large monorepos disable it deliberately for performance. The plugin provides `ide_sync_files` for on-demand refresh when the agent needs it.

### Suppress exit confirmation

**Settings > Appearance & Behavior > System Settings**

- [ ] Confirm application exit

The lifecycle manager may close projects automatically. An exit confirmation dialog would block that process.

### Open projects in the same window without asking

**Settings > Appearance & Behavior > System Settings**

- Open project in: **Current Window**

When `ide_open_project` or `ide_open_workspace` opens a project, a "New Window / This Window / Cancel" dialog blocks the tool call until answered. Setting this to "Current Window" (or "New Window" if you prefer separate windows) removes the prompt.

### Disable tips on startup

**Settings > Appearance & Behavior > System Settings**

- [ ] Show tips on startup

A tips dialog on startup is harmless for humans but can obscure the IDE window and potentially interfere with automated workflows.

## Registry Settings

Open the registry via **Help > Find Action** (Ctrl+Shift+A / Cmd+Shift+A), type "Registry", and search for:

### Disable non-project file write protection

Key: `ide.non.project.file.protection`
Value: `false`

When agents create or edit files outside the project's content roots (e.g., in a newly created directory not yet marked as a source root), the IDE shows a "Non-Project File Protection" dialog. Setting this to `false` suppresses it.

## VCS Settings

**Settings > Version Control > Confirmation**

### Suppress VCS add/remove dialogs

- When files are created: **Do not add**
- When files are deleted: **Do not remove**

Without these, every file the agent creates or deletes triggers a "Add to Git?" or "Remove from Git?" dialog. Agents manage VCS through the command line, so IDE-level VCS prompts are unnecessary.

## Build Tool Settings

### Auto-reload on build file changes

**Settings > Build, Execution, Deployment > Build Tools**

- Reload changes in build scripts: **Any changes**

When an agent modifies `pom.xml` or `build.gradle`, the IDE should automatically re-import the build model. Without this, a notification bar appears asking "Load Maven/Gradle changes" — the agent can't click it, so the project model goes stale.

## JVM Options

Edit the IDE's VM options via **Help > Edit Custom VM Options**.

### Increase heap for multi-project workloads

```
-Xmx8g
```

The default heap (2-4 GB) is sufficient for single projects. When running multiple projects simultaneously via `ide_open_workspace` or lifecycle management, increase it to avoid GC pressure that causes UI pauses.

## macOS-Specific

### Disable App Nap

Run in Terminal:

```bash
defaults write com.jetbrains.intellij NSAppSleepDisabled -bool YES
```

macOS aggressively suspends background applications. When the IDE is in the background while agents work through MCP, App Nap can pause the entire JVM — the UI becomes unresponsive even though the process is technically alive. The EDT heartbeat and external watchdog cannot distinguish this from a genuine freeze.

Restart IntelliJ after applying this change.

> **Note:** The bundle identifier varies by IDE. Use `com.jetbrains.intellij` for IntelliJ IDEA, `com.jetbrains.pycharm` for PyCharm, etc. Run `mdls -name kMDItemCFBundleIdentifier /Applications/IntelliJ\ IDEA.app` to find yours.

## Verification

After applying these settings, verify with:

```
ide_index_status → should return without prompts
ide_sync_files → should complete without dialogs
```

If the IDE still shows modal dialogs during agent operation, check the EDT heartbeat:

```
ide_index_status → edtResponsive: false means the EDT is blocked (likely by a dialog)
```

Report the specific dialog text as an issue — the plugin may be able to suppress it automatically in a future version.

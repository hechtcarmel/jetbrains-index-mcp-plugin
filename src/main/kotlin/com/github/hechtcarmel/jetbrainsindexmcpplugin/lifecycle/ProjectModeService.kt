package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.ide.PowerSaveMode
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
@State(name = "McpProjectModeService", storages = [Storage("mcp-lifecycle.xml", roamingType = RoamingType.DISABLED)])
class ProjectModeService : PersistentStateComponent<ProjectModeService.State>, Disposable {

    /**
     * The editor tabs a dormant transition closed in one project and has not yet given back.
     *
     * A plain bean (public no-arg constructor, mutable properties) so the component store can
     * serialize it: the set is persisted because the IDE saves a dormant project's workspace with
     * *no* open editors, so without it a restart — or a lifecycle close and reopen — would still
     * lose every tab the user had open (issue #369).
     */
    class DormantEditors(
        /** `VirtualFile.url`s in tab order; URLs rather than paths so library/jar entries survive. */
        var fileUrls: MutableList<String> = mutableListOf(),
        /** The tab that was selected when the editors were closed; reselected on restore. */
        var selectedFileUrl: String? = null
    )

    data class State(
        var closedProjectPaths: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        var managedProjectPaths: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        /** Project path → editors closed by the dormant transition and owed to the user. */
        var dormantEditors: MutableMap<String, DormantEditors> = ConcurrentHashMap()
    )

    /**
     * One running background→dormant countdown: what (re)started it and when it fires. Kept so
     * the log can say how long a project was really idle when the timer fires, and so tests can
     * observe that an MCP call pushed the deadline out (issue #369).
     */
    internal class IdleClock(val startedMs: Long, val deadlineMs: Long, val startedBy: String)

    private var persistedState = State()

    /** Live mode per project path. CLOSED paths are in persistedState only — the Project object no longer exists. */
    private val modes = ConcurrentHashMap<String, ProjectMode>()

    private val focusAlarms = ConcurrentHashMap<String, Alarm>()
    private val inactivityAlarms = ConcurrentHashMap<String, Alarm>()

    /** Path → the background→dormant countdown currently armed for it, if any. */
    private val idleClocks = ConcurrentHashMap<String, IdleClock>()

    /** Paths blocked from closing by the floor — awaiting event-driven flush. */
    private val pendingClose = ConcurrentHashMap.newKeySet<String>()

    /** Single safety-net alarm that runs the health check every 30 minutes. */
    private val healthAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var healthAlarmStarted = false

    override fun getState(): State = persistedState

    override fun loadState(state: State) {
        // IntelliJ collapses real paths to macros (e.g. $USER_HOME$) when persisting but does
        // not expand them back automatically for plain Set<String> fields — expand explicitly.
        val expandedClosed = state.closedProjectPaths.mapTo(ConcurrentHashMap.newKeySet(), ::expandMacros)
        val expandedManaged = state.managedProjectPaths.mapTo(ConcurrentHashMap.newKeySet(), ::expandMacros)
        val expandedEditors = ConcurrentHashMap<String, DormantEditors>()
        state.dormantEditors.forEach { (path, editors) ->
            expandedEditors[expandMacros(path)] = DormantEditors(
                fileUrls = editors.fileUrls.mapTo(mutableListOf(), ::expandMacros),
                selectedFileUrl = editors.selectedFileUrl?.let(::expandMacros)
            )
        }

        val ghostPaths = expandedManaged.filter { !java.io.File(it).isDirectory }
        if (ghostPaths.isNotEmpty()) {
            LOG.info("Pruning ${ghostPaths.size} managed project(s) whose path no longer exists on disk")
            ghostPaths.forEach { path ->
                expandedManaged.remove(path)
                expandedClosed.remove(path)
                LOG.info("  pruned: $path")
            }
        }
        // Remembered tabs belong to managed projects only; anything else is stale.
        expandedEditors.keys.retainAll(expandedManaged)

        persistedState = State(
            closedProjectPaths = expandedClosed,
            managedProjectPaths = expandedManaged,
            dormantEditors = expandedEditors
        )
        persistedState.closedProjectPaths.forEach { modes[it] = ProjectMode.CLOSED }
    }

    private fun expandMacros(path: String): String =
        path.replace("\$USER_HOME\$", System.getProperty("user.home"))

    override fun dispose() {
        focusAlarms.values.forEach { Disposer.dispose(it) }
        inactivityAlarms.values.forEach { Disposer.dispose(it) }
        Disposer.dispose(healthAlarm)
    }

    fun enroll(project: Project) {
        val path = project.basePath ?: return
        if (persistedState.managedProjectPaths.contains(path)) return
        persistedState.managedProjectPaths.add(path)
        // Start in ACTIVE if the project window currently has focus so the user doesn't
        // immediately lose inspections while they're working. Otherwise start in BACKGROUND.
        val hasFocus = WindowManager.getInstance().getFrame(project)?.isFocused == true
        modes[path] = if (hasFocus) ProjectMode.ACTIVE else ProjectMode.BACKGROUND
        ApplicationManager.getApplication().invokeLater { reconcilePowerSaveMode() }
        if (!hasFocus) scheduleInactivityTransition(project, "enrollment")
        val modeLabel = if (hasFocus) "active" else "background"
        val detail = if (hasFocus) {
            "window focused → active"
        } else {
            "window not focused → background; ${dormantRuleHint()}"
        }
        LifecycleEventLog.getInstance().log(
            LifecycleEventLog.Entry(project = project.name, path = path, event = "enroll", trigger = "mcp_call", detail = detail)
        )
        notify(project, "MCP enrolled '${project.name}' into lifecycle management ($modeLabel). " +
            "Project will sleep when idle.")

        startHealthAlarmIfNeeded()
        // Enrolling a new project is an opportunity to flush any projects that were
        // blocked from closing by the floor — now that we are above the floor again.
        flushPendingCloses()
    }

    fun release(project: Project) {
        val path = project.basePath ?: return
        release(path, project.name)
        ApplicationManager.getApplication().invokeLater { reconcilePowerSaveMode() }
    }

    fun release(path: String) {
        release(path, path.substringAfterLast("/"))
        // Don't toggle PowerSaveMode here — other managed projects may still need it.
        // releaseAll() handles the global reset after clearing everything.
    }

    private fun release(path: String, name: String) {
        persistedState.managedProjectPaths.remove(path)
        persistedState.closedProjectPaths.remove(path)
        modes.remove(path)
        cancelAllAlarms(path)
        disposeAlarms(path)
        // "Returning full control to the user" includes giving back the tabs dormant took.
        val openProject = ProjectManager.getInstance().openProjects
            .firstOrNull { !it.isDefault && it.basePath == path }
        if (openProject != null) {
            restoreDormantEditors(openProject, path, trigger = "release")
        } else {
            persistedState.dormantEditors.remove(path)
        }
        LifecycleEventLog.getInstance().log(
            LifecycleEventLog.Entry(project = name, path = path, event = "release", trigger = "mcp_call")
        )
    }

    fun releaseAll() {
        persistedState.managedProjectPaths.toList().forEach { release(it) }
        ApplicationManager.getApplication().invokeLater { PowerSaveMode.setEnabled(false) }
    }

    fun enrollAll(openProjects: List<Project>) {
        openProjects.filter { !it.isDefault && !isManaged(it) }.forEach { enroll(it) }
    }

    fun isManaged(project: Project): Boolean =
        project.basePath?.let { persistedState.managedProjectPaths.contains(it) } ?: false

    fun isManaged(path: String): Boolean = persistedState.managedProjectPaths.contains(path)

    fun getMode(project: Project): ProjectMode =
        project.basePath?.let { getMode(it) } ?: ProjectMode.BACKGROUND

    fun getMode(path: String): ProjectMode =
        modes[path] ?: if (persistedState.closedProjectPaths.contains(path)) ProjectMode.CLOSED
        else ProjectMode.BACKGROUND

    /** Returns path → mode for all managed projects, including those we closed. */
    fun getAllManagedModes(): Map<String, ProjectMode> =
        persistedState.managedProjectPaths.associateWith { getMode(it) }

    /** The background→dormant countdown armed for [path], or null when none is running. */
    internal fun idleClock(path: String): IdleClock? = idleClocks[path]

    /** Editor tabs a dormant transition closed for [path] and not yet restored, in tab order. */
    internal fun rememberedEditorUrls(path: String): List<String> =
        persistedState.dormantEditors[path]?.fileUrls?.toList() ?: emptyList()

    fun transition(project: Project, mode: ProjectMode, trigger: String = "mcp_call", detail: String? = null) {
        val path = project.basePath ?: return
        val previous = getMode(path)
        if (previous == mode) return
        modes[path] = mode
        LOG.debug("${project.name}: $previous → $mode")

        // For CLOSED, log after onClosed() resolves — it may keep the project dormant
        // instead of closing, and we want the log to reflect the actual outcome.
        if (mode != ProjectMode.CLOSED) {
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(
                    project = project.name,
                    path = path,
                    event = "transition",
                    from = previous.name.lowercase(),
                    to = mode.name.lowercase(),
                    trigger = trigger,
                    detail = detail
                )
            )
        }

        when (mode) {
            ProjectMode.ACTIVE -> onActive(project, path, trigger)
            ProjectMode.BACKGROUND -> onBackground(project, path)
            ProjectMode.DORMANT -> onDormant(project, path, trigger)
            ProjectMode.CLOSED -> onClosed(project, path, previous, trigger)
        }
    }

    /**
     * Wakes a dormant project for an incoming MCP call without reopening editors, and restarts
     * the background→dormant countdown: every tool call on a managed project is activity.
     */
    fun wakeForMcp(project: Project) {
        val path = project.basePath ?: return
        val woke = modes.replace(path, ProjectMode.DORMANT, ProjectMode.BACKGROUND)
        if (getMode(path) == ProjectMode.CLOSED) return
        if (woke) {
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(
                    project = project.name,
                    path = path,
                    event = "wake",
                    from = "dormant",
                    to = "background",
                    trigger = "mcp_call",
                    detail = "editor tabs stay closed until the window regains focus; ${dormantRuleHint()}"
                )
            )
        }
        resetInactivityTimer(project, "MCP call")
    }

    /**
     * The project window took focus: the user is back. Promotes a managed project to ACTIVE,
     * which also returns the editor tabs a dormant transition closed. When lifecycle automation
     * is switched off in Settings the mode is frozen, but tabs the manager closed earlier are
     * still owed — they come back regardless.
     */
    fun onWindowFocusGained(project: Project) {
        val path = project.basePath ?: return
        if (!isManaged(path)) return
        if (McpSettings.getInstance().lifecycleEnabled) {
            cancelFocusAlarm(project)
            transition(project, ProjectMode.ACTIVE, "focus_gained")
        } else {
            restoreDormantEditors(project, path, trigger = "focus_gained")
        }
    }

    /** The project window lost focus: start the focus→background countdown. */
    fun onWindowFocusLost(project: Project) {
        val path = project.basePath ?: return
        val settings = McpSettings.getInstance()
        if (!settings.lifecycleEnabled) return
        if (!isManaged(path)) return
        LifecycleEventLog.getInstance().log(
            LifecycleEventLog.Entry(
                project = project.name,
                path = path,
                event = "focus_lost",
                trigger = "focus_lost",
                detail = "background in ${settings.focusToBackgroundMinutes} min unless focus returns"
            )
        )
        scheduleFocusTransition(project)
    }

    fun scheduleFocusTransition(project: Project) {
        val settings = McpSettings.getInstance()
        if (!settings.lifecycleEnabled) return
        val path = project.basePath ?: return
        val minutes = settings.focusToBackgroundMinutes
        val alarm = focusAlarms.getOrPut(path) { Alarm(Alarm.ThreadToUse.POOLED_THREAD, this) }
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (project.isDisposed || !settings.lifecycleEnabled) return@addRequest
            transition(
                project, ProjectMode.BACKGROUND, "timer:focus",
                detail = "window unfocused for $minutes min; ${dormantRuleHint()}"
            )
        }, minutes * 60_000L)
    }

    fun cancelFocusAlarm(project: Project) {
        focusAlarms[project.basePath ?: return]?.cancelAllRequests()
    }

    /**
     * Restarts the background→dormant countdown, recording what did it ([startedBy]) for the
     * log. A no-op in ACTIVE: the user has the window, and nothing is counting down.
     */
    fun resetInactivityTimer(project: Project, startedBy: String = "MCP call") {
        val settings = McpSettings.getInstance()
        if (!settings.lifecycleEnabled) return
        if (getMode(project) == ProjectMode.ACTIVE) return
        scheduleInactivityTransition(project, startedBy)
    }

    private fun scheduleInactivityTransition(project: Project, startedBy: String) {
        val settings = McpSettings.getInstance()
        val path = project.basePath ?: return
        val delayMs = settings.backgroundToDormantMinutes * 60_000L
        val now = System.currentTimeMillis()
        val clock = IdleClock(startedMs = now, deadlineMs = now + delayMs, startedBy = startedBy)
        idleClocks[path] = clock
        val alarm = inactivityAlarms.getOrPut(path) { Alarm(Alarm.ThreadToUse.POOLED_THREAD, this) }
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (project.isDisposed || getMode(path) != ProjectMode.BACKGROUND) return@addRequest
            // Switching lifecycle management off in Settings must also stop a countdown that was
            // already armed — otherwise the last alarm still closes the user's editors.
            if (!settings.lifecycleEnabled) return@addRequest
            // A newer clock means activity re-armed the countdown after this request was queued
            // (the alarm cannot cancel a task that has already started); the newer one owns it.
            if (idleClocks[path] !== clock) return@addRequest
            idleClocks.remove(path, clock)
            transition(project, ProjectMode.DORMANT, "timer:inactivity", detail = idleDetail(clock))
        }, delayMs)
    }

    private fun idleDetail(clock: IdleClock): String {
        val idleFor = LifecycleEventLog.formatDuration(System.currentTimeMillis() - clock.startedMs)
        val since = LifecycleEventLog.formatTimeOfDay(clock.startedMs)
        return "no MCP call for $idleFor (countdown started by ${clock.startedBy} at $since); " +
            "editor tabs close now and reopen when the window regains focus"
    }

    /** The rule the background→dormant countdown follows, spelled out for the log. */
    private fun dormantRuleHint(): String {
        val minutes = McpSettings.getInstance().backgroundToDormantMinutes
        return "dormant after $minutes min without MCP calls (editor tabs close, reopen on next focus)"
    }

    private fun scheduleCloseTransition(project: Project) {
        val settings = McpSettings.getInstance()
        val path = project.basePath ?: return
        idleClocks.remove(path)
        val alarm = inactivityAlarms.getOrPut(path) { Alarm(Alarm.ThreadToUse.POOLED_THREAD, this) }
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (project.isDisposed || getMode(path) != ProjectMode.DORMANT) return@addRequest
            if (!settings.lifecycleEnabled) return@addRequest
            transition(project, ProjectMode.CLOSED, "timer:close")
        }, settings.dormantToClosedMinutes * 60_000L)
    }

    fun cancelAllAlarms(path: String) {
        focusAlarms[path]?.cancelAllRequests()
        inactivityAlarms[path]?.cancelAllRequests()
        idleClocks.remove(path)
    }

    /** Cancel and dispose alarms for a path, removing them from the maps entirely.
     *  Call on permanent release/close — getOrPut will create fresh ones if re-enrolled.
     *  Plain [cancelAllAlarms] should be used for transient state changes (ACTIVE→BACKGROUND)
     *  where alarms will be rescheduled shortly after. */
    private fun disposeAlarms(path: String) {
        focusAlarms.remove(path)?.let { runCatching { Disposer.dispose(it) } }
        inactivityAlarms.remove(path)?.let { runCatching { Disposer.dispose(it) } }
    }

    fun wasClosedByUs(path: String): Boolean = persistedState.closedProjectPaths.contains(path)

    /** Updates the registry without touching an open project window — used when re-registering after restart. */
    fun markClosed(path: String) {
        persistedState.closedProjectPaths.add(path)
        persistedState.managedProjectPaths.add(path)
        modes[path] = ProjectMode.CLOSED
    }

    fun markReopened(path: String) {
        val wasClosed = persistedState.closedProjectPaths.remove(path)
        // A window usually takes focus as it appears, so the focus listener may already have
        // promoted this project to ACTIVE before the opener gets here — never demote it, or the
        // user works in a focused window whose dormant countdown is running.
        modes.compute(path) { _, current -> if (current == ProjectMode.ACTIVE) current else ProjectMode.BACKGROUND }
        if (wasClosed) {
            val name = path.substringAfterLast("/")
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(project = name, path = path, event = "opened", trigger = "auto_open")
            )
        }
    }

    private fun onActive(project: Project, path: String, trigger: String) {
        cancelAllAlarms(path)
        ApplicationManager.getApplication().invokeLater { reconcilePowerSaveMode() }
        restoreDormantEditors(project, path, trigger)
    }

    private fun onBackground(project: Project, path: String) {
        cancelFocusAlarm(project)
        ApplicationManager.getApplication().invokeLater { reconcilePowerSaveMode() }
        scheduleInactivityTransition(project, "entering background")
    }

    /**
     * PSM is IDE-global, not per-project. Reconcile it after every state change:
     * PSM should be ON iff no managed project is currently ACTIVE.
     * Per-event toggling causes stuck-ON bugs when project B → BACKGROUND while A
     * is still ACTIVE (B's invokeLater enables PSM, A never re-disables it).
     */
    fun reconcilePowerSaveMode() {
        // No managed projects means lifecycle management is inactive — restore full IDE
        // capabilities. Without this, releasing the last managed project would compute
        // anyActive=false over an empty set and turn PSM ON permanently (nothing managed
        // remains to ever trigger another reconcile).
        if (persistedState.managedProjectPaths.isEmpty()) {
            PowerSaveMode.setEnabled(false)
            return
        }
        val anyActive = persistedState.managedProjectPaths.any { getMode(it) == ProjectMode.ACTIVE }
        PowerSaveMode.setEnabled(!anyActive)
    }

    private fun onDormant(project: Project, path: String, trigger: String) {
        // Cancel the focus alarm — if it fires after the inactivity alarm it would
        // immediately wake the project back to background, defeating dormant.
        cancelFocusAlarm(project)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val fem = FileEditorManager.getInstance(project)
            val open = fem.openFiles.toList()
            if (open.isNotEmpty()) {
                // Remember before closing: the IDE saves a workspace with no editors once they
                // are gone, so this list is the only record of what the user had open.
                rememberDormantEditors(path, open.map { it.url }, fem.selectedFiles.firstOrNull()?.url)
                open.forEach { fem.closeFile(it) }
                LifecycleEventLog.getInstance().log(
                    LifecycleEventLog.Entry(
                        project = project.name, path = path, event = "editors_closed", trigger = trigger,
                        detail = "${open.size} editor tab(s) closed; they reopen when the project window regains focus"
                    )
                )
            }
            // dropPsiCaches() is intentionally omitted: in IntelliJ 2025+ it internally
            // calls runWriteAction, which requires a write-safe context that invokeLater
            // scheduled from a pooled-thread Alarm does not provide. Closing editors
            // already releases the strong PSI references; the cache reclaims via GC.
        }
        scheduleCloseTransition(project)
    }

    /**
     * Records tabs closed by a dormant transition. Cycles accumulate: a project that went
     * dormant, was woken by MCP (tabs stay closed), had a file opened by `ide_open_file`, and
     * went dormant again owes the user both sets, so the union is kept until it is restored.
     */
    private fun rememberDormantEditors(path: String, fileUrls: List<String>, selectedFileUrl: String?) {
        persistedState.dormantEditors.compute(path) { _, existing ->
            val merged = LinkedHashSet<String>()
            existing?.fileUrls?.let { merged.addAll(it) }
            merged.addAll(fileUrls)
            DormantEditors(fileUrls = merged.toMutableList(), selectedFileUrl = selectedFileUrl ?: existing?.selectedFileUrl)
        }
    }

    /**
     * Reopens the tabs a dormant transition closed, on the EDT, and forgets them. Runs when the
     * user comes back to the project (or it leaves management) — never on an MCP wake: an agent
     * needs no editors, and reopening them would spend the memory dormant freed.
     */
    private fun restoreDormantEditors(project: Project, path: String, trigger: String) {
        val remembered = persistedState.dormantEditors.remove(path) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val fem = FileEditorManager.getInstance(project)
            val vfm = VirtualFileManager.getInstance()
            // Open the previously selected tab last so it ends up selected again.
            val selected = remembered.selectedFileUrl?.takeIf { it in remembered.fileUrls }
            val ordered = remembered.fileUrls.filter { it != selected } + listOfNotNull(selected)
            var reopened = 0
            for (url in ordered) {
                val file = vfm.findFileByUrl(url)?.takeIf { it.isValid } ?: continue
                if (fem.openFile(file, false).isNotEmpty()) reopened++
            }
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(
                    project = project.name, path = path, event = "editors_restored", trigger = trigger,
                    detail = "$reopened of ${remembered.fileUrls.size} editor tab(s) closed by dormant reopened"
                )
            )
        }
    }

    private fun onClosed(project: Project, path: String, previous: ProjectMode, trigger: String) {
        cancelAllAlarms(path)
        disposeAlarms(path)

        // Never close below the minimum. Rather than rescheduling the alarm (which hammers
        // the log every 10 min), add to pendingClose and wait for an event-driven flush.
        val min = runCatching { McpSettings.getInstance().minimumOpenProjects }.getOrDefault(4)
        val openManaged = ProjectManager.getInstance().openProjects
            .filter { !it.isDefault && isManaged(it) }
        if (openManaged.size <= min) {
            modes[path] = ProjectMode.DORMANT
            pendingClose.add(path)
            LOG.info("Keeping '${project.name}' dormant — added to pendingClose (floor=$min)")
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(
                    project = project.name, path = path,
                    event = "transition", from = previous.name.lowercase(), to = "dormant",
                    trigger = "last_project_kept"
                )
            )
            return
        }

        // Record the close only after it actually happens — closeAndDispose runs canClose,
        // which any ProjectCloseHandler (e.g. a terminate-running-processes prompt) can veto.
        ApplicationManager.getApplication().invokeLater {
            executeDeferredClose(project, path, previous.name.lowercase(), trigger)
        }
    }

    /**
     * Runs on the EDT. State is recorded according to the actual outcome of the close:
     * recording CLOSED before the attempt would leave the registry permanently disagreeing
     * with an open window when the close is vetoed (healthCheck would then report a false
     * "open but in closedProjectPaths" bug every cycle). A vetoed close falls back into the
     * existing pendingClose retry machinery.
     *
     * [closeAndDispose] exists as a seam for tests only — light test projects short-circuit
     * the platform's canClose veto check, so the veto outcome cannot be produced with a real
     * close in the test fixture. Production always uses the default.
     */
    internal fun executeDeferredClose(
        project: Project,
        path: String,
        previousModeLabel: String,
        trigger: String,
        closeAndDispose: (Project) -> Boolean = { ProjectManagerEx.getInstanceEx().closeAndDispose(it) }
    ) {
        if (project.isDisposed) return
        val projectName = project.name
        if (closeAndDispose(project)) {
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(
                    project = projectName, path = path,
                    event = "transition", from = previousModeLabel, to = "closed",
                    trigger = trigger
                )
            )
            markClosed(path)
            LOG.info("closed: $path")
        } else {
            modes[path] = ProjectMode.DORMANT
            pendingClose.add(path)
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(
                    project = projectName, path = path,
                    event = "transition", from = previousModeLabel, to = "dormant",
                    trigger = "close_vetoed"
                )
            )
            LOG.info("close vetoed, kept dormant: $path")
        }
    }

    // ── pendingClose: event-driven flush ────────────────────────────────────

    /** Flush projects that were blocked from closing by the floor, if they are now eligible. */
    fun flushPendingCloses() {
        if (pendingClose.isEmpty()) return
        val min = runCatching { McpSettings.getInstance().minimumOpenProjects }.getOrDefault(4)
        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDefault }
        val openByPath = openProjects.associateBy { normalizePath(it.basePath ?: "") }
        var current = openProjects.filter { isManaged(it) }.size

        // Remove stale entries first (project woke up or was closed externally)
        for (path in pendingClose.toList()) {
            val proj = openByPath[path]
            when {
                proj == null -> pendingClose.remove(path)
                getMode(path) != ProjectMode.DORMANT -> pendingClose.remove(path)
            }
        }

        // Close eligible pending projects (prefer most dormant — DORMANT only)
        for (path in pendingClose.toList()) {
            if (current <= min) break
            val proj = openByPath[path] ?: continue
            if (getMode(path) != ProjectMode.DORMANT) continue
            pendingClose.remove(path)
            transition(proj, ProjectMode.CLOSED, "pending_close_flushed")
            current--
        }
    }

    fun isInPendingClose(path: String): Boolean = pendingClose.contains(normalizePath(path))

    /** Called by ProjectLifecycleListener when a project is closed externally (not by us). */
    fun onProjectClosedExternally(path: String, name: String) {
        val normalized = normalizePath(path)
        if (pendingClose.remove(normalized)) {
            // Was waiting to close — now it is; mark properly if managed
            if (isManaged(normalized)) {
                markClosed(normalized)
                LifecycleEventLog.getInstance().log(
                    LifecycleEventLog.Entry(project = name, path = normalized, event = "closed", trigger = "user")
                )
            }
        }
        flushPendingCloses()
    }

    // ── Health check ─────────────────────────────────────────────────────────

    /**
     * Verifies lifecycle invariants and logs what was observed vs what is expected.
     * Distinguishes expected drift (floor/ceiling normal operation) from bugs
     * (open project in closedProjectPaths, pendingClose project not dormant, etc.).
     */
    fun healthCheck(trigger: String) {
        val min = runCatching { McpSettings.getInstance().minimumOpenProjects }.getOrDefault(4)
        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDefault }
        val openManaged = openProjects.filter { isManaged(it) }
        val openCount = openManaged.size
        val pendingCount = pendingClose.size

        LOG.info("Health check [trigger=$trigger]: open=$openCount, pending=$pendingCount, min=$min")

        val issues = mutableListOf<String>()
        val notes = mutableListOf<String>()

        if (openCount < min && pendingCount == 0 && persistedState.managedProjectPaths.isNotEmpty()) {
            // The manager never closes below the floor, so a count under it can only come from
            // windows closed by the user or by IDE shutdown — say so, or the note reads as if
            // the lifecycle manager unloaded them.
            notes.add(
                "open count ($openCount) below minimum ($min) — closed by the user or IDE shutdown, " +
                    "not by the lifecycle manager (it never closes below the floor), pending=$pendingCount"
            )
        }

        val openByPath = openProjects.associateBy { normalizePath(it.basePath ?: "") }
        for (path in pendingClose.toList()) {
            val proj = openByPath[path]
            when {
                proj == null -> issues.add("pendingClose '$path' not in openProjects — stale entry")
                getMode(path) != ProjectMode.DORMANT ->
                    issues.add("pendingClose '${proj.name}' is ${getMode(path)}, expected DORMANT")
            }
        }
        for (proj in openManaged) {
            val path = normalizePath(proj.basePath ?: "")
            if (persistedState.closedProjectPaths.contains(path)) {
                issues.add("'${proj.name}' is open but also in closedProjectPaths — state inconsistency")
            }
        }

        if (issues.isEmpty() && notes.isEmpty()) {
            LOG.debug("Health check: OK")
        } else {
            notes.forEach { LOG.info("  note: $it") }
            issues.forEach { LOG.warn("  ISSUE: $it") }
        }

        val outcome = when {
            issues.isNotEmpty() -> "bug:${issues.size} — ${issues.joinToString("; ")}"
            notes.isNotEmpty() -> "drift:${notes.joinToString("; ")}"
            else -> "ok"
        }
        LifecycleEventLog.getInstance().log(
            LifecycleEventLog.Entry(
                project = "health",
                path = "",
                event = "health_check",
                from = "open=$openCount/pending=$pendingCount",
                to = outcome,
                trigger = trigger
            )
        )

        flushPendingCloses()
    }

    private fun startHealthAlarmIfNeeded() {
        if (!healthAlarmStarted) {
            healthAlarmStarted = true
            scheduleNextHealthCheck()
        }
    }

    private fun scheduleNextHealthCheck() {
        healthAlarm.cancelAllRequests()
        healthAlarm.addRequest({
            healthCheck("safety_net_30min")
            scheduleNextHealthCheck()
        }, 30 * 60_000L)
    }

    private fun normalizePath(path: String) = path.trimEnd('/', '\\').replace('\\', '/')

    private fun notify(project: Project, message: String) {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Index MCP Server")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    companion object {
        private val LOG = logger<ProjectModeService>()
        fun getInstance(): ProjectModeService = service()
    }
}

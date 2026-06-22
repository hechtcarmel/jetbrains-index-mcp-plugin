package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.ide.PowerSaveMode
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
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
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.components.RoamingType
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
@State(name = "McpProjectModeService", storages = [Storage("mcp-lifecycle.xml", roamingType = RoamingType.DISABLED)])
class ProjectModeService : PersistentStateComponent<ProjectModeService.State>, Disposable {

    data class State(
        var closedProjectPaths: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        var managedProjectPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    )

    private var persistedState = State()

    /** Live mode per project path. CLOSED paths are in persistedState only — the Project object no longer exists. */
    private val modes = ConcurrentHashMap<String, ProjectMode>()

    private val focusAlarms = ConcurrentHashMap<String, Alarm>()
    private val inactivityAlarms = ConcurrentHashMap<String, Alarm>()

    /** Paths blocked from closing by the floor — awaiting event-driven flush. */
    private val pendingClose = ConcurrentHashMap.newKeySet<String>()

    /** Single safety-net alarm that runs the health check every 30 minutes. */
    private val healthAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var healthAlarmStarted = false

    override fun getState(): State = persistedState

    override fun loadState(state: State) {
        // IntelliJ collapses real paths to macros (e.g. $USER_HOME$) when persisting but does
        // not expand them back automatically for plain Set<String> fields — expand explicitly.
        persistedState = State(
            closedProjectPaths = state.closedProjectPaths.mapTo(ConcurrentHashMap.newKeySet(), ::expandMacros),
            managedProjectPaths = state.managedProjectPaths.mapTo(ConcurrentHashMap.newKeySet(), ::expandMacros)
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
        if (!hasFocus) scheduleInactivityTransition(project)
        val modeLabel = if (hasFocus) "active" else "background"
        LifecycleEventLog.getInstance().log(
            LifecycleEventLog.Entry(project = project.name, path = path, event = "enroll", trigger = "mcp_call")
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

    fun transition(project: Project, mode: ProjectMode, trigger: String = "mcp_call") {
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
                    trigger = trigger
                )
            )
        }

        when (mode) {
            ProjectMode.ACTIVE -> onActive(project, path)
            ProjectMode.BACKGROUND -> onBackground(project, path)
            ProjectMode.DORMANT -> onDormant(project, path)
            ProjectMode.CLOSED -> onClosed(project, path, previous, trigger)
        }
    }

    /** Wakes a dormant project for an incoming MCP call without reopening editors. */
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
                    trigger = "mcp_call"
                )
            )
        }
        resetInactivityTimer(project)
    }

    fun scheduleFocusTransition(project: Project) {
        val settings = McpSettings.getInstance()
        if (!settings.lifecycleEnabled) return
        val path = project.basePath ?: return
        val alarm = focusAlarms.getOrPut(path) { Alarm(Alarm.ThreadToUse.POOLED_THREAD, this) }
        alarm.cancelAllRequests()
        alarm.addRequest(
            { transition(project, ProjectMode.BACKGROUND, "timer:focus") },
            settings.focusToBackgroundMinutes * 60_000L
        )
    }

    fun cancelFocusAlarm(project: Project) {
        focusAlarms[project.basePath ?: return]?.cancelAllRequests()
    }

    fun resetInactivityTimer(project: Project) {
        val settings = McpSettings.getInstance()
        if (!settings.lifecycleEnabled) return
        if (getMode(project) == ProjectMode.ACTIVE) return
        scheduleInactivityTransition(project)
    }

    private fun scheduleInactivityTransition(project: Project) {
        val settings = McpSettings.getInstance()
        val path = project.basePath ?: return
        val alarm = inactivityAlarms.getOrPut(path) { Alarm(Alarm.ThreadToUse.POOLED_THREAD, this) }
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed && getMode(path) == ProjectMode.BACKGROUND) {
                transition(project, ProjectMode.DORMANT, "timer:inactivity")
            }
        }, settings.backgroundToDormantMinutes * 60_000L)
    }

    private fun scheduleCloseTransition(project: Project) {
        val settings = McpSettings.getInstance()
        val path = project.basePath ?: return
        val alarm = inactivityAlarms.getOrPut(path) { Alarm(Alarm.ThreadToUse.POOLED_THREAD, this) }
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed && getMode(path) == ProjectMode.DORMANT) {
                transition(project, ProjectMode.CLOSED, "timer:close")
            }
        }, settings.dormantToClosedMinutes * 60_000L)
    }

    fun cancelAllAlarms(path: String) {
        focusAlarms[path]?.cancelAllRequests()
        inactivityAlarms[path]?.cancelAllRequests()
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
        modes[path] = ProjectMode.BACKGROUND
        if (wasClosed) {
            val name = path.substringAfterLast("/")
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(project = name, path = path, event = "opened", trigger = "auto_open")
            )
        }
    }

    private fun onActive(project: Project, path: String) {
        cancelAllAlarms(path)
        ApplicationManager.getApplication().invokeLater { reconcilePowerSaveMode() }
    }

    private fun onBackground(project: Project, path: String) {
        cancelFocusAlarm(project)
        ApplicationManager.getApplication().invokeLater { reconcilePowerSaveMode() }
        scheduleInactivityTransition(project)
    }

    /**
     * PSM is IDE-global, not per-project. Reconcile it after every state change:
     * PSM should be ON iff no managed project is currently ACTIVE.
     * Per-event toggling causes stuck-ON bugs when project B → BACKGROUND while A
     * is still ACTIVE (B's invokeLater enables PSM, A never re-disables it).
     */
    fun reconcilePowerSaveMode() {
        val anyActive = persistedState.managedProjectPaths.any { getMode(it) == ProjectMode.ACTIVE }
        PowerSaveMode.setEnabled(!anyActive)
    }

    private fun onDormant(project: Project, path: String) {
        // Cancel the focus alarm — if it fires after the inactivity alarm it would
        // immediately wake the project back to background, defeating dormant.
        cancelFocusAlarm(project)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                val fem = FileEditorManager.getInstance(project)
                fem.openFiles.forEach { fem.closeFile(it) }
                // dropPsiCaches() is intentionally omitted: in IntelliJ 2025+ it internally
                // calls runWriteAction, which requires a write-safe context that invokeLater
                // scheduled from a pooled-thread Alarm does not provide. Closing editors
                // already releases the strong PSI references; the cache reclaims via GC.
            }
        }
        scheduleCloseTransition(project)
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

        // Log the actual close now that we've confirmed it will happen.
        LifecycleEventLog.getInstance().log(
            LifecycleEventLog.Entry(
                project = project.name, path = path,
                event = "transition", from = previous.name.lowercase(), to = "closed",
                trigger = trigger
            )
        )
        markClosed(path)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ProjectManagerEx.getInstanceEx().closeAndDispose(project)
                LOG.info("closed: $path")
            }
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
            notes.add("open count ($openCount) below minimum ($min) — some projects fully closed, pending=$pendingCount")
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

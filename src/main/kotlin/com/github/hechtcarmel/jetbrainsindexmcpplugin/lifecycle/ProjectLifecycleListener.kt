package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Hooks into project open/close events to keep [ProjectModeService] consistent:
 *
 * - On close: if the project was in [ProjectModeService.pendingClose], marks it properly
 *   as closed and flushes the pending set. Triggers a health check so any state drift
 *   from the user closing a window externally is detected and logged.
 *
 * - Open events are handled by [ProjectFocusActivity] which runs per-project at startup
 *   and already triggers [ProjectModeService.flushPendingCloses].
 */
class ProjectLifecycleListener : ProjectManagerListener {

    override fun projectClosing(project: Project) {
        if (project.isDefault) return
        val modeService = runCatching { ProjectModeService.getInstance() }.getOrNull() ?: return

        if (modeService.isManaged(project)) {
            // Managed project closing — could be user or lifecycle manager.
            // Delegate to service: if it was in pendingClose, mark it; then health-check.
            modeService.onProjectClosedExternally(project.basePath ?: "", project.name)
            modeService.healthCheck("project_closed:${project.name}")
        } else {
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(
                    project = project.name,
                    path = project.basePath ?: "",
                    event = "closed",
                    trigger = "user"
                )
            )
        }
    }
}

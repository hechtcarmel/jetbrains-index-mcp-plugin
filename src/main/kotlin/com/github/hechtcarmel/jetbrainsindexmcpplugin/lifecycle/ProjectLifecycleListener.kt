package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Hooks into project close events to keep [ProjectModeService] consistent.
 *
 * On close: if the project was managed, marks it closed and schedules a health
 * check after the project is fully disposed (running it inside projectClosing
 * would produce false positives because the project is still in openProjects
 * while closedProjectPaths already contains it).
 *
 * Restart re-enrollment is handled by [ProjectFocusActivity], which runs when
 * the project frame is ready and calls markReopened + resetInactivityTimer.
 */
class ProjectLifecycleListener : ProjectManagerListener {

    override fun projectClosing(project: Project) {
        if (project.isDefault) return
        ProjectResolver.onProjectClosed(project)
        val modeService = runCatching { ProjectModeService.getInstance() }.getOrNull() ?: return

        if (modeService.isManaged(project)) {
            val name = project.name
            modeService.onProjectClosedExternally(project.basePath ?: "", name)
            // Defer health check until after the project is fully disposed — otherwise the
            // project is still in openProjects while closedProjectPaths already contains it,
            // which the health check would incorrectly report as a bug.
            ApplicationManager.getApplication().invokeLater {
                modeService.healthCheck("project_closed:$name")
            }
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

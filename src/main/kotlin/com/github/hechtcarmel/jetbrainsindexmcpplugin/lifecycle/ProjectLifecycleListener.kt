package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Hooks into project open/close events to keep [ProjectModeService] consistent.
 *
 * On close: if the project was in [ProjectModeService.pendingClose], marks it properly
 * as closed and flushes the pending set. The health check is scheduled via invokeLater
 * so it runs after the project is fully disposed — running it inside projectClosing
 * would produce false positives because the project is still in openProjects at that point
 * even though markClosed has already been called.
 */
class ProjectLifecycleListener : ProjectManagerListener {

    override fun projectClosing(project: Project) {
        if (project.isDefault) return
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

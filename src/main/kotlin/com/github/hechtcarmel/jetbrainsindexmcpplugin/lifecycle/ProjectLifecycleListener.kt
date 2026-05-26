package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Logs close events for projects not managed by [ProjectModeService].
 * Managed projects log their own close events inside [ProjectModeService.transition].
 * Open events are logged from [ProjectFocusActivity] which runs for every project.
 */
class ProjectLifecycleListener : ProjectManagerListener {

    override fun projectClosing(project: Project) {
        if (project.isDefault) return
        val modeService = runCatching { ProjectModeService.getInstance() }.getOrNull() ?: return
        if (modeService.isManaged(project)) return
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

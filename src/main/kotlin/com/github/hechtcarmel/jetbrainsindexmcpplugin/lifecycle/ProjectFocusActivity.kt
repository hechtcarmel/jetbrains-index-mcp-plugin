package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

class ProjectFocusActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val frame = WindowManager.getInstance().getFrame(project) ?: run {
            LOG.warn("No frame found for project ${project.name}, skipping focus listener")
            return
        }

        frame.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) {
                val modeService = ProjectModeService.getInstance()
                if (!McpSettings.getInstance().lifecycleEnabled) return
                if (!modeService.isManaged(project)) return
                modeService.cancelFocusAlarm(project)
                modeService.transition(project, ProjectMode.ACTIVE, "focus_gained")
            }

            override fun windowLostFocus(e: WindowEvent) {
                val modeService = ProjectModeService.getInstance()
                if (!McpSettings.getInstance().lifecycleEnabled) return
                if (!modeService.isManaged(project)) return
                LifecycleEventLog.getInstance().log(
                    LifecycleEventLog.Entry(
                        project = project.name,
                        path = project.basePath ?: "",
                        event = "focus_lost",
                        trigger = "focus_lost"
                    )
                )
                modeService.scheduleFocusTransition(project)
            }
        })

        val modeService = ProjectModeService.getInstance()
        if (McpSettings.getInstance().lifecycleEnabled && modeService.isManaged(project)) {
            modeService.markReopened(project.basePath ?: return)
            modeService.resetInactivityTimer(project)
        } else {
            LifecycleEventLog.getInstance().log(
                LifecycleEventLog.Entry(
                    project = project.name,
                    path = project.basePath ?: "",
                    event = "opened",
                    trigger = "user"
                )
            )
        }
        // A new project window is open — some pendingClose projects may now be eligible to close.
        modeService.flushPendingCloses()
    }

    companion object {
        private val LOG = logger<ProjectFocusActivity>()
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

class ProjectFocusActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Attach the focus listener via invokeLater so the frame is available.
        // If the frame is still null (headless/test env or very early startup),
        // retry exactly once — no infinite loop.
        ApplicationManager.getApplication().invokeLater {
            registerFocusListener(project, retry = true)
        }
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

    private fun registerFocusListener(project: Project, retry: Boolean = false) {
        if (project.isDisposed) return
        val frame = WindowManager.getInstance().getFrame(project) ?: run {
            // Frame not ready yet. Retry once more if this is the first attempt.
            // No further retry after that — headless/test environments never have a frame.
            if (retry) ApplicationManager.getApplication().invokeLater {
                registerFocusListener(project, retry = false)
            }
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
    }

    companion object {
        private val LOG = logger<ProjectFocusActivity>()
    }
}

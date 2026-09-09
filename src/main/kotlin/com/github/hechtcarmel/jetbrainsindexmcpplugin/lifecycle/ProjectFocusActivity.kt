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
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode) {
            LOG.info("Skipping project focus lifecycle activity in unit test mode")
            return
        }

        if (!application.isHeadlessEnvironment) {
            application.invokeLater {
                registerFocusListener(project, retry = true)
            }
        }
        val modeService = ProjectModeService.getInstance()
        if (McpSettings.getInstance().lifecycleEnabled && modeService.isManaged(project)) {
            modeService.markReopened(project.basePath ?: return)
            modeService.resetInactivityTimer(project, "project open")
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
                ProjectModeService.getInstance().onWindowFocusGained(project)
            }

            override fun windowLostFocus(e: WindowEvent) {
                ProjectModeService.getInstance().onWindowFocusLost(project)
            }
        })
        // A window that took focus before the listener existed never reports it. On an IDE
        // restart the restored frame is usually focused by the time this runs, so a managed
        // project would sit in BACKGROUND with its dormant countdown running while the user
        // works in it — and lose its editor tabs a couple of minutes later (issue #369).
        // Catch up on the focus state the listener missed.
        if (frame.isFocused) {
            ProjectModeService.getInstance().onWindowFocusGained(project)
        }
    }

    companion object {
        private val LOG = logger<ProjectFocusActivity>()
    }
}

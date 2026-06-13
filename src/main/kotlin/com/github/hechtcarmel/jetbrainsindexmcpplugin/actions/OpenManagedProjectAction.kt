package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectMode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SimpleListCellRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.file.Path
import kotlin.coroutines.resume

class OpenManagedProjectAction : AnAction("MCP: Open Project") {

    override fun actionPerformed(e: AnActionEvent) {
        val currentProject = e.project ?: return
        val modeService = ProjectModeService.getInstance()
        val allModes = modeService.getAllManagedModes()

        if (allModes.isEmpty()) {
            Messages.showInfoMessage(
                currentProject,
                "No projects are currently managed by MCP lifecycle.",
                "MCP: Open Project"
            )
            return
        }

        val entries = allModes.entries
            .sortedBy { (_, mode) ->
                when (mode) {
                    ProjectMode.CLOSED -> 0
                    ProjectMode.DORMANT -> 1
                    ProjectMode.BACKGROUND -> 2
                    ProjectMode.ACTIVE -> 3
                }
            }
            .map { (path, mode) -> ManagedProjectEntry(path, path.substringAfterLast("/"), mode) }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setTitle("MCP Managed Projects")
            .setRenderer(ManagedProjectRenderer())
            .setItemChosenCallback { entry -> openOrWake(entry, modeService) }
            .createPopup()

        val frame = WindowManager.getInstance().getFrame(currentProject)
        if (frame != null) popup.showInCenterOf(frame) else popup.showInFocusCenter()
    }

    private fun openOrWake(entry: ManagedProjectEntry, modeService: ProjectModeService) {
        when (entry.mode) {
            ProjectMode.CLOSED -> {
                // openProjectAsync is a suspend function — run it on a pooled thread.
                ApplicationManager.getApplication().executeOnPooledThread {
                    runBlocking {
                        val openTask = OpenProjectTask.build().withForceOpenInNewFrame(true)
                        val opened = ProjectManagerEx.getInstanceEx().openProjectAsync(
                            Path.of(entry.path), openTask
                        ) ?: return@runBlocking
                        modeService.markReopened(entry.path)
                        suspendCancellableCoroutine { continuation ->
                            ApplicationManager.getApplication().invokeLater({
                                if (!opened.isDisposed) {
                                    DumbService.getInstance(opened).runWhenSmart {
                                        if (!continuation.isCompleted) continuation.resume(Unit)
                                    }
                                } else {
                                    continuation.cancel()
                                }
                            }, ModalityState.nonModal())
                        }
                        modeService.transition(opened, ProjectMode.ACTIVE)
                    }
                }
            }
            else -> {
                ProjectManager.getInstance().openProjects
                    .find { it.basePath == entry.path }
                    ?.let { project ->
                        if (!modeService.isManaged(project)) modeService.enroll(project)
                        modeService.transition(project, ProjectMode.ACTIVE)
                    }
            }
        }
    }

    data class ManagedProjectEntry(val path: String, val name: String, val mode: ProjectMode)

    private class ManagedProjectRenderer : SimpleListCellRenderer<ManagedProjectEntry>() {
        override fun customize(
            list: javax.swing.JList<out ManagedProjectEntry>,
            value: ManagedProjectEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            value ?: return
            val modeLabel = when (value.mode) {
                ProjectMode.ACTIVE -> "[active]"
                ProjectMode.BACKGROUND -> "[background]"
                ProjectMode.DORMANT -> "[dormant]"
                ProjectMode.CLOSED -> "[closed]"
            }
            text = "${value.name}  $modeLabel"
            toolTipText = value.path
        }
    }
}

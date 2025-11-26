package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class ExportHistoryAction : AnAction(
    McpBundle.message("toolWindow.exportHistory"),
    "Export command history to file",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val historyService = CommandHistoryService.getInstance(project)

        val descriptor = FileSaverDescriptor(
            "Export Command History",
            "Save command history to file",
            "json", "csv"
        )

        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, "mcp-history")

        wrapper?.let { vfw ->
            val file = vfw.file
            val extension = file.extension.lowercase()

            val content = when (extension) {
                "csv" -> historyService.exportToCsv()
                else -> historyService.exportToJson()
            }

            file.writeText(content)

            NotificationGroupManager.getInstance()
                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                    McpBundle.message("notification.historyCopied", file.path),
                    NotificationType.INFORMATION
                )
                .notify(project)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

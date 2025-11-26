package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

class CopyServerUrlAction : AnAction(
    McpBundle.message("toolWindow.copyUrl"),
    "Copy the MCP server URL to clipboard",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val url = McpServerService.getInstance().getServerUrl()
        CopyPasteManager.getInstance().setContents(StringSelection(url))

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Index MCP Server")
            .createNotification(
                McpBundle.message("notification.urlCopied"),
                NotificationType.INFORMATION
            )
            .notify(e.project)
    }
}

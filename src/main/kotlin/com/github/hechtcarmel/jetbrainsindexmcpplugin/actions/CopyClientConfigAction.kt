package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.mcpserver.settings.McpServerSettingsConfigurable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Opens JetBrains' built-in MCP setup so users can configure clients natively.
 */
class CopyClientConfigAction : AnAction() {

    init {
        templatePresentation.text = "Open Built-in MCP Setup"
        templatePresentation.description = "Open JetBrains MCP Server setup and documentation"
        templatePresentation.icon = AllIcons.General.Settings
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = true
        e.presentation.text = "Open Built-in MCP Setup"
    }

    override fun actionPerformed(e: AnActionEvent) {
        runCatching {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, McpServerSettingsConfigurable::class.java)
        }.onFailure {
            BrowserUtil.browse(McpConstants.MCP_HELP_URL)
            NotificationGroupManager.getInstance()
                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                    "Opened MCP Documentation",
                    "JetBrains MCP Server settings were unavailable, so the official setup guide was opened instead.",
                    NotificationType.INFORMATION
                )
                .notify(e.project)
        }
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.startup

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.IdeProductInfo
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

class McpServerStartupActivity : ProjectActivity {

    companion object {
        private val LOG = logger<McpServerStartupActivity>()
    }

    override suspend fun execute(project: Project) {
        LOG.info("MCP Server startup activity executing for project: ${project.name}")

        try {
            // Check for v2.0.0 migration
            val settings = McpSettings.getInstance()
            if (settings.needsV2Migration()) {
                showMigrationNotification(project)
                settings.markV2MigrationComplete()
            }

            // Initialize the MCP server service (this triggers tool registration)
            val mcpService = McpServerService.getInstance()
            val serverUrl = mcpService.getServerUrl()
            val serverError = mcpService.getServerError()

            if (serverError != null) {
                // Server failed to start (e.g., port in use)
                LOG.warn("MCP Server failed to start: ${serverError.message}")
                // Notification is already shown by McpServerService
            } else if (serverUrl != null) {
                LOG.info("MCP Server available at: $serverUrl")

                // Show notification only for fresh installs (not migrations)
                if (!settings.needsV2Migration()) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                        .createNotification(
                            McpConstants.PLUGIN_NAME,
                            McpBundle.message("notification.serverStarted", serverUrl),
                            NotificationType.INFORMATION
                        )
                        .notify(project)
                }
            }

        } catch (e: Exception) {
            LOG.error("Failed to start MCP Server", e)

            NotificationGroupManager.getInstance()
                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                .createNotification(
                    McpConstants.PLUGIN_NAME,
                    McpBundle.message("notification.serverError", e.message ?: "Unknown error"),
                    NotificationType.ERROR
                )
                .notify(project)
        }
    }

    /**
     * Shows a notification to users upgrading from v1.x about the breaking changes.
     */
    private fun showMigrationNotification(project: Project) {
        val serverName = IdeProductInfo.getServerName()
        val port = McpSettings.getInstance().serverPort

        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
            .createNotification(
                "MCP Server v2.0 - Reinstall Required",
                """
                    The server URL has changed. Please reinstall using the <b>"Install on Coding Agents"</b> button in the Index MCP Server tool window.
                    <br><br>
                    <b>New server name:</b> <code>$serverName</code><br>
                    <b>New port:</b> <code>$port</code>
                """.trimIndent(),
                NotificationType.WARNING
            )
            .addAction(object : NotificationAction("Open Tool Window") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    ToolWindowManager.getInstance(project)
                        .getToolWindow(McpConstants.TOOL_WINDOW_ID)
                        ?.show()
                    notification.expire()
                }
            })
            .notify(project)
    }
}

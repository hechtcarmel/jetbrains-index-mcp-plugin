package com.github.hechtcarmel.jetbrainsindexmcpplugin.startup

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class McpServerStartupActivity : ProjectActivity {

    companion object {
        private val LOG = logger<McpServerStartupActivity>()
    }

    override suspend fun execute(project: Project) {
        LOG.info("MCP Server startup activity executing for project: ${project.name}")

        try {
            // Initialize the MCP server service (this triggers tool registration)
            val mcpService = McpServerService.getInstance()
            val serverUrl = mcpService.getServerUrl()

            LOG.info("MCP Server available at: $serverUrl")

            // Show notification
            NotificationGroupManager.getInstance()
                .getNotificationGroup("MCP Server")
                .createNotification(
                    "MCP Server",
                    McpBundle.message("notification.serverStarted", serverUrl),
                    NotificationType.INFORMATION
                )
                .notify(project)

        } catch (e: Exception) {
            LOG.error("Failed to start MCP Server", e)

            NotificationGroupManager.getInstance()
                .getNotificationGroup("MCP Server")
                .createNotification(
                    "MCP Server",
                    McpBundle.message("notification.serverError", e.message ?: "Unknown error"),
                    NotificationType.ERROR
                )
                .notify(project)
        }
    }
}

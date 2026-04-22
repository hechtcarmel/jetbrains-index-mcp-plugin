package com.github.hechtcarmel.jetbrainsindexmcpplugin.startup

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class McpServerStartupActivity : ProjectActivity {

    companion object {
        private val LOG = logger<McpServerStartupActivity>()
    }

    override suspend fun execute(project: Project) {
        LOG.info("MCP Server startup activity executing for project: ${project.name}")

        if (ApplicationManager.getApplication().isUnitTestMode) {
            return
        }

        try {
            BuildDiagnosticsCacheService.getInstance(project).initialize()

            // McpServerService self-initializes asynchronously from its constructor (see issue #73).
            // This call is a redundant safety net — initialize() is idempotent.
            val mcpService = McpServerService.getInstance()
            mcpService.initialize()
            val serverUrl = mcpService.getServerUrl()
            val serverError = mcpService.getServerError()

            if (serverError != null) {
                LOG.warn("MCP Server failed to start: ${serverError.message}")
            } else if (serverUrl != null) {
                LOG.info("MCP Server available at: $serverUrl")

                NotificationGroupManager.getInstance()
                    .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                    .createNotification(
                        McpConstants.PLUGIN_NAME,
                        McpBundle.message("notification.serverStarted", serverUrl),
                        NotificationType.INFORMATION
                    )
                    .notify(project)
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
}

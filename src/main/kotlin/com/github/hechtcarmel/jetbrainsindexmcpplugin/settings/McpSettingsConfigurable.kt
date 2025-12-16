package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class McpSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var maxHistorySizeSpinner: JSpinner? = null
    private var serverPortSpinner: JSpinner? = null
    private var syncExternalChangesCheckBox: JBCheckBox? = null
    private val toolCheckBoxes = mutableMapOf<String, JBCheckBox>()

    override fun getDisplayName(): String = McpBundle.message("settings.title")

    override fun createComponent(): JComponent {
        maxHistorySizeSpinner = JSpinner(SpinnerNumberModel(100, 10, 10000, 10))
        serverPortSpinner = JSpinner(SpinnerNumberModel(McpConstants.getDefaultServerPort(), 1024, 65535, 1)).apply {
            toolTipText = McpBundle.message("settings.serverPort.tooltip")
        }
        syncExternalChangesCheckBox = JBCheckBox(McpBundle.message("settings.syncExternalChanges")).apply {
            toolTipText = McpBundle.message("settings.syncExternalChanges.tooltip")
        }

        val warningLabel = JBLabel(McpBundle.message("settings.syncExternalChanges.warning")).apply {
            foreground = JBColor.RED
        }

        val syncPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val checkboxRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(syncExternalChangesCheckBox)
            }
            add(checkboxRow)
            val warningRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(24), 0)).apply {
                add(warningLabel)
            }
            add(warningRow)
        }

        val toolsPanel = createToolsPanel()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(McpBundle.message("settings.serverPort") + ":"), serverPortSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.maxHistorySize") + ":"), maxHistorySizeSpinner!!, 1, false)
            .addComponent(syncPanel, 1)
            .addSeparator(10)
            .addComponent(JBLabel(McpBundle.message("settings.tools.title")), 5)
            .addComponent(toolsPanel, 5)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    private fun createToolsPanel(): JComponent {
        val toolsContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val toolRegistry = McpServerService.getInstance().getToolRegistry()
        val allTools = toolRegistry.getAllToolDefinitions().sortedBy { it.name }
        val settings = McpSettings.getInstance()

        for (tool in allTools) {
            val checkbox = JBCheckBox(tool.name, settings.isToolEnabled(tool.name)).apply {
                toolTipText = tool.description
            }
            toolCheckBoxes[tool.name] = checkbox
            toolsContainer.add(checkbox)
        }

        return toolsContainer
    }

    override fun isModified(): Boolean {
        val settings = McpSettings.getInstance()

        if (serverPortSpinner?.value != settings.serverPort ||
            maxHistorySizeSpinner?.value != settings.maxHistorySize ||
            syncExternalChangesCheckBox?.isSelected != settings.syncExternalChanges) {
            return true
        }

        for ((toolName, checkbox) in toolCheckBoxes) {
            if (checkbox.isSelected != settings.isToolEnabled(toolName)) {
                return true
            }
        }

        return false
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val settings = McpSettings.getInstance()
        val oldPort = settings.serverPort
        val newPort = serverPortSpinner?.value as? Int ?: McpConstants.getDefaultServerPort()

        // Validate port availability before applying (only if port changed)
        if (newPort != oldPort && !isPortAvailable(newPort)) {
            throw ConfigurationException(
                "Port $newPort is already in use. Please choose a different port.",
                "Port Unavailable"
            )
        }

        settings.serverPort = newPort
        settings.maxHistorySize = maxHistorySizeSpinner?.value as? Int ?: 100
        settings.syncExternalChanges = syncExternalChangesCheckBox?.isSelected ?: false

        val disabledTools = mutableSetOf<String>()
        for ((toolName, checkbox) in toolCheckBoxes) {
            if (!checkbox.isSelected) {
                disabledTools.add(toolName)
            }
        }
        settings.disabledTools = disabledTools

        // Auto-restart server if port changed
        if (newPort != oldPort) {
            ApplicationManager.getApplication().invokeLater {
                val result = McpServerService.getInstance().restartServer(newPort)
                when (result) {
                    is KtorMcpServer.StartResult.Success -> {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                McpBundle.message("notification.serverRestarted.title"),
                                McpBundle.message("notification.serverRestarted", newPort),
                                NotificationType.INFORMATION
                            )
                            .notify(null)
                    }
                    is KtorMcpServer.StartResult.PortInUse -> {
                        // This shouldn't happen since we validated above, but handle it anyway
                    }
                    is KtorMcpServer.StartResult.Error -> {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                "MCP Server Error",
                                result.message,
                                NotificationType.ERROR
                            )
                            .notify(null)
                    }
                }
            }
        }
    }

    /**
     * Checks if a port is available for binding.
     * Returns true if we can bind to the port, false if it's in use.
     */
    private fun isPortAvailable(port: Int): Boolean {
        // If it's the current server port, it's "available" (we'll restart the server)
        val currentPort = McpSettings.getInstance().serverPort
        if (port == currentPort && McpServerService.getInstance().isServerRunning()) {
            return true
        }

        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(McpConstants.DEFAULT_SERVER_HOST, port))
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        serverPortSpinner?.value = settings.serverPort
        maxHistorySizeSpinner?.value = settings.maxHistorySize
        syncExternalChangesCheckBox?.isSelected = settings.syncExternalChanges

        for ((toolName, checkbox) in toolCheckBoxes) {
            checkbox.isSelected = settings.isToolEnabled(toolName)
        }
    }

    override fun disposeUIResources() {
        panel = null
        serverPortSpinner = null
        maxHistorySizeSpinner = null
        syncExternalChangesCheckBox = null
        toolCheckBoxes.clear()
    }
}

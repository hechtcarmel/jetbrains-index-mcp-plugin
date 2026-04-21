package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.VisibleForTesting
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.function.Supplier
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

class McpSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var serverHostField: JBTextField? = null
    private var maxHistorySizeSpinner: JSpinner? = null
    private var serverPortSpinner: JSpinner? = null
    private var syncExternalChangesCheckBox: JBCheckBox? = null
    private var availableProjectsModeComboBox: ComboBox<McpSettings.AvailableProjectsMode>? = null
    private var responseFormatComboBox: ComboBox<McpSettings.ResponseFormat>? = null
    private val toolCheckBoxes = mutableMapOf<String, JBCheckBox>()
    private var uiDisposable: Disposable? = null

    private var lastHostValidation: ValidationInfo? = null
    private var hostValidationErrorLabel: JBLabel? = null
    private var hostValidationIcon: AsyncProcessIcon? = null
    private var hostValidIcon: JBLabel? = null
    private var hostWarningLabel: JBLabel? = null
    private var isHostValidationPending = false

    override fun getDisplayName(): String = McpBundle.message("settings.title")

    override fun createComponent(): JComponent {
        uiDisposable = Disposer.newDisposable()
        serverHostField = JBTextField(McpConstants.DEFAULT_SERVER_HOST, 25).apply {
            toolTipText = McpBundle.message("settings.serverHost.tooltip")
        }
        hostValidationErrorLabel = JBLabel().apply {
            foreground = JBColor.RED
            isVisible = false
        }
        hostValidationIcon = AsyncProcessIcon("HostValidation").apply {
            isVisible = false
        }
        hostValidIcon = JBLabel(AllIcons.General.InspectionsOK).apply {
            isVisible = false
        }
        hostWarningLabel = JBLabel(McpBundle.message("settings.serverHost.publicWarning")).apply {
            foreground = JBColor.RED
            isVisible = false
            border = JBUI.Borders.emptyBottom(5)
        }

        val serverHostInputRow = JPanel(HorizontalLayout(JBUI.scale(5))).apply {
            add(serverHostField)
            add(hostValidationIcon)
            add(hostValidIcon)
            add(hostValidationErrorLabel)
        }

        installHostValidator(serverHostField!!)

        maxHistorySizeSpinner = JSpinner(SpinnerNumberModel(100, 10, 10000, 10))
        serverPortSpinner = JSpinner(SpinnerNumberModel(McpConstants.getDefaultServerPort(), 1024, 65535, 1)).apply {
            toolTipText = McpBundle.message("settings.serverPort.tooltip")
        }
        syncExternalChangesCheckBox = JBCheckBox(McpBundle.message("settings.syncExternalChanges")).apply {
            toolTipText = McpBundle.message("settings.syncExternalChanges.tooltip")
        }
        availableProjectsModeComboBox = ComboBox(McpSettings.AvailableProjectsMode.values()).apply {
            toolTipText = McpBundle.message("settings.availableProjectsMode.tooltip")
            renderer = SimpleListCellRenderer.create("") { value ->
                value?.let(::availableProjectsModeLabel).orEmpty()
            }
        }
        responseFormatComboBox = ComboBox(McpSettings.ResponseFormat.values()).apply {
            toolTipText = McpBundle.message("settings.responseFormat.tooltip")
            renderer = SimpleListCellRenderer.create("") { value ->
                value?.let(::responseFormatLabel).orEmpty()
            }
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

        val availableToolsPanel = createToolsPanel()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(McpBundle.message("settings.serverHost") + ":"), serverHostInputRow, 1, false)
            .addComponentToRightColumn(hostWarningLabel!!)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.serverPort") + ":"), serverPortSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.maxHistorySize") + ":"), maxHistorySizeSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.availableProjectsMode") + ":"), availableProjectsModeComboBox!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.responseFormat") + ":"), responseFormatComboBox!!, 1, false)
            .addComponent(syncPanel, 1)
            .addSeparator(10)
            .addComponent(JBLabel(McpBundle.message("settings.tools.title")), 5)
            .addComponent(availableToolsPanel, 5)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    private fun createToolsPanel(): JComponent {
        val toolsContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val mcpService = McpServerService.getInstance()
        if (!mcpService.isInitialized) {
            toolsContainer.add(JBLabel("Server is initializing...").apply {
                foreground = JBColor(0xD9A343, 0xD9A343)
            })
            return toolsContainer
        }

        val toolRegistry = mcpService.getToolRegistry()
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

        if (serverHostField?.text?.trim() != settings.serverHost ||
            serverPortSpinner?.value != settings.serverPort ||
            maxHistorySizeSpinner?.value != settings.maxHistorySize ||
            syncExternalChangesCheckBox?.isSelected != settings.syncExternalChanges ||
            availableProjectsModeComboBox?.selectedItem != settings.availableProjectsMode ||
            responseFormatComboBox?.selectedItem != settings.responseFormat) {
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
        if (isHostValidationPending) {
            throw ConfigurationException(
                McpBundle.message("settings.serverHost.validating"),
                McpBundle.message("settings.validation.pending.title")
            )
        }

        val settings = McpSettings.getInstance()
        val oldHost = settings.serverHost
        val oldPort = settings.serverPort
        val newHost = serverHostField?.text?.trim() ?: McpConstants.DEFAULT_SERVER_HOST
        val newPort = serverPortSpinner?.value as? Int ?: McpConstants.getDefaultServerPort()

        if (newHost.isEmpty()) {
            throw ConfigurationException(
                McpBundle.message("settings.serverHost.empty"),
                McpBundle.message("settings.validation.host.title")
            )
        }

        if (lastHostValidation != null) {
            throw ConfigurationException(
                McpBundle.message("settings.serverHost.invalid", newHost),
                McpBundle.message("settings.validation.host.title")
            )
        }

        // Validate address availability before applying
        if (!isServerAddressAvailable(newHost, newPort)) {
            throw ConfigurationException(
                McpBundle.message("settings.serverAddress.unavailable", "$newHost:$newPort"),
                McpBundle.message("settings.validation.serverAddress.title")
            )
        }

        settings.serverHost = newHost
        settings.serverPort = newPort
        settings.maxHistorySize = maxHistorySizeSpinner?.value as? Int ?: 100
        settings.syncExternalChanges = syncExternalChangesCheckBox?.isSelected ?: false
        settings.availableProjectsMode =
            availableProjectsModeComboBox?.selectedItem as? McpSettings.AvailableProjectsMode
                ?: McpSettings.AvailableProjectsMode.EXPANDED
        settings.responseFormat =
            responseFormatComboBox?.selectedItem as? McpSettings.ResponseFormat
                ?: McpSettings.ResponseFormat.JSON

        val disabledTools = mutableSetOf<String>()
        for ((toolName, checkbox) in toolCheckBoxes) {
            if (!checkbox.isSelected) {
                disabledTools.add(toolName)
            }
        }
        settings.disabledTools = disabledTools

        // Auto-restart server if host/port changed
        if (newHost != oldHost || newPort != oldPort) {
            ApplicationManager.getApplication().invokeLater({
                val mcpService = McpServerService.getInstance()
                if (!mcpService.isInitialized) return@invokeLater
                val result = mcpService.restartServer(newHost, newPort)
                when (result) {
                    is KtorMcpServer.StartResult.Success -> {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                McpBundle.message("notification.serverRestarted.title"),
                                McpBundle.message("notification.serverRestarted", "$newHost:$newPort"),
                                NotificationType.INFORMATION
                            )
                            .notify(null)
                    }
                    is KtorMcpServer.StartResult.PortInUse -> {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                McpBundle.message("notification.serverStartFailed.title"),
                                McpBundle.message("notification.serverPortInUse.content", result.port, newHost),
                                NotificationType.ERROR
                            )
                            .notify(null)
                    }
                    is KtorMcpServer.StartResult.Error -> {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                            .createNotification(
                                McpBundle.message("notification.serverStartFailed.title"),
                                McpBundle.message("notification.serverStartFailed.content", result.message),
                                NotificationType.ERROR
                            )
                            .notify(null)
                    }
                }
            }, ModalityState.any())
        }
    }

    /**
     * Checks if a host:port is available for binding.
     * Returns true if we can bind to the address, false if it's in use.
     */
    private fun isServerAddressAvailable(host: String, port: Int): Boolean {
        val mcpService = McpServerService.getInstance()
        val currentPort = McpSettings.getInstance().serverPort

        // If the port matches our current server's port and it's running, we consider it available.
        // We skip the bind check here because our own server is already occupying the port,
        // which would cause a false "address in use" error (especially when switching 
        // between 0.0.0.0 and 127.0.0.1). We trust that we will stop our server 
        // before binding to the new address during restart.
        if (port == currentPort && mcpService.isInitialized && mcpService.isServerRunning()) {
            return true
        }

        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(host, port))
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        serverHostField?.text = settings.serverHost
        serverPortSpinner?.value = settings.serverPort
        maxHistorySizeSpinner?.value = settings.maxHistorySize
        syncExternalChangesCheckBox?.isSelected = settings.syncExternalChanges
        availableProjectsModeComboBox?.selectedItem = settings.availableProjectsMode
        responseFormatComboBox?.selectedItem = settings.responseFormat
        
        hostValidationErrorLabel?.isVisible = false
        hostValidationIcon?.isVisible = false
        hostValidIcon?.isVisible = false
        updateHostWarning(settings.serverHost)
        isHostValidationPending = false
        lastHostValidation = null

        for ((toolName, checkbox) in toolCheckBoxes) {
            checkbox.isSelected = settings.isToolEnabled(toolName)
        }
    }

    private fun updateHostWarning(host: String) {
        val trimmedHost = host.trim()
        val isDefault = trimmedHost == McpConstants.DEFAULT_SERVER_HOST
        hostWarningLabel?.isVisible = !isDefault
    }

    private fun installHostValidator(field: JBTextField) {
        // We use an empty validation info initially so we can control when it appears
        val validator = ComponentValidator(uiDisposable!!).withValidator {
            lastHostValidation
        }.installOn(field)

        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // Clear validation state immediately on typing.
                // Host validation runs only after focus is lost.
                hostValidationErrorLabel?.isVisible = false
                hostValidIcon?.isVisible = false
                hostValidationIcon?.isVisible = false
                isHostValidationPending = true
                lastHostValidation = null

                // Update warning visibility
                updateHostWarning(field.text)

                ComponentValidator.getInstance(field).ifPresent { it.updateInfo(lastHostValidation) }
            }
        })

        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                val host = field.text.trim()

                isHostValidationPending = true
                hostValidationErrorLabel?.isVisible = false
                hostValidIcon?.isVisible = false
                hostValidationIcon?.isVisible = true

                ApplicationManager.getApplication().executeOnPooledThread {
                    val isValid = isValidHost(host)
                    val labelMessage = if (host.isEmpty()) 
                        McpBundle.message("settings.serverHost.empty") 
                    else 
                        McpBundle.message("settings.serverHost.invalidShort")
                    
                    // Use empty message for ComponentValidator to show red border but avoid tooltip popup
                    // as we are showing the error message in the label next to the input
                    val info = if (isValid) null else ValidationInfo("", field)

                    ApplicationManager.getApplication().invokeLater({
                        // Only update UI if the text hasn't changed since we started
                        if (field.text.trim() == host) {
                            hostValidationIcon?.isVisible = false
                            isHostValidationPending = false
                            lastHostValidation = info

                            ComponentValidator.getInstance(field).ifPresent { it.updateInfo(info) }

                            if (!isValid) {
                                hostValidationErrorLabel?.text = labelMessage
                                hostValidationErrorLabel?.isVisible = true
                                hostValidIcon?.isVisible = false
                            } else {
                                hostValidationErrorLabel?.isVisible = false
                                hostValidIcon?.isVisible = true
                            }
                        }
                    }, ModalityState.any())
                }
            }
        })
    }

    override fun disposeUIResources() {
        panel = null
        serverHostField = null
        hostValidationErrorLabel = null
        hostValidationIcon = null
        hostValidIcon = null
        serverPortSpinner = null
        maxHistorySizeSpinner = null
        syncExternalChangesCheckBox = null
        availableProjectsModeComboBox = null
        responseFormatComboBox = null
        toolCheckBoxes.clear()
        uiDisposable?.let { Disposer.dispose(it) }
        uiDisposable = null
    }

    private fun availableProjectsModeLabel(mode: McpSettings.AvailableProjectsMode): String =
        when (mode) {
            McpSettings.AvailableProjectsMode.EXPANDED -> McpBundle.message("settings.availableProjectsMode.expanded")
            McpSettings.AvailableProjectsMode.COMPACT -> McpBundle.message("settings.availableProjectsMode.compact")
        }

    private fun responseFormatLabel(format: McpSettings.ResponseFormat): String =
        when (format) {
            McpSettings.ResponseFormat.JSON -> McpBundle.message("settings.responseFormat.json")
            McpSettings.ResponseFormat.TOON -> McpBundle.message("settings.responseFormat.toon")
        }

    companion object {
        private val IPV4_PATTERN = Regex("^[0-9.]+\$")

        @VisibleForTesting
        fun isValidIpv4(host: String): Boolean {
            if (!IPV4_PATTERN.matches(host)) return false
            val parts = host.split(".")
            // Check for exactly 4 non-empty parts with valid octet values (0-255)
            return parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        @VisibleForTesting
        fun isValidHost(host: String): Boolean {
            val trimmedHost = host.trim()
            if (trimmedHost.isEmpty()) return false

            // Check if input consists only of numbers and dots (potential IPv4)
            if (IPV4_PATTERN.matches(trimmedHost)) {
                return isValidIpv4(trimmedHost)
            }

            // Fallback for hostnames
            return runCatching { InetAddress.getByName(trimmedHost) }.isSuccess
        }
    }
}

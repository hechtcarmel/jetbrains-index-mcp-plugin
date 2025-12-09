package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class McpSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var maxHistorySizeSpinner: JSpinner? = null
    private var syncExternalChangesCheckBox: JBCheckBox? = null
    private val toolCheckBoxes = mutableMapOf<String, JBCheckBox>()

    override fun getDisplayName(): String = McpBundle.message("settings.title")

    override fun createComponent(): JComponent {
        maxHistorySizeSpinner = JSpinner(SpinnerNumberModel(100, 10, 10000, 10))
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

        if (maxHistorySizeSpinner?.value != settings.maxHistorySize ||
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

    override fun apply() {
        val settings = McpSettings.getInstance()
        settings.maxHistorySize = maxHistorySizeSpinner?.value as? Int ?: 100
        settings.syncExternalChanges = syncExternalChangesCheckBox?.isSelected ?: false

        val disabledTools = mutableSetOf<String>()
        for ((toolName, checkbox) in toolCheckBoxes) {
            if (!checkbox.isSelected) {
                disabledTools.add(toolName)
            }
        }
        settings.disabledTools = disabledTools
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        maxHistorySizeSpinner?.value = settings.maxHistorySize
        syncExternalChangesCheckBox?.isSelected = settings.syncExternalChanges

        for ((toolName, checkbox) in toolCheckBoxes) {
            checkbox.isSelected = settings.isToolEnabled(toolName)
        }
    }

    override fun disposeUIResources() {
        panel = null
        maxHistorySizeSpinner = null
        syncExternalChangesCheckBox = null
        toolCheckBoxes.clear()
    }
}

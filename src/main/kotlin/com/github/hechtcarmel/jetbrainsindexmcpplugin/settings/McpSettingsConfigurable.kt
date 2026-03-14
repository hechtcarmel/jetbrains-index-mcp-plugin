package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
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
import com.intellij.openapi.options.Configurable

class McpSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var maxHistorySizeSpinner: JSpinner? = null
    private var syncExternalChangesCheckBox: JBCheckBox? = null
    private val toolCheckBoxes = linkedMapOf<String, JBCheckBox>()

    override fun getDisplayName(): String = McpBundle.message("settings.title")

    override fun createComponent(): JComponent {
        maxHistorySizeSpinner = JSpinner(SpinnerNumberModel(100, 10, 10000, 10))
        syncExternalChangesCheckBox = JBCheckBox(McpBundle.message("settings.syncExternalChanges")).apply {
            toolTipText = McpBundle.message("settings.syncExternalChanges.tooltip")
        }

        val syncWarning = JBLabel(McpBundle.message("settings.syncExternalChanges.warning")).apply {
            foreground = JBColor.RED
        }
        val nativeHint = JBLabel(McpBundle.message("settings.nativeServerManaged")).apply {
            foreground = JBColor.GRAY
        }

        val syncPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(syncExternalChangesCheckBox)
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(24), 0)).apply {
                isOpaque = false
                add(syncWarning)
            })
        }

        panel = FormBuilder.createFormBuilder()
            .addComponent(nativeHint, 1)
            .addLabeledComponent(
                JBLabel(McpBundle.message("settings.maxHistorySize") + ":"),
                maxHistorySizeSpinner!!,
                1,
                false
            )
            .addComponent(syncPanel, 1)
            .addSeparator(10)
            .addComponent(JBLabel(McpBundle.message("settings.tools.title")), 5)
            .addComponent(createToolsPanel(), 5)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    private fun createToolsPanel(): JComponent {
        val toolsContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val settings = McpSettings.getInstance()
        val toolDefinitions = McpServerService.getInstance()
            .getToolRegistry()
            .getAllToolDefinitions()
            .sortedBy { it.name }

        toolCheckBoxes.clear()
        for (tool in toolDefinitions) {
            val checkbox = JBCheckBox(tool.name, settings.isToolEnabled(tool.name)).apply {
                toolTipText = tool.description
                isOpaque = false
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

        return toolCheckBoxes.any { (toolName, checkbox) ->
            checkbox.isSelected != settings.isToolEnabled(toolName)
        }
    }

    override fun apply() {
        val settings = McpSettings.getInstance()
        settings.maxHistorySize = maxHistorySizeSpinner?.value as? Int ?: 100
        settings.syncExternalChanges = syncExternalChangesCheckBox?.isSelected ?: false
        settings.disabledTools = toolCheckBoxes
            .filterValues { !it.isSelected }
            .keys
            .toSet()

        McpServerService.getInstance().notifyStatusChanged()
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

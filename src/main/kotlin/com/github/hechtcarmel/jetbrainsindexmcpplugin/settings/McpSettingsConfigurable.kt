package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
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

    override fun getDisplayName(): String = McpBundle.message("settings.title")

    override fun createComponent(): JComponent {
        maxHistorySizeSpinner = JSpinner(SpinnerNumberModel(100, 10, 10000, 10))
        syncExternalChangesCheckBox = JBCheckBox(McpBundle.message("settings.syncExternalChanges")).apply {
            toolTipText = McpBundle.message("settings.syncExternalChanges.tooltip")
        }

        // Create warning label with red text
        val warningLabel = JBLabel(McpBundle.message("settings.syncExternalChanges.warning")).apply {
            foreground = JBColor.RED
        }

        // Create panel for sync checkbox with warning below it
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

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(McpBundle.message("settings.maxHistorySize") + ":"), maxHistorySizeSpinner!!, 1, false)
            .addComponent(syncPanel, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = McpSettings.getInstance()
        return maxHistorySizeSpinner?.value != settings.maxHistorySize ||
            syncExternalChangesCheckBox?.isSelected != settings.syncExternalChanges
    }

    override fun apply() {
        val settings = McpSettings.getInstance()
        settings.maxHistorySize = maxHistorySizeSpinner?.value as? Int ?: 100
        settings.syncExternalChanges = syncExternalChangesCheckBox?.isSelected ?: false
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        maxHistorySizeSpinner?.value = settings.maxHistorySize
        syncExternalChangesCheckBox?.isSelected = settings.syncExternalChanges
    }

    override fun disposeUIResources() {
        panel = null
        maxHistorySizeSpinner = null
        syncExternalChangesCheckBox = null
    }
}

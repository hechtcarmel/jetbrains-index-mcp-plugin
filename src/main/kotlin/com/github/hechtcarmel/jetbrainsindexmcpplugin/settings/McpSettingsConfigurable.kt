package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class McpSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var maxHistorySizeSpinner: JSpinner? = null
    private var autoScrollCheckBox: JBCheckBox? = null
    private var showTimestampsCheckBox: JBCheckBox? = null
    private var confirmWriteOperationsCheckBox: JBCheckBox? = null
    private var logToFileCheckBox: JBCheckBox? = null
    private var logFilePathField: JBTextField? = null

    override fun getDisplayName(): String = McpBundle.message("settings.title")

    override fun createComponent(): JComponent {
        maxHistorySizeSpinner = JSpinner(SpinnerNumberModel(100, 10, 10000, 10))
        autoScrollCheckBox = JBCheckBox(McpBundle.message("settings.autoScroll"))
        showTimestampsCheckBox = JBCheckBox(McpBundle.message("settings.showTimestamps"))
        confirmWriteOperationsCheckBox = JBCheckBox(McpBundle.message("settings.confirmWriteOperations"))
        logToFileCheckBox = JBCheckBox(McpBundle.message("settings.logToFile"))
        logFilePathField = JBTextField()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(McpBundle.message("settings.maxHistorySize") + ":"), maxHistorySizeSpinner!!, 1, false)
            .addComponent(autoScrollCheckBox!!, 1)
            .addComponent(showTimestampsCheckBox!!, 1)
            .addComponent(confirmWriteOperationsCheckBox!!, 1)
            .addComponent(logToFileCheckBox!!, 1)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.logFilePath") + ":"), logFilePathField!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = McpSettings.getInstance()
        return maxHistorySizeSpinner?.value != settings.maxHistorySize ||
            autoScrollCheckBox?.isSelected != settings.autoScroll ||
            showTimestampsCheckBox?.isSelected != settings.showTimestamps ||
            confirmWriteOperationsCheckBox?.isSelected != settings.confirmWriteOperations ||
            logToFileCheckBox?.isSelected != settings.logToFile ||
            logFilePathField?.text != settings.logFilePath
    }

    override fun apply() {
        val settings = McpSettings.getInstance()
        settings.maxHistorySize = maxHistorySizeSpinner?.value as? Int ?: 100
        settings.autoScroll = autoScrollCheckBox?.isSelected ?: true
        settings.showTimestamps = showTimestampsCheckBox?.isSelected ?: true
        settings.confirmWriteOperations = confirmWriteOperationsCheckBox?.isSelected ?: true
        settings.logToFile = logToFileCheckBox?.isSelected ?: false
        settings.logFilePath = logFilePathField?.text ?: ""
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        maxHistorySizeSpinner?.value = settings.maxHistorySize
        autoScrollCheckBox?.isSelected = settings.autoScroll
        showTimestampsCheckBox?.isSelected = settings.showTimestamps
        confirmWriteOperationsCheckBox?.isSelected = settings.confirmWriteOperations
        logToFileCheckBox?.isSelected = settings.logToFile
        logFilePathField?.text = settings.logFilePath
    }

    override fun disposeUIResources() {
        panel = null
        maxHistorySizeSpinner = null
        autoScrollCheckBox = null
        showTimestampsCheckBox = null
        confirmWriteOperationsCheckBox = null
        logToFileCheckBox = null
        logFilePathField = null
    }
}

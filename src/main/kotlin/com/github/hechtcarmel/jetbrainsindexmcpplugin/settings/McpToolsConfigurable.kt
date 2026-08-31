package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page listing every registered MCP tool with an enable/disable checkbox.
 * Registered in plugin.xml as a child of mcp.settings, mirroring the IDE's own
 * MCP Server settings layout.
 */
class McpToolsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val toolCheckBoxes = mutableMapOf<String, JBCheckBox>()

    override fun getDisplayName(): String = McpBundle.message("settings.exposedTools.title")

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(McpBundle.message("settings.tools.title")), 5)
            .addComponent(createToolsPanel(), 5)
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
        for ((toolName, checkbox) in toolCheckBoxes) {
            if (checkbox.isSelected != settings.isToolEnabled(toolName)) {
                return true
            }
        }
        return false
    }

    override fun apply() {
        McpSettings.getInstance().updateToolEnabledStates(
            toolCheckBoxes.mapValues { (_, checkbox) -> checkbox.isSelected }
        )
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        for ((toolName, checkbox) in toolCheckBoxes) {
            checkbox.isSelected = settings.isToolEnabled(toolName)
        }
    }

    override fun disposeUIResources() {
        panel = null
        toolCheckBoxes.clear()
    }
}

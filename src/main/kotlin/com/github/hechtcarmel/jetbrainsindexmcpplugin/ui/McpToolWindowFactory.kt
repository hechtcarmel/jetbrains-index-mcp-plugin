package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.ClearHistoryAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.CopyClientConfigAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.CopyServerUrlAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.ExportHistoryAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.OpenSettingsAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.RefreshAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class McpToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project)

        // Left toolbar actions (utility buttons)
        val leftActionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            addSeparator()
            add(CopyServerUrlAction())
            addSeparator()
            add(ClearHistoryAction())
            add(ExportHistoryAction())
            addSeparator()
            add(OpenSettingsAction())
        }

        val leftToolbar = ActionManager.getInstance().createActionToolbar(
            "McpServerToolbarLeft",
            leftActionGroup,
            true
        )
        leftToolbar.targetComponent = panel

        // Create prominent "Install on Coding Agents" button with text
        val installAction = CopyClientConfigAction()
        val installButton = JButton("Install on Coding Agents").apply {
            icon = AllIcons.FileTypes.Config
            toolTipText = "Copy MCP client configuration to clipboard"
            isFocusable = false
            addActionListener {
                val dataContext = com.intellij.openapi.actionSystem.DataContext { dataId ->
                    when (dataId) {
                        com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> project
                        else -> null
                    }
                }
                val event = AnActionEvent.createFromAnAction(
                    installAction,
                    null,
                    ActionPlaces.TOOLWINDOW_CONTENT,
                    dataContext
                )
                installAction.actionPerformed(event)
            }
        }

        // Right panel with the button
        val rightPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(installButton)
        }

        // Create toolbar panel with left actions on left, right button on right
        val toolbarPanel = JPanel(BorderLayout()).apply {
            add(leftToolbar.component, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }

        // Create wrapper panel with toolbar at top and main panel in center
        val wrapperPanel = JPanel(BorderLayout()).apply {
            add(toolbarPanel, BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(
            wrapperPanel,
            McpBundle.message("toolWindow.title"),
            false
        )
        toolWindow.contentManager.addContent(content)

        // Also add quick actions to title bar
        toolWindow.setTitleActions(listOf(CopyServerUrlAction(), RefreshAction()))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

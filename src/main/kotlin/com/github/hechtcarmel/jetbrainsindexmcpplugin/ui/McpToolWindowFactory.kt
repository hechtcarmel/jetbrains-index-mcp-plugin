package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.ClearHistoryAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.CopyServerUrlAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.ExportHistoryAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.RefreshAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class McpToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(
            panel,
            McpBundle.message("toolWindow.title"),
            false
        )
        toolWindow.contentManager.addContent(content)

        // Set up toolbar actions
        val actionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            add(CopyServerUrlAction())
            addSeparator()
            add(ClearHistoryAction())
            add(ExportHistoryAction())
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "McpServerToolbar",
            actionGroup,
            true
        )
        toolbar.targetComponent = panel
        toolWindow.setTitleActions(listOf(CopyServerUrlAction(), RefreshAction()))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

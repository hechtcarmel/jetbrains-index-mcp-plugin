package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.ui.McpToolWindowPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class RefreshAction : AnAction(
    McpBundle.message("toolWindow.refresh"),
    "Refresh server status and history",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Index MCP Server")
        toolWindow?.contentManager?.contents?.forEach { content ->
            val component = content.component
            if (component is McpToolWindowPanel) {
                component.refresh()
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

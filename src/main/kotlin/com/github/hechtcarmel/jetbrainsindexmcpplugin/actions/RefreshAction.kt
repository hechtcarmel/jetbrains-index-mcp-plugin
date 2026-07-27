package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.ui.McpToolWindowPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.UIUtil

class RefreshAction : AnAction(
    McpBundle.message("toolWindow.refresh"),
    "Refresh server status and history",
    AllIcons.Actions.Refresh
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(McpConstants.TOOL_WINDOW_ID)
        toolWindow?.contentManager?.contents?.forEach { content ->
            // The content component is a wrapper JPanel (toolbar + panel), so the panel has to
            // be located inside it rather than matched against the component itself.
            UIUtil.findComponentOfType(content.component, McpToolWindowPanel::class.java)?.refresh()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

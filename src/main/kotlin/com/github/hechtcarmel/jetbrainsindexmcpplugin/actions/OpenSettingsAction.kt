package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

class OpenSettingsAction : AnAction(
    McpBundle.message("toolWindow.settings"),
    "Open MCP Server settings",
    AllIcons.General.Settings
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, "Index MCP Server")
    }
}

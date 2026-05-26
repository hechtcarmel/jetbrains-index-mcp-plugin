package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Opens the MCP settings panel directly.
 * Accessible via Cmd+Shift+A → "MCP: Show Project States".
 */
class ShowProjectStatesAction : AnAction("MCP: Show Project States") {

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, "Index MCP Server")
    }
}

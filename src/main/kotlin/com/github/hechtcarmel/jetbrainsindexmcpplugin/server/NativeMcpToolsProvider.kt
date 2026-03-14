package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolsProvider

/**
 * Provides plugin tools to JetBrains' built-in MCP server.
 */
class NativeMcpToolsProvider : McpToolsProvider {
    override fun getTools(): List<McpTool> {
        return McpServerService.getInstance()
            .getEnabledTools()
            .map(::NativeMcpToolAdapter)
    }
}

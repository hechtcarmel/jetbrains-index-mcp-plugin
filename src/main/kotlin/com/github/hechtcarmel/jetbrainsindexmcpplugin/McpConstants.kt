package com.github.hechtcarmel.jetbrainsindexmcpplugin

object McpConstants {
    const val PLUGIN_NAME = "Index MCP Server"
    const val TOOL_WINDOW_ID = PLUGIN_NAME
    const val NOTIFICATION_GROUP_ID = PLUGIN_NAME
    const val SETTINGS_DISPLAY_NAME = PLUGIN_NAME

    // MCP Endpoint paths (HTTP+SSE transport)
    const val MCP_ENDPOINT_PATH = "/index-mcp"
    const val SSE_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/sse"

    // JSON-RPC version
    const val JSON_RPC_VERSION = "2.0"

    // MCP Protocol version
    const val MCP_PROTOCOL_VERSION = "2024-11-05"

    // Server identification
    const val SERVER_NAME = "intellij-index-mcp"
    const val SERVER_VERSION = "1.0.0"
    const val SERVER_DESCRIPTION = "IntelliJ IDEA code intelligence and refactoring server. Provides semantic code navigation (find usages, find definition, type/call hierarchies, find implementations), safe IDE-powered refactoring (rename, extract method/variable, inline, move, safe delete), and code analysis (completions, inspections, quick fixes). Use when you need accurate cross-project code understanding, want to refactor code safely with all references updated, or need IDE-level code completions and suggestions. Requires an open IntelliJ project. Refactoring operations modify source files with undo support."
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin

object McpConstants {
    const val PLUGIN_NAME = "Index MCP Server"
    const val TOOL_WINDOW_ID = PLUGIN_NAME
    const val NOTIFICATION_GROUP_ID = PLUGIN_NAME
    const val SETTINGS_DISPLAY_NAME = PLUGIN_NAME

    // MCP Endpoint paths (HTTP+SSE transport)
    const val MCP_ENDPOINT_PATH = "/index-mcp"
    const val SSE_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/sse"
    const val SESSION_ID_PARAM = "sessionId"

    // JSON-RPC version
    const val JSON_RPC_VERSION = "2.0"

    // MCP Protocol version
    const val MCP_PROTOCOL_VERSION = "2024-11-05"

    // Server identification
    const val SERVER_NAME = "jetbrains-index-mcp"
    const val SERVER_VERSION = "1.0.0"
    const val SERVER_DESCRIPTION = "JetBrains IDE code intelligence server supporting Java, Kotlin, Python, JavaScript, TypeScript, Go, PHP, and Rust. Provides semantic code navigation (find usages, find definition, type/call hierarchies, find implementations), safe IDE-powered refactoring (rename, safe delete), and code analysis (diagnostics, inspections). Use when you need accurate, token efficient project code understanding or want to refactor code safely with all references updated. Requires an open JetBrains IDE project. Refactoring operations modify source files."
}

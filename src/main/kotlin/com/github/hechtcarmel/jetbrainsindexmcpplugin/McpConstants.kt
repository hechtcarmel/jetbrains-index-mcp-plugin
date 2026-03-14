package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.intellij.util.messages.Topic

object McpConstants {
    const val PLUGIN_NAME = "Index MCP Server"
    const val TOOL_WINDOW_ID = PLUGIN_NAME
    const val NOTIFICATION_GROUP_ID = PLUGIN_NAME
    const val SETTINGS_DISPLAY_NAME = PLUGIN_NAME

    const val SERVER_VERSION = "5.0.0"
    const val MCP_HELP_URL = "https://www.jetbrains.com/help/idea/mcp-server.html"

    /**
     * Topic for server status change notifications.
     * Used to notify UI components when plugin-side MCP integration changes.
     */
    @JvmField
    val SERVER_STATUS_TOPIC: Topic<ServerStatusListener> = Topic.create(
        "MCP Server Status",
        ServerStatusListener::class.java
    )
}

/**
 * Listener interface for server status changes.
 */
interface ServerStatusListener {
    fun serverStatusChanged()
}

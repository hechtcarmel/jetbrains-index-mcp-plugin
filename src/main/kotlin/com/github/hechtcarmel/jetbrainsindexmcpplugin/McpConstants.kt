package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.IdeProductInfo
import com.intellij.util.messages.Topic
import java.util.Properties

object McpConstants {
    const val PLUGIN_NAME = "Index MCP Server"
    const val TOOL_WINDOW_ID = PLUGIN_NAME
    const val NOTIFICATION_GROUP_ID = PLUGIN_NAME
    const val SETTINGS_DISPLAY_NAME = PLUGIN_NAME

    // Server configuration - IDE-specific defaults
    const val DEFAULT_SERVER_HOST = "127.0.0.1"

    /**
     * Returns the IDE-specific default server port.
     * Each IDE has a unique default port to avoid conflicts when multiple IDEs run simultaneously.
     */
    @JvmStatic
    fun getDefaultServerPort(): Int = IdeProductInfo.getDefaultPort()

    /**
     * Legacy constant for backwards compatibility.
     * New code should use getDefaultServerPort() for IDE-specific ports.
     */
    const val DEFAULT_SERVER_PORT = 29170

    // MCP Endpoint paths
    const val MCP_ENDPOINT_PATH = "/index-mcp"
    const val SSE_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/sse"
    const val STREAMABLE_HTTP_ENDPOINT_PATH = "$MCP_ENDPOINT_PATH/streamable-http"
    const val SESSION_ID_PARAM = "sessionId"

    // JSON-RPC version
    const val JSON_RPC_VERSION = "2.0"

    // MCP Protocol versions
    const val LEGACY_MCP_PROTOCOL_VERSION = "2024-11-05"
    const val STREAMABLE_HTTP_MCP_PROTOCOL_VERSION = "2025-03-26"
    const val MCP_PROTOCOL_VERSION = STREAMABLE_HTTP_MCP_PROTOCOL_VERSION

    // Server identification - IDE-specific
    /**
     * Returns the IDE-specific server name (e.g., "intellij-index", "pycharm-index").
     */
    @JvmStatic
    fun getServerName(): String = IdeProductInfo.getServerName()

    /**
     * Legacy constant for backwards compatibility.
     */
    const val SERVER_NAME = "jetbrains-index-mcp"

    /**
     * Version reported to MCP clients in `initialize`.
     *
     * Stamped into `mcp-server.properties` by the build rather than hardcoded — the constant this
     * replaced said "4.10.4" while the plugin shipped 4.31.x, so every client saw a stale version.
     * The descriptor is not an option: every platform API that exposes it is `@ApiStatus.Internal`
     * as of 2026.2.
     */
    @JvmStatic
    fun getServerVersion(): String = stampedVersion

    private val stampedVersion: String by lazy {
        McpConstants::class.java.getResourceAsStream("/mcp-server.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        } ?: "unknown"
    }

    const val SERVER_DESCRIPTION = "Code intelligence server for JetBrains IDEs (IntelliJ, PyCharm, WebStorm, GoLand, PhpStorm, RustRover). Use this instead of grep/ripgrep for semantic code understanding. Capabilities: find usages, go to definition, type/call hierarchies, find implementations, symbol search, rename refactoring, safe delete, diagnostics. Languages: Java, Kotlin, Python, JavaScript, TypeScript, Go, PHP, Rust, and Markdown file structure. Prerequisite: project must be open in IDE. Note: refactoring tools modify source files."

    /**
     * Topic for server status change notifications.
     * Used to notify UI components when the server restarts or encounters errors.
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

package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.IdeProductInfo
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.messages.Topic

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
     * Plugin id declared in META-INF/plugin.xml. Kept in sync with the `<id>` element so
     * [getServerVersion] can look the plugin up at runtime.
     */
    const val PLUGIN_ID = "com.github.hechtcarmel.jetbrainsindexmcpplugin"

    /**
     * Compile-time fallback used only when the plugin descriptor cannot be resolved
     * (e.g. inside unit tests that don't load the manifest). Update alongside
     * `pluginVersion` in `gradle.properties`.
     */
    const val SERVER_VERSION_FALLBACK = "4.20.1"

    /**
     * Resolve the plugin's reported version from the live plugin descriptor at runtime
     * so it always tracks `pluginVersion` from gradle.properties — eliminating the
     * drift bug where this constant lagged behind every release.
     */
    @JvmStatic
    fun getServerVersion(): String {
        return try {
            PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
                ?: SERVER_VERSION_FALLBACK
        } catch (t: Throwable) {
            thisLogger().warn("Failed to read plugin descriptor for $PLUGIN_ID; using fallback version", t)
            SERVER_VERSION_FALLBACK
        }
    }

    @Deprecated(
        "Use getServerVersion() so the version matches the loaded plugin descriptor.",
        ReplaceWith("McpConstants.getServerVersion()"),
    )
    const val SERVER_VERSION = SERVER_VERSION_FALLBACK
    const val SERVER_DESCRIPTION = "Code intelligence server for JetBrains IDEs (IntelliJ, PyCharm, WebStorm, GoLand, PhpStorm, RustRover, Rider). Use this instead of grep/ripgrep for semantic code understanding. Capabilities: find usages, go to definition, type/call hierarchies, find implementations, symbol search, rename refactoring, safe delete, diagnostics. Languages: Java, Kotlin, Python, JavaScript, TypeScript, Go, PHP, Rust, C# (Rider), and Markdown file structure. Prerequisite: project must be open in IDE. Note: refactoring tools modify source files."

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

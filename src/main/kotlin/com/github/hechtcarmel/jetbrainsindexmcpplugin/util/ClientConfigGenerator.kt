package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.util.SystemInfo


/**
 * Generates MCP client configuration snippets for various AI coding assistants.
 *
 * This utility generates ready-to-use configuration for:
 * - Claude Code
 * - Codex CLI
 * - Gemini CLI
 * - Cursor
 *
 * Also provides generic configurations:
 * - Streamable HTTP (for modern clients with native support)
 * - Legacy SSE (for older clients)
 *
 * All configurations use Streamable HTTP as the primary transport.
 */
object ClientConfigGenerator {

    /**
     * Gets the Streamable HTTP server URL (primary), using the running server URL if available,
     * or constructing a URL from settings if the server is not running.
     */
    private fun getStreamableHttpUrlOrDefault(): String {
        return McpServerService.getInstance().getServerUrl()
            ?: run {
                val settings = McpSettings.getInstance()
                "http://${settings.serverHost}:${settings.serverPort}${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}"
            }
    }

    /**
     * Gets the legacy SSE server URL, using the running server URL if available,
     * or constructing a URL from settings if the server is not running.
     */
    private fun getLegacySseUrlOrDefault(): String {
        return McpServerService.getInstance().getLegacySseUrl()
            ?: run {
                val settings = McpSettings.getInstance()
                "http://${settings.serverHost}:${settings.serverPort}${McpConstants.SSE_ENDPOINT_PATH}"
            }
    }

    /**
     * Supported MCP client types.
     */
    enum class ClientType(val displayName: String, val supportsInstallCommand: Boolean = false) {
        CLAUDE_CODE("Claude Code", true),
        CODEX_CLI("Codex CLI", true),
        GEMINI_CLI("Gemini CLI"),
        CURSOR("Cursor")
    }

    enum class CommandPlatform {
        POSIX,
        WINDOWS
    }

    private fun currentCommandPlatform(): CommandPlatform =
        if (SystemInfo.isWindows) CommandPlatform.WINDOWS else CommandPlatform.POSIX

    /**
     * Returns the IDE-specific server name (e.g., "intellij-index", "pycharm-index").
     */
    fun getDefaultServerName(): String = McpConstants.getServerName()

    /**
     * Generates the MCP configuration for the specified client type.
     *
     * @param clientType The type of MCP client to generate configuration for
     * @param serverName Optional custom name for the server (defaults to IDE-specific name)
     * @param platform The command platform to use for command-based clients
     * @return The configuration string in the appropriate format for the client
     */
    fun generateConfig(
        clientType: ClientType,
        serverName: String = getDefaultServerName(),
        platform: CommandPlatform = currentCommandPlatform()
    ): String {
        val serverUrl = getStreamableHttpUrlOrDefault()

        return when (clientType) {
            ClientType.CLAUDE_CODE -> generateClaudeCodeConfig(serverUrl, serverName, platform)
            ClientType.CODEX_CLI -> generateCodexConfig(serverUrl, serverName, platform)
            ClientType.GEMINI_CLI -> generateGeminiCliConfig(serverUrl, serverName)
            ClientType.CURSOR -> generateCursorConfig(serverUrl, serverName)
        }
    }

    /**
     * Generates the install command for clients that support direct installation.
     *
     * @param clientType The type of MCP client
     * @param serverName Optional custom name for the server (defaults to IDE-specific name)
     * @param platform The command platform to use for direct install commands
     * @return The install command, or null if the client doesn't support install commands
     */
    fun generateInstallCommand(
        clientType: ClientType,
        serverName: String = getDefaultServerName(),
        platform: CommandPlatform = currentCommandPlatform()
    ): String? {
        if (!clientType.supportsInstallCommand) return null
        val serverUrl = getStreamableHttpUrlOrDefault()

        return when (clientType) {
            ClientType.CLAUDE_CODE -> buildClaudeCodeCommand(serverUrl, serverName, platform)
            ClientType.CODEX_CLI -> buildCodexCommand(serverUrl, serverName, platform)
            else -> null
        }
    }

    /**
     * Builds the shell invocation used to execute an install command.
     */
    internal fun buildShellInvocation(
        command: String,
        platform: CommandPlatform = currentCommandPlatform()
    ): List<String> {
        return when (platform) {
            CommandPlatform.POSIX -> listOf("sh", "-c", command)
            CommandPlatform.WINDOWS -> listOf("cmd.exe", "/d", "/c", command)
        }
    }

    /**
     * Legacy server name from v1.x that should be uninstalled during upgrade.
     */
    private const val LEGACY_SERVER_NAME = "jetbrains-index-mcp"

    /**
     * Builds the Claude Code CLI command for reinstalling the MCP server.
     *
     * Removes any existing installation first (to handle port changes), then adds the server.
     * Also removes legacy server names from v1.x (jetbrains-index-mcp) to clean up after upgrade.
     * Remove command failures are suppressed because the server may not be installed yet.
     * Uses a platform-specific command separator so add runs regardless of remove's exit status.
     *
     * This method is internal for testing purposes.
     *
     * @param serverUrl The URL of the MCP server
     * @param serverName The name to register the server as
     * @return A shell command that removes legacy names, removes current name, and reinstalls the MCP server
     */
    internal fun buildClaudeCodeCommand(
        serverUrl: String,
        serverName: String,
        platform: CommandPlatform = CommandPlatform.POSIX
    ): String {
        val removeErrorRedirect = when (platform) {
            CommandPlatform.POSIX -> "2>/dev/null"
            CommandPlatform.WINDOWS -> "2>NUL"
        }
        val separator = commandSeparator(platform)
        val removeLegacyCmd = "claude mcp remove $LEGACY_SERVER_NAME $removeErrorRedirect"
        val removeCmd = "claude mcp remove $serverName $removeErrorRedirect"
        val addCmd = "claude mcp add --transport http --scope user $serverName $serverUrl"
        return "$removeLegacyCmd$separator$removeCmd$separator$addCmd"
    }

    private fun generateClaudeCodeConfig(
        serverUrl: String,
        serverName: String,
        platform: CommandPlatform
    ): String {
        return buildClaudeCodeCommand(serverUrl, serverName, platform)
    }

    /**
     * Builds the Codex CLI command for reinstalling the MCP server.
     *
     * Removes any existing installation first, then adds the server using native
     * Streamable HTTP transport (no mcp-remote bridge needed).
     * Remove command failures are suppressed because the server may not be installed yet.
     * Uses a platform-specific command separator so add runs regardless of remove's exit status.
     *
     * This method is internal for testing purposes.
     *
     * @param serverUrl The URL of the MCP server
     * @param serverName The name to register the server as
     * @return A shell command that removes the current name and reinstalls the MCP server
     */
    internal fun buildCodexCommand(
        serverUrl: String,
        serverName: String,
        platform: CommandPlatform = CommandPlatform.POSIX
    ): String {
        val removeOutputRedirect = when (platform) {
            CommandPlatform.POSIX -> ">/dev/null 2>&1"
            CommandPlatform.WINDOWS -> ">NUL 2>&1"
        }
        val separator = commandSeparator(platform)
        val removeCmd = "codex mcp remove $serverName $removeOutputRedirect"
        val addCmd = "codex mcp add $serverName --url $serverUrl"
        return "$removeCmd$separator$addCmd"
    }

    private fun generateCodexConfig(
        serverUrl: String,
        serverName: String,
        platform: CommandPlatform
    ): String {
        return buildCodexCommand(serverUrl, serverName, platform)
    }

    private fun commandSeparator(platform: CommandPlatform): String =
        when (platform) {
            CommandPlatform.POSIX -> " ; "
            CommandPlatform.WINDOWS -> " & "
        }

    /**
     * Generates Gemini CLI MCP configuration.
     *
     * Uses native Streamable HTTP transport via the httpUrl field.
     * Add this to ~/.gemini/settings.json
     */
    private fun generateGeminiCliConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcpServers": {
    "$serverName": {
      "httpUrl": "$serverUrl"
    }
  }
}
        """.trimIndent()
    }

    /**
     * Generates Cursor MCP configuration.
     *
     * Add this to .cursor/mcp.json in your project root or globally at
     * ~/.cursor/mcp.json
     */
    private fun generateCursorConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcpServers": {
    "$serverName": {
      "url": "$serverUrl"
    }
  }
}
        """.trimIndent()
    }

    /**
     * Generates Streamable HTTP configuration for modern MCP clients.
     */
    fun generateStreamableHttpConfig(serverName: String = getDefaultServerName()): String {
        val serverUrl = getStreamableHttpUrlOrDefault()
        return """
{
  "mcpServers": {
    "$serverName": {
      "url": "$serverUrl"
    }
  }
}
        """.trimIndent()
    }

    /**
     * Generates legacy SSE configuration for older MCP clients.
     */
    fun generateLegacySseConfig(serverName: String = getDefaultServerName()): String {
        val serverUrl = getLegacySseUrlOrDefault()
        return """
{
  "mcpServers": {
    "$serverName": {
      "url": "$serverUrl"
    }
  }
}
        """.trimIndent()
    }

    /**
     * Returns a human-readable description of where to add the configuration
     * for the specified client type.
     */
    fun getConfigLocationHint(clientType: ClientType): String {
        val serverName = getDefaultServerName()
        return when (clientType) {
            ClientType.CLAUDE_CODE -> """
                Runs installation command in your terminal.
                Automatically handles reinstall if already installed (port may change).

                • --scope user: Adds globally for all projects
                • --scope project: Adds to current project only

                To remove manually: claude mcp remove $serverName
            """.trimIndent()

            ClientType.CODEX_CLI -> """
                Runs installation command in your terminal.
                Automatically handles reinstall if already installed (port may change).

                To remove manually: codex mcp remove $serverName
            """.trimIndent()

            ClientType.GEMINI_CLI -> """
                Add to your Gemini CLI settings file:
                • Config file: ~/.gemini/settings.json

                Uses native Streamable HTTP transport via the httpUrl field.
            """.trimIndent()

            ClientType.CURSOR -> """
                Add to your Cursor MCP configuration:
                • Project-local: .cursor/mcp.json in your project root
                • Global: ~/.cursor/mcp.json
            """.trimIndent()
        }
    }

    /**
     * Returns hint text for Streamable HTTP configuration.
     */
    fun getStreamableHttpHint(): String = """
        Standard MCP configuration using Streamable HTTP transport (2025-03-26 spec).
        Use this for any modern MCP client that supports Streamable HTTP natively.
    """.trimIndent()

    /**
     * Returns hint text for legacy SSE configuration.
     */
    fun getLegacySseHint(): String = """
        Legacy MCP configuration using SSE transport (2024-11-05 spec).
        Use this for older MCP clients that don't support Streamable HTTP.
    """.trimIndent()

    /**
     * Returns all available client types for UI display.
     */
    fun getAvailableClients(): List<ClientType> = ClientType.entries

    /**
     * Returns client types that support direct installation commands.
     */
    fun getInstallableClients(): List<ClientType> = ClientType.entries.filter { it.supportsInstallCommand }

    /**
     * Returns client types that can be copied to clipboard.
     */
    fun getCopyableClients(): List<ClientType> = ClientType.entries
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService

/**
 * Generates MCP client configuration snippets for various AI coding assistants.
 *
 * This utility generates ready-to-use configuration for:
 * - Claude Code
 * - Gemini CLI
 * - Cursor
 *
 * Also provides generic configurations:
 * - Standard SSE (for clients with native SSE support)
 * - mcp-remote (for clients without SSE support)
 *
 * All configurations use the HTTP+SSE transport to connect to the IDE's built-in web server.
 */
object ClientConfigGenerator {

    /**
     * Supported MCP client types.
     */
    enum class ClientType(val displayName: String, val supportsInstallCommand: Boolean = false) {
        CLAUDE_CODE("Claude Code", true),
        GEMINI_CLI("Gemini CLI"),
        CURSOR("Cursor")
    }

    /**
     * Generates the MCP configuration for the specified client type.
     *
     * @param clientType The type of MCP client to generate configuration for
     * @param serverName Optional custom name for the server (defaults to "jetbrains-index")
     * @return The configuration string in the appropriate format for the client
     */
    fun generateConfig(clientType: ClientType, serverName: String = "jetbrains-index"): String {
        val serverUrl = McpServerService.getInstance().getServerUrl()

        return when (clientType) {
            ClientType.CLAUDE_CODE -> generateClaudeCodeConfig(serverUrl, serverName)
            ClientType.GEMINI_CLI -> generateGeminiCliConfig(serverUrl, serverName)
            ClientType.CURSOR -> generateCursorConfig(serverUrl, serverName)
        }
    }

    /**
     * Generates the install command for clients that support direct installation.
     *
     * @param clientType The type of MCP client
     * @param serverName Optional custom name for the server
     * @return The install command, or null if the client doesn't support install commands
     */
    fun generateInstallCommand(clientType: ClientType, serverName: String = "jetbrains-index"): String? {
        if (!clientType.supportsInstallCommand) return null
        val serverUrl = McpServerService.getInstance().getServerUrl()

        return when (clientType) {
            ClientType.CLAUDE_CODE -> buildClaudeCodeCommand(serverUrl, serverName)
            else -> null
        }
    }

    /**
     * Builds the Claude Code CLI command for reinstalling the MCP server.
     *
     * Removes any existing installation first (to handle port changes), then adds the server.
     * The remove command uses 2>/dev/null to suppress errors if the server wasn't installed.
     * Uses `;` between commands so add runs regardless of remove's exit status.
     *
     * This method is internal for testing purposes.
     *
     * @param serverUrl The URL of the MCP server
     * @param serverName The name to register the server as
     * @return A shell command that removes and reinstalls the MCP server
     */
    internal fun buildClaudeCodeCommand(serverUrl: String, serverName: String): String {
        val removeCmd = "claude mcp remove $serverName 2>/dev/null"
        val addCmd = "claude mcp add --transport http $serverName $serverUrl --scope user"
        return "$removeCmd ; $addCmd"
    }

    private fun generateClaudeCodeConfig(serverUrl: String, serverName: String): String {
        return buildClaudeCodeCommand(serverUrl, serverName)
    }

    /**
     * Generates Gemini CLI MCP configuration.
     *
     * Uses mcp-remote to bridge SSE to stdio transport.
     * Add this to ~/.gemini/settings.json
     */
    private fun generateGeminiCliConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcpServers": {
    "$serverName": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "$serverUrl",
        "--allow-http"
      ]
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
     * Generates standard SSE configuration for MCP clients with native SSE support.
     */
    fun generateStandardSseConfig(serverName: String = "jetbrains-index"): String {
        val serverUrl = McpServerService.getInstance().getServerUrl()
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
     * Generates mcp-remote configuration for MCP clients without SSE support.
     * Uses npx mcp-remote to bridge SSE to stdio transport.
     */
    fun generateMcpRemoteConfig(serverName: String = "jetbrains-index"): String {
        val serverUrl = McpServerService.getInstance().getServerUrl()
        return """
{
  "mcpServers": {
    "$serverName": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "$serverUrl",
        "--allow-http"
      ]
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
        return when (clientType) {
            ClientType.CLAUDE_CODE -> """
                Runs installation command in your terminal.
                Automatically handles reinstall if already installed (port may change).

                • --scope user: Adds globally for all projects
                • --scope project: Adds to current project only

                To remove manually: claude mcp remove jetbrains-index
            """.trimIndent()

            ClientType.GEMINI_CLI -> """
                Add to your Gemini CLI settings file:
                • Config file: ~/.gemini/settings.json

                Uses mcp-remote to bridge SSE to stdio transport.
                Requires Node.js/npx to be available in your PATH.
            """.trimIndent()

            ClientType.CURSOR -> """
                Add to your Cursor MCP configuration:
                • Project-local: .cursor/mcp.json in your project root
                • Global: ~/.cursor/mcp.json
            """.trimIndent()
        }
    }

    /**
     * Returns hint text for standard SSE configuration.
     */
    fun getStandardSseHint(): String = """
        Standard MCP configuration using SSE (Server-Sent Events) transport.
        Use this for any MCP client that supports the SSE transport natively.
    """.trimIndent()

    /**
     * Returns hint text for mcp-remote configuration.
     */
    fun getMcpRemoteHint(): String = """
        For MCP clients that don't support SSE transport natively.
        Uses mcp-remote to bridge SSE to stdio transport.

        Requires Node.js and npx to be available in your PATH.
        The --allow-http flag is needed for 127.0.0.1 connections.
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

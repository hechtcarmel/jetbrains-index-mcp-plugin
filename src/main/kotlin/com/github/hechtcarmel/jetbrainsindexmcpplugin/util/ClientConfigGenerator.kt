package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService

/**
 * Generates MCP client configuration snippets for various AI coding assistants.
 *
 * This utility generates ready-to-use configuration for:
 * - Claude Code (CLI)
 * - Cursor
 * - VS Code (generic MCP)
 * - Windsurf
 *
 * All configurations use the HTTP+SSE transport to connect to the IDE's built-in web server.
 */
object ClientConfigGenerator {

    /**
     * Supported MCP client types.
     */
    enum class ClientType(val displayName: String) {
        CLAUDE_CODE("Claude Code (CLI)"),
        CURSOR("Cursor"),
        VSCODE("VS Code (Generic MCP)"),
        WINDSURF("Windsurf")
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
            ClientType.CURSOR -> generateCursorConfig(serverUrl, serverName)
            ClientType.VSCODE -> generateVSCodeConfig(serverUrl, serverName)
            ClientType.WINDSURF -> generateWindsurfConfig(serverUrl, serverName)
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
     * Generates generic VS Code MCP configuration.
     *
     * Add to your VS Code settings.json or workspace configuration.
     */
    private fun generateVSCodeConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcp.servers": {
    "$serverName": {
      "transport": "sse",
      "url": "$serverUrl"
    }
  }
}
        """.trimIndent()
    }

    /**
     * Generates Windsurf MCP configuration.
     *
     * Add this to ~/.codeium/windsurf/mcp_config.json
     */
    private fun generateWindsurfConfig(serverUrl: String, serverName: String): String {
        return """
{
  "mcpServers": {
    "$serverName": {
      "serverUrl": "$serverUrl"
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

            ClientType.CURSOR -> """
                Add to your Cursor MCP configuration:
                • Project-local: .cursor/mcp.json in your project root
                • Global: ~/.cursor/mcp.json
            """.trimIndent()

            ClientType.VSCODE -> """
                Add to your VS Code settings:
                • Open Settings (JSON) and add the configuration
                • Or add to .vscode/settings.json in your project
            """.trimIndent()

            ClientType.WINDSURF -> """
                Add to your Windsurf MCP configuration:
                • Config file: ~/.codeium/windsurf/mcp_config.json
            """.trimIndent()
        }
    }

    /**
     * Returns all available client types for UI display.
     */
    fun getAvailableClients(): List<ClientType> = ClientType.entries
}

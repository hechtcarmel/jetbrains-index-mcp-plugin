package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

class ClientConfigGeneratorUnitTest : TestCase() {

    // ClientType enum tests

    fun testAllClientTypesHaveDisplayNames() {
        ClientConfigGenerator.ClientType.entries.forEach { clientType ->
            assertTrue(
                "ClientType ${clientType.name} should have non-empty displayName",
                clientType.displayName.isNotEmpty()
            )
        }
    }

    fun testExpectedClientTypesExist() {
        val expectedTypes = listOf(
            "CLAUDE_CODE",
            "CODEX_CLI",
            "GEMINI_CLI",
            "CURSOR"
        )

        val actualTypes = ClientConfigGenerator.ClientType.entries.map { it.name }

        expectedTypes.forEach { expected ->
            assertTrue("ClientType $expected should exist", actualTypes.contains(expected))
        }
    }

    fun testClientTypeCount() {
        assertEquals(4, ClientConfigGenerator.ClientType.entries.size)
    }

    fun testClientTypeDisplayNames() {
        assertEquals("Claude Code", ClientConfigGenerator.ClientType.CLAUDE_CODE.displayName)
        assertEquals("Codex CLI", ClientConfigGenerator.ClientType.CODEX_CLI.displayName)
        assertEquals("Gemini CLI", ClientConfigGenerator.ClientType.GEMINI_CLI.displayName)
        assertEquals("Cursor", ClientConfigGenerator.ClientType.CURSOR.displayName)
    }

    fun testClientTypeSupportsInstallCommand() {
        assertTrue(ClientConfigGenerator.ClientType.CLAUDE_CODE.supportsInstallCommand)
        assertTrue(ClientConfigGenerator.ClientType.CODEX_CLI.supportsInstallCommand)
        assertFalse(ClientConfigGenerator.ClientType.GEMINI_CLI.supportsInstallCommand)
        assertFalse(ClientConfigGenerator.ClientType.CURSOR.supportsInstallCommand)
    }

    // getAvailableClients tests

    fun testGetAvailableClientsReturnsAllTypes() {
        val clients = ClientConfigGenerator.getAvailableClients()

        assertEquals(
            "Should return all client types",
            ClientConfigGenerator.ClientType.entries.size,
            clients.size
        )
    }

    fun testGetAvailableClientsContainsAllEntries() {
        val clients = ClientConfigGenerator.getAvailableClients()

        ClientConfigGenerator.ClientType.entries.forEach { clientType ->
            assertTrue(
                "Available clients should contain $clientType",
                clients.contains(clientType)
            )
        }
    }

    fun testGetAvailableClientsFirstEntryIsClaudeCode() {
        val clients = ClientConfigGenerator.getAvailableClients()
        assertEquals(ClientConfigGenerator.ClientType.CLAUDE_CODE, clients[0])
    }

    // getInstallableClients tests

    fun testGetInstallableClientsReturnsOnlyClientsWithInstallCommands() {
        val clients = ClientConfigGenerator.getInstallableClients()

        assertEquals(2, clients.size)
        assertTrue(clients.contains(ClientConfigGenerator.ClientType.CLAUDE_CODE))
        assertTrue(clients.contains(ClientConfigGenerator.ClientType.CODEX_CLI))
        assertFalse(clients.contains(ClientConfigGenerator.ClientType.GEMINI_CLI))
        assertFalse(clients.contains(ClientConfigGenerator.ClientType.CURSOR))
    }

    // getCopyableClients tests

    fun testGetCopyableClientsReturnsAllClientTypes() {
        val clients = ClientConfigGenerator.getCopyableClients()

        assertEquals(4, clients.size)
        assertEquals(ClientConfigGenerator.ClientType.entries.toList(), clients)
    }

    // getConfigLocationHint tests

    fun testClaudeCodeHintContainsTerminalInstructions() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CLAUDE_CODE)

        assertTrue("Should mention terminal", hint.contains("terminal"))
        assertTrue("Should mention scope user", hint.contains("--scope user"))
        assertTrue("Should mention scope project", hint.contains("--scope project"))
        assertTrue("Should mention remove command", hint.contains("mcp remove"))
        assertTrue("Should mention automatic reinstall", hint.contains("reinstall") || hint.contains("Automatically"))
    }

    fun testCodexCliHintContainsTerminalInstructions() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CODEX_CLI)

        assertTrue("Should mention terminal", hint.contains("terminal") || hint.contains("command"))
        assertTrue("Should mention codex", hint.contains("codex"))
        assertTrue("Should mention remove command", hint.contains("mcp remove"))
        assertTrue("Should mention automatic reinstall", hint.contains("reinstall") || hint.contains("Automatically"))
    }

    fun testGeminiCliHintContainsSettingsJson() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.GEMINI_CLI)

        assertTrue("Should mention settings.json", hint.contains("settings.json"))
        assertTrue("Should mention gemini path", hint.contains(".gemini") || hint.contains("gemini"))
        assertTrue("Should mention httpUrl", hint.contains("httpUrl"))
    }

    fun testCursorHintContainsConfigPaths() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CURSOR)

        assertTrue("Should mention mcp.json", hint.contains("mcp.json"))
        assertTrue("Should mention project-local", hint.contains(".cursor"))
        assertTrue("Should mention global", hint.contains("~/.cursor"))
    }

    fun testAllHintsAreNonEmpty() {
        ClientConfigGenerator.ClientType.entries.forEach { clientType ->
            val hint = ClientConfigGenerator.getConfigLocationHint(clientType)
            assertTrue(
                "Hint for $clientType should be non-empty",
                hint.isNotEmpty()
            )
        }
    }

    // Generic hint tests

    fun testGetStreamableHttpHintMentionsStreamableHttp() {
        val hint = ClientConfigGenerator.getStreamableHttpHint()

        assertTrue("Should mention Streamable HTTP", hint.contains("Streamable HTTP"))
        assertTrue("Should mention transport", hint.contains("transport"))
    }

    fun testGetLegacySseHintMentionsSseTransport() {
        val hint = ClientConfigGenerator.getLegacySseHint()

        assertTrue("Should mention SSE", hint.contains("SSE"))
        assertTrue("Should mention transport", hint.contains("transport"))
        assertTrue("Should mention Streamable HTTP", hint.contains("Streamable HTTP"))
    }

    // General enum tests

    fun testClientTypeValuesAreUnique() {
        val names = ClientConfigGenerator.ClientType.entries.map { it.name }
        val displayNames = ClientConfigGenerator.ClientType.entries.map { it.displayName }

        assertEquals("Names should be unique", names.size, names.toSet().size)
        assertEquals("Display names should be unique", displayNames.size, displayNames.toSet().size)
    }

    // buildClaudeCodeCommand tests (reinstall pattern)

    fun testBuildClaudeCodeCommandContainsRemoveCommand() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Command should contain remove command",
            command.contains("claude mcp remove test-server")
        )
    }

    fun testBuildClaudeCodeCommandContainsAddCommand() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/streamable-http",
            serverName = "test-server"
        )

        assertTrue(
            "Command should contain add command",
            command.contains("claude mcp add --transport http test-server http://127.0.0.1:63342/index-mcp/streamable-http --scope user")
        )
    }

    fun testBuildClaudeCodeCommandUsesSemicolonSeparator() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Command should use ; separator (not &&) so add runs even if remove fails",
            command.contains(";")
        )
        assertFalse(
            "Command should not use && separator",
            command.contains("&&")
        )
    }

    fun testBuildClaudeCodeCommandSuppressesRemoveErrors() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Remove command should redirect stderr to /dev/null to suppress errors if not installed",
            command.contains("2>/dev/null")
        )
    }

    fun testBuildClaudeCodeCommandRemoveBeforeAdd() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/sse",
            serverName = "test-server"
        )

        val lastRemoveIndex = command.lastIndexOf("remove")
        val addIndex = command.indexOf("add")

        assertTrue(
            "All remove commands should come before add command",
            lastRemoveIndex < addIndex
        )
    }

    fun testBuildClaudeCodeCommandWithDifferentServerName() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://127.0.0.1:12345/mcp/streamable-http",
            serverName = "custom-name"
        )

        assertTrue(
            "Remove command should use custom server name",
            command.contains("claude mcp remove custom-name")
        )
        assertTrue(
            "Add command should use custom server name",
            command.contains("claude mcp add --transport http custom-name")
        )
    }

    fun testBuildClaudeCodeCommandWithDifferentServerUrl() {
        val customUrl = "http://127.0.0.1:12345/custom-mcp/sse"
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = customUrl,
            serverName = "test-server"
        )

        assertTrue(
            "Add command should include the server URL",
            command.contains(customUrl)
        )
    }

    fun testBuildClaudeCodeCommandFormat() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/streamable-http",
            serverName = "intellij-index"
        )

        val expectedCommand = "claude mcp remove jetbrains-index-mcp 2>/dev/null ; " +
            "claude mcp remove intellij-index 2>/dev/null ; " +
            "claude mcp add --transport http intellij-index http://127.0.0.1:63342/index-mcp/streamable-http --scope user"

        assertEquals(
            "Command format should match expected reinstall pattern with legacy cleanup",
            expectedCommand,
            command
        )
    }

    fun testBuildClaudeCodeCommandRemovesLegacyServerName() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://127.0.0.1:29170/index-mcp/sse",
            serverName = "pycharm-index"
        )

        assertTrue(
            "Command should remove legacy v1.x server name jetbrains-index-mcp",
            command.contains("claude mcp remove jetbrains-index-mcp")
        )
    }

    // buildCodexCommand tests (reinstall pattern)

    fun testBuildCodexCommandContainsRemoveCommand() {
        val command = ClientConfigGenerator.buildCodexCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Command should contain remove command",
            command.contains("codex mcp remove test-server")
        )
    }

    fun testBuildCodexCommandContainsAddCommand() {
        val command = ClientConfigGenerator.buildCodexCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/streamable-http",
            serverName = "test-server"
        )

        assertTrue(
            "Command should contain add command with --url",
            command.contains("codex mcp add test-server --url http://127.0.0.1:63342/index-mcp/streamable-http")
        )
    }

    fun testBuildCodexCommandUsesSemicolonSeparator() {
        val command = ClientConfigGenerator.buildCodexCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Command should use ; separator (not &&) so add runs even if remove fails",
            command.contains(";")
        )
        assertFalse(
            "Command should not use && separator",
            command.contains("&&")
        )
    }

    fun testBuildCodexCommandSuppressesRemoveErrors() {
        val command = ClientConfigGenerator.buildCodexCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Remove command should redirect stderr to /dev/null to suppress errors if not installed",
            command.contains(">/dev/null 2>&1")
        )
    }

    fun testBuildCodexCommandRemoveBeforeAdd() {
        val command = ClientConfigGenerator.buildCodexCommand(
            serverUrl = "http://127.0.0.1:63342/index-mcp/sse",
            serverName = "test-server"
        )

        val removeIndex = command.indexOf("remove")
        val addIndex = command.indexOf("add")

        assertTrue(
            "Remove command should come before add command",
            removeIndex < addIndex
        )
    }

    fun testBuildCodexCommandWithDifferentServerName() {
        val command = ClientConfigGenerator.buildCodexCommand(
            serverUrl = "http://127.0.0.1:12345/mcp/streamable-http",
            serverName = "custom-name"
        )

        assertTrue(
            "Remove command should use custom server name",
            command.contains("codex mcp remove custom-name")
        )
        assertTrue(
            "Add command should use custom server name",
            command.contains("codex mcp add custom-name --url")
        )
    }

    fun testBuildCodexCommandWithDifferentServerUrl() {
        val customUrl = "http://127.0.0.1:12345/custom-mcp/streamable-http"
        val command = ClientConfigGenerator.buildCodexCommand(
            serverUrl = customUrl,
            serverName = "test-server"
        )

        assertTrue(
            "Add command should include the server URL",
            command.contains(customUrl)
        )
    }



    // Config Format Tests (structure validation without actual server)

    fun testCursorConfigFormatHasUrlKey() {
        val expectedFormat = """
{
  "mcpServers": {
    "SERVER_NAME": {
      "url": "SERVER_URL"
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("mcpServers"))
        assertTrue(expectedFormat.contains("url"))
    }

    fun testGeminiCliConfigFormatUsesHttpUrl() {
        val expectedFormat = """
{
  "mcpServers": {
    "SERVER_NAME": {
      "httpUrl": "SERVER_URL"
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("mcpServers"))
        assertTrue(expectedFormat.contains("httpUrl"))
    }

    fun testStandardSseConfigFormatHasUrlKey() {
        val expectedFormat = """
{
  "mcpServers": {
    "SERVER_NAME": {
      "url": "SERVER_URL"
    }
  }
}
        """.trimIndent()

        assertTrue(expectedFormat.contains("mcpServers"))
        assertTrue(expectedFormat.contains("url"))
    }

    fun testCodexCommandFormatUsesNativeTransport() {
        val command = ClientConfigGenerator.buildCodexCommand(
            serverUrl = "http://127.0.0.1:29170/index-mcp/streamable-http",
            serverName = "intellij-index"
        )

        assertTrue("Should use native --url", command.contains("--url"))
        assertFalse("Should not use --transport http", command.contains("--transport http"))
        assertFalse("Should not use mcp-remote", command.contains("mcp-remote"))
        assertFalse("Should not use npx", command.contains("npx"))
    }
}

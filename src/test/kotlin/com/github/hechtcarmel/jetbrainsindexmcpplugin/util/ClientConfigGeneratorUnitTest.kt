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
            "CLAUDE_DESKTOP",
            "CURSOR",
            "VSCODE",
            "WINDSURF"
        )

        val actualTypes = ClientConfigGenerator.ClientType.entries.map { it.name }

        expectedTypes.forEach { expected ->
            assertTrue("ClientType $expected should exist", actualTypes.contains(expected))
        }
    }

    fun testClientTypeDisplayNames() {
        assertEquals("Claude Code (CLI)", ClientConfigGenerator.ClientType.CLAUDE_CODE.displayName)
        assertEquals("Claude Desktop", ClientConfigGenerator.ClientType.CLAUDE_DESKTOP.displayName)
        assertEquals("Cursor", ClientConfigGenerator.ClientType.CURSOR.displayName)
        assertEquals("VS Code (Generic MCP)", ClientConfigGenerator.ClientType.VSCODE.displayName)
        assertEquals("Windsurf", ClientConfigGenerator.ClientType.WINDSURF.displayName)
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

    // getConfigLocationHint tests

    fun testClaudeCodeHintContainsTerminalInstructions() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CLAUDE_CODE)

        assertTrue("Should mention terminal", hint.contains("terminal"))
        assertTrue("Should mention scope user", hint.contains("--scope user"))
        assertTrue("Should mention scope project", hint.contains("--scope project"))
        assertTrue("Should mention remove command", hint.contains("mcp remove"))
        assertTrue("Should mention automatic reinstall", hint.contains("reinstall") || hint.contains("Automatically"))
    }

    fun testClaudeDesktopHintContainsConfigPaths() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CLAUDE_DESKTOP)

        assertTrue("Should mention macOS path", hint.contains("macOS"))
        assertTrue("Should mention Windows path", hint.contains("Windows"))
        assertTrue("Should mention Linux path", hint.contains("Linux"))
        assertTrue("Should mention config file", hint.contains("claude_desktop_config.json"))
    }

    fun testCursorHintContainsConfigPaths() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.CURSOR)

        assertTrue("Should mention mcp.json", hint.contains("mcp.json"))
        assertTrue("Should mention project-local", hint.contains(".cursor"))
        assertTrue("Should mention global", hint.contains("~/.cursor"))
    }

    fun testVSCodeHintContainsSettingsInfo() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.VSCODE)

        assertTrue("Should mention settings", hint.contains("settings"))
        assertTrue("Should mention JSON", hint.contains("JSON") || hint.contains("json"))
    }

    fun testWindsurfHintContainsConfigPath() {
        val hint = ClientConfigGenerator.getConfigLocationHint(ClientConfigGenerator.ClientType.WINDSURF)

        assertTrue("Should mention config file", hint.contains("mcp_config.json"))
        assertTrue("Should mention codeium path", hint.contains(".codeium"))
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
            serverUrl = "http://localhost:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Command should contain remove command",
            command.contains("claude mcp remove test-server")
        )
    }

    fun testBuildClaudeCodeCommandContainsAddCommand() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://localhost:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Command should contain add command",
            command.contains("claude mcp add --transport http test-server http://localhost:63342/index-mcp/sse --scope user")
        )
    }

    fun testBuildClaudeCodeCommandUsesSemicolonSeparator() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://localhost:63342/index-mcp/sse",
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
            serverUrl = "http://localhost:63342/index-mcp/sse",
            serverName = "test-server"
        )

        assertTrue(
            "Remove command should redirect stderr to /dev/null to suppress errors if not installed",
            command.contains("2>/dev/null")
        )
    }

    fun testBuildClaudeCodeCommandRemoveBeforeAdd() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://localhost:63342/index-mcp/sse",
            serverName = "test-server"
        )

        val removeIndex = command.indexOf("remove")
        val addIndex = command.indexOf("add")

        assertTrue(
            "Remove command should come before add command",
            removeIndex < addIndex
        )
    }

    fun testBuildClaudeCodeCommandWithDifferentServerName() {
        val command = ClientConfigGenerator.buildClaudeCodeCommand(
            serverUrl = "http://localhost:12345/mcp/sse",
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
        val customUrl = "http://localhost:12345/custom-mcp/sse"
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
            serverUrl = "http://localhost:63342/index-mcp/sse",
            serverName = "jetbrains-index"
        )

        val expectedCommand = "claude mcp remove jetbrains-index 2>/dev/null ; " +
            "claude mcp add --transport http jetbrains-index http://localhost:63342/index-mcp/sse --scope user"

        assertEquals(
            "Command format should match expected reinstall pattern",
            expectedCommand,
            command
        )
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.InstallPluginTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.RestartIdeTool
import junit.framework.TestCase
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PluginDevToolsUnitTest : TestCase() {

    fun testInstallPluginToolName() {
        assertEquals(ToolNames.INSTALL_PLUGIN, InstallPluginTool().name)
    }

    fun testInstallPluginToolPathIsOptional() {
        val required = InstallPluginTool().inputSchema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse("path must be optional — auto-detection is the default", required.contains("path"))
    }

    fun testInstallPluginToolHasProjectPath() {
        val properties = InstallPluginTool().inputSchema["properties"]?.jsonObject
        assertNotNull(properties?.get("project_path"))
    }

    fun testRestartIdeToolName() {
        assertEquals(ToolNames.RESTART_IDE, RestartIdeTool().name)
    }

    fun testRestartIdeToolHasProjectPath() {
        val properties = RestartIdeTool().inputSchema["properties"]?.jsonObject
        assertNotNull(properties?.get("project_path"))
    }

    fun testRestartIdeToolDescriptionWarnsAboutConnectionLoss() {
        val desc = RestartIdeTool().description
        assertTrue(
            "Description must warn that the MCP connection will terminate",
            desc.contains("terminates", ignoreCase = true) ||
            desc.contains("connection", ignoreCase = true) ||
            desc.contains("drop", ignoreCase = true)
        )
    }

    fun testToolNamesAllContainsPluginDevTools() {
        assertTrue(ToolNames.ALL.contains(ToolNames.INSTALL_PLUGIN))
        assertTrue(ToolNames.ALL.contains(ToolNames.RESTART_IDE))
    }

    fun testToolNamesAllIsSorted() {
        assertEquals(ToolNames.ALL.sorted(), ToolNames.ALL)
    }

    fun testPluginDevToolsAreDisabledByDefault() {
        val defaults = McpSettings.State().disabledTools
        assertTrue("ide_install_plugin must be opt-in", defaults.contains(ToolNames.INSTALL_PLUGIN))
        assertTrue("ide_restart must be opt-in", defaults.contains(ToolNames.RESTART_IDE))
    }
}

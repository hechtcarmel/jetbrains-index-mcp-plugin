package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.CloseProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.OpenProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.ReloadProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.SetPowerSaveModeTool
import junit.framework.TestCase
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProjectWindowToolsUnitTest : TestCase() {

    fun testSetPowerSaveModeToolName() {
        assertEquals(ToolNames.SET_POWER_SAVE_MODE, SetPowerSaveModeTool().name)
    }

    fun testSetPowerSaveModeToolRequiresEnabled() {
        val required = SetPowerSaveModeTool().inputSchema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(required)
        assertTrue(required!!.contains("enabled"))
    }

    fun testSetPowerSaveModeToolProjectPathIsOptional() {
        val required = SetPowerSaveModeTool().inputSchema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse(required.contains("project_path"))
    }

    fun testCloseProjectToolName() {
        assertEquals(ToolNames.CLOSE_PROJECT, CloseProjectTool().name)
    }

    fun testCloseProjectToolHasNoRequiredFields() {
        val required = CloseProjectTool().inputSchema["required"]?.jsonArray
        assertTrue(required == null || required.isEmpty())
    }

    fun testOpenProjectToolName() {
        assertEquals(ToolNames.OPEN_PROJECT, OpenProjectTool().name)
    }

    fun testOpenProjectToolRequiresPath() {
        val required = OpenProjectTool().inputSchema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(required)
        assertTrue(required!!.contains("path"))
    }

    fun testOpenProjectToolProjectPathIsOptional() {
        val required = OpenProjectTool().inputSchema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse(required.contains("project_path"))
    }

    fun testOpenProjectToolHasOptionalTimeoutSeconds() {
        val schema = OpenProjectTool().inputSchema
        val properties = schema["properties"]?.jsonObject
        assertNotNull(properties)
        assertTrue(properties!!.containsKey("timeoutSeconds"))
        val required = schema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse(required.contains("timeoutSeconds"))
    }

    fun testAllToolsInToolNamesAll() {
        assertTrue(ToolNames.ALL.contains(ToolNames.SET_POWER_SAVE_MODE))
        assertTrue(ToolNames.ALL.contains(ToolNames.CLOSE_PROJECT))
        assertTrue(ToolNames.ALL.contains(ToolNames.OPEN_PROJECT))
    }

    fun testToolNamesAllRemainsAlphabeticallySorted() {
        assertEquals(ToolNames.ALL.sorted(), ToolNames.ALL)
    }

    fun testProjectWindowToolsAreDisabledByDefault() {
        val defaults = McpSettings.State().disabledTools
        assertTrue("ide_close_project must be opt-in", defaults.contains(ToolNames.CLOSE_PROJECT))
        assertTrue("ide_open_project must be opt-in", defaults.contains(ToolNames.OPEN_PROJECT))
        assertTrue("ide_set_power_save_mode must be opt-in", defaults.contains(ToolNames.SET_POWER_SAVE_MODE))
    }

    fun testReloadProjectToolName() {
        assertEquals(ToolNames.RELOAD_PROJECT, ReloadProjectTool().name)
    }

    fun testReloadProjectToolHasNoRequiredFields() {
        val required = ReloadProjectTool().inputSchema["required"]?.jsonArray
        assertTrue(required == null || required.isEmpty())
    }

    fun testReloadProjectToolIsDisabledByDefault() {
        val defaults = McpSettings.State().disabledTools
        assertTrue("ide_reload_project must be opt-in by default", defaults.contains(ToolNames.RELOAD_PROJECT))
    }
}

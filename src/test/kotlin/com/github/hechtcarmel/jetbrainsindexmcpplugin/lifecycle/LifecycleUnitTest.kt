package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.LifecycleEventLog
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.EnrollAllProjectsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.GetProjectModesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.LifecycleLogTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.SetLifecycleLogFileTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.ReleaseAllProjectsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.ReleaseProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.SetAllProjectModesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.SetProjectModeTool
import junit.framework.TestCase
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LifecycleUnitTest : TestCase() {

    fun testProjectModeHasExactlyFourStates() {
        val values = ProjectMode.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(ProjectMode.ACTIVE))
        assertTrue(values.contains(ProjectMode.BACKGROUND))
        assertTrue(values.contains(ProjectMode.DORMANT))
        assertTrue(values.contains(ProjectMode.CLOSED))
    }

    fun testStateDefaultsAreEmpty() {
        val state = ProjectModeService.State()
        assertTrue(state.closedProjectPaths.isEmpty())
        assertTrue(state.managedProjectPaths.isEmpty())
    }

    fun testStateClosedAndManagedAreIndependentSets() {
        val state = ProjectModeService.State()
        state.closedProjectPaths.add("/closed/project")
        state.managedProjectPaths.add("/managed/project")

        assertFalse(state.closedProjectPaths.contains("/managed/project"))
        assertFalse(state.managedProjectPaths.contains("/closed/project"))
    }

    fun testLifecycleSettingsDefaultValues() {
        val state = McpSettings.State()
        assertFalse(state.lifecycleEnabled)
        assertEquals(2, state.focusToBackgroundMinutes)
        assertEquals(2, state.backgroundToDormantMinutes)
        assertEquals(10, state.dormantToClosedMinutes)
        assertEquals(LifecycleEventLog.DEFAULT_CAPACITY, state.lifecycleLogBufferSize)
    }

    fun testMinimumOpenProjectsDefaultIs4() {
        assertEquals(4, McpSettings.State().minimumOpenProjects)
    }


    fun testMinimumOpenProjectsIsConfigurable() {
        val settings = McpSettings()
        settings.loadState(McpSettings.State(minimumOpenProjects = 6))
        assertEquals(6, settings.minimumOpenProjects)
    }


    fun testLifecycleLogBufferSizeIsConfigurable() {
        val settings = McpSettings()
        settings.loadState(McpSettings.State(lifecycleLogBufferSize = 2000))
        assertEquals(2000, settings.lifecycleLogBufferSize)
    }

    fun testLifecycleSettingsRoundTrip() {
        val settings = McpSettings()
        settings.loadState(McpSettings.State(
            lifecycleEnabled = false,
            focusToBackgroundMinutes = 7,
            backgroundToDormantMinutes = 4,
            dormantToClosedMinutes = 15
        ))

        assertFalse(settings.lifecycleEnabled)
        assertEquals(7, settings.focusToBackgroundMinutes)
        assertEquals(4, settings.backgroundToDormantMinutes)
        assertEquals(15, settings.dormantToClosedMinutes)
    }

    fun testLifecycleSettingsGetStateReflectsChanges() {
        val settings = McpSettings()
        settings.lifecycleEnabled = false
        settings.dormantToClosedMinutes = 30

        assertFalse(settings.getState().lifecycleEnabled)
        assertEquals(30, settings.getState().dormantToClosedMinutes)
    }

    fun testSetProjectModeToolName() {
        assertEquals(ToolNames.SET_PROJECT_MODE, SetProjectModeTool().name)
    }

    fun testSetProjectModeToolDescriptionMentionsAllModes() {
        val desc = SetProjectModeTool().description
        assertTrue(desc.contains("active"))
        assertTrue(desc.contains("background"))
        assertTrue(desc.contains("dormant"))
        assertTrue(desc.contains("closed"))
    }

    fun testSetProjectModeToolModeIsRequired() {
        val schema = SetProjectModeTool().inputSchema
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(required)
        assertTrue(required!!.contains("mode"))
    }

    fun testSetProjectModeToolModeEnumMatchesProjectMode() {
        val schema = SetProjectModeTool().inputSchema
        val modeEnum = schema["properties"]?.jsonObject
            ?.get("mode")?.jsonObject
            ?.get("enum")?.jsonArray
            ?.map { it.jsonPrimitive.content }

        assertNotNull("mode must declare an enum constraint", modeEnum)
        assertEquals(4, modeEnum!!.size)
        assertTrue(modeEnum.contains("active"))
        assertTrue(modeEnum.contains("background"))
        assertTrue(modeEnum.contains("dormant"))
        assertTrue(modeEnum.contains("closed"))
    }

    fun testSetProjectModeToolProjectPathIsOptional() {
        val required = SetProjectModeTool().inputSchema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse(required.contains("project_path"))
    }

    fun testGetProjectModesToolName() {
        assertEquals(ToolNames.GET_PROJECT_MODES, GetProjectModesTool().name)
    }

    fun testGetProjectModesToolHasNoRequiredFields() {
        val required = GetProjectModesTool().inputSchema["required"]?.jsonArray
        assertTrue(required == null || required.isEmpty())
    }

    fun testReleaseProjectToolName() {
        assertEquals(ToolNames.RELEASE_PROJECT, ReleaseProjectTool().name)
    }

    fun testReleaseProjectToolHasNoRequiredFields() {
        val required = ReleaseProjectTool().inputSchema["required"]?.jsonArray
        assertTrue(required == null || required.isEmpty())
    }

    fun testToolNamesAllContainsLifecycleTools() {
        assertTrue(ToolNames.ALL.contains(ToolNames.ENROLL_ALL_PROJECTS))
        assertTrue(ToolNames.ALL.contains(ToolNames.GET_PROJECT_MODES))
        assertTrue(ToolNames.ALL.contains(ToolNames.LIFECYCLE_LOG))
        assertTrue(ToolNames.ALL.contains(ToolNames.PROJECT_STATUS))
        assertTrue(ToolNames.ALL.contains(ToolNames.RELEASE_ALL_PROJECTS))
        assertTrue(ToolNames.ALL.contains(ToolNames.RELEASE_PROJECT))
        assertTrue(ToolNames.ALL.contains(ToolNames.SET_ALL_PROJECT_MODES))
        assertTrue(ToolNames.ALL.contains(ToolNames.SET_PROJECT_MODE))
    }

    fun testEnrollAllProjectsToolName() {
        assertEquals(ToolNames.ENROLL_ALL_PROJECTS, EnrollAllProjectsTool().name)
    }

    fun testEnrollAllProjectsToolHasNoRequiredFields() {
        val required = EnrollAllProjectsTool().inputSchema["required"]?.jsonArray
        assertTrue(required == null || required.isEmpty())
    }

    fun testReleaseAllProjectsToolName() {
        assertEquals(ToolNames.RELEASE_ALL_PROJECTS, ReleaseAllProjectsTool().name)
    }

    fun testReleaseAllProjectsToolHasNoRequiredFields() {
        val required = ReleaseAllProjectsTool().inputSchema["required"]?.jsonArray
        assertTrue(required == null || required.isEmpty())
    }

    fun testReleaseProjectToolPathParamIsOptional() {
        val required = ReleaseProjectTool().inputSchema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse("path must be optional", required.contains("path"))
    }

    fun testLifecycleLogToolName() {
        assertEquals(ToolNames.LIFECYCLE_LOG, LifecycleLogTool().name)
    }

    fun testSetLifecycleLogFileToolName() {
        assertEquals(ToolNames.LIFECYCLE_LOG_FILE, SetLifecycleLogFileTool().name)
    }

    fun testSetLifecycleLogFileToolRequiresEnabled() {
        val required = SetLifecycleLogFileTool().inputSchema["required"]?.jsonArray
        assertNotNull("schema must have required fields", required)
        assertTrue("enabled must be required", required!!.any { it.jsonPrimitive.content == "enabled" })
    }

    fun testLifecycleLogToolHasNoRequiredFields() {
        val required = LifecycleLogTool().inputSchema["required"]?.jsonArray
        assertTrue(required == null || required.isEmpty())
    }

    fun testLifecycleLogToolSchemaHasLimitAndProjectParams() {
        val props = LifecycleLogTool().inputSchema["properties"]?.jsonObject
        assertNotNull("schema must have a properties object", props)
        assertNotNull("schema must include limit", props!!["limit"])
        assertNotNull("schema must include project", props["project"])
    }

    fun testLifecycleLogToolDescriptionDocumentsKeyTriggers() {
        val desc = LifecycleLogTool().description
        assertTrue(desc.contains("timer:inactivity"))
        assertTrue(desc.contains("mcp_call"))
        assertTrue(desc.contains("auto_open"))
        assertTrue(desc.contains("log_file"))
    }

    fun testLifecycleToolNameConstants() {
        assertEquals("ide_enroll_all_projects", ToolNames.ENROLL_ALL_PROJECTS)
        assertEquals("ide_get_project_modes", ToolNames.GET_PROJECT_MODES)
        assertEquals("ide_lifecycle_log", ToolNames.LIFECYCLE_LOG)
        assertEquals("ide_set_lifecycle_log_file", ToolNames.LIFECYCLE_LOG_FILE)
        assertEquals("ide_project_status", ToolNames.PROJECT_STATUS)
        assertEquals("ide_release_all_projects", ToolNames.RELEASE_ALL_PROJECTS)
        assertEquals("ide_release_project", ToolNames.RELEASE_PROJECT)
        assertEquals("ide_set_all_project_modes", ToolNames.SET_ALL_PROJECT_MODES)
        assertEquals("ide_set_project_mode", ToolNames.SET_PROJECT_MODE)
    }

    fun testLifecycleToolsAreDisabledByDefault() {
        val defaults = McpSettings.State().disabledTools
        listOf(
            ToolNames.ENROLL_ALL_PROJECTS, ToolNames.GET_PROJECT_MODES, ToolNames.LIFECYCLE_LOG,
            ToolNames.LIFECYCLE_LOG_FILE, ToolNames.RELEASE_ALL_PROJECTS,
            ToolNames.RELEASE_PROJECT, ToolNames.SET_ALL_PROJECT_MODES, ToolNames.SET_PROJECT_MODE
        ).forEach { tool ->
            assertTrue("$tool must be opt-in by default", defaults.contains(tool))
        }
    }

    fun testProjectStatusIsEnabledByDefault() {
        // ide_project_status is read-only and required for Claudes to discover project paths
        // so they can self-navigate without asking the user to open projects manually.
        val defaults = McpSettings.State().disabledTools
        assertFalse("ide_project_status must be enabled by default", defaults.contains(ToolNames.PROJECT_STATUS))
    }

    fun testSetAllProjectModesToolName() {
        assertEquals(ToolNames.SET_ALL_PROJECT_MODES, SetAllProjectModesTool().name)
    }

    fun testSetAllProjectModesToolExcludesClosedFromEnum() {
        val schema = SetAllProjectModesTool().inputSchema
        val modeEnum = schema["properties"]?.jsonObject
            ?.get("mode")?.jsonObject
            ?.get("enum")?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertNotNull(modeEnum)
        assertTrue(modeEnum!!.contains("active"))
        assertTrue(modeEnum.contains("background"))
        assertTrue(modeEnum.contains("dormant"))
        assertFalse("closed must not be in the enum — CLOSED projects have no Project object", modeEnum.contains("closed"))
    }

    fun testToolNamesAllIsSorted() {
        assertEquals(ToolNames.ALL.sorted(), ToolNames.ALL)
    }

}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.get

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
import com.intellij.util.xmlb.XmlSerializer
import junit.framework.TestCase
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LifecycleUnitTest : TestCase() {

    fun testStateDefaultsAreEmpty() {
        val state = ProjectModeService.State()
        assertTrue(state.closedProjectPaths.isEmpty())
        assertTrue(state.managedProjectPaths.isEmpty())
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

    fun testSetProjectModeToolModeIsRequired() {
        val schema = SetProjectModeTool().inputSchema
        val required = schema.required
        assertNotNull(required)
        assertTrue(required!!.contains("mode"))
    }

    fun testSetProjectModeToolModeEnumMatchesProjectMode() {
        val schema = SetProjectModeTool().inputSchema
        val modeEnum = schema.properties
            ?.get("mode")?.jsonObject
            ?.get("enum")?.jsonArray
            ?.map { it.jsonPrimitive.content }

        assertNotNull("mode must declare an enum constraint", modeEnum)
        assertEquals(
            "every ProjectMode must be reachable through the schema enum",
            ProjectMode.entries.map { it.name.lowercase() }.sorted(),
            modeEnum!!.sorted()
        )
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
        val modeEnum = schema.properties
            ?.get("mode")?.jsonObject
            ?.get("enum")?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertNotNull(modeEnum)
        assertTrue(modeEnum!!.contains("active"))
        assertTrue(modeEnum.contains("background"))
        assertTrue(modeEnum.contains("dormant"))
        assertFalse("closed must not be in the enum — CLOSED projects have no Project object", modeEnum.contains("closed"))
    }

    // ── Issue #369: the log must say what happened, not just when ───────────────────────────

    fun testLogLineNamesTheEventWhenThereIsNoModeChange() {
        // The bare "[mcp_call] kmo3" line was read as "an MCP call reset the timer here" when it
        // was the one-off enrollment; naming the event removes the ambiguity.
        val entry = LifecycleEventLog.Entry(
            timestampMs = 0L, project = "kmo3", path = "D:/develop/kmo3", event = "enroll", trigger = "mcp_call"
        )
        assertEquals("1970-01-01T00:00:00Z [mcp_call] kmo3: enroll  (D:/develop/kmo3)", entry.toLogLine())
    }

    fun testLogLineShowsTheModeChangeAndTheDetail() {
        val entry = LifecycleEventLog.Entry(
            timestampMs = 0L, project = "kmo3", path = "D:/develop/kmo3", event = "transition",
            from = "background", to = "dormant", trigger = "timer:inactivity", detail = "no MCP call for 2m 1s"
        )
        assertEquals(
            "1970-01-01T00:00:00Z [timer:inactivity] kmo3: background→dormant — no MCP call for 2m 1s  (D:/develop/kmo3)",
            entry.toLogLine()
        )
    }

    fun testLogLineDoesNotRepeatATriggerThatAlreadyNamesTheEvent() {
        val entry = LifecycleEventLog.Entry(
            timestampMs = 0L, project = "kmo3", path = "D:/develop/kmo3", event = "focus_lost", trigger = "focus_lost"
        )
        assertEquals("1970-01-01T00:00:00Z [focus_lost] kmo3  (D:/develop/kmo3)", entry.toLogLine())
    }

    fun testJsonCarriesDetailOnlyWhenPresent() {
        val without = LifecycleEventLog.Entry(project = "p", path = "/p", event = "enroll", trigger = "mcp_call").toJson()
        assertNull("no detail → no key, so clients can rely on its presence meaning something", without["detail"])

        val with = LifecycleEventLog.Entry(
            project = "p", path = "/p", event = "enroll", trigger = "mcp_call", detail = "window focused → active"
        ).toJson()
        assertEquals("window focused → active", with["detail"]?.jsonPrimitive?.content)
        assertEquals("enroll", with["event"]?.jsonPrimitive?.content)
    }

    fun testFormatDurationIsCoarseAndNeverNegative() {
        assertEquals("0s", LifecycleEventLog.formatDuration(0))
        assertEquals("45s", LifecycleEventLog.formatDuration(45_999))
        assertEquals("2m 1s", LifecycleEventLog.formatDuration(121_000))
        assertEquals("1h 3m", LifecycleEventLog.formatDuration(3_780_000))
        assertEquals("0s", LifecycleEventLog.formatDuration(-5_000))
    }

    fun testFormatTimeOfDayIsUtcLikeTheLogTimestamps() {
        assertEquals("00:00:00Z", LifecycleEventLog.formatTimeOfDay(0L))
        assertEquals("09:51:08Z", LifecycleEventLog.formatTimeOfDay(9 * 3_600_000L + 51 * 60_000L + 8_000L))
    }

    fun testDormantEditorsSurviveXmlStatePersistence() {
        // The component store serializes State through the platform's XML serializer; a shape it
        // cannot round-trip would silently drop the remembered tabs on every IDE restart.
        val state = ProjectModeService.State()
        state.managedProjectPaths.add("/proj")
        state.dormantEditors["/proj"] = ProjectModeService.DormantEditors(
            fileUrls = mutableListOf("file:///proj/A.kt", "jar:///lib/x.jar!/B.class"),
            selectedFileUrl = "file:///proj/A.kt"
        )

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), ProjectModeService.State::class.java)

        assertEquals(setOf("/proj"), restored.managedProjectPaths)
        val editors = restored.dormantEditors["/proj"]
        assertNotNull("dormant editors must survive XML persistence", editors)
        assertEquals(listOf("file:///proj/A.kt", "jar:///lib/x.jar!/B.class"), editors!!.fileUrls)
        assertEquals("file:///proj/A.kt", editors.selectedFileUrl)
    }

    fun testStateWithoutDormantEditorsStillLoads() {
        // Persisted state written by an older plugin version has no dormantEditors element.
        val legacy = ProjectModeService.State()
        legacy.managedProjectPaths.add("/proj")
        val element = XmlSerializer.serialize(legacy)
        element.children.removeIf { it.getAttributeValue("name") == "dormantEditors" }

        val restored = XmlSerializer.deserialize(element, ProjectModeService.State::class.java)

        assertEquals(setOf("/proj"), restored.managedProjectPaths)
        assertTrue(restored.dormantEditors.isEmpty())
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.EnrollAllProjectsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.GetProjectModesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.GetIndexStatusTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.ReleaseAllProjectsTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.ReleaseProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.SetAllProjectModesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.lifecycle.SetProjectModeTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LifecycleToolsTest : BasePlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var modeService: ProjectModeService

    private var previousLifecycleEnabled = false

    override fun setUp() {
        super.setUp()
        modeService = ProjectModeService.getInstance()
        modeService.loadState(ProjectModeService.State())
        previousLifecycleEnabled = McpSettings.getInstance().lifecycleEnabled
        McpSettings.getInstance().lifecycleEnabled = true
    }

    override fun tearDown() {
        try {
            if (modeService.isManaged(project)) modeService.release(project)
            modeService.loadState(ProjectModeService.State())
            McpSettings.getInstance().lifecycleEnabled = previousLifecycleEnabled
        } finally {
            super.tearDown()
        }
    }

    private fun resultText(result: ToolCallResult): String =
        (result.content.firstOrNull() as? ContentBlock.Text)?.text ?: ""

    fun testSetProjectModeToolReturnErrorWhenModeIsMissing() = runBlocking {
        val result = SetProjectModeTool().execute(project, buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("mode", ignoreCase = true))
    }

    fun testSetProjectModeToolReturnErrorWhenModeIsInvalid() = runBlocking {
        val result = SetProjectModeTool().execute(project, buildJsonObject { put("mode", "TURBO_SLEEP") })

        assertTrue(result.isError)
        val text = resultText(result)
        assertTrue(text.contains("TURBO_SLEEP", ignoreCase = true))
        // Error must name all valid modes so the caller knows how to fix it
        assertTrue(text.contains("active"))
        assertTrue(text.contains("background"))
        assertTrue(text.contains("dormant"))
        assertTrue(text.contains("closed"))
    }

    fun testSetProjectModeToolSetsActiveMode() = runBlocking {
        val result = SetProjectModeTool().execute(project, buildJsonObject { put("mode", "active") })

        assertFalse(result.isError)
        assertEquals(ProjectMode.ACTIVE, modeService.getMode(project))
    }

    fun testSetProjectModeToolSetsBackgroundMode() = runBlocking {
        modeService.enroll(project)
        modeService.transition(project, ProjectMode.ACTIVE)

        val result = SetProjectModeTool().execute(project, buildJsonObject { put("mode", "background") })

        assertFalse(result.isError)
        assertEquals(ProjectMode.BACKGROUND, modeService.getMode(project))
    }

    fun testSetProjectModeToolSetsDormantMode() = runBlocking {
        val result = SetProjectModeTool().execute(project, buildJsonObject { put("mode", "dormant") })

        assertFalse(result.isError)
        assertEquals(ProjectMode.DORMANT, modeService.getMode(project))
    }

    fun testSetProjectModeToolAutoEnrollsProject() = runBlocking {
        assertFalse(modeService.isManaged(project))

        SetProjectModeTool().execute(project, buildJsonObject { put("mode", "background") })

        assertTrue(modeService.isManaged(project))
    }

    fun testSetProjectModeToolModeIsCaseInsensitive() = runBlocking {
        val result = SetProjectModeTool().execute(project, buildJsonObject { put("mode", "ACTIVE") })
        assertFalse(result.isError)
        assertEquals(ProjectMode.ACTIVE, modeService.getMode(project))
    }

    fun testGetProjectModesToolReturnsHelpfulMessageWhenNothingManaged() = runBlocking {
        val settings = McpSettings.getInstance()
        val previouslyEnabled = settings.lifecycleEnabled
        settings.lifecycleEnabled = false
        try {
            val result = GetProjectModesTool().execute(project, buildJsonObject { })

            assertFalse(result.isError)
            val text = resultText(result)
            assertTrue(
                text.contains("No projects", ignoreCase = true) ||
                text.contains("none", ignoreCase = true) ||
                text.contains("automatically", ignoreCase = true)
            )
        } finally {
            settings.lifecycleEnabled = previouslyEnabled
        }
    }

    fun testGetProjectModesToolListsManagedProjects() = runBlocking {
        modeService.enroll(project)

        val result = GetProjectModesTool().execute(project, buildJsonObject { })

        assertFalse(result.isError)
        val text = resultText(result)
        assertTrue(text.contains(project.name) || text.contains(project.basePath ?: ""))
        assertTrue(text.contains("background", ignoreCase = true))
    }

    fun testGetProjectModesToolShowsClosedProjects() = runBlocking {
        val fakePath = "/fake/closed/project"
        modeService.markClosed(fakePath)

        val result = GetProjectModesTool().execute(project, buildJsonObject { })

        assertFalse(result.isError)
        val text = resultText(result)
        assertTrue(text.contains("closed", ignoreCase = true))
        assertTrue(text.contains(fakePath) || text.contains("project"))
    }

    fun testGetProjectModesToolIncludesCount() = runBlocking {
        modeService.enroll(project)
        modeService.markClosed("/another/project")

        val result = GetProjectModesTool().execute(project, buildJsonObject { })

        assertFalse(result.isError)
        val parsed = json.parseToJsonElement(resultText(result)).jsonObject
        val total = parsed["total"]?.jsonPrimitive?.content?.toIntOrNull()
        assertNotNull("Result must include a total count", total)
        assertEquals("Total must count all managed projects", 2, total)
    }

    fun testReleaseProjectToolOnUnmanagedProjectSucceedsWithNote() = runBlocking {
        val settings = McpSettings.getInstance()
        val previouslyEnabled = settings.lifecycleEnabled
        settings.lifecycleEnabled = false
        try {
            assertFalse(modeService.isManaged(project))

            val result = ReleaseProjectTool().execute(project, buildJsonObject { })

            assertFalse(result.isError)
            assertTrue(resultText(result).contains("not managed", ignoreCase = true))
        } finally {
            settings.lifecycleEnabled = previouslyEnabled
        }
    }

    fun testReleaseProjectToolReleasesEnrolledProject() = runBlocking {
        modeService.enroll(project)

        val result = ReleaseProjectTool().execute(project, buildJsonObject { })

        assertFalse(result.isError)
        assertFalse(modeService.isManaged(project))
    }

    fun testReleaseProjectToolConfirmsProjectName() = runBlocking {
        modeService.enroll(project)

        val result = ReleaseProjectTool().execute(project, buildJsonObject { })

        assertFalse(result.isError)
        assertTrue(resultText(result).contains(project.name))
    }

    fun testSetAllProjectModesToolSetsBackgroundOnManagedProjects() = runBlocking {
        modeService.enroll(project)
        modeService.transition(project, ProjectMode.ACTIVE)

        val result = SetAllProjectModesTool().execute(project, buildJsonObject { put("mode", "background") })

        assertFalse(result.isError)
        assertEquals(ProjectMode.BACKGROUND, modeService.getMode(project))
    }

    fun testSetAllProjectModesToolRejectsClosedMode() = runBlocking {
        modeService.enroll(project)

        val result = SetAllProjectModesTool().execute(project, buildJsonObject { put("mode", "closed") })

        assertTrue("closed is not a valid mode for set_all_project_modes", result.isError)
        val text = resultText(result)
        assertTrue(text.contains("active"))
        assertTrue(text.contains("background"))
        assertTrue(text.contains("dormant"))
    }

    fun testSetAllProjectModesToolReportsCountInResult() = runBlocking {
        modeService.enroll(project)

        val result = SetAllProjectModesTool().execute(project, buildJsonObject { put("mode", "dormant") })

        assertFalse(result.isError)
        assertTrue(resultText(result).contains("1"))
        assertTrue(resultText(result).contains(project.name))
    }

    fun testAnyToolCallAutoEnrollsProject() = runBlocking {
        assertFalse(modeService.isManaged(project))

        // Use a semantic tool (participatesInLifecycle=true) — lifecycle management tools
        // like GetProjectModesTool have participatesInLifecycle=false by design.
        GetIndexStatusTool().execute(project, buildJsonObject { })

        assertTrue(modeService.isManaged(project))
    }

    fun testAnyToolCallWakesProjectFromDormant() = runBlocking {
        modeService.enroll(project)
        modeService.transition(project, ProjectMode.DORMANT)
        assertEquals(ProjectMode.DORMANT, modeService.getMode(project))

        GetIndexStatusTool().execute(project, buildJsonObject { })

        assertEquals(ProjectMode.BACKGROUND, modeService.getMode(project))
    }

    fun testReleaseAllToolReleasesAllManagedProjects() = runBlocking {
        modeService.enroll(project)
        modeService.markClosed("/fake/closed/project")

        assertTrue(modeService.isManaged(project))
        assertTrue(modeService.isManaged("/fake/closed/project"))

        ReleaseAllProjectsTool().execute(project, buildJsonObject { })

        assertFalse(modeService.isManaged(project))
        assertFalse(modeService.isManaged("/fake/closed/project"))
    }

    fun testEnrollAllToolEnrollsOpenProjects() = runBlocking {
        assertFalse(modeService.isManaged(project))

        EnrollAllProjectsTool().execute(project, buildJsonObject { })

        assertTrue(modeService.isManaged(project))
    }

    fun testReleaseProjectToolByPathReleasesClosedProject() = runBlocking {
        val closedPath = "/fake/closed/project"
        modeService.markClosed(closedPath)
        assertTrue(modeService.isManaged(closedPath))

        ReleaseProjectTool().execute(project, buildJsonObject { put("path", closedPath) })

        assertFalse(modeService.isManaged(closedPath))
    }
}

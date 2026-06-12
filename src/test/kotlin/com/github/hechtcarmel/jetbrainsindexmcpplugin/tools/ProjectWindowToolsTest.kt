package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.CloseProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.OpenProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.SetPowerSaveModeTool
import com.intellij.ide.PowerSaveMode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProjectWindowToolsTest : BasePlatformTestCase() {

    private fun resultText(result: ToolCallResult) =
        (result.content.firstOrNull() as? ContentBlock.Text)?.text ?: ""

    override fun tearDown() {
        // Restore power save to off so test isolation isn't broken
        PowerSaveMode.setEnabled(false)
        super.tearDown()
    }

    fun testSetPowerSaveModeEnables() = runBlocking {
        PowerSaveMode.setEnabled(false)

        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { put("enabled", true) })

        assertFalse(result.isError)
        assertTrue(PowerSaveMode.isEnabled())
        assertTrue(resultText(result).contains("enabled", ignoreCase = true))
    }

    fun testSetPowerSaveModeDisables() = runBlocking {
        PowerSaveMode.setEnabled(true)

        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { put("enabled", false) })

        assertFalse(result.isError)
        assertFalse(PowerSaveMode.isEnabled())
        assertTrue(resultText(result).contains("disabled", ignoreCase = true))
    }

    fun testSetPowerSaveModeRequiresEnabledParam() = runBlocking {
        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("enabled", ignoreCase = true))
    }

    fun testSetPowerSaveModeRejectsNonBooleanEnabled() = runBlocking {
        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { put("enabled", "yes") })

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("enabled", ignoreCase = true))
    }

    fun testSetPowerSaveModeMessageSaysIdeWide() = runBlocking {
        // Power Save Mode is an application-level setting; the message must not
        // imply it only affects the context project.
        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { put("enabled", true) })

        assertFalse(result.isError)
        assertTrue(resultText(result).contains("IDE-wide"))
    }

    fun testCloseProjectRefusesToCloseLastOpenProject() = runBlocking {
        // The test fixture project is the only open project. Closing it would leave
        // the MCP server without a JSON-RPC context project (every call, including
        // ide_open_project, fails with no_project_open), so the tool must refuse.
        val result = CloseProjectTool().execute(project, buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("last open project", ignoreCase = true))
    }

    fun testOpenProjectRequiresPathParam() = runBlocking {
        val result = OpenProjectTool().execute(project, buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("path", ignoreCase = true))
    }

    fun testOpenProjectRejectsBlankPath() = runBlocking {
        val result = OpenProjectTool().execute(project, buildJsonObject { put("path", "   ") })

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("path", ignoreCase = true))
    }

    fun testOpenProjectRejectsRelativePath() = runBlocking {
        val result = OpenProjectTool().execute(project, buildJsonObject { put("path", "relative/project/dir") })

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("absolute", ignoreCase = true))
    }

    fun testOpenProjectRejectsNonPositiveTimeout() = runBlocking {
        val result = OpenProjectTool().execute(
            project,
            buildJsonObject {
                put("path", "/nonexistent/project/path")
                put("timeoutSeconds", 0)
            }
        )

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("timeoutSeconds"))
    }

    fun testOpenProjectReturnsErrorForNonExistentPath() = runBlocking {
        val result = OpenProjectTool().execute(
            project,
            buildJsonObject { put("path", "/nonexistent/project/path") }
        )

        assertTrue(result.isError)
    }

    fun testOpenProjectIsIdempotentWhenProjectAlreadyOpen() = runBlocking {
        val basePath = project.basePath
        assertNotNull("test project must have a basePath", basePath)

        val result = OpenProjectTool().execute(project, buildJsonObject { put("path", basePath!!) })

        assertFalse(result.isError)
        assertTrue(resultText(result).contains("already open", ignoreCase = true))
    }
}

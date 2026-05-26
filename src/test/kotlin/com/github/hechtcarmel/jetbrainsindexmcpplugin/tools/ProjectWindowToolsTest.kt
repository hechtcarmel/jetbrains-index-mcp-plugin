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

    fun testCloseProjectReturnsSuccess() = runBlocking {
        // We can't actually close the test project (it would break the test runner),
        // but we verify the tool accepts the call and returns a success message.
        // The close itself is scheduled via invokeLater and fires after the test.
        val result = CloseProjectTool().execute(project, buildJsonObject { })

        assertFalse(result.isError)
        assertTrue(resultText(result).contains(project.name))
    }

    fun testOpenProjectRequiresPathParam() = runBlocking {
        val result = OpenProjectTool().execute(project, buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("path", ignoreCase = true))
    }

    fun testOpenProjectReturnsErrorForNonExistentPath() = runBlocking {
        val result = OpenProjectTool().execute(
            project,
            buildJsonObject { put("path", "/nonexistent/project/path") }
        )

        assertTrue(result.isError)
    }
}

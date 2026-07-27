package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.CloseProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.OpenProjectTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.SetPowerSaveModeTool
import com.intellij.ide.PowerSaveMode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class ProjectWindowToolsTest : BasePlatformTestCase() {

    private fun resultText(result: CallToolResult) =
        (result.content.firstOrNull() as? TextContent)?.text ?: ""

    private fun missingAbsoluteProjectPath(): String =
        Path.of(requireNotNull(project.basePath) { "test project must have a basePath" }, "__missing_project_for_open_tool_tests__")
            .toAbsolutePath()
            .normalize()
            .toString()

    override fun tearDown() {
        // Restore power save to off so test isolation isn't broken
        PowerSaveMode.setEnabled(false)
        super.tearDown()
    }

    fun testSetPowerSaveModeEnables() = runBlocking {
        PowerSaveMode.setEnabled(false)

        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { put("enabled", true) })

        assertFalse(result.isFailure)
        assertTrue(PowerSaveMode.isEnabled())
        assertTrue(resultText(result).contains("enabled", ignoreCase = true))
    }

    fun testSetPowerSaveModeDisables() = runBlocking {
        PowerSaveMode.setEnabled(true)

        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { put("enabled", false) })

        assertFalse(result.isFailure)
        assertFalse(PowerSaveMode.isEnabled())
        assertTrue(resultText(result).contains("disabled", ignoreCase = true))
    }

    fun testSetPowerSaveModeRequiresEnabledParam() = runBlocking {
        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { })

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains("enabled", ignoreCase = true))
    }

    fun testSetPowerSaveModeRejectsNonBooleanEnabled() = runBlocking {
        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { put("enabled", "yes") })

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains("enabled", ignoreCase = true))
    }

    fun testSetPowerSaveModeMessageSaysIdeWide() = runBlocking {
        // Power Save Mode is an application-level setting; the message must not
        // imply it only affects the context project.
        val result = SetPowerSaveModeTool().execute(project, buildJsonObject { put("enabled", true) })

        assertFalse(result.isFailure)
        assertTrue(resultText(result).contains("IDE-wide"))
    }

    fun testCloseProjectRefusesToCloseLastOpenProject() = runBlocking {
        // The test fixture project is the only open project. Closing it would leave
        // the MCP server without a JSON-RPC context project (every call, including
        // ide_open_project, fails with no_project_open), so the tool must refuse.
        val result = CloseProjectTool().execute(project, buildJsonObject { })

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains("last open project", ignoreCase = true))
    }

    fun testDeferredCloseReChecksLastOpenProjectGuardOnEdt() {
        // The guard in doExecute runs on the caller thread; by the time the deferred
        // EDT block runs, a concurrent close may have made this the last open project.
        // The EDT block must re-check and skip instead of closing the last project.
        // Here the fixture project is already the last open project, so the deferred
        // close must refuse and leave it open.
        val closed = CloseProjectTool().closeOnEdt(project)

        assertFalse("Deferred close must skip when the project became the last open project", closed)
        assertFalse("Project must remain open after the skipped close", project.isDisposed)
    }

    fun testOpenProjectRequiresPathParam() = runBlocking {
        val result = OpenProjectTool().execute(project, buildJsonObject { })

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains("path", ignoreCase = true))
    }

    fun testOpenProjectRejectsBlankPath() = runBlocking {
        val result = OpenProjectTool().execute(project, buildJsonObject { put("path", "   ") })

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains("path", ignoreCase = true))
    }

    fun testOpenProjectRejectsRelativePath() = runBlocking {
        val result = OpenProjectTool().execute(project, buildJsonObject { put("path", "relative/project/dir") })

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains("absolute", ignoreCase = true))
    }

    fun testOpenProjectRejectsNonPositiveTimeout() = runBlocking {
        val result = OpenProjectTool().execute(
            project,
            buildJsonObject {
                put("path", missingAbsoluteProjectPath())
                put("timeoutSeconds", 0)
            }
        )

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains("timeoutSeconds"))
    }

    fun testOpenProjectReturnsErrorForNonExistentPath() = runBlocking {
        val result = OpenProjectTool().execute(
            project,
            buildJsonObject { put("path", missingAbsoluteProjectPath()) }
        )

        assertTrue(result.isFailure)
    }

    fun testOpenProjectIsIdempotentWhenProjectAlreadyOpen() = runBlocking {
        val basePath = project.basePath
        assertNotNull("test project must have a basePath", basePath)

        val result = OpenProjectTool().execute(project, buildJsonObject { put("path", basePath!!) })

        assertFalse(result.isFailure)
        assertTrue(resultText(result).contains("already open", ignoreCase = true))
    }
}

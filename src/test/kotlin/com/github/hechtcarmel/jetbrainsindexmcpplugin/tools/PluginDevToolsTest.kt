package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.InstallPluginTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PluginDevToolsTest : BasePlatformTestCase() {

    private fun resultText(result: ToolCallResult) =
        (result.content.firstOrNull() as? ContentBlock.Text)?.text ?: ""

    fun testInstallPluginToolReturnsErrorWhenNoBuildOutputExists() = runBlocking {
        // The test project has no build/distributions/ directory, so auto-detection fails.
        val result = InstallPluginTool().execute(project, buildJsonObject { })

        assertTrue("Missing zip must produce an error", result.isError)
        val text = resultText(result)
        assertTrue(
            "Error must suggest running buildPlugin",
            text.contains("buildPlugin", ignoreCase = true) ||
            text.contains("No plugin zip", ignoreCase = true)
        )
    }

    fun testInstallPluginToolReturnsErrorForNonExistentPath() = runBlocking {
        val result = InstallPluginTool().execute(
            project,
            buildJsonObject { put("path", "/nonexistent/plugin.zip") }
        )

        assertTrue(result.isError)
        assertTrue(resultText(result).contains("not found", ignoreCase = true))
    }

    fun testInstallPluginToolRejectsNonZipPath() = runBlocking {
        val result = InstallPluginTool().execute(
            project,
            buildJsonObject { put("path", "/some/plugin.jar") }
        )

        assertTrue(result.isError)
        assertTrue(resultText(result).contains(".zip", ignoreCase = true))
    }
}

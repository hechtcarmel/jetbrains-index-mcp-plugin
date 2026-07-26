package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.InstallPluginTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PluginDevToolsTest : BasePlatformTestCase() {

    private fun resultText(result: CallToolResult) =
        (result.content.firstOrNull() as? TextContent)?.text ?: ""

    fun testInstallPluginToolReturnsErrorWhenNoBuildOutputExists() = runBlocking {
        // The test project has no build/distributions/ directory, so auto-detection fails.
        val result = InstallPluginTool().execute(project, buildJsonObject { })

        assertTrue("Missing zip must produce an error", result.isFailure)
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

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains("not found", ignoreCase = true))
    }

    fun testInstallPluginToolRejectsNonZipPath() = runBlocking {
        val result = InstallPluginTool().execute(
            project,
            buildJsonObject { put("path", "/some/plugin.jar") }
        )

        assertTrue(result.isFailure)
        assertTrue(resultText(result).contains(".zip", ignoreCase = true))
    }
}

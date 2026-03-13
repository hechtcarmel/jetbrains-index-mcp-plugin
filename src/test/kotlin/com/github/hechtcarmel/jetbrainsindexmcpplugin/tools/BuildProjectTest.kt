package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.BuildProjectTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Smoke tests for BuildProjectTool.
 *
 * These use BasePlatformTestCase (light fixture) and cannot validate real JPS
 * or Gradle build execution. Real build integration testing requires manual or
 * CI testing with a full project.
 */
class BuildProjectTest : BasePlatformTestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testBuildProjectToolReturnsResult() = runBlocking {
        val tool = BuildProjectTool()

        val result = tool.execute(project, buildJsonObject { })

        assertTrue("Should have content", result.content.isNotEmpty())
        val content = result.content.first()
        assertTrue("Content should be text", content is ContentBlock.Text)
    }

    fun testBuildProjectToolResultStructure() = runBlocking {
        val tool = BuildProjectTool()

        val result = tool.execute(project, buildJsonObject { })

        if (!result.isError) {
            val textContent = (result.content.first() as ContentBlock.Text).text
            val resultJson = json.parseToJsonElement(textContent).jsonObject

            assertNotNull("Result should have success", resultJson["success"])
            assertNotNull("Result should have aborted", resultJson["aborted"])
            assertNotNull("Result should have buildMessages", resultJson["buildMessages"])
            assertNotNull("Result should have durationMs", resultJson["durationMs"])
            assertNotNull("Result should have truncated", resultJson["truncated"])
        }
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Platform-dependent tests for multi-project resolution.
 * For schema validation tests that don't need the platform, see ToolsUnitTest.
 */
class MultiProjectResolutionTest : BasePlatformTestCase() {

    private lateinit var dispatcher: McpToolDispatcher

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        dispatcher = McpToolDispatcher(ToolRegistry().apply { registerBuiltInTools() })
    }

    fun testToolCallWithSingleProject() = runBlocking {
        val result = dispatcher.call(ToolNames.INDEX_STATUS, buildJsonObject { })

        assertFalse("Tool should succeed with single project: ${result.text}", result.isFailure)
    }

    fun testToolCallWithExplicitProjectPath() = runBlocking {
        val result = dispatcher.call(
            ToolNames.INDEX_STATUS,
            buildJsonObject { put("project_path", project.basePath ?: "") }
        )

        assertFalse("Tool should succeed with explicit project_path: ${result.text}", result.isFailure)
    }

    fun testToolCallWithInvalidProjectPath() = runBlocking {
        val result = dispatcher.call(
            ToolNames.INDEX_STATUS,
            buildJsonObject { put("project_path", "/non/existent/project/path") }
        )

        assertTrue("Tool should return error for invalid project_path", result.isFailure)

        val errorJson = json.parseToJsonElement(result.text).jsonObject
        assertEquals("project_not_found", errorJson["error"]?.jsonPrimitive?.content)
        assertNotNull("Should include available_projects", errorJson["available_projects"])
    }
}

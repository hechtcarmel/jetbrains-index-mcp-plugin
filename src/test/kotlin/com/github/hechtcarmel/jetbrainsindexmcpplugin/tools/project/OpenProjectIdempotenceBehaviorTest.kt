package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Moved from the EDT-only window tests because open now waits for indexing on retries too. */
class OpenProjectIdempotenceBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testOpenProjectIsIdempotentWhenProjectAlreadyOpen() {
        val basePath = requireNotNull(project.basePath)
        val result = runBlocking { OpenProjectTool().execute(project, buildJsonObject { put("path", basePath) }) }
        assertToolSucceeded("already-open project setup must be idempotent", result)
        assertTrue(toolText(result).contains("already open", ignoreCase = true))
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-dependent tests that require IntelliJ Platform indexing.
 * For pure unit tests that don't need the platform, see McpPluginUnitTest.
 */
class McpPluginTest : BasePlatformTestCase() {

    fun testProjectAvailable() {
        assertNotNull("Project should be available", project)
        assertNotNull("Project base path should be available", project.basePath)
    }

    fun testCoroutineScopeHasModalityState() {
        val service = McpServerService.getInstance()
        val context = service.coroutineScope.coroutineContext
        var hasModalityState = false
        context.fold(Unit) { _, element ->
            if (element.toString().contains("ModalityState")) {
                hasModalityState = true
            }
        }
        assertTrue(
            "MCP coroutine scope must include ModalityState context element to prevent " +
                "tool hangs when modal dialogs are open (issue #68)",
            hasModalityState
        )
    }
}

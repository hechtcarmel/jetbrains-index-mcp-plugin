package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject

class ReloadProjectToolBehaviorTest : McpPlatformTestCase() {
    fun testLinkedRefreshRunsOnNonModalEdtBeforeSuccess() = runBlocking {
        var refreshed = false
        val tool = ReloadProjectTool(
            linkedCount = { _, id -> if (id.id == "GRADLE") 1 else 0 },
            refresh = { _, id ->
                assertEquals("GRADLE", id.id)
                assertTrue(ApplicationManager.getApplication().isDispatchThread)
                assertEquals(ModalityState.nonModal(), ModalityState.current())
                refreshed = true
            }
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val call = scope.async { tool.execute(project, buildJsonObject {}) }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!call.isCompleted && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                delay(10)
            }
            assertTrue("Reload must finish", call.isCompleted)
            val result = call.await()
            assertFalse(result.text, result.isFailure)
            assertTrue(refreshed)
            assertTrue(result.text.contains("Gradle: reload scheduled (1 linked project(s))"))
        } finally {
            scope.cancel()
            UIUtil.dispatchAllInvocationEvents()
        }
    }
}

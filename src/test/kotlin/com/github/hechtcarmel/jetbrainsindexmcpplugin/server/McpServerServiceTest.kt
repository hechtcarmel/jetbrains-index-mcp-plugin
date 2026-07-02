package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.ServerSocket

class McpServerServiceTest : BasePlatformTestCase() {

    private lateinit var testScope: CoroutineScope
    private lateinit var originalSettings: McpSettings.State
    private var service: McpServerService? = null

    override fun setUp() {
        super.setUp()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val settings = McpSettings.getInstance()
        originalSettings = settings.state.copy(
            disabledTools = settings.state.disabledTools.toMutableSet()
        )
        settings.loadState(
            settings.state.copy(
                serverHost = "127.0.0.1",
                serverPort = findFreePort(),
                disabledTools = settings.state.disabledTools.toMutableSet()
            )
        )
    }

    override fun tearDown() {
        try {
            service?.dispose()
            service = null
            testScope.cancel()
            McpSettings.getInstance().loadState(originalSettings)
        } finally {
            super.tearDown()
        }
    }

    fun testInitializeDoesNotStartServerInUnitTestMode() {
        val application = ApplicationManager.getApplication()
        assertTrue(
            "platform tests must run in unit test mode",
            application.isUnitTestMode
        )

        service = McpServerService(testScope)
        service!!.initialize()

        assertTrue("service should still initialize tool metadata", service!!.isInitialized)
        assertFalse("unit test initialization must not bind the MCP server port", service!!.isServerRunning())
        assertNull("unit test initialization must not expose a server URL", service!!.getServerUrl())
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

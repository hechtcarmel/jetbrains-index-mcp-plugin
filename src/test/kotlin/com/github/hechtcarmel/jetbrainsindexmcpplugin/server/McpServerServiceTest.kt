package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.InetAddress
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

    /**
     * A failed bind must surface as an error result — not as a thrown engine-side
     * CancellationException that kills the calling coroutine with no serverError recorded.
     * The socket stays open for the whole attempt so the conflict cannot evaporate
     * between picking the port and Ktor binding it.
     */
    fun testStartServerOnOccupiedPortReportsErrorWithoutThrowing() {
        service = McpServerService(testScope)
        service!!.initialize()

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val result = service!!.startServer("127.0.0.1", occupied.localPort)

            assertFalse(
                "start on an occupied port must not report Success, got $result",
                result is KtorMcpServer.StartResult.Success
            )
            assertNotNull(
                "a failed start must record a server error so status reflects the failure",
                service!!.getServerError()
            )
            assertFalse(
                "a failed start must not report the server as running",
                service!!.isServerRunning()
            )
            assertNull(
                "a failed start must not advertise a server URL",
                service!!.getServerUrl()
            )
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

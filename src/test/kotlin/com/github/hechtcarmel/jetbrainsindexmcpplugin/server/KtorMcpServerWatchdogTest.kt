package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorSseSessionManager
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Platform tests for KtorMcpServer watchdog behaviour.
 *
 * Tests that isRunning() correctly reflects actual engine state and that
 * onUnexpectedStop fires only when the stop was not intentional.
 *
 * Servers bind port 0 (ephemeral) so the suite can never collide with a running IDE, a parallel
 * CI job, or one of its own sockets still in TIME_WAIT. The port-in-use case is the exception:
 * it holds a real socket open for the whole assertion, so the conflict cannot evaporate between
 * picking the port and Ktor binding it.
 */
class KtorMcpServerWatchdogTest : BasePlatformTestCase() {

    private lateinit var testScope: CoroutineScope
    private var server: KtorMcpServer? = null

    override fun setUp() {
        super.setUp()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun tearDown() {
        try {
            server?.stop()
            server = null
            testScope.cancel()
        } finally {
            super.tearDown()
        }
    }

    // ── isRunning() reflects actual engine lifecycle ───────────────────────

    fun testIsRunningFalseBeforeStart() {
        server = makeServer()
        assertFalse("isRunning must be false before start()", server!!.isRunning())
    }

    fun testIsRunningTrueAfterSuccessfulStart() {
        server = makeServer()
        val result = server!!.start()
        assertEquals("start must succeed on free port", KtorMcpServer.StartResult.Success, result)
        assertTrue("isRunning must be true after successful start()", server!!.isRunning())
    }

    fun testIsRunningFalseAfterStop() {
        server = makeServer()
        server!!.start()
        assertTrue("sanity: isRunning must be true after start", server!!.isRunning())

        server!!.stop()
        assertFalse("isRunning must be false after stop()", server!!.isRunning())
    }

    fun testIsRunningFalseAfterDispose() {
        server = makeServer()
        server!!.start()
        server!!.dispose()
        assertFalse("isRunning must be false after dispose()", server!!.isRunning())
        server = null // already disposed
    }

    // ── onUnexpectedStop callback ─────────────────────────────────────────

    fun testOnUnexpectedStopNotFiredOnIntentionalStop() {
        var callCount = 0
        server = makeServer(onUnexpectedStop = { callCount++ })
        server!!.start()
        server!!.stop()

        assertEquals(
            "onUnexpectedStop must NOT fire when stop() is called intentionally",
            0, callCount
        )
    }

    fun testOnUnexpectedStopNotFiredOnDispose() {
        var callCount = 0
        server = makeServer(onUnexpectedStop = { callCount++ })
        server!!.start()
        server!!.dispose()
        server = null

        assertEquals(
            "onUnexpectedStop must NOT fire on dispose()",
            0, callCount
        )
    }

    fun testStartResultPortInUseWhenPortTaken() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val blocked = makeServer(port = occupied.localPort)

            // Ktor CIO reports a lost bind in three shapes: StartResult.PortInUse,
            // StartResult.Error wrapping BindException, or a thrown CancellationException whose
            // cause chain contains the BindException (bind failed inside the engine coroutine).
            // All three mean the conflict was detected; none is StartResult.Success.
            val result = runCatching { blocked.start() }
            try {
                if (result.isFailure) {
                    val bindFailure = generateSequence(result.exceptionOrNull()) { it.cause }
                        .any { it is BindException }
                    assertTrue("exception must be caused by BindException", bindFailure)
                } else {
                    assertFalse(
                        "start on occupied port must not return Success, got ${result.getOrNull()}",
                        result.getOrNull() is KtorMcpServer.StartResult.Success
                    )
                }
            } finally {
                blocked.stop()
            }
        }
    }

    fun testMultipleStartStopCycles() {
        server = makeServer()

        repeat(3) { cycle ->
            val result = server!!.start()
            assertEquals("cycle $cycle: start must succeed", KtorMcpServer.StartResult.Success, result)
            assertTrue("cycle $cycle: isRunning must be true", server!!.isRunning())
            server!!.stop()
            assertFalse("cycle $cycle: isRunning must be false after stop", server!!.isRunning())
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private fun makeServer(port: Int = EPHEMERAL_PORT, onUnexpectedStop: (() -> Unit)? = null): KtorMcpServer {
        return KtorMcpServer(
            port = port,
            host = "127.0.0.1",
            jsonRpcHandler = JsonRpcHandler(ToolRegistry()),
            sseSessionManager = KtorSseSessionManager(),
            coroutineScope = testScope,
            onUnexpectedStop = onUnexpectedStop
        )
    }

    private companion object {
        private const val EPHEMERAL_PORT = 0
    }
}

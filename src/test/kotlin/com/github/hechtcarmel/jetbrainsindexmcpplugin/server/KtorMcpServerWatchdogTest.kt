package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorSseSessionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Platform tests for KtorMcpServer watchdog behaviour.
 *
 * Tests that isRunning() correctly reflects actual engine state and that
 * onUnexpectedStop fires only when the stop was not intentional.
 *
 * Uses a fixed test port offset from the production port to avoid conflicts
 * when IntelliJ is running alongside the test suite.
 */
class KtorMcpServerWatchdogTest : BasePlatformTestCase() {

    private lateinit var testScope: CoroutineScope
    private var server: KtorMcpServer? = null

    private val testPort = 29280 // offset from production 29170

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

    fun testStartResultSuccessOnFreePort() {
        server = makeServer()
        val result = server!!.start()
        assertTrue(
            "start on a free port must return Success, got $result",
            result is KtorMcpServer.StartResult.Success
        )
    }

    fun testStartResultPortInUseWhenPortTaken() {
        // Start first server to occupy the port
        val first = makeServer()
        val firstResult = first.start()
        assertEquals(KtorMcpServer.StartResult.Success, firstResult)

        try {
            // Second server on same port must fail — either PortInUse, Error wrapping BindException,
            // or a thrown CancellationException (Ktor CIO can wrap BindException in a JobCancellation
            // when the bind fails inside a coroutine). All three outcomes indicate the conflict was
            // detected; none is StartResult.Success.
            val second = makeServer()
            val result = runCatching { second.start() }
            val startResult = result.getOrNull()

            if (result.isFailure) {
                // Thrown CancellationException — conflict detected, server did not start
                val cause = generateSequence(result.exceptionOrNull()) { it.cause }
                    .any { it is java.net.BindException }
                assertTrue("exception must be caused by BindException", cause)
            } else {
                assertFalse(
                    "second start on occupied port must not return Success, got $startResult",
                    startResult is KtorMcpServer.StartResult.Success
                )
            }
            second.stop()
        } finally {
            first.stop()
        }
    }

    // ── isRunning() uses engineRunning flag, not just server != null ───────

    fun testIsRunningFalseWhenNeverStarted() {
        // engineRunning starts as false; server reference is null → false
        server = makeServer()
        assertFalse(server!!.isRunning())
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

    private fun makeServer(onUnexpectedStop: (() -> Unit)? = null): KtorMcpServer {
        val handler = JsonRpcHandler(com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry())
        return KtorMcpServer(
            port = testPort,
            host = "127.0.0.1",
            jsonRpcHandler = handler,
            sseSessionManager = KtorSseSessionManager(),
            coroutineScope = testScope,
            onUnexpectedStop = onUnexpectedStop
        )
    }
}

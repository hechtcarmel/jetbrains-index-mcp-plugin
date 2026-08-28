package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ide_run_tests must never block a single MCP call past the client's own request timeout
 * (issue #277: Claude Code kills any call at 60s, so `timeoutSeconds: 7200` could never work).
 * Instead the run is registered in [ActiveTestRunRegistry] and each call long-polls within a
 * bounded wait budget, returning either final results or a `running` payload with a `runId`.
 *
 * These tests drive the real tool against registry-seeded runs (a [NopProcessHandler] plus the
 * same deferreds the start path wires), which exercises the shared await/collect/remove path
 * without launching a JVM test process — something the fixture cannot do.
 */
class RunTestsLongPollBehaviorTest : McpPlatformTestCase() {

    private val tool = RunTestsTool()
    private val json = Json { ignoreUnknownKeys = true }

    private val registeredIds = mutableListOf<String>()

    override fun tearDown() {
        try {
            registeredIds.forEach { ActiveTestRunRegistry.getInstance(project).remove(it) }
            registeredIds.clear()
        } finally {
            super.tearDown()
        }
    }

    /**
     * A run whose execution environment is launched but whose test process has not started yet —
     * the IDE is still compiling (issue #348). This is the state every run now begins in.
     */
    private fun registerStartingRun(
        id: String,
        timeoutSeconds: Int = 600,
        processStartAllowanceMs: Long = ActiveTestRunRegistry.processStartAllowanceMs(timeoutSeconds)
    ): ActiveTestRunRegistry.ActiveTestRun {
        registeredIds.add(id)
        return ActiveTestRunRegistry.getInstance(project).register(
            ActiveTestRunRegistry.ActiveTestRun(
                id = id,
                configName = "Fake Config",
                createdAtMs = System.currentTimeMillis(),
                timeoutSeconds = timeoutSeconds,
                processStartAllowanceMs = processStartAllowanceMs,
                exitCode = CompletableDeferred(),
                testRoot = CompletableDeferred(),
                connection = null
            )
        )
    }

    private fun registerRun(
        id: String,
        timeoutSeconds: Int = 600,
        hasResultsViewer: Boolean = false
    ): ActiveTestRunRegistry.ActiveTestRun {
        val run = registerStartingRun(id, timeoutSeconds)
        val handler = NopProcessHandler()
        handler.startNotify()
        run.markProcessStarted(handler, hasResultsViewer = hasResultsViewer)
        return run
    }

    private fun callTool(vararg args: Pair<String, Any>): CallToolResult = runBlocking {
        tool.execute(project, buildJsonObject {
            args.forEach { (name, value) ->
                when (value) {
                    is Int -> put(name, value)
                    is Boolean -> put(name, value)
                    else -> put(name, value.toString())
                }
            }
        })
    }

    fun testUnknownRunIdIsRejected() {
        val result = callTool("runId" to "no-such-run")
        assertToolFailed("polling an unknown runId must fail", result)
        assertTrue(
            "error must echo the runId so the agent can see what it asked for",
            toolText(result).contains("no-such-run")
        )
    }

    fun testTargetAndRunIdTogetherAreRejected() {
        val result = callTool("target" to "com.example.MyTest", "runId" to "some-run")
        assertToolFailed("target and runId are mutually exclusive", result)
    }

    fun testAttachReturnsInProgressWhileRunStillExecuting() {
        registerRun("run-in-flight")

        val result = callTool("runId" to "run-in-flight", "waitSeconds" to 1)

        assertToolSucceeded("an in-flight poll is a success, not an error", result)
        val payload = json.decodeFromString(RunTestsInProgressResult.serializer(), toolText(result))
        assertEquals("running", payload.status)
        assertEquals("run-in-flight", payload.runId)
        assertEquals("Fake Config", payload.configName)

        val again = callTool("runId" to "run-in-flight", "waitSeconds" to 0)
        assertToolSucceeded("the run must remain pollable until it completes", again)
        val againPayload = json.decodeFromString(RunTestsInProgressResult.serializer(), toolText(again))
        assertEquals("second poll must still report running, not fabricate final results", "running", againPayload.status)
    }

    fun testAttachDeliversResultsOnceRunCompletes() {
        val run = registerRun("run-finished")
        run.exitCode.complete(0)
        run.testRoot.complete(null)

        val result = callTool("runId" to "run-finished", "waitSeconds" to 0)

        assertToolSucceeded("a completed run must return final results", result)
        val payload = json.decodeFromString(RunTestsResult.serializer(), toolText(result))
        assertFalse("completed run is not timed out", payload.timedOut)
        assertEquals(0, payload.exitCode)
        assertTrue("no SM tree in this fixture, so no tests found", payload.noTestsFound)

        assertToolFailed(
            "collected runs must be removed from the registry",
            callTool("runId" to "run-finished")
        )
    }

    /**
     * The issue #346 contract, end to end through the tool: a completed run's per-test console
     * output rides on each test entry and unattributed (root-level) output on the result's
     * `output` field — through the real off-EDT alarm-queue collection path, not a direct
     * collector call.
     */
    fun testAttachDeliversConsoleOutputWithResults() {
        val run = registerRun("run-with-output", hasResultsViewer = true)
        val root = SMTestProxy.SMRootTestProxy()
        root.setStarted()
        root.addStdOutput("framework banner\n")
        val suite = SMTestProxy("MainTest", true, null)
        root.addChild(suite)
        suite.setStarted()
        val test = SMTestProxy("testLogs", false, null)
        suite.addChild(test)
        test.setStarted()
        test.addStdOutput("Hello world\n")
        test.setFinished()
        suite.setFinished()
        root.setFinished()
        run.exitCode.complete(0)
        run.testRoot.complete(root)

        val result = callTool("runId" to "run-with-output", "waitSeconds" to 10)

        assertToolSucceeded("a completed run must return final results", result)
        val payload = json.decodeFromString(RunTestsResult.serializer(), toolText(result))
        assertEquals(1, payload.passed)
        assertEquals(
            "the test's own prints must ride on its entry",
            "Hello world\n",
            payload.tests.single().output
        )
        assertEquals(
            "root-level prints must ride on the result's output field",
            "framework banner\n",
            payload.output
        )
    }

    fun testWatchdogKillsRunAtTimeoutAndPollReportsTimedOut() {
        val run = registerRun("run-overdue", timeoutSeconds = 1)
        val handler = run.handler!!

        val deadline = System.currentTimeMillis() + 30_000
        while (!handler.isProcessTerminated && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue(
            "the registry watchdog must destroy the process once timeoutSeconds expires, " +
                    "even if no MCP call is waiting on the run",
            handler.isProcessTerminated
        )

        val result = callTool("runId" to "run-overdue", "waitSeconds" to 0)
        assertToolSucceeded("a timed-out run still returns a structured result", result)
        val payload = json.decodeFromString(RunTestsResult.serializer(), toolText(result))
        assertTrue("watchdog kill must surface as timedOut", payload.timedOut)
        assertEquals("timed-out runs report exit code -1", -1, payload.exitCode)
        assertFalse(payload.success)
    }

    /**
     * When the watchdog has already declared a run dead but the process never confirmed its
     * death (destroyProcess ignored — zombie), a poll must NOT sit out its whole wait budget
     * before noticing the verdict: the fixture's un-wired handler leaves exitCode forever
     * incomplete, so waiting at all would block the full waitSeconds.
     */
    fun testPollAfterWatchdogVerdictReturnsWithoutWaitingOutTheBudget() {
        val run = registerRun("run-zombie", timeoutSeconds = 1)

        val flagDeadline = System.currentTimeMillis() + 30_000
        while (!run.timedOutByWatchdog && System.currentTimeMillis() < flagDeadline) {
            Thread.sleep(100)
        }
        assertTrue("precondition: watchdog fired", run.timedOutByWatchdog)

        val start = System.currentTimeMillis()
        val result = callTool("runId" to "run-zombie", "waitSeconds" to 30)
        val elapsedMs = System.currentTimeMillis() - start

        val payload = json.decodeFromString(RunTestsResult.serializer(), toolText(result))
        assertTrue("verdict already known — must report timedOut", payload.timedOut)
        assertTrue(
            "poll took ${elapsedMs}ms; it must return promptly instead of waiting out the 30s budget",
            elapsedMs < 10_000
        )
    }

    // ── issue #348: slow before-run builds — the process has not started yet ──────────────────

    /**
     * A run whose test process has not started (the IDE is still compiling) must poll as
     * "running", never error. Before the fix this state was unrepresentable: the run was only
     * registered after process start, so a slow build failed the call with "Test process did
     * not start within 44s" while the build kept going untracked in the IDE.
     */
    fun testStartingPhasePollReportsRunningNotError() {
        registerStartingRun("run-still-building")

        val result = callTool("runId" to "run-still-building", "waitSeconds" to 1)

        assertToolSucceeded("a run still building must poll as in-progress, not fail", result)
        val payload = json.decodeFromString(RunTestsInProgressResult.serializer(), toolText(result))
        assertEquals("running", payload.status)
        assertEquals("run-still-building", payload.runId)
        assertTrue(
            "starting-phase message must say the process has not started yet, got: ${payload.message}",
            payload.message.contains("not started")
        )
        assertTrue("message must carry the runId for the next poll", payload.message.contains("run-still-building"))
    }

    /**
     * The issue #348 scenario end to end at the registry level: the build outlasts the first
     * call's wait budget, then finishes; the process runs and exits; a later poll must deliver
     * the final results.
     */
    fun testRunStartingAfterFirstPollStillDeliversResults() {
        val run = registerStartingRun("run-slow-build")

        val first = callTool("runId" to "run-slow-build", "waitSeconds" to 0)
        assertToolSucceeded("first poll during the build phase reports in-progress", first)
        assertEquals(
            "running",
            json.decodeFromString(RunTestsInProgressResult.serializer(), toolText(first)).status
        )

        val handler = NopProcessHandler()
        handler.startNotify()
        run.markProcessStarted(handler, hasResultsViewer = false)
        run.exitCode.complete(0)
        run.testRoot.complete(null)

        val second = callTool("runId" to "run-slow-build", "waitSeconds" to 10)
        assertToolSucceeded("once the run completes, a poll must return final results", second)
        val payload = json.decodeFromString(RunTestsResult.serializer(), toolText(second))
        assertFalse("run completed normally — not a timeout", payload.timedOut)
        assertEquals(0, payload.exitCode)
    }

    /**
     * The start allowance is the watchdog backstop for a build that hangs forever. A process
     * that starts only after the allowance expired must be killed immediately — a run already
     * reported as timed out must never keep executing unmanaged.
     */
    fun testProcessStartingAfterStartAllowanceExpiredIsKilled() {
        val run = registerStartingRun("run-hung-build", processStartAllowanceMs = 200)

        val flagDeadline = System.currentTimeMillis() + 30_000
        while (!run.timedOutByWatchdog && System.currentTimeMillis() < flagDeadline) {
            Thread.sleep(100)
        }
        assertTrue("precondition: start allowance expired and the watchdog fired", run.timedOutByWatchdog)

        val result = callTool("runId" to "run-hung-build", "waitSeconds" to 0)
        assertToolSucceeded("an expired starting phase still returns a structured result", result)
        val payload = json.decodeFromString(RunTestsResult.serializer(), toolText(result))
        assertTrue("expired start allowance must surface as timedOut", payload.timedOut)

        val handler = NopProcessHandler()
        handler.startNotify()
        run.markProcessStarted(handler, hasResultsViewer = false)
        assertTrue(
            "a process starting after the timeout verdict must be destroyed immediately",
            handler.isProcessTerminated || handler.isProcessTerminating
        )
    }

    /**
     * ExecutionListener.processNotStarted (before-run build failed or was cancelled) is
     * terminal: the poll must surface it as a tool error and the run must be removed.
     */
    fun testProcessNotStartedSurfacesErrorAndRemovesRun() {
        val run = registerStartingRun("run-build-failed")
        run.markProcessNotStarted("Test process failed to start for 'Fake Config' — the before-launch build failed or was cancelled.")

        val result = callTool("runId" to "run-build-failed", "waitSeconds" to 0)
        assertToolFailed("a run whose process can never start must fail the poll", result)
        assertTrue(
            "error must explain the start failure, got: ${toolText(result)}",
            toolText(result).contains("failed to start")
        )

        assertToolFailed(
            "a failed-to-start run must be removed from the registry",
            callTool("runId" to "run-build-failed")
        )
    }
}

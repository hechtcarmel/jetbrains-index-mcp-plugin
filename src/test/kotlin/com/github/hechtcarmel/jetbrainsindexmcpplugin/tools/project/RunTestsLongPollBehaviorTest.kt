package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.intellij.execution.process.NopProcessHandler
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

    private fun registerRun(id: String, timeoutSeconds: Int = 600): ActiveTestRunRegistry.ActiveTestRun {
        val handler = NopProcessHandler()
        handler.startNotify()
        registeredIds.add(id)
        return ActiveTestRunRegistry.getInstance(project).register(
            ActiveTestRunRegistry.ActiveTestRun(
                id = id,
                configName = "Fake Config",
                startedAtMs = System.currentTimeMillis(),
                timeoutSeconds = timeoutSeconds,
                handler = handler,
                exitCode = CompletableDeferred(),
                testRoot = CompletableDeferred(),
                hasResultsViewer = false,
                connection = null
            )
        )
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

    fun testWatchdogKillsRunAtTimeoutAndPollReportsTimedOut() {
        val run = registerRun("run-overdue", timeoutSeconds = 1)

        val deadline = System.currentTimeMillis() + 30_000
        while (!run.handler.isProcessTerminated && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue(
            "the registry watchdog must destroy the process once timeoutSeconds expires, " +
                    "even if no MCP call is waiting on the run",
            run.handler.isProcessTerminated
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
}

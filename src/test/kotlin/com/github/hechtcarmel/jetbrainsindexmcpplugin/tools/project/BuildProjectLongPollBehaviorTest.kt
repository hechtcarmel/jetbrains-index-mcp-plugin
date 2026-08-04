package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildProjectResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.task.ProjectTaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.function.BiPredicate

/**
 * ide_build_project has the same client-timeout exposure as ide_run_tests (issue #277): a build
 * longer than the MCP client's request timeout killed the call client-side — and with
 * timeoutSeconds omitted the tool awaited *unbounded*. Each call now long-polls within a wait
 * budget and returns `{"status": "running", "buildId"}` for still-running builds.
 *
 * Tests seed [ActiveBuildRegistry] with fake builds (a deferred plus empty message buffers, the
 * same pieces the start path wires), exercising the shared await/collect/remove path without
 * running a real build — which the fixture cannot do.
 */
class BuildProjectLongPollBehaviorTest : McpPlatformTestCase() {

    private val tool = BuildProjectTool()
    private val json = Json { ignoreUnknownKeys = true }

    private val registeredIds = mutableListOf<String>()

    override fun tearDown() {
        try {
            registeredIds.forEach { ActiveBuildRegistry.getInstance(project).remove(it) }
            registeredIds.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun successfulResult(): ProjectTaskManager.Result = object : ProjectTaskManager.Result {
        override fun getContext(): ProjectTaskContext = ProjectTaskContext()
        override fun isAborted(): Boolean = false
        override fun hasErrors(): Boolean = false
        override fun anyTaskMatches(predicate: BiPredicate<in ProjectTask, in ProjectTaskState>): Boolean = false
    }

    private fun registerBuild(id: String, timeoutSeconds: Int? = 600): ActiveBuildRegistry.ActiveBuild {
        registeredIds.add(id)
        return ActiveBuildRegistry.getInstance(project).register(
            ActiveBuildRegistry.ActiveBuild(
                id = id,
                startedAtMs = System.currentTimeMillis(),
                timeoutSeconds = timeoutSeconds,
                includeRawOutput = false,
                result = CompletableDeferred(),
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

    fun testUnknownBuildIdIsRejected() {
        val result = callTool("buildId" to "no-such-build")
        assertToolFailed("polling an unknown buildId must fail", result)
        assertTrue(
            "error must echo the buildId so the agent can see what it asked for",
            toolText(result).contains("no-such-build")
        )
    }

    fun testAttachReturnsInProgressWhileBuildStillExecuting() {
        registerBuild("bld-in-flight")

        val result = callTool("buildId" to "bld-in-flight", "waitSeconds" to 1)

        assertToolSucceeded("an in-flight poll is a success, not an error", result)
        val payload = json.decodeFromString(BuildInProgressResult.serializer(), toolText(result))
        assertEquals("running", payload.status)
        assertEquals("bld-in-flight", payload.buildId)

        val again = callTool("buildId" to "bld-in-flight", "waitSeconds" to 0)
        assertToolSucceeded("the build must remain pollable until it completes", again)
        val againPayload = json.decodeFromString(BuildInProgressResult.serializer(), toolText(again))
        assertEquals("second poll must still report running, not fabricate final results", "running", againPayload.status)
    }

    fun testAttachDeliversResultsOnceBuildCompletes() {
        val build = registerBuild("bld-finished")
        build.result.complete(successfulResult())

        val result = callTool("buildId" to "bld-finished", "waitSeconds" to 0)

        assertToolSucceeded("a completed build must return final results", result)
        val payload = json.decodeFromString(BuildProjectResult.serializer(), toolText(result))
        assertTrue("fake result has no errors", payload.success)
        assertFalse("completed build is not aborted", payload.aborted)

        assertToolFailed(
            "collected builds must be removed from the registry",
            callTool("buildId" to "bld-finished")
        )
    }

    fun testWatchdogMarksOverdueBuildTimedOut() {
        val build = registerBuild("bld-overdue", timeoutSeconds = 1)

        val deadline = System.currentTimeMillis() + 30_000
        while (!build.timedOutByWatchdog && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue(
            "the registry watchdog must flag the build once timeoutSeconds expires, " +
                    "even if no MCP call is waiting on it",
            build.timedOutByWatchdog
        )

        val result = callTool("buildId" to "bld-overdue", "waitSeconds" to 0)
        assertToolSucceeded("a timed-out build still returns a structured result", result)
        val payload = json.decodeFromString(BuildProjectResult.serializer(), toolText(result))
        assertTrue("timeout must surface as aborted", payload.aborted)
        assertFalse(payload.success)
    }

    /**
     * Once the watchdog has declared a build timed out, a poll must notice the verdict before
     * waiting — the fixture's deferred never completes, so waiting at all would block the full
     * waitSeconds budget.
     */
    fun testPollAfterWatchdogVerdictReturnsWithoutWaitingOutTheBudget() {
        val build = registerBuild("bld-zombie", timeoutSeconds = 1)

        val flagDeadline = System.currentTimeMillis() + 30_000
        while (!build.timedOutByWatchdog && System.currentTimeMillis() < flagDeadline) {
            Thread.sleep(100)
        }
        assertTrue("precondition: watchdog fired", build.timedOutByWatchdog)

        val start = System.currentTimeMillis()
        val result = callTool("buildId" to "bld-zombie", "waitSeconds" to 30)
        val elapsedMs = System.currentTimeMillis() - start

        val payload = json.decodeFromString(BuildProjectResult.serializer(), toolText(result))
        assertTrue("verdict already known — must report aborted", payload.aborted)
        assertTrue(
            "poll took ${elapsedMs}ms; it must return promptly instead of waiting out the 30s budget",
            elapsedMs < 10_000
        )
    }
}

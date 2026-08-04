package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import junit.framework.TestCase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RunTestsUnitTest : TestCase() {

    // ── parseTarget ────────────────────────────────────────────────────────────

    fun testParseTargetSimpleClassName() {
        val (className, method) = RunTestsTool.parseTarget("com.example.MyTest")
        assertEquals("com.example.MyTest", className)
        assertNull("No method expected", method)
    }

    fun testParseTargetClassAndMethod() {
        val (className, method) = RunTestsTool.parseTarget("com.example.MyTest#testFoo")
        assertEquals("com.example.MyTest", className)
        assertEquals("testFoo", method)
    }

    fun testParseTargetBlankMethodAfterHash() {
        // "Class#" — the part after # is empty/blank, so method should be null
        val (className, method) = RunTestsTool.parseTarget("com.example.MyTest#")
        assertEquals("com.example.MyTest", className)
        assertNull("Blank method part should become null", method)
    }

    fun testParseTargetRunConfigName() {
        // Run-config names may contain spaces and no # — passthrough as class, no method
        val (className, method) = RunTestsTool.parseTarget("All Tests")
        assertEquals("All Tests", className)
        assertNull(method)
    }

    fun testParseTargetMultipleHashes() {
        // split with limit=2 → second part preserves any further # characters
        val (className, method) = RunTestsTool.parseTarget("com.example.MyTest#foo#extra")
        assertEquals("com.example.MyTest", className)
        assertEquals("foo#extra", method)
    }

    fun testParseTargetEmptyString() {
        val (className, method) = RunTestsTool.parseTarget("")
        assertEquals("", className)
        assertNull(method)
    }

    // ── shouldActivateToolWindow ──────────────────────────────────────────────

    /**
     * The default is part of the tool contract (issue #278): agent runs must not pop the Run tool
     * window unless explicitly requested. The literal wire name pins the schema parameter.
     */
    fun testActivateToolWindowDefaultsToFalse() {
        assertFalse(RunTestsTool.shouldActivateToolWindow(buildJsonObject { }))
    }

    fun testActivateToolWindowExplicitTrue() {
        assertTrue(RunTestsTool.shouldActivateToolWindow(buildJsonObject { put("activateToolWindow", true) }))
    }

    fun testActivateToolWindowExplicitFalse() {
        assertFalse(RunTestsTool.shouldActivateToolWindow(buildJsonObject { put("activateToolWindow", false) }))
    }

    // ── resolveRequestMode ─────────────────────────────────────────────────────

    /**
     * The target/runId split is the tool contract for issue #277: a fresh call starts a run, a
     * runId call attaches to one already in flight. Both or neither must be rejected before any
     * project work happens.
     */
    fun testRequestModeStartWhenOnlyTargetProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject { put("target", "com.example.MyTest") })
        assertEquals(RunTestsTool.RequestMode.Start("com.example.MyTest"), mode)
    }

    fun testRequestModeAttachWhenOnlyRunIdProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject { put("runId", "abc-123") })
        assertEquals(RunTestsTool.RequestMode.Attach("abc-123"), mode)
    }

    fun testRequestModeInvalidWhenBothProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("target", "com.example.MyTest")
            put("runId", "abc-123")
        })
        assertTrue("target and runId together must be rejected", mode is RunTestsTool.RequestMode.Invalid)
    }

    fun testRequestModeInvalidWhenNeitherProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject { })
        assertTrue("one of target/runId is required", mode is RunTestsTool.RequestMode.Invalid)
    }

    fun testRequestModeTreatsBlankValuesAsAbsent() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("target", "   ")
            put("runId", "")
        })
        assertTrue("blank strings are not real values", mode is RunTestsTool.RequestMode.Invalid)
    }

    // ── resolveWaitSeconds ─────────────────────────────────────────────────────

    /**
     * Every call must return well under the MCP client's own request timeout (60s in Claude
     * Code / the TS SDK) — that client timeout is what issue #277 actually hit. The default and
     * ceiling below keep worst-case call time (wait + tree-finalize grace) under it.
     */
    fun testWaitSecondsDefaultsToSafeBudget() {
        assertEquals(45, RunTestsTool.resolveWaitSeconds(buildJsonObject { }))
    }

    fun testWaitSecondsClampsAboveCeiling() {
        assertEquals(55, RunTestsTool.resolveWaitSeconds(buildJsonObject { put("waitSeconds", 3600) }))
    }

    fun testWaitSecondsClampsNegativeToZero() {
        assertEquals(0, RunTestsTool.resolveWaitSeconds(buildJsonObject { put("waitSeconds", -5) }))
    }

    fun testWaitSecondsExplicitValueWithinRange() {
        assertEquals(10, RunTestsTool.resolveWaitSeconds(buildJsonObject { put("waitSeconds", 10) }))
    }

    // ── finalizeWaitMillis ─────────────────────────────────────────────────────

    /**
     * The tree-finalize grace after process exit must fit inside what remains of the call's wait
     * budget — otherwise a late exit stacks up to ~65s total, the client aborts at 60s, and the
     * collected-and-removed results are lost forever. A small floor keeps collection possible
     * even when the budget is already spent (e.g. waitSeconds=0 polls).
     */
    fun testFinalizeWaitCappedAtTreeFinalizeTimeout() {
        // 45s budget, 5s elapsed: plenty left, but never wait longer than the 10s finalize cap
        assertEquals(10_000L, RunTestsTool.finalizeWaitMillis(45, callStartMs = 0, nowMs = 5_000))
    }

    fun testFinalizeWaitShrinksToRemainingBudget() {
        // 45s budget, 40s elapsed: only 5s left — use it instead of the full 10s cap
        assertEquals(5_000L, RunTestsTool.finalizeWaitMillis(45, callStartMs = 0, nowMs = 40_000))
    }

    fun testFinalizeWaitFlooredWhenBudgetExhausted() {
        // 55s budget, 54.5s elapsed: remaining 500ms is too little to ever collect a tree —
        // floor at 3s rather than returning with results silently dropped
        assertEquals(3_000L, RunTestsTool.finalizeWaitMillis(55, callStartMs = 0, nowMs = 54_500))
        assertEquals(3_000L, RunTestsTool.finalizeWaitMillis(0, callStartMs = 0, nowMs = 0))
    }

    // ── needsPsiSync ───────────────────────────────────────────────────────────

    /**
     * Attach polls touch no PSI until final collection, and with "Sync external file changes"
     * enabled a full VFS refresh per poll costs seconds on large repos — a 2h run polls ~160
     * times. Start calls resolve the target from PSI and must keep syncing.
     */
    fun testPollCallsSkipPsiSyncButStartCallsDoNot() {
        val tool = RunTestsTool()
        assertFalse(
            "runId polls must not pay the PSI sync tax",
            tool.needsPsiSync(buildJsonObject { put("runId", "abc-123") })
        )
        assertTrue(
            "target starts resolve PSI and must sync",
            tool.needsPsiSync(buildJsonObject { put("target", "com.example.MyTest") })
        )
    }

    // ── in-progress result composition ────────────────────────────────────────

    /**
     * The in-progress payload is what an agent sees instead of a client-side timeout. It must
     * carry the runId both as a field and inside an actionable poll instruction.
     */
    fun testInProgressResultTellsAgentHowToPoll() {
        val result = RunTestsTool.buildInProgressResult(
            runId = "abc-123",
            configName = "MyTest config",
            elapsedSeconds = 61,
            timeoutSeconds = 7200
        )
        assertEquals("running", result.status)
        assertEquals("abc-123", result.runId)
        assertEquals("MyTest config", result.configName)
        assertEquals(61L, result.elapsedSeconds)
        assertEquals(7200, result.timeoutSeconds)
        assertTrue("message must repeat the runId for the poll call", result.message.contains("abc-123"))
        assertTrue("message must name the runId parameter", result.message.contains("runId"))
    }

    // ── TestStatus.isFailure ───────────────────────────────────────────────────

    /**
     * Drives whether [TestResultsCollector] attaches an error message to a run entry, so the
     * failure/non-failure split is part of the ide_run_tests output contract.
     */
    fun testOnlyFailedAndErrorStatusesAreFailures() {
        assertTrue(TestStatus.FAILED.isFailure)
        assertTrue(TestStatus.ERROR.isFailure)
        assertFalse(TestStatus.PASSED.isFailure)
        assertFalse(TestStatus.SKIPPED.isFailure)
    }

    // ── TestResultsCollector.composeName ──────────────────────────────────────

    fun testComposeNameWithSuite() {
        assertEquals("MyClass.testFoo", TestResultsCollector.composeName("testFoo", "MyClass"))
    }

    fun testComposeNameWithoutSuite() {
        assertEquals("testFoo", TestResultsCollector.composeName("testFoo", null))
    }

    fun testComposeNameWithBlankParent() {
        // Blank parent should be treated as absent
        assertEquals("testFoo", TestResultsCollector.composeName("testFoo", ""))
        assertEquals("testFoo", TestResultsCollector.composeName("testFoo", "   "))
    }
}

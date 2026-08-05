package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import junit.framework.TestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * Pins the shared await logic every long-poll tool leans on. The ordering of the three branches
 * is load-bearing: a completed operation must win over a stale watchdog verdict, and a watchdog
 * verdict must win over waiting — getting this wrong either fabricates timeouts for finished
 * work or burns a full wait budget on an operation already declared dead (both were near-misses
 * found in review of the first implementation).
 */
class LongPollRegistryUnitTest : TestCase() {

    private class FakeOperation(
        override val deadlineMs: Long? = null
    ) : LongPollOperation(id = "fake-op") {
        override val completion: CompletableDeferred<Int> = CompletableDeferred()
        override fun onDeadline() {}
        override fun onCleanup() {}
    }

    fun testCompletedOperationReturnsValueEvenAfterWatchdogVerdict() = runBlocking<Unit> {
        val op = FakeOperation()
        op.completion.complete(42)
        op.timedOutByWatchdog = true
        assertEquals(
            "a real result must win over a stale timeout verdict",
            42,
            op.awaitWithinBudget(op.completion, waitSeconds = 10, callStartMs = System.currentTimeMillis())
        )
    }

    fun testWatchdogVerdictShortCircuitsTheWait() = runBlocking<Unit> {
        val op = FakeOperation()
        op.timedOutByWatchdog = true
        val start = System.currentTimeMillis()
        val result = op.awaitWithinBudget(op.completion, waitSeconds = 30, callStartMs = start)
        assertNull("declared-dead operation must not be awaited", result)
        assertTrue(
            "verdict check must not consume the wait budget",
            System.currentTimeMillis() - start < 5_000
        )
    }

    fun testExhaustedBudgetReturnsNullWithoutWaiting() = runBlocking<Unit> {
        val op = FakeOperation()
        val result = op.awaitWithinBudget(op.completion, waitSeconds = 0, callStartMs = System.currentTimeMillis())
        assertNull("zero budget means status check only", result)
    }

    fun testPendingOperationWaitsOutTheBudgetThenReturnsNull() = runBlocking<Unit> {
        val op = FakeOperation()
        val start = System.currentTimeMillis()
        val result = op.awaitWithinBudget(op.completion, waitSeconds = 1, callStartMs = start)
        assertNull(result)
        assertTrue("must have actually waited the budget", System.currentTimeMillis() - start >= 900)
    }
}

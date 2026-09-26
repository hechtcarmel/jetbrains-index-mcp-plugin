package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.intellij.execution.testframework.sm.runner.SMTestProxy

/**
 * Pins the ide_run_tests per-test output produced by [TestResultsCollector.collectRunEntries]
 * from a real SMTestProxy tree — in particular that failed/errored tests carry the stack trace
 * reported by the test framework (issue #316), and passed tests do not.
 */
class TestResultsCollectorRunEntriesTest : McpPlatformTestCase() {

    private val stackTrace = """
        java.lang.RuntimeException: depth 500
        ${'\t'}at com.example.FooTest.recurse(FooTest.java:44)
        ${'\t'}at com.example.FooTest.longStackTraceTest(FooTest.java:34)
        Caused by: java.lang.RuntimeException: depth 0
    """.trimIndent()

    private fun buildRoot(configure: (SMTestProxy) -> Unit): SMTestProxy.SMRootTestProxy {
        val root = SMTestProxy.SMRootTestProxy()
        root.setStarted()
        val suite = SMTestProxy("FooTest", true, null)
        root.addChild(suite)
        suite.setStarted()
        val test = SMTestProxy("testX", false, null)
        suite.addChild(test)
        test.setStarted()
        configure(test)
        suite.setFinished()
        root.setFinished()
        return root
    }

    fun testFailedTestCarriesErrorMessageAndStackTrace() {
        val root = buildRoot { it.setTestFailed("depth 500", stackTrace, false) }

        val entries = TestResultsCollector.collectRunEntries(root)

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("FooTest.testX", entry.name)
        assertEquals(TestStatus.FAILED, entry.status)
        assertEquals("depth 500", entry.errorMessage)
        assertEquals(stackTrace, entry.stackTrace)
    }

    fun testErroredTestCarriesStackTrace() {
        val root = buildRoot { it.setTestFailed("depth 500", stackTrace, true) }

        val entry = TestResultsCollector.collectRunEntries(root).single()
        assertEquals(TestStatus.ERROR, entry.status)
        assertEquals(stackTrace, entry.stackTrace)
    }

    fun testPassedTestHasNoStackTrace() {
        val root = buildRoot { it.setFinished() }

        val entry = TestResultsCollector.collectRunEntries(root).single()
        assertEquals(TestStatus.PASSED, entry.status)
        assertNull(entry.errorMessage)
        assertNull(entry.stackTrace)
    }

    fun testBlankStackTraceBecomesNull() {
        val root = buildRoot { it.setTestFailed("boom", "   \n", false) }

        val entry = TestResultsCollector.collectRunEntries(root).single()
        assertEquals(TestStatus.FAILED, entry.status)
        assertNull("blank traces must not produce a whitespace-only field", entry.stackTrace)
    }

    fun testOversizedStackTraceIsTruncatedKeepingHeadAndTail() {
        val monster = "java.lang.RuntimeException: depth 500\n" +
                "\tat com.example.FooTest.recurse(FooTest.java:44)\n".repeat(5_000) +
                "Caused by: java.lang.RuntimeException: depth 0"
        val root = buildRoot { it.setTestFailed("depth 500", monster, false) }

        val entry = TestResultsCollector.collectRunEntries(root).single()
        val trace = entry.stackTrace!!
        assertTrue(
            "trace must be capped near MAX_RUN_ENTRY_STACKTRACE_LENGTH, was ${trace.length}",
            trace.length < TestResultsCollector.MAX_RUN_ENTRY_STACKTRACE_LENGTH + 100
        )
        assertTrue("must keep the head", trace.startsWith("java.lang.RuntimeException: depth 500"))
        assertTrue("must keep the tail (root cause)", trace.endsWith("Caused by: java.lang.RuntimeException: depth 0"))
        assertTrue("must mark the elision", trace.contains("chars truncated"))
    }

    fun testAggregateBudgetDropsTracesOnceSpent() {
        val root = SMTestProxy.SMRootTestProxy()
        root.setStarted()
        val suite = SMTestProxy("FooTest", true, null)
        root.addChild(suite)
        suite.setStarted()
        repeat(3) { i ->
            val test = SMTestProxy("test$i", false, null)
            suite.addChild(test)
            test.setStarted()
            test.setTestFailed("boom $i", stackTrace, false)
        }
        suite.setFinished()
        root.setFinished()

        // Budget fits exactly one trace: the first failure keeps it, the rest fall back to
        // errorMessage only — a mass failure must not produce an unbounded response.
        val entries = TestResultsCollector.collectRunEntries(root, totalStackTraceBudget = stackTrace.length)

        assertEquals(3, entries.size)
        assertEquals(stackTrace, entries[0].stackTrace)
        assertNull("budget spent — trace must be dropped", entries[1].stackTrace)
        assertNull("budget spent — trace must be dropped", entries[2].stackTrace)
        assertEquals("errorMessage survives the budget cut", "boom 1", entries[1].errorMessage)
    }

    // ── collectRunProgress: the in-progress snapshot (issue #426) ─────────────────────────────

    /** A tree that is still running: a started root and suite, children added by [addTest]. */
    private fun liveSuite(): Pair<SMTestProxy.SMRootTestProxy, SMTestProxy> {
        val root = SMTestProxy.SMRootTestProxy()
        root.setStarted()
        val suite = SMTestProxy("FooTest", true, null)
        root.addChild(suite)
        suite.setStarted()
        return root to suite
    }

    private fun addTest(suite: SMTestProxy, name: String, state: (SMTestProxy) -> Unit): SMTestProxy =
        SMTestProxy(name, false, null).also {
            suite.addChild(it)
            state(it)
        }

    private fun failingTests(count: Int): SMTestProxy.SMRootTestProxy {
        val (root, suite) = liveSuite()
        repeat(count) { i ->
            addTest(suite, "test$i") {
                it.setStarted()
                it.setTestFailed("boom $i", stackTrace, false)
            }
        }
        return root
    }

    fun testProgressCountsFinishedTestsAndListsOnlyFailures() {
        val (root, suite) = liveSuite()
        addTest(suite, "testPassed") { it.setStarted(); it.setFinished() }
        addTest(suite, "testFailed") {
            it.setStarted()
            it.addStdOutput("state before assertion: 2\n")
            it.setTestFailed("expected:<1> but was:<2>", stackTrace, false)
        }
        addTest(suite, "testErrored") { it.setStarted(); it.setTestFailed("boom", stackTrace, true) }
        addTest(suite, "testSkipped") { it.setStarted(); it.setTestIgnored("disabled", null) }
        addTest(suite, "testRunning") { it.setStarted() }
        addTest(suite, "testNotRunYet") { }

        val progress = TestResultsCollector.collectRunProgress(root)

        assertEquals(1, progress.passed)
        assertEquals(1, progress.failed)
        assertEquals(1, progress.errors)
        assertEquals(
            "only finished failed/errored tests are listed, in run order",
            listOf("FooTest.testFailed" to TestStatus.FAILED, "FooTest.testErrored" to TestStatus.ERROR),
            progress.failures.map { it.name to it.status }
        )
        val failed = progress.failures.first()
        assertEquals("expected:<1> but was:<2>", failed.errorMessage)
        assertEquals(stackTrace, failed.stackTrace)
        assertNull("console output waits for the final result", failed.output)
    }

    fun testProgressOfTreeWithNothingFinishedIsNone() {
        val (root, suite) = liveSuite()
        addTest(suite, "testRunning") { it.setStarted() }

        assertEquals(TestResultsCollector.RunProgress.NONE, TestResultsCollector.collectRunProgress(root))
    }

    /** Mid-run entries are the final result's entries for the same tests, so the two never disagree. */
    fun testProgressFailuresMatchFinalEntries() {
        val (root, suite) = liveSuite()
        addTest(suite, "testPassed") { it.setStarted(); it.setFinished() }
        addTest(suite, "testFailed") { it.setStarted(); it.setTestFailed("expected:<1> but was:<2>", stackTrace, false) }
        addTest(suite, "testErrored") { it.setStarted(); it.setTestFailed("boom", "   \n", true) }

        assertEquals(
            TestResultsCollector.collectRunEntries(root).filter { it.status.isFailure },
            TestResultsCollector.collectRunProgress(root).failures
        )
    }

    fun testProgressCapsFailuresButKeepsCountsExact() {
        val root = failingTests(TestResultsCollector.MAX_PROGRESS_FAILURES + 10)

        val progress = TestResultsCollector.collectRunProgress(root)

        assertEquals(
            "the count must stay exact past the cap",
            TestResultsCollector.MAX_PROGRESS_FAILURES + 10,
            progress.failed
        )
        assertEquals(TestResultsCollector.MAX_PROGRESS_FAILURES, progress.failures.size)
        assertEquals("the cap keeps the first failures", "FooTest.test0", progress.failures.first().name)
    }

    fun testProgressSpendsOneStackTraceBudgetAcrossFailures() {
        val root = failingTests(3)

        val progress = TestResultsCollector.collectRunProgress(root, totalStackTraceBudget = stackTrace.length)

        assertEquals(stackTrace, progress.failures[0].stackTrace)
        assertNull("budget spent — trace must be dropped", progress.failures[1].stackTrace)
        assertEquals("errorMessage survives the budget cut", "boom 1", progress.failures[1].errorMessage)
    }

    fun testDiagnosticsPathTruncationKeepsRootCause() {
        // ide_diagnostics shares the same truncation helper: its 500-char cap must also keep
        // the root cause at the bottom of a chained trace, not just the head.
        val monster = "java.lang.RuntimeException: depth 500\n" +
                "\tat com.example.FooTest.recurse(FooTest.java:44)\n".repeat(50) +
                "Caused by: java.lang.RuntimeException: depth 0"
        val proxy = SMTestProxy("testX", false, null)
        proxy.setStarted()
        proxy.setTestFailed("depth 500", monster, false)

        val info = TestResultsCollector.toTestResultInfo(proxy, project)

        val trace = info.stacktrace!!
        assertTrue("must keep the head", trace.startsWith("java.lang.RuntimeException: depth 500"))
        assertTrue(
            "diagnostics traces must keep the root cause",
            trace.endsWith("Caused by: java.lang.RuntimeException: depth 0")
        )
        assertTrue("must mark the elision", trace.contains("chars truncated"))
    }
}

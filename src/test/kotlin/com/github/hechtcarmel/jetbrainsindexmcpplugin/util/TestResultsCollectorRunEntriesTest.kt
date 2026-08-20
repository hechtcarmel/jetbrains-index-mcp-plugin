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

package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.execution.testframework.sm.runner.SMTestProxy

/**
 * Pins the ide_run_tests console-output capture (issue #346) against a real SMTestProxy tree:
 * a test's own prints come back on its entry (stdout/stderr merged in print order, ANSI
 * stripped, system messages excluded), root/suite/config prints come back as the run-level
 * `output`, and a failed test's output never duplicates the errorMessage/stackTrace fields.
 *
 * In unit-test mode CompositePrintable replays synchronously on any thread, so these tests call
 * [TestResultsCollector.collectRunOutputs] directly; production routes the call through
 * RunTestsTool's alarm-queue bridge.
 */
class TestResultsCollectorOutputTest : McpPlatformTestCase() {

    private lateinit var root: SMTestProxy.SMRootTestProxy
    private lateinit var suite: SMTestProxy

    private fun buildTree(): SMTestProxy.SMRootTestProxy {
        root = SMTestProxy.SMRootTestProxy()
        root.setStarted()
        suite = SMTestProxy("FooTest", true, null)
        root.addChild(suite)
        suite.setStarted()
        return root
    }

    private fun addTest(name: String, configure: (SMTestProxy) -> Unit): SMTestProxy {
        val test = SMTestProxy(name, false, null)
        suite.addChild(test)
        test.setStarted()
        configure(test)
        return test
    }

    private fun finish(): SMTestProxy.SMRootTestProxy {
        suite.setFinished()
        root.setFinished()
        return root
    }

    fun testPerTestOutputMergesStdoutAndStderrInPrintOrder() {
        buildTree()
        val test = addTest("testLogs") {
            it.addStdOutput("Hello world\n")
            it.addStdErr("warning: careful\n")
            it.addStdOutput("done\n")
            it.setFinished()
        }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)

        assertEquals("Hello world\nwarning: careful\ndone\n", outputs.perTest[test])
    }

    fun testCollectRunEntriesAttachesOutputToTheMatchingEntry() {
        buildTree()
        addTest("testLogs") {
            it.addStdOutput("Hello world\n")
            it.setFinished()
        }
        addTest("testSilent") { it.setFinished() }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)
        val entries = TestResultsCollector.collectRunEntries(root, outputs = outputs.perTest)

        assertEquals(2, entries.size)
        val (logs, silent) = entries.partition { it.name == "FooTest.testLogs" }
        assertEquals("Hello world\n", logs.single().output)
        assertNull("a test that printed nothing must carry no output field", silent.single().output)
    }

    fun testSystemOutputIsExcluded() {
        buildTree()
        val test = addTest("testX") {
            it.addSystemOutput("/usr/bin/java -cp <huge classpath> Runner\n")
            it.addStdOutput("real output\n")
            it.setFinished()
        }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)

        assertEquals("real output\n", outputs.perTest[test])
    }

    fun testAnsiEscapesAreStripped() {
        buildTree()
        val test = addTest("testX") {
            it.addStdOutput("[31mred[0m plain\n")
            it.setFinished()
        }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)

        assertEquals("red plain\n", outputs.perTest[test])
    }

    fun testFailureStateIsNotDuplicatedIntoOutput() {
        val trace = "java.lang.AssertionError: boom\n\tat com.example.FooTest.testX(FooTest.java:12)"
        buildTree()
        val test = addTest("testX") {
            it.addStdOutput("logged before the failure\n")
            it.setTestFailed("boom", trace, false)
        }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)
        val entry = TestResultsCollector.collectRunEntries(root, outputs = outputs.perTest).single()

        assertEquals("logged before the failure\n", entry.output)
        assertEquals("the structured field still carries the trace", trace, entry.stackTrace)
        assertFalse(
            "output must not duplicate the stacktrace the entry already carries",
            entry.output!!.contains("AssertionError")
        )
    }

    fun testRootSuiteAndConfigOutputLandsInUnattributed() {
        buildTree()
        root.addStdOutput("framework banner\n")
        suite.addStdOutput("suite-level line\n")
        val config = SMTestProxy("Class Configuration", false, null)
        config.setConfig(true)
        suite.addChild(config)
        config.setStarted()
        config.addStdOutput("@BeforeAll print\n")
        config.setFinished()
        val test = addTest("testX") {
            it.addStdOutput("test print\n")
            it.setFinished()
        }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)

        assertEquals("test print\n", outputs.perTest[test])
        val unattributed = outputs.unattributed!!
        assertTrue("root output belongs to the run", unattributed.contains("framework banner"))
        assertTrue("suite output belongs to the run", unattributed.contains("suite-level line"))
        assertTrue("config-node output belongs to the run", unattributed.contains("@BeforeAll print"))
        assertFalse("per-test output must not leak into the run-level field", unattributed.contains("test print"))
        val entries = TestResultsCollector.collectRunEntries(root, outputs = outputs.perTest)
        assertEquals("config nodes are not test entries", 1, entries.size)
    }

    fun testNoOutputAnywhereYieldsNullsNotEmptyStrings() {
        buildTree()
        addTest("testX") { it.setFinished() }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)

        assertTrue(outputs.perTest.isEmpty())
        assertNull(outputs.unattributed)
    }

    fun testBlankOutputBecomesNull() {
        buildTree()
        val test = addTest("testX") {
            it.addStdOutput("   \n")
            it.setFinished()
        }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)

        assertNull("whitespace-only output must not produce a field", outputs.perTest[test])
    }

    fun testOversizedPerTestOutputIsTruncatedKeepingHeadAndTail() {
        buildTree()
        val test = addTest("testX") {
            it.addStdOutput("START\n")
            repeat(2_000) { i -> it.addStdOutput("noise line $i\n") }
            it.addStdOutput("THE-END\n")
            it.setFinished()
        }
        finish()

        val outputs = TestResultsCollector.collectRunOutputs(root)

        val output = outputs.perTest[test]!!
        assertTrue(
            "output must be capped near MAX_RUN_ENTRY_OUTPUT_LENGTH, was ${output.length}",
            output.length < TestResultsCollector.MAX_RUN_ENTRY_OUTPUT_LENGTH + 100
        )
        assertTrue("must keep the head", output.startsWith("START\n"))
        assertTrue("must keep the tail", output.endsWith("THE-END\n"))
        assertTrue("must mark the elision", output.contains("chars truncated"))
    }

    fun testAggregateOutputBudgetStopsAttachingOnceSpent() {
        buildTree()
        val tests = (0 until 3).map { i ->
            addTest("test$i") {
                it.addStdOutput("output of test $i\n")
                it.setFinished()
            }
        }
        finish()

        // Budget fits exactly the first test's output: later tests must carry none, so a mass
        // logging suite cannot produce an unbounded response.
        val outputs = TestResultsCollector.collectRunOutputs(
            root,
            totalOutputBudget = "output of test 0\n".length
        )

        assertEquals("output of test 0\n", outputs.perTest[tests[0]])
        assertNull("budget spent — output must be dropped", outputs.perTest[tests[1]])
        assertNull("budget spent — output must be dropped", outputs.perTest[tests[2]])
    }
}

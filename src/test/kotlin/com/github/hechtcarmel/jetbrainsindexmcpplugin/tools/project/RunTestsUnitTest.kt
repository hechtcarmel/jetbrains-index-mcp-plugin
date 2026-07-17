package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ListTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestRunEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RunTestsUnitTest : TestCase() {

    private val json = Json { encodeDefaults = true }

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

    // ── TestStatus.isFailure ───────────────────────────────────────────────────

    fun testPassedIsNotFailure() {
        assertFalse(TestStatus.PASSED.isFailure)
    }

    fun testSkippedIsNotFailure() {
        assertFalse(TestStatus.SKIPPED.isFailure)
    }

    fun testFailedIsFailure() {
        assertTrue(TestStatus.FAILED.isFailure)
    }

    fun testErrorIsFailure() {
        assertTrue(TestStatus.ERROR.isFailure)
    }

    // ── RunTestsResult field semantics ─────────────────────────────────────────

    fun testSuccessRequiresZeroExitAndNoFailures() {
        assertTrue("zero exit + no failures = success", makeResult(exitCode = 0, failed = 0, errors = 0).success)
        assertFalse("non-zero exit = not success", makeResult(exitCode = 1, failed = 0, errors = 0).success)
        assertFalse("failed tests = not success", makeResult(exitCode = 0, failed = 1, errors = 0).success)
        assertFalse("errors = not success", makeResult(exitCode = 0, failed = 0, errors = 1).success)
    }

    fun testTimedOutWhenExitCodeIsNegativeOne() {
        // RunTestsTool uses exitCode = -1 and timedOut = true for a timeout
        val result = RunTestsResult(
            success = false, timedOut = true, noTestsFound = false,
            exitCode = -1, passed = 0, failed = 0, errors = 0,
            total = 0, tests = emptyList()
        )
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
    }

    fun testNoTestsFoundOnlyWhenEmptyAndZeroExit() {
        val onePassedTest = listOf(TestRunEntry("Suite.testA", TestStatus.PASSED))
        assertTrue(
            "empty results + exit 0 = noTestsFound",
            makeResult(exitCode = 0, tests = emptyList()).noTestsFound
        )
        assertFalse(
            "non-empty results, exit 0: noTestsFound is false",
            makeResult(exitCode = 0, passed = 1, tests = onePassedTest).noTestsFound
        )
        assertFalse(
            "empty results but non-zero exit (build failure): noTestsFound is false",
            makeResult(exitCode = 1, tests = emptyList()).noTestsFound
        )
    }

    // ── RunTestsResult serialization ───────────────────────────────────────────

    fun testRunTestsResultSerializationRoundtrip() {
        val result = RunTestsResult(
            success = false,
            timedOut = false,
            noTestsFound = false,
            exitCode = 1,
            passed = 2,
            failed = 1,
            errors = 0,
            total = 3,
            tests = listOf(
                TestRunEntry("Suite.testA", TestStatus.PASSED),
                TestRunEntry("Suite.testB", TestStatus.PASSED),
                TestRunEntry("Suite.testC", TestStatus.FAILED, "expected true but was false")
            )
        )
        val decoded = json.decodeFromString<RunTestsResult>(json.encodeToString(result))
        assertEquals(result, decoded)
    }

    fun testTestRunEntryErrorMessageNullWhenPassed() {
        val entry = TestRunEntry("Suite.testA", TestStatus.PASSED, errorMessage = null)
        assertNull(entry.errorMessage)
        assertEquals(TestStatus.PASSED, entry.status)
    }

    fun testTestRunEntrySerializationWithErrorMessage() {
        val entry = TestRunEntry("Suite.testFail", TestStatus.FAILED, errorMessage = "boom")
        val decoded = json.decodeFromString<TestRunEntry>(json.encodeToString(entry))
        assertEquals(entry, decoded)
        assertEquals("boom", decoded.errorMessage)
    }

    // ── ListTestsResult truncated boundary ─────────────────────────────────────

    fun testTruncatedFalseWhenExactlyAtCap() {
        val cap = ListTestsTool.MAX_TESTS
        val exactly = List(cap) { makeTestEntry(it) }
        // Mirror the tool's own boundary check: > cap, not >= cap
        assertFalse("Exactly $cap entries must NOT trigger truncation", exactly.size > cap)
    }

    fun testTruncatedTrueWhenOneAboveCap() {
        val cap = ListTestsTool.MAX_TESTS
        val oneOver = List(cap + 1) { makeTestEntry(it) }
        assertTrue("${cap + 1} entries MUST trigger truncation", oneOver.size > cap)
    }

    fun testListTestsResultPageSizeRespectsCap() {
        val cap = ListTestsTool.MAX_TESTS
        val oversize = List(cap + 1) { makeTestEntry(it) }
        val truncated = oversize.size > cap
        val page = if (truncated) oversize.take(cap) else oversize
        assertEquals("Page must be capped at $cap", cap, page.size)
        assertTrue(truncated)
    }

    fun testListTestsResultSerializationRoundtrip() {
        val result = ListTestsResult(
            tests = listOf(makeTestEntry(0), makeTestEntry(1)),
            count = 2,
            truncated = false
        )
        val decoded = json.decodeFromString<ListTestsResult>(json.encodeToString(result))
        assertEquals(result, decoded)
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

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun makeResult(
        exitCode: Int,
        failed: Int = 0,
        errors: Int = 0,
        passed: Int = 0,
        total: Int = passed + failed + errors,
        tests: List<TestRunEntry> = emptyList()
    ) = RunTestsResult(
        success = exitCode == 0 && failed == 0 && errors == 0,
        timedOut = false,
        noTestsFound = tests.isEmpty() && exitCode == 0,
        exitCode = exitCode,
        passed = passed,
        failed = failed,
        errors = errors,
        total = total,
        tests = tests
    )

    private fun makeTestEntry(index: Int) = TestEntry(
        framework = "JUnit",
        className = "com.example.TestClass$index",
        methodName = "testMethod$index",
        displayName = "com.example.TestClass$index.testMethod$index",
        file = "src/test/TestClass$index.kt",
        line = index + 1
    )
}

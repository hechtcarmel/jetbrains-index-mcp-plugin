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

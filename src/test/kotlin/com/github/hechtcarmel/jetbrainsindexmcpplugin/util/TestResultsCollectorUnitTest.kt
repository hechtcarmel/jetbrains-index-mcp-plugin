package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import junit.framework.TestCase

/**
 * Tests for [TestResultsCollector.magnitudeIndexToStatus].
 *
 * Integer values are those returned by the public SMTestProxy.getMagnitude() method
 * (i.e. Magnitude.getValue()), documented in magnitudeIndexToStatus's KDoc:
 *   0=SKIPPED, 1=COMPLETE/PASSED, 2=NOT_RUN, 3=RUNNING, 4=TERMINATED, 5=IGNORED, 6=FAILED, 8=ERROR
 */
class TestResultsCollectorUnitTest : TestCase() {

    fun testPassedIndex() {
        // PASSED_INDEX = 1
        assertEquals(TestStatus.PASSED, TestResultsCollector.magnitudeIndexToStatus(1))
    }

    fun testSkippedIndex() {
        // SKIPPED_INDEX = 0; isPassed() returns true for this in 2025.3, but we map it to SKIPPED
        assertEquals(TestStatus.SKIPPED, TestResultsCollector.magnitudeIndexToStatus(0))
    }

    fun testIgnoredIndex() {
        // IGNORED_INDEX = 5
        assertEquals(TestStatus.SKIPPED, TestResultsCollector.magnitudeIndexToStatus(5))
    }

    fun testFailedIndex() {
        // FAILED_INDEX = 6
        assertEquals(TestStatus.FAILED, TestResultsCollector.magnitudeIndexToStatus(6))
    }

    fun testErrorIndex() {
        // ERROR_INDEX = 8
        assertEquals(TestStatus.ERROR, TestResultsCollector.magnitudeIndexToStatus(8))
    }

    fun testNotRunReturnsNull() {
        // NOT_RUN_INDEX = 2
        assertNull(TestResultsCollector.magnitudeIndexToStatus(2))
    }

    fun testRunningReturnsNull() {
        // RUNNING_INDEX = 3
        assertNull(TestResultsCollector.magnitudeIndexToStatus(3))
    }

    fun testTerminatedReturnsNull() {
        // TERMINATED_INDEX = 4
        assertNull(TestResultsCollector.magnitudeIndexToStatus(4))
    }

    fun testCompleteSuiteValueMapsToPassedStatusItselfNotNull() {
        // COMPLETE_INDEX = 1, same int value as PASSED_INDEX.
        // collectRunEntries excludes suite nodes via isSuite() before calling this function,
        // so value 1 is unambiguously PASSED at this layer.
        assertEquals(TestStatus.PASSED, TestResultsCollector.magnitudeIndexToStatus(1))
    }

    // ── truncateStackTrace (issue #316) ───────────────────────────────────────

    fun testShortStackTracePassesThroughUntouched() {
        val trace = "java.lang.RuntimeException: boom\n\tat com.example.FooTest.test(FooTest.java:12)"
        assertEquals(trace, TestResultsCollector.truncateStackTrace(trace))
    }

    fun testStackTraceAtExactCapPassesThroughUntouched() {
        val trace = "x".repeat(100)
        assertEquals(trace, TestResultsCollector.truncateStackTrace(trace, maxLength = 100))
    }

    fun testLongStackTraceKeepsHeadAndTail() {
        // Chained exceptions put the root cause at the END of the trace — truncation must keep
        // both the top (message + throw site) and the bottom (deepest causes).
        val head = "java.lang.RuntimeException: depth 500\n"
        val tail = "Caused by: java.lang.RuntimeException: depth 0\n"
        val trace = head + "\tat com.example.FooTest.recurse(FooTest.java:44)\n".repeat(2000) + tail

        val truncated = TestResultsCollector.truncateStackTrace(trace, maxLength = 300)

        assertTrue("must keep the trace head", truncated.startsWith(head))
        assertTrue("must keep the trace tail (root cause)", truncated.endsWith(tail))
        assertTrue("must mark the elision", truncated.contains("chars truncated"))
        // 300 cap + the elision marker line — far below the original ~90KB
        assertTrue("must actually shrink the trace", truncated.length < 400)
    }

    fun testTruncationNeverSplitsSurrogatePairs() {
        // "😀" is a surrogate pair (2 chars). maxLength=101 puts the head cut mid-pair,
        // maxLength=103 puts the tail cut mid-pair — both must shift instead of splitting.
        val trace = "😀".repeat(400)
        for (maxLength in listOf(101, 103)) {
            val truncated = TestResultsCollector.truncateStackTrace(trace, maxLength = maxLength)
            for (i in truncated.indices) {
                val c = truncated[i]
                if (c.isHighSurrogate()) {
                    assertTrue(
                        "unpaired high surrogate at $i (maxLength=$maxLength)",
                        i + 1 < truncated.length && truncated[i + 1].isLowSurrogate()
                    )
                }
                if (c.isLowSurrogate()) {
                    assertTrue(
                        "unpaired low surrogate at $i (maxLength=$maxLength)",
                        i > 0 && truncated[i - 1].isHighSurrogate()
                    )
                }
            }
        }
    }

    fun testDegenerateCapDoesNotCrash() {
        // The helper is shared by two call sites with different caps and formats *error* output:
        // a crash here would replace a real test failure with an internal error.
        for (cap in listOf(0, 1, 2, 3)) {
            val out = TestResultsCollector.truncateStackTrace("abcdefghij", maxLength = cap)
            assertTrue("cap=$cap must still mark the elision", out.contains("chars truncated"))
        }
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import junit.framework.TestCase

class DiagnosticsAnalysisBudgetUnitTest : TestCase() {
    fun testDefaultRemainsThirtySeconds() {
        assertEquals(30_000L, DiagnosticsAnalysisBudget.DEFAULT_TIMEOUT_MS)
        assertEquals(600, DiagnosticsAnalysisBudget.MAX_FILE_TIMEOUT_SECONDS)
    }

    fun testRequestedFileLimitAndRemainingRunTimeBothBoundAnalysis() {
        assertEquals(30_000L, DiagnosticsAnalysisBudget.perFileTimeoutMs(30_000, 600_000))
        assertEquals(300_000L, DiagnosticsAnalysisBudget.perFileTimeoutMs(300_000, 600_000))
        assertEquals(125L, DiagnosticsAnalysisBudget.perFileTimeoutMs(600_000, 125))
    }

    fun testExhaustedDeadlineNeverStartsAnotherFile() {
        assertEquals(0L, DiagnosticsAnalysisBudget.perFileTimeoutMs(600_000, 0))
        assertEquals(0L, DiagnosticsAnalysisBudget.perFileTimeoutMs(600_000, -1))
        assertEquals(0L, DiagnosticsAnalysisBudget.perFileTimeoutMs(600_000, Long.MIN_VALUE))
    }
}

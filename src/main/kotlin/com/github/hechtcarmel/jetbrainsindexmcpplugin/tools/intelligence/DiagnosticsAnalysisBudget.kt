package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

/** Per-call budgets; never change the shared analysis service's configuration. */
internal object DiagnosticsAnalysisBudget {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_FILE_TIMEOUT_SECONDS = 600

    fun perFileTimeoutMs(requestedTimeoutMs: Long, remainingRunMs: Long): Long =
        minOf(requestedTimeoutMs, remainingRunMs).coerceAtLeast(0L)
}

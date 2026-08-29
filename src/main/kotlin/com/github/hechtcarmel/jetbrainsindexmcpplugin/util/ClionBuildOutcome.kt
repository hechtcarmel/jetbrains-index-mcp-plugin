package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

/**
 * Accumulates the CLion cidr build events of one build session ([ClionBuildCapture]).
 *
 * A session ("Build All", a run configuration's build step) can run several cidr builds — one
 * per CMake profile — each delivering its own `beforeStarted`/`afterFinished` pair. Counts are
 * summed across them; the session counts as finished when every build that started has
 * finished (an `afterFinished` with nothing in flight — a missed `beforeStarted` — closes a
 * session of its own, so a lone finish event still reports).
 *
 * Pure state, no reflection: the reflective listener proxy feeds it, tests drive it directly.
 * Thread-safe — cidr events and the MCP call's collection race by design. All reads go through
 * an immutable [Snapshot], taken under the same lock the events mutate under, so a reader can
 * never observe a session mid-reset (a long-lived subscription reuses one accumulator across
 * sessions and the next session's first event wipes the previous session's state).
 */
class ClionBuildOutcome {

    /** One consistent view of the accumulator; safe to read on any thread at any later time. */
    data class Snapshot(
        /** True once any cidr build event was seen — i.e. this really is a CLion CMake build. */
        val sawAnyBuild: Boolean,
        val failed: Boolean,
        /**
         * One-line summary of the failed session for a fallback build message, e.g.
         * `Build failed: 2 errors, 1 warning — <CLion's own message>`. Null while nothing failed.
         */
        val failureSummary: String?,
        val buildIds: List<Any>
    )

    private val lock = Any()

    private var started = 0
    private var finished = 0
    private var inFlight = 0
    private var allSucceeded = true
    private var anyCanceled = false
    private var errorTotal = 0
    private var warningTotal = 0
    private var lastMessage: String? = null
    private val ids = LinkedHashSet<Any>()

    fun buildStarted(buildId: Any?) {
        synchronized(lock) {
            resetIfPreviousSessionEnded()
            started++
            inFlight++
            buildId?.let { ids.add(it) }
        }
    }

    /**
     * Records one finished cidr build. Returns the session's snapshot when this finish
     * completed it — nothing is in flight any more — and null while builds are still running.
     * The snapshot is taken inside the same critical section, so a new session starting right
     * after cannot reset the state out from under the caller.
     */
    fun buildFinished(
        buildId: Any?,
        succeeded: Boolean?,
        canceled: Boolean?,
        errors: Int?,
        warnings: Int?,
        message: String?
    ): Snapshot? {
        synchronized(lock) {
            resetIfPreviousSessionEnded()
            finished++
            if (inFlight > 0) inFlight--
            buildId?.let { ids.add(it) }
            if (succeeded == false) allSucceeded = false
            if (canceled == true) anyCanceled = true
            errorTotal += errors ?: 0
            warningTotal += warnings ?: 0
            if (!message.isNullOrBlank()) lastMessage = message
            return if (inFlight == 0) snapshotLocked() else null
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): Snapshot {
        val failed = finished > 0 && (!allSucceeded || anyCanceled)
        return Snapshot(
            sawAnyBuild = started > 0 || finished > 0,
            failed = failed,
            failureSummary = if (failed) failureSummaryLocked() else null,
            buildIds = ids.toList()
        )
    }

    private fun failureSummaryLocked(): String {
        val headline = if (anyCanceled) "Build canceled" else "Build failed"
        val counts = "$errorTotal ${if (errorTotal == 1) "error" else "errors"}, " +
                "$warningTotal ${if (warningTotal == 1) "warning" else "warnings"}"
        val message = lastMessage
        return if (message.isNullOrBlank()) {
            "$headline: $counts"
        } else {
            "$headline: $counts — $message"
        }
    }

    /**
     * A long-lived subscription (the diagnostics cache) reuses one accumulator across build
     * sessions: an event arriving while nothing is in flight but finishes were recorded begins
     * a new session, and the previous session's state must not bleed into it.
     */
    private fun resetIfPreviousSessionEnded() {
        if (inFlight == 0 && finished > 0) {
            started = 0
            finished = 0
            allSucceeded = true
            anyCanceled = false
            errorTotal = 0
            warningTotal = 0
            lastMessage = null
            ids.clear()
        }
    }
}

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
 * Thread-safe — cidr events and the MCP call's collection race by design.
 */
class ClionBuildOutcome {

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
     * Records one finished cidr build. Returns true when this finish completed the session —
     * nothing is in flight any more.
     */
    fun buildFinished(
        buildId: Any?,
        succeeded: Boolean?,
        canceled: Boolean?,
        errors: Int?,
        warnings: Int?,
        message: String?
    ): Boolean {
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
            return inFlight == 0
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

    /** True once any cidr build event was seen — i.e. this really is a CLion CMake build. */
    fun sawAnyBuild(): Boolean = synchronized(lock) { started > 0 || finished > 0 }

    fun anyBuildFailed(): Boolean = synchronized(lock) { finished > 0 && (!allSucceeded || anyCanceled) }

    fun buildIds(): List<Any> = synchronized(lock) { ids.toList() }

    /**
     * One-line summary of the failed session for a fallback build message, e.g.
     * `Build failed: 2 errors, 1 warning — <CLion's own message>`. Null while nothing failed.
     */
    fun failureSummary(): String? {
        synchronized(lock) {
            if (finished == 0 || (allSucceeded && !anyCanceled)) return null
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
    }
}

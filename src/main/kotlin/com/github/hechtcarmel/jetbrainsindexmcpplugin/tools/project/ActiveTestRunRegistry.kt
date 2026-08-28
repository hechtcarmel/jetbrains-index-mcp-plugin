package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Tracks test runs started by ide_run_tests that outlive a single MCP call (issue #277); see
 * [LongPollRegistry] for the shared lifetime machinery. The registry — not the HTTP call — owns
 * the run: the watchdog kills the process once `timeoutSeconds` expires, preserving the
 * pre-#277 guarantee that a runaway test run never outlives its budget, even when the agent
 * stops polling.
 *
 * A run is registered the moment its execution environment is launched — before the test
 * process exists, because the IDE's before-run tasks (compilation) can outlast any single
 * call's wait budget (issue #348). The run therefore has two phases:
 *
 *  1. **Starting** — bounded by [ActiveTestRun.processStartAllowanceMs], not `timeoutSeconds`:
 *     build/prepare time is not billed to the run. Polls report an in-progress status.
 *  2. **Running** — entered via [ActiveTestRun.markProcessStarted]; `timeoutSeconds` is
 *     anchored here, so the watchdog kill deadline re-anchors mid-flight (which is why
 *     [ActiveTestRun.awaitCompletionOrDeadline] waits phase by phase instead of relying on the
 *     base's single deadline read).
 */
@Service(Service.Level.PROJECT)
class ActiveTestRunRegistry(scope: CoroutineScope) : LongPollRegistry<ActiveTestRunRegistry.ActiveTestRun>(scope) {

    class ActiveTestRun(
        id: String,
        val configName: String,
        /** When the execution environment was launched — the starting phase's clock anchor. */
        val createdAtMs: Long,
        val timeoutSeconds: Int,
        /** Resolved via [processStartAllowanceMs]; a constructor parameter so tests can shrink it. */
        val processStartAllowanceMs: Long,
        val exitCode: CompletableDeferred<Int>,
        val testRoot: CompletableDeferred<SMTestProxy.SMRootTestProxy?>,
        private val connection: MessageBusConnection?
    ) : LongPollOperation(id) {

        /** Null until the test process starts; set by [markProcessStarted]. */
        @Volatile
        var processStartedAtMs: Long? = null
            private set

        /** Null until the test process starts — the watchdog has nothing to kill before then. */
        @Volatile
        var handler: ProcessHandler? = null
            private set

        /**
         * Whether an SM results viewer was attached at process start — a boolean rather than the
         * viewer itself so a retained entry never pins a (possibly disposed) Swing component for
         * the whole retention window; [testRoot] carries the actual results.
         */
        @Volatile
        var hasResultsViewer: Boolean = false
            private set

        /**
         * Completes normally when the process starts, exceptionally when the IDE reports it
         * could not be started (before-run build failed or was cancelled). Gates the watchdog's
         * phase switch in [awaitCompletionOrDeadline].
         */
        private val processStarted = CompletableDeferred<Unit>()

        /**
         * Transitions the run into its running phase. If the watchdog already declared the run
         * dead (the start allowance expired just as the build finished), the fresh process is
         * killed immediately so a run reported as timed out never keeps executing unmanaged.
         */
        fun markProcessStarted(handler: ProcessHandler, hasResultsViewer: Boolean) {
            this.processStartedAtMs = System.currentTimeMillis()
            this.handler = handler
            this.hasResultsViewer = hasResultsViewer
            processStarted.complete(Unit)
            if (timedOutByWatchdog) {
                handler.destroyProcess()
            }
        }

        /**
         * Terminal failure before any process existed (ExecutionListener.processNotStarted:
         * the before-run build failed, or the user cancelled it). Completes both phase gates
         * exceptionally so the watchdog goes terminal and the next poll surfaces [message]
         * as the tool error.
         */
        fun markProcessNotStarted(message: String) {
            val failure = IllegalStateException(message)
            processStarted.completeExceptionally(failure)
            exitCode.completeExceptionally(failure)
        }

        override val deadlineMs: Long
            get() = processStartedAtMs?.let { it + timeoutSeconds * 1000L }
                ?: (createdAtMs + processStartAllowanceMs)

        override val completion: CompletableDeferred<Int> get() = exitCode

        /**
         * Two-phase wait matching the two clocks above: the start allowance while the IDE
         * builds, then `timeoutSeconds` from actual process start. The base's single-read
         * default would bill build time to the run (or never re-anchor the kill deadline).
         */
        override suspend fun awaitCompletionOrDeadline(): Boolean {
            val startBudgetLeft = (createdAtMs + processStartAllowanceMs - System.currentTimeMillis())
                .coerceAtLeast(0)
            withTimeoutOrNull(startBudgetLeft.milliseconds) { processStarted.await() }
                ?: return false
            return super.awaitCompletionOrDeadline()
        }

        override fun onDeadline() {
            handler?.destroyProcess()
        }

        override fun onCleanup() {
            connection?.disconnect()
        }
    }

    companion object {
        fun getInstance(project: Project): ActiveTestRunRegistry = project.service()

        /**
         * How long the IDE may build/prepare before the test process must start. Generous on
         * purpose: this is the backstop that keeps a hung build from leaving an immortal
         * registry entry, not something an agent should ever hit while actively polling.
         */
        internal val PROCESS_START_ALLOWANCE = 30.minutes

        /**
         * The starting phase's budget. `timeoutSeconds` extends (never shrinks) the default
         * allowance: a caller who budgets a multi-hour run has implicitly accepted a build
         * longer than 30 minutes, and there is no separate knob for the build phase.
         */
        internal fun processStartAllowanceMs(timeoutSeconds: Int): Long =
            maxOf(PROCESS_START_ALLOWANCE.inWholeMilliseconds, timeoutSeconds * 1000L)
    }
}

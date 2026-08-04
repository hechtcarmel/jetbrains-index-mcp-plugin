package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Tracks test runs started by ide_run_tests that outlive a single MCP call.
 *
 * MCP clients enforce their own request timeout (60s by default in Claude Code), so the tool
 * cannot block until a long test run finishes (issue #277). Instead the run is registered here
 * and each tool call long-polls it by `runId`. The registry — not the HTTP call — owns the run's
 * lifetime:
 *
 *  - a watchdog kills the process once the run's `timeoutSeconds` expires, preserving the
 *    pre-#277 guarantee that a runaway test run never outlives its budget, even when the agent
 *    stops polling;
 *  - entries removed on collection disconnect their message-bus connection and cancel the
 *    watchdog;
 *  - uncollected entries are evicted [RETENTION] after the run terminated, and everything is
 *    cleaned up on project close via [dispose].
 */
@Service(Service.Level.PROJECT)
class ActiveTestRunRegistry(private val scope: CoroutineScope) : Disposable {

    class ActiveTestRun(
        val id: String,
        val configName: String,
        val startedAtMs: Long,
        val timeoutSeconds: Int,
        val handler: ProcessHandler,
        val exitCode: CompletableDeferred<Int>,
        val testRoot: CompletableDeferred<SMTestProxy.SMRootTestProxy?>,
        /**
         * Whether an SM results viewer was attached at start — a boolean rather than the viewer
         * itself so a retained entry never pins a (possibly disposed) Swing component for the
         * whole retention window; [testRoot] carries the actual results.
         */
        val hasResultsViewer: Boolean,
        private val connection: MessageBusConnection?
    ) {
        /** Set by the watchdog before killing the process; wins over any exit code the kill produces. */
        @Volatile
        var timedOutByWatchdog: Boolean = false
            internal set

        internal var watchdog: Job? = null

        private val cleanedUp = AtomicBoolean(false)

        internal fun cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) return
            watchdog?.cancel()
            connection?.disconnect()
        }
    }

    companion object {
        fun getInstance(project: Project): ActiveTestRunRegistry = project.service()

        /** How long a terminated-but-never-collected run stays pollable before eviction. */
        private val RETENTION = 30.minutes
    }

    private val runs = ConcurrentHashMap<String, ActiveTestRun>()

    fun register(run: ActiveTestRun): ActiveTestRun {
        // Watchdog is armed before the entry becomes visible so cleanup() can never observe a
        // registered run with a null watchdog job.
        run.watchdog = scope.launch {
            val budgetLeft = (run.startedAtMs + run.timeoutSeconds * 1000L - System.currentTimeMillis())
                .coerceAtLeast(0)
            val exited = withTimeoutOrNull(budgetLeft.milliseconds) { run.exitCode.await() } != null
            if (!exited) {
                run.timedOutByWatchdog = true
                run.handler.destroyProcess()
            }
            delay(RETENTION)
            remove(run.id)
        }
        runs[run.id] = run
        return run
    }

    fun get(id: String): ActiveTestRun? = runs[id]

    fun remove(id: String) {
        runs.remove(id)?.cleanup()
    }

    override fun dispose() {
        runs.keys.toList().forEach { remove(it) }
    }
}

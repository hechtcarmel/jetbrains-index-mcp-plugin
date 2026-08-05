package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope

/**
 * Tracks test runs started by ide_run_tests that outlive a single MCP call (issue #277); see
 * [LongPollRegistry] for the shared lifetime machinery. The registry — not the HTTP call — owns
 * the run: the watchdog kills the process once `timeoutSeconds` expires, preserving the
 * pre-#277 guarantee that a runaway test run never outlives its budget, even when the agent
 * stops polling.
 */
@Service(Service.Level.PROJECT)
class ActiveTestRunRegistry(scope: CoroutineScope) : LongPollRegistry<ActiveTestRunRegistry.ActiveTestRun>(scope) {

    class ActiveTestRun(
        id: String,
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
    ) : LongPollOperation(id) {

        override val deadlineMs: Long get() = startedAtMs + timeoutSeconds * 1000L

        override val completion: CompletableDeferred<Int> get() = exitCode

        override fun onDeadline() {
            handler.destroyProcess()
        }

        override fun onCleanup() {
            connection?.disconnect()
        }
    }

    companion object {
        fun getInstance(project: Project): ActiveTestRunRegistry = project.service()
    }
}

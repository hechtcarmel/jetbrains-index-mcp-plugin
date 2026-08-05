package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTaskManager
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.util.Collections

/**
 * Tracks builds started by ide_build_project that outlive a single MCP call — the build-side
 * twin of [ActiveTestRunRegistry]; see [LongPollRegistry] for the shared lifetime machinery.
 *
 * Unlike test runs, a timed-out build is only *marked* — ProjectTaskManager exposes no
 * cancellation handle, so the IDE build keeps going, exactly as the pre-long-poll tool behaved
 * (it stopped listening on timeout and reported `aborted`). The watchdog's flag makes the next
 * poll report `timedOut`/`aborted`.
 */
@Service(Service.Level.PROJECT)
class ActiveBuildRegistry(scope: CoroutineScope) : LongPollRegistry<ActiveBuildRegistry.ActiveBuild>(scope) {

    class ActiveBuild(
        id: String,
        val startedAtMs: Long,
        /** null = unbounded wait, the tool's historical default when timeoutSeconds is omitted. */
        val timeoutSeconds: Int?,
        val includeRawOutput: Boolean,
        val result: CompletableDeferred<ProjectTaskManager.Result>,
        private val connection: MessageBusConnection?,
        private val listenersDisposable: Disposable? = null
    ) : LongPollOperation(id) {

        /** JPS compiler messages (Java/Kotlin via Java plugin — high fidelity). */
        val compilerMessages: MutableList<BuildMessage> = Collections.synchronizedList(mutableListOf())
        val compilerRawOutput = StringBuffer()

        /** Build events (Gradle/Maven/universal via BuildViewManager — fallback). */
        val buildEventMessages: MutableList<BuildMessage> = Collections.synchronizedList(mutableListOf())
        val failureMessages: MutableList<BuildMessage> = Collections.synchronizedList(mutableListOf())
        val buildEventRawOutput = StringBuffer()

        override val deadlineMs: Long? get() = timeoutSeconds?.let { startedAtMs + it * 1000L }

        override val completion: CompletableDeferred<ProjectTaskManager.Result> get() = result

        // No onDeadline override: there is nothing to kill — the base's timed-out flag is the
        // whole verdict, and the IDE build keeps running as it did before the long-poll rework.

        override fun onCleanup() {
            connection?.disconnect()
            listenersDisposable?.let { Disposer.dispose(it) }
        }
    }

    companion object {
        fun getInstance(project: Project): ActiveBuildRegistry = project.service()
    }
}

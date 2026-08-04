package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTaskManager
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Tracks builds started by ide_build_project that outlive a single MCP call — the build-side
 * twin of [ActiveTestRunRegistry] (same client-timeout root cause as issue #277; kept separate
 * because the entries share almost nothing: builds have no process to kill, own their message
 * buffers, and allow an unbounded timeout).
 *
 * Unlike test runs, a timed-out build is only *marked* — ProjectTaskManager exposes no
 * cancellation handle, so the IDE build keeps going, exactly as the pre-long-poll tool behaved
 * (it stopped listening on timeout and reported `aborted`). The watchdog's flag makes the next
 * poll report `timedOut`/`aborted`; retention evicts entries nobody collects and [dispose]
 * cleans up on project close.
 */
@Service(Service.Level.PROJECT)
class ActiveBuildRegistry(private val scope: CoroutineScope) : Disposable {

    class ActiveBuild(
        val id: String,
        val startedAtMs: Long,
        /** null = unbounded wait, the tool's historical default when timeoutSeconds is omitted. */
        val timeoutSeconds: Int?,
        val includeRawOutput: Boolean,
        val result: CompletableDeferred<ProjectTaskManager.Result>,
        private val connection: MessageBusConnection?,
        private val listenersDisposable: Disposable? = null
    ) {
        /** JPS compiler messages (Java/Kotlin via Java plugin — high fidelity). */
        val compilerMessages: MutableList<BuildMessage> = Collections.synchronizedList(mutableListOf())
        val compilerRawOutput = StringBuffer()

        /** Build events (Gradle/Maven/universal via BuildViewManager — fallback). */
        val buildEventMessages: MutableList<BuildMessage> = Collections.synchronizedList(mutableListOf())
        val failureMessages: MutableList<BuildMessage> = Collections.synchronizedList(mutableListOf())
        val buildEventRawOutput = StringBuffer()

        /** Set by the watchdog when timeoutSeconds expires; the next poll reports timedOut/aborted. */
        @Volatile
        var timedOutByWatchdog: Boolean = false
            internal set

        internal var watchdog: Job? = null

        private val cleanedUp = AtomicBoolean(false)

        internal fun cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) return
            watchdog?.cancel()
            connection?.disconnect()
            listenersDisposable?.let { Disposer.dispose(it) }
        }
    }

    companion object {
        fun getInstance(project: Project): ActiveBuildRegistry = project.service()

        /** How long a finished-but-never-collected build stays pollable before eviction. */
        private val RETENTION = 30.minutes
    }

    private val builds = ConcurrentHashMap<String, ActiveBuild>()

    fun register(build: ActiveBuild): ActiveBuild {
        // Watchdog is armed before the entry becomes visible so cleanup() can never observe a
        // registered build with a null watchdog job.
        build.watchdog = scope.launch {
            val finished = try {
                if (build.timeoutSeconds == null) {
                    build.result.await()
                    true
                } else {
                    val budgetLeft = (build.startedAtMs + build.timeoutSeconds * 1000L - System.currentTimeMillis())
                        .coerceAtLeast(0)
                    withTimeoutOrNull(budgetLeft.milliseconds) { build.result.await() } != null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed build completes the deferred exceptionally; for lifetime purposes
                // that is just "finished" — the poll path surfaces the actual error.
                true
            }
            if (!finished) {
                build.timedOutByWatchdog = true
            }
            delay(RETENTION)
            remove(build.id)
        }
        builds[build.id] = build
        return build
    }

    fun get(id: String): ActiveBuild? = builds[id]

    fun remove(id: String) {
        builds.remove(id)?.cleanup()
    }

    override fun dispose() {
        builds.keys.toList().forEach { remove(it) }
    }
}

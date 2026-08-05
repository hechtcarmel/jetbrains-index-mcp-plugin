package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.LongPoll
import com.intellij.openapi.Disposable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Shared infrastructure for tools whose operations outlive a single MCP call (see [LongPoll] for
 * why no call may block past the client's request timeout). A tool plugs in by providing three
 * pieces:
 *
 *  1. an operation class extending [LongPollOperation] — its payload plus the [deadlineMs] /
 *     [onDeadline] / [onCleanup] hooks;
 *  2. a project-level `@Service` registry extending [LongPollRegistry] (a few lines);
 *  3. a poll parameter (`runId` / `buildId` / …) whose attach path calls
 *     [LongPollOperation.awaitWithinBudget] and maps terminal state to the tool's result model.
 *
 * The base owns everything that was subtle enough to get wrong twice: the watchdog that enforces
 * the operation deadline even when nobody polls, retention-based eviction of uncollected
 * entries, exactly-once cleanup, and the await ordering (completed result beats a stale timeout
 * verdict, which beats waiting).
 */
abstract class LongPollOperation(val id: String) {

    /** Absolute wall-clock deadline for the watchdog, or null for unbounded operations. */
    abstract val deadlineMs: Long?

    /** Completes (possibly exceptionally) when the operation reaches a terminal state. */
    abstract val completion: Deferred<*>

    /**
     * Invoked once by the watchdog when [deadlineMs] passes without completion — kill the
     * process if there is one to kill; the timed-out flag is already set by the base.
     */
    open fun onDeadline() {}

    /** Tool-specific teardown (message-bus connections, disposables); runs exactly once. */
    open fun onCleanup() {}

    /** Set by the watchdog before [onDeadline]; wins over any exit state the kill produces. */
    @Volatile
    var timedOutByWatchdog: Boolean = false
        internal set

    internal var watchdog: Job? = null

    private val cleanedUp = AtomicBoolean(false)

    internal fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        watchdog?.cancel()
        onCleanup()
    }

    /**
     * Waits for [deferred] within what remains of the call's wait budget. Branch order is the
     * contract: a completed operation wins over a stale watchdog verdict (a real result must
     * never be reported as a timeout), and a watchdog verdict wins over waiting (a zombie
     * operation must not cost a wait budget per poll). Returns null when the caller should
     * either report in-progress (flag unset) or timed-out (flag set). An exceptionally
     * completed [deferred] rethrows — callers map that to their error result.
     */
    suspend fun <T> awaitWithinBudget(deferred: Deferred<T>, waitSeconds: Int, callStartMs: Long): T? {
        val waitLeftMs = waitSeconds * 1000L - (System.currentTimeMillis() - callStartMs)
        return when {
            deferred.isCompleted -> deferred.await()
            timedOutByWatchdog -> null
            waitLeftMs > 0 -> withTimeoutOrNull(waitLeftMs.milliseconds) { deferred.await() }
            else -> null
        }
    }
}

abstract class LongPollRegistry<E : LongPollOperation>(private val scope: CoroutineScope) : Disposable {

    companion object {
        /** How long a terminated-but-never-collected operation stays pollable before eviction. */
        internal val RETENTION = 30.minutes
    }

    private val operations = ConcurrentHashMap<String, E>()

    fun register(operation: E): E {
        // Watchdog is armed before the entry becomes visible so cleanup() can never observe a
        // registered operation with a null watchdog job.
        operation.watchdog = scope.launch {
            val finished = try {
                val deadline = operation.deadlineMs
                if (deadline == null) {
                    operation.completion.await()
                    true
                } else {
                    val budgetLeft = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                    withTimeoutOrNull(budgetLeft.milliseconds) { operation.completion.await() } != null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Exceptional completion is terminal for lifetime purposes; the poll path
                // surfaces the actual error to the client.
                true
            }
            if (!finished) {
                operation.timedOutByWatchdog = true
                operation.onDeadline()
            }
            delay(RETENTION)
            remove(operation.id)
        }
        operations[operation.id] = operation
        return operation
    }

    fun get(id: String): E? = operations[id]

    fun remove(id: String) {
        operations.remove(id)?.cleanup()
    }

    override fun dispose() {
        operations.keys.toList().forEach { remove(it) }
    }
}

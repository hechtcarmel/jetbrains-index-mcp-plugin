package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProjectDiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.LongPollOperation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.LongPollRegistry
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks project-scope diagnostics runs started by ide_project_diagnostics that outlive a single
 * MCP call; see [LongPollRegistry] for the shared lifetime machinery.
 *
 * Unlike builds and test runs, the analysis loop is our own code: it self-enforces
 * `timeoutSeconds` at every file boundary and always completes [ActiveProjectAnalysis.result]
 * with a partial, fail-closed result. The base watchdog therefore only provides retention
 * eviction ([deadlineMs][LongPollOperation.deadlineMs] is null).
 */
@Service(Service.Level.PROJECT)
class ActiveProjectAnalysisRegistry(private val analysisScope: CoroutineScope) :
    LongPollRegistry<ActiveProjectAnalysisRegistry.ActiveProjectAnalysis>(analysisScope) {

    class ActiveProjectAnalysis(
        id: String,
        val startedAtMs: Long,
        val timeoutSeconds: Int,
        val filesConsidered: Int,
        val result: CompletableDeferred<ProjectDiagnosticsResult>
    ) : LongPollOperation(id) {

        /** Files that reached a terminal per-file state so far; read by in-progress responses. */
        val filesProcessed = AtomicInteger(0)

        val deadlineAtMs: Long get() = startedAtMs + timeoutSeconds * 1000L

        override val deadlineMs: Long? get() = null

        override val completion: CompletableDeferred<ProjectDiagnosticsResult> get() = result
    }

    /**
     * The most recently started analysis. Runs serialize on the app-level main-pass lock anyway,
     * so overlapping project scans only multiply wall-clock time — the tool refuses to start a
     * second one while this is incomplete.
     */
    @Volatile
    var mostRecent: ActiveProjectAnalysis? = null
        private set

    /**
     * Atomically claims the single per-project analysis slot. Returns null when [analysis] was
     * registered, or the still-running analysis that holds the slot — synchronized because the
     * tool's pre-flight check is only a fast path and two concurrent starts must not both pass it.
     */
    @Synchronized
    fun tryStartExclusive(analysis: ActiveProjectAnalysis): ActiveProjectAnalysis? {
        mostRecent?.takeIf { !it.result.isCompleted }?.let { return it }
        mostRecent = analysis
        register(analysis)
        return null
    }

    fun launchAnalysis(block: suspend () -> Unit): Job = analysisScope.launch { block() }

    companion object {
        fun getInstance(project: Project): ActiveProjectAnalysisRegistry = project.service()
    }
}

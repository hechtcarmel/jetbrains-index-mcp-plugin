package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.LongPoll
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileCoverageInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProjectDiagnosticsInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProjectDiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * MCP tool that analyzes many files — up to the whole project — for code problems, with
 * fail-closed coverage metadata (issue #246).
 *
 * Every file in scope reaches exactly one coverage state, and `complete` is true only when every
 * considered file was actually analyzed, so an empty problems list can never be mistaken for a
 * clean project when analysis was partial. Per-file analysis reuses
 * [DiagnosticsAnalysisService.analyzeFile] — open files get fresh daemon highlights, closed files
 * get the IDE's public batch analysis — so results match what ide_diagnostics reports per file.
 */
class ProjectDiagnosticsTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<ProjectDiagnosticsTool>()

        /** Per-file problem cap, matching ide_diagnostics' MAX_PROBLEMS. */
        internal const val MAX_PROBLEMS_PER_FILE = 100
        private const val DEFAULT_MAX_TOTAL_PROBLEMS = 1000
        private const val MAX_TOTAL_PROBLEMS = 5000
        private const val DEFAULT_MAX_FILES = 1000
        private const val MAX_MAX_FILES = 10_000
        private const val DEFAULT_TIMEOUT_SECONDS = 600
        private const val MAX_TIMEOUT_SECONDS = 3600
        internal const val MAX_INCOMPLETE_FILE_ENTRIES = 100

        internal const val STATE_ANALYZED = "analyzed"
        internal const val STATE_TIMED_OUT = "timed_out"
        internal const val STATE_FAILED = "failed"
        internal const val STATE_SKIPPED = "skipped"
        internal const val STATE_NOT_ANALYZED = "not_analyzed"
        internal const val STATE_NOT_FOUND = "not_found"

        internal const val STATUS_COMPLETED = "completed"
        internal const val STATUS_TIMED_OUT = "timed_out"
        internal const val STATUS_RUNNING = "running"

        private const val COVERAGE_DISCLOSURE =
            "Files open in an editor are analyzed with fresh daemon highlights (open_daemon); closed files " +
                    "use the IDE's public batch analysis (closed_batch), which reports errors and warnings but " +
                    "not weak warnings or editor-only annotator results. Binary files are excluded from scope. " +
                    "Treat an empty problems list as a clean signal only when complete=true."

        /** Maps a per-file analysis outcome to its coverage state and human-readable reason. */
        internal fun coverageStateFor(result: DiagnosticsAnalysisService.FileAnalysisResult): Pair<String, String?> = when {
            result.analysisTimedOut -> STATE_TIMED_OUT to (result.analysisMessage ?: "File analysis timed out.")
            result.analysisFresh -> STATE_ANALYZED to null
            else -> STATE_SKIPPED to (result.analysisMessage ?: "File is not eligible for IDE diagnostics analysis.")
        }
    }

    override val name = ToolNames.PROJECT_DIAGNOSTICS

    override val description = """
        Analyze many project files, including unopened files, for errors and warnings with fail-closed coverage.

        Every considered file gets one state: analyzed, timed_out, failed, skipped (with reason), or
        not_analyzed (maxFiles/timeoutSeconds). An empty problems list is a clean signal ONLY when
        complete=true. Inspect incompleteFiles and per-state counts otherwise. Open files use fresh
        daemon highlights; closed-file batch analysis omits weak warnings and editor-only annotators.

        Each call waits at most waitSeconds (default 45, max 55). While analysis continues in the IDE,
        it returns {"status": "running", "analysisId": "..."}; poll with that analysisId. Only one run
        per project is allowed. Polls ignore other analysis options.

        fileTimeoutSeconds (integer 1–600, default 30) bounds each file, including setup and queueing,
        and is capped by the remaining timeoutSeconds for the whole run (default 600, max 3600).
        Expiry keeps coverage incomplete. maxFiles defaults to 1000 (max 10000); maxProblems defaults
        to 1000 (max 5000). severity defaults to 'all'. Omit paths to cover all project content roots.

        Returns: complete, status (completed | timed_out), file counts by state and analysis mode,
        incompleteFiles (capped at 100), problems with severity/location, problemCount, errorCount,
        warningCount, durationMs.

        Example: {"paths": ["src/Huge.java"], "fileTimeoutSeconds": 300}; then {"analysisId": "..."}.
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .property(ParamNames.PATHS, kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive("array"))
            put(
                "description",
                JsonPrimitive(
                    "Files or directories to analyze, relative to the project root. " +
                            "Omit to analyze every file in the project's content roots."
                )
            )
            put("items", kotlinx.serialization.json.buildJsonObject { put("type", JsonPrimitive("string")) })
            put("minItems", JsonPrimitive(1))
        })
        .enumProperty(ParamNames.SEVERITY, "Filter problems by severity. Default: all.", listOf("all", "errors", "warnings"))
        .intProperty(
            ParamNames.MAX_FILES,
            "Maximum files to analyze. Files beyond the limit are reported as not_analyzed and complete " +
                    "becomes false. Default: $DEFAULT_MAX_FILES, max: $MAX_MAX_FILES."
        )
        .intProperty(
            ParamNames.MAX_PROBLEMS,
            "Maximum problems to return across all files (problemCount keeps counting beyond it). " +
                    "Default: $DEFAULT_MAX_TOTAL_PROBLEMS, max: $MAX_TOTAL_PROBLEMS."
        )
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds the whole analysis may take; remaining files are reported as not_analyzed " +
                    "when it elapses. Default: $DEFAULT_TIMEOUT_SECONDS, max: $MAX_TIMEOUT_SECONDS. Ignored when analysisId is given."
        )
        .stringProperty(
            ParamNames.ANALYSIS_ID,
            "analysisId from a previous {\"status\": \"running\"} response: attaches to that analysis and " +
                    "keeps waiting instead of starting a new one."
        )
        .intProperty(
            ParamNames.FILE_TIMEOUT_SECONDS,
            "Per-file analysis seconds, including setup and queueing; capped by the whole-run deadline. " +
                    "Default: 30, range: 1–600. Ignored when analysisId is given."
        )
        .intProperty(
            ParamNames.WAIT_SECONDS,
            "Maximum seconds this call may block before returning results or a \"running\" status. " +
                    "Default: ${LongPoll.DEFAULT_WAIT_SECONDS}, max: ${LongPoll.MAX_WAIT_SECONDS}. Keep below your MCP client's request timeout."
        )
        .build()

    /** Attach polls (`analysisId`) read no PSI until final collection — skip the per-call sync tax. */
    override fun needsPsiSync(arguments: JsonObject): Boolean =
        LongPoll.optionalTrimmedString(arguments, ParamNames.ANALYSIS_ID) == null

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val callStartMs = System.currentTimeMillis()
        val waitSeconds = LongPoll.resolveWaitSeconds(arguments)
        val registry = ActiveProjectAnalysisRegistry.getInstance(project)

        LongPoll.optionalTrimmedString(arguments, ParamNames.ANALYSIS_ID)?.let { analysisId ->
            val analysis = registry.get(analysisId)
                ?: return createErrorResult(
                    "No active analysis with id '$analysisId'. Its results may have already been collected, " +
                            "the analysis may have been evicted after completing, or the IDE was restarted. " +
                            "Start a new analysis by omitting analysisId."
                )
            return awaitAnalysisResult(project, analysis, waitSeconds, callStartMs)
        }

        val fileTimeoutArg = arguments[ParamNames.FILE_TIMEOUT_SECONDS]
        val fileTimeoutMs = if (fileTimeoutArg == null) {
            null // Keep the service default, including its test-only override.
        } else {
            val seconds = (fileTimeoutArg as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (seconds == null || seconds !in 1..DiagnosticsAnalysisBudget.MAX_FILE_TIMEOUT_SECONDS) {
                return createErrorResult("fileTimeoutSeconds must be an integer between 1 and 600.")
            }
            seconds * 1000L
        }

        // Fast path only — the authoritative, race-free claim is tryStartExclusive below.
        registry.mostRecent?.takeIf { !it.result.isCompleted }?.let { running ->
            return alreadyRunningResult(running)
        }

        requireSmartMode(project)

        val severity = arguments[ParamNames.SEVERITY]?.jsonPrimitive?.contentOrNull ?: "all"
        val maxFiles = (arguments[ParamNames.MAX_FILES]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_FILES)
            .coerceIn(1, MAX_MAX_FILES)
        val maxProblems = (arguments[ParamNames.MAX_PROBLEMS]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_TOTAL_PROBLEMS)
            .coerceIn(1, MAX_TOTAL_PROBLEMS)
        val timeoutSeconds = (arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        val pathsArg = arguments[ParamNames.PATHS]
        val paths: List<String>? = when (pathsArg) {
            null -> null
            is JsonArray -> pathsArg.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                .ifEmpty { return createErrorResult("'paths' must contain at least one non-empty path.") }
            else -> return createErrorResult("'paths' must be an array of strings.")
        }

        val scope = collectTargetFiles(project, paths)
        val targets = when (scope) {
            is ScopeResolution.Error -> return createErrorResult(scope.message)
            is ScopeResolution.Resolved -> scope.files
        }

        val analysis = ActiveProjectAnalysisRegistry.ActiveProjectAnalysis(
            id = UUID.randomUUID().toString(),
            startedAtMs = callStartMs,
            timeoutSeconds = timeoutSeconds,
            filesConsidered = targets.size,
            result = CompletableDeferred()
        )
        registry.tryStartExclusive(analysis)?.let { running ->
            return alreadyRunningResult(running)
        }

        registry.launchAnalysis {
            try {
                runAnalysisLoop(project, analysis, targets, maxFiles, severity, maxProblems, fileTimeoutMs)
            } catch (t: Throwable) {
                if (!analysis.result.isCompleted) {
                    analysis.result.completeExceptionally(
                        if (t is CancellationException) {
                            RuntimeException("Analysis was cancelled (project closing or plugin unload).")
                        } else {
                            t
                        }
                    )
                }
                if (t is CancellationException) throw t
                LOG.warn("Project diagnostics analysis failed", t)
            }
        }

        return awaitAnalysisResult(project, analysis, waitSeconds, callStartMs)
    }

    private fun alreadyRunningResult(running: ActiveProjectAnalysisRegistry.ActiveProjectAnalysis): CallToolResult =
        createErrorResult(
            "Analysis '${running.id}' is already running for this project " +
                    "(${running.filesProcessed.get()}/${running.filesConsidered} files processed). " +
                    "Poll it with {\"analysisId\": \"${running.id}\"} or wait for it to finish."
        )

    private data class TargetFile(val virtualFile: VirtualFile, val relativePath: String)

    private sealed interface ScopeResolution {
        data class Resolved(val files: List<TargetFile>) : ScopeResolution
        data class Error(val message: String) : ScopeResolution
    }

    /**
     * Re-reads the requested scope from disk before it is enumerated.
     *
     * Scope collection runs through [ProjectFileIndex], which only sees files the VFS already
     * knows about. A file an agent created out of band is invisible to it, and the tool then
     * reports `complete = true` over a scope that silently omitted it — precisely the false
     * "clean project" signal the coverage metadata exists to prevent.
     *
     * Recursive, unlike the single-file refresh in [DiagnosticsAnalysisService]: new files are
     * only discovered by re-reading directories. The cost is bounded by the requested scope and
     * is small next to the per-file analysis that follows, which runs in the hundreds of
     * milliseconds per file.
     */
    private fun refreshScopeFromDisk(project: Project, requestedRoots: List<VirtualFile>) {
        val roots = requestedRoots.ifEmpty {
            ProjectUtils.getModuleContentRoots(project)
                .mapNotNull { LocalFileSystem.getInstance().findFileByPath(it) }
        }
        if (roots.isNotEmpty()) {
            VfsUtil.markDirtyAndRefresh(false, true, true, *roots.toTypedArray())
        }
    }

    private suspend fun collectTargetFiles(project: Project, paths: List<String>?): ScopeResolution {
        // Resolve path arguments outside the read action so error messages stay precise.
        val roots = mutableListOf<Pair<String, VirtualFile>>()
        if (paths != null) {
            for (path in paths) {
                val vf = resolveFile(project, path)
                    ?: return ScopeResolution.Error("Path not found in project: $path")
                roots.add(path to vf)
            }
        }

        refreshScopeFromDisk(project, roots.map { it.second })

        return suspendingReadAction {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val collected = linkedSetOf<VirtualFile>()
            val addFile = { fileOrDir: VirtualFile ->
                if (!fileOrDir.isDirectory && !fileOrDir.fileType.isBinary) {
                    collected.add(fileOrDir)
                }
                true
            }

            if (paths == null) {
                fileIndex.iterateContent(addFile)
            } else {
                for ((_, vf) in roots) {
                    if (vf.isDirectory) {
                        fileIndex.iterateContentUnderDirectory(vf, addFile)
                    } else if (!vf.fileType.isBinary) {
                        collected.add(vf)
                    }
                }
            }

            val targets = collected
                .map { TargetFile(it, ProjectUtils.getRelativePath(project, it)) }
                .sortedBy { it.relativePath }
            ScopeResolution.Resolved(targets)
        }
    }

    private suspend fun runAnalysisLoop(
        project: Project,
        analysis: ActiveProjectAnalysisRegistry.ActiveProjectAnalysis,
        targets: List<TargetFile>,
        maxFiles: Int,
        severity: String,
        maxProblems: Int,
        fileTimeoutMs: Long?
    ) {
        val service = DiagnosticsAnalysisService.getInstance(project)
        val problems = mutableListOf<ProblemInfo>()
        val incompleteFiles = mutableListOf<FileCoverageInfo>()
        var incompleteFilesTruncated = false
        var problemCount = 0
        var errorCount = 0
        var warningCount = 0
        var perFileCapHit = false
        var analyzed = 0
        var analyzedOpenDaemon = 0
        var analyzedClosedBatch = 0
        var timedOut = 0
        var failed = 0
        var skipped = 0
        var notAnalyzed = 0
        var deadlineHit = System.currentTimeMillis() >= analysis.deadlineAtMs

        fun recordIncomplete(target: TargetFile, state: String, reason: String?) {
            if (incompleteFiles.size < MAX_INCOMPLETE_FILE_ENTRIES) {
                incompleteFiles.add(FileCoverageInfo(file = target.relativePath, state = state, reason = reason))
            } else {
                incompleteFilesTruncated = true
            }
        }

        val toAnalyze = targets.take(maxFiles)
        val overflow = targets.drop(maxFiles)

        for ((index, target) in toAnalyze.withIndex()) {
            // Deterministic exit when the registry scope dies (project closing, plugin unload)
            // instead of relying on the next suspension point inside analyzeFile.
            currentCoroutineContext().ensureActive()

            val budgetMs = DiagnosticsAnalysisBudget.perFileTimeoutMs(
                fileTimeoutMs ?: service.configuredAnalysisTimeoutMs(),
                analysis.deadlineAtMs - System.currentTimeMillis()
            )
            if (budgetMs == 0L) {
                deadlineHit = true
                for (remaining in toAnalyze.subList(index, toAnalyze.size)) {
                    notAnalyzed++
                    recordIncomplete(
                        remaining,
                        STATE_NOT_ANALYZED,
                        "timeoutSeconds (${analysis.timeoutSeconds}s) elapsed before this file was analyzed."
                    )
                    analysis.filesProcessed.incrementAndGet()
                }
                break
            }

            val outcome = try {
                service.analyzeFile(
                    virtualFile = target.virtualFile,
                    filePath = target.relativePath,
                    severity = severity,
                    startLine = null,
                    endLine = null,
                    maxProblems = MAX_PROBLEMS_PER_FILE,
                    timeoutMs = budgetMs
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Includes ProcessCanceledException from the file's own analysis run: this loop
                // is not under that indicator, so the cancellation is per-file, not ours — the
                // file is reported failed and coverage stays fail-closed. A cancelled registry
                // scope exits via ensureActive() above.
                deadlineHit = System.currentTimeMillis() >= analysis.deadlineAtMs
                if (deadlineHit) {
                    timedOut++
                    recordIncomplete(target, STATE_TIMED_OUT,
                        "timeoutSeconds (${analysis.timeoutSeconds}s) elapsed during this file's analysis.")
                } else {
                    failed++
                    recordIncomplete(target, STATE_FAILED, e.message ?: e.javaClass.simpleName)
                }
                analysis.filesProcessed.incrementAndGet()
                continue
            }

            // Also check the last file: there may be no next iteration to notice the deadline.
            // Never accept a late fresh result from a cooperatively cancelled provider as clean.
            deadlineHit = System.currentTimeMillis() >= analysis.deadlineAtMs
            val (state, reason) = if (deadlineHit) {
                STATE_TIMED_OUT to "timeoutSeconds (${analysis.timeoutSeconds}s) elapsed during this file's analysis."
            } else {
                coverageStateFor(outcome)
            }
            when (state) {
                STATE_ANALYZED -> {
                    analyzed++
                    when (outcome.analysisMode) {
                        DiagnosticsAnalysisService.MODE_OPEN_DAEMON -> analyzedOpenDaemon++
                        DiagnosticsAnalysisService.MODE_CLOSED_BATCH -> analyzedClosedBatch++
                    }
                    if (outcome.problems.size >= MAX_PROBLEMS_PER_FILE) {
                        perFileCapHit = true
                    }
                    problemCount += outcome.problems.size
                    errorCount += outcome.problems.count { it.severity == "ERROR" }
                    warningCount += outcome.problems.count { it.severity != "ERROR" }
                    if (problems.size < maxProblems) {
                        problems.addAll(outcome.problems.take(maxProblems - problems.size))
                    }
                }
                STATE_TIMED_OUT -> {
                    timedOut++
                    recordIncomplete(target, STATE_TIMED_OUT, reason)
                }
                else -> {
                    skipped++
                    recordIncomplete(target, STATE_SKIPPED, reason)
                }
            }
            analysis.filesProcessed.incrementAndGet()
        }

        for (target in overflow) {
            notAnalyzed++
            recordIncomplete(target, STATE_NOT_ANALYZED, "maxFiles limit ($maxFiles) reached before this file was analyzed.")
            analysis.filesProcessed.incrementAndGet()
        }

        val message = buildString {
            append(COVERAGE_DISCLOSURE)
            if (perFileCapHit) {
                append(" At least one file hit the per-file problem cap of $MAX_PROBLEMS_PER_FILE, so problemCount is a lower bound.")
            }
            if (targets.isEmpty()) {
                append(" No analyzable files were found in the requested scope.")
            }
        }

        analysis.result.complete(
            ProjectDiagnosticsResult(
                complete = !deadlineHit && analyzed == targets.size,
                status = if (deadlineHit) STATUS_TIMED_OUT else STATUS_COMPLETED,
                filesConsidered = targets.size,
                filesAnalyzed = analyzed,
                filesAnalyzedOpenDaemon = analyzedOpenDaemon,
                filesAnalyzedClosedBatch = analyzedClosedBatch,
                filesTimedOut = timedOut,
                filesFailed = failed,
                filesSkipped = skipped,
                filesNotAnalyzed = notAnalyzed,
                incompleteFiles = incompleteFiles,
                incompleteFilesTruncated = incompleteFilesTruncated,
                problems = problems,
                problemCount = problemCount,
                errorCount = errorCount,
                warningCount = warningCount,
                problemsTruncated = perFileCapHit || problemCount > problems.size,
                durationMs = System.currentTimeMillis() - analysis.startedAtMs,
                analysisMessage = message
            )
        )
    }

    private suspend fun awaitAnalysisResult(
        project: Project,
        analysis: ActiveProjectAnalysisRegistry.ActiveProjectAnalysis,
        waitSeconds: Int,
        callStartMs: Long
    ): CallToolResult {
        val result: ProjectDiagnosticsResult? = try {
            analysis.awaitWithinBudget(analysis.result, waitSeconds, callStartMs)
        } catch (e: CancellationException) {
            // The MCP call was aborted (client hung up) — the analysis must stay pollable.
            throw e
        } catch (e: Exception) {
            ActiveProjectAnalysisRegistry.getInstance(project).remove(analysis.id)
            LOG.warn("Project diagnostics analysis failed with exception", e)
            return createErrorResult("Project diagnostics analysis failed: ${e.message}")
        }

        if (result == null) {
            // The loop self-enforces its deadline and always completes with a partial result, so
            // null here always means "still running", never a watchdog verdict.
            val elapsedSeconds = (System.currentTimeMillis() - analysis.startedAtMs) / 1000
            return createJsonResult(
                ProjectDiagnosticsInProgressResult(
                    status = STATUS_RUNNING,
                    analysisId = analysis.id,
                    elapsedSeconds = elapsedSeconds,
                    filesProcessed = analysis.filesProcessed.get(),
                    filesConsidered = analysis.filesConsidered,
                    timeoutSeconds = analysis.timeoutSeconds,
                    message = "Analysis is still executing (${analysis.filesProcessed.get()}/${analysis.filesConsidered} " +
                            "files processed, ${elapsedSeconds}s elapsed, ${analysis.timeoutSeconds}s limit). " +
                            "Call ide_project_diagnostics again with {\"analysisId\": \"${analysis.id}\"} to keep " +
                            "waiting for its results (include the same project_path if you provided one)."
                )
            )
        }

        ActiveProjectAnalysisRegistry.getInstance(project).remove(analysis.id)
        return createJsonResult(result)
    }
}

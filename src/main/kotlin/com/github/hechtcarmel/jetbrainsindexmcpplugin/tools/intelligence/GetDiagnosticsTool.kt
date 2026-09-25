package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.AmbiguousFileException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileDiagnosticsAnalysis
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IntentionInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * MCP tool that analyzes files for code problems and available intentions.
 *
 * This tool leverages public IntelliJ diagnostics APIs to detect:
 * - Compilation errors
 * - Code warnings and weak warnings
 * - Available quick fixes and intentions
 *
 * Additionally supports:
 * - Build errors/warnings from the last build
 * - Test results from open test run tabs
 *
 * File diagnostics use open-editor daemon highlights when the file is already
 * open, and public batch code-smell analysis for closed files. Either way the file is
 * re-read from disk first, so an out-of-band edit is analyzed as written.
 */
class GetDiagnosticsTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_MAX_PROBLEMS = 100
        private const val MAX_MAX_PROBLEMS = 500
        private const val MAX_INTENTIONS = 50
        private const val MAX_FILES = 100
        private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
    }

    override val name = "ide_diagnostics"

    override val description = """
        Get code diagnostics from multiple sources: file analysis (errors, warnings, intentions), build output (compiler errors/warnings from last build), and test results (from open test run tabs).

        Returns: problems with severity and location, available intentions/quick fixes, build errors, and test results with error messages and stack traces. Single-file calls report legacy top-level analysis metadata. Multi-file calls return one aggregate problems list plus fileAnalyses entries with state, reason, mode, returned problemCount, and problemsTruncated metadata for each file. States are analyzed, timed_out, failed, skipped (not eligible), not_analyzed (not started before the shared deadline), and not_found. Code problems share the configurable maxProblems response cap; top-level problemsTruncated reports omitted problems. An analyzed state does not imply complete output: re-query truncated files individually, narrowing startLine/endLine if needed.

        At least one source must be active: provide exactly one of 'file' or 'files' for code analysis, 'includeBuildErrors' for build output, or 'includeTestResults' for test results. Can combine file analysis with build and test results. A multi-file call accepts at most 100 supplied paths, all sharing one analysis timeout budget; aliases and duplicate paths are analyzed only once.

        File analysis uses fresh daemon highlights for files that are already open in an editor. Closed files use public batch analysis, so weak warnings and quick-fix intentions may be less complete unless the file is open. If an open-editor daemon does not run and returns before the deadline, analysis falls back to the batch path; if it consumes the shared timeout, the result is timed_out without starting a second budget. The analyzed file is re-read from disk first, so results reflect edits made outside the IDE without calling ide_sync_files. For diagnostics across many files or the whole project with per-file coverage metadata, use ide_project_diagnostics.

        Parameters: file or files (mutually exclusive, relative or in-project absolute paths), line + column (optional, for intentions, single file only), startLine/endLine (optional, single file only), includeBuildErrors (optional), includeTestResults (optional), severity (optional, default 'all'), maxProblems (optional, default 100, max 500), testResultFilter (optional, default 'failed'), maxBuildErrors (optional, default 100), maxTestResults (optional, default 100).

        Example: {"file": "src/MyClass.java"} or {"files": ["src/A.java", "src/B.java"]} or {"includeBuildErrors": true, "severity": "errors"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .file(required = false, description = "Project-relative or in-project absolute file path. Optional — enables per-file code analysis.")
        .property("files", buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
            }
            put("minItems", 1)
            put("maxItems", MAX_FILES)
            put("description", "Project-relative or in-project absolute file paths to analyze under one shared timeout budget (max $MAX_FILES supplied paths). Aliases and duplicates are analyzed once. Mutually exclusive with 'file'.")
        })
        .intProperty("line", "1-based line number for intention lookup. Optional, defaults to 1. Requires file.")
        .intProperty("column", "1-based column number for intention lookup. Optional, defaults to 1. Requires file.")
        .intProperty("startLine", "Filter problems to start from this line. Optional. Requires file.")
        .intProperty("endLine", "Filter problems to end at this line. Optional. Requires file.")
        .booleanProperty(ParamNames.INCLUDE_BUILD_ERRORS, "Include errors/warnings from the last build. Default: false.")
        .booleanProperty(ParamNames.INCLUDE_TEST_RESULTS, "Include test results from open test run tabs. Default: false.")
        .enumProperty(ParamNames.SEVERITY, "Filter by severity across all sources. Default: all.", listOf("all", "errors", "warnings"))
        .enumProperty(ParamNames.TEST_RESULT_FILTER, "Filter test results: 'failed' (default) or 'all'.", listOf("failed", "all"))
        .intProperty(ParamNames.MAX_PROBLEMS, "Max code problems to return. Default: $DEFAULT_MAX_PROBLEMS, max: $MAX_MAX_PROBLEMS.")
        .intProperty(ParamNames.MAX_BUILD_ERRORS, "Max build errors to return. Default: 100, max: 500.")
        .intProperty(ParamNames.MAX_TEST_RESULTS, "Max test results to return. Default: 100, max: 500.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        // Parse arguments
        val rawFile = arguments["file"]
        val rawFiles = arguments["files"]
        val hasFileArgument = rawFile != null && rawFile != JsonNull
        val hasFilesArgument = rawFiles != null && rawFiles != JsonNull
        val filePath = if (hasFileArgument) {
            (rawFile as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return createErrorResult("Parameter 'file' must be a non-blank file path.")
        } else {
            null
        }
        val filePaths = if (hasFilesArgument) {
            val paths = (rawFiles as? JsonArray)
                ?: return createErrorResult("Parameter 'files' must be an array of file paths.")
            if (paths.size > MAX_FILES) {
                return createErrorResult("Parameter 'files' supports at most $MAX_FILES paths per request.")
            }
            paths.mapIndexed { index, element ->
                (element as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: return createErrorResult("Parameter 'files[$index]' must be a non-blank file path.")
            }
        } else {
            null
        }
        val line = arguments["line"]?.jsonPrimitive?.intOrNull ?: 1
        val column = arguments["column"]?.jsonPrimitive?.intOrNull ?: 1
        val startLine = arguments["startLine"]?.jsonPrimitive?.intOrNull
        val endLine = arguments["endLine"]?.jsonPrimitive?.intOrNull
        val includeBuildErrors = arguments[ParamNames.INCLUDE_BUILD_ERRORS]?.jsonPrimitive?.booleanOrNull ?: false
        val includeTestResults = arguments[ParamNames.INCLUDE_TEST_RESULTS]?.jsonPrimitive?.booleanOrNull ?: false
        val severity = arguments[ParamNames.SEVERITY]?.jsonPrimitive?.content ?: "all"
        val testResultFilter = arguments[ParamNames.TEST_RESULT_FILTER]?.jsonPrimitive?.content ?: "failed"
        val maxBuildErrors = (arguments[ParamNames.MAX_BUILD_ERRORS]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 500)
        val maxTestResults = (arguments[ParamNames.MAX_TEST_RESULTS]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 500)
        val maxProblems = (arguments[ParamNames.MAX_PROBLEMS]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_PROBLEMS)
            .coerceIn(1, MAX_MAX_PROBLEMS)

        if (hasFileArgument && hasFilesArgument) {
            return createErrorResult("Parameters 'file' and 'files' are mutually exclusive; provide exactly one of them for code analysis.")
        }

        if (hasFilesArgument && filePaths.isNullOrEmpty()) {
            return createErrorResult("Parameter 'files' must contain at least one file path.")
        }

        // Location filters and intention lookup only have unambiguous semantics for one file.
        val hasLocationArguments = listOf("line", "column", "startLine", "endLine")
            .any { name -> arguments[name]?.let { it != JsonNull } == true }
        if (hasFilesArgument && hasLocationArguments) {
            return createErrorResult("Parameters 'line', 'column', 'startLine', and 'endLine' are only supported with the single 'file' parameter.")
        }

        if (!hasFileArgument && !hasFilesArgument && (startLine != null || endLine != null)) {
            return createErrorResult("Parameters 'startLine' and 'endLine' require 'file' to be specified.")
        }

        // Validate: at least one source must be active
        if (filePath == null && filePaths.isNullOrEmpty() && !includeBuildErrors && !includeTestResults) {
            return createErrorResult("At least one source must be active: provide 'file' or 'files' for code analysis, 'includeBuildErrors' for build output, or 'includeTestResults' for test results.")
        }

        // File diagnostics
        var problems: List<ProblemInfo>? = null
        var problemsTruncated: Boolean? = null
        var intentions: List<IntentionInfo>? = null
        var analysisFresh: Boolean? = null
        var analysisTimedOut: Boolean? = null
        var analysisMessage: String? = null
        var analysisMode: String? = null
        var fileAnalyses: List<FileDiagnosticsAnalysis>? = null

        if (filePath != null) {
            requireSmartMode(project)

            val virtualFile = resolveFile(project, filePath)
                ?: return createErrorResult("File not found: $filePath")

            val fileEditorManager = FileEditorManager.getInstance(project)
            val analysisResult = DiagnosticsAnalysisService.getInstance(project).analyzeFile(
                virtualFile = virtualFile,
                filePath = filePath,
                severity = severity,
                startLine = startLine,
                endLine = endLine,
                // One-item lookahead distinguishes exactly-at-limit output from truncation.
                maxProblems = maxProblems + 1
            )
            // analyzeFile refreshes the file from disk, so it can discover the file is gone; the
            // VirtualFile is then invalid and every PSI lookup below it throws.
            if (!virtualFile.isValid) {
                return createErrorResult("File no longer exists on disk: $filePath")
            }

            problems = analysisResult.problems.take(maxProblems)
            problemsTruncated = analysisResult.problems.size > maxProblems
            analysisFresh = analysisResult.analysisFresh
            analysisTimedOut = analysisResult.analysisTimedOut
            analysisMessage = analysisResult.analysisMessage
            if (problemsTruncated) {
                analysisMessage = appendAnalysisMessage(
                    analysisMessage,
                    "Problem output was truncated at $maxProblems items. Re-query with narrower startLine/endLine filters."
                )
            }
            analysisMode = analysisResult.analysisMode
            intentions = analyzeIntentions(
                project = project,
                fileEditorManager = fileEditorManager,
                virtualFile = virtualFile,
                line = line,
                column = column,
                highlights = analysisResult.highlights
            )

            if (intentions.isNullOrEmpty() && fileEditorManager.getEditors(virtualFile).filterIsInstance<TextEditor>().firstOrNull()?.editor == null) {
                analysisMessage = appendAnalysisMessage(
                    analysisMessage,
                    "Intentions are unavailable because the file is not open in an editor."
                )
            }
        } else if (filePaths != null) {
            requireSmartMode(project)

            val analysisService = DiagnosticsAnalysisService.getInstance(project)
            // Path lookup and deduplication are part of the shared request budget too.
            val deadlineNanos = System.nanoTime() + analysisService.configuredAnalysisTimeoutMs() * 1_000_000L
            val firstTargetByResolvedKey = linkedMapOf<String, BatchFileTarget>()
            for ((index, path) in filePaths.withIndex()) {
                currentCoroutineContext().ensureActive()
                val lexicalKey = try {
                    normalizeDiagnosticPath(path)
                } catch (e: IllegalArgumentException) {
                    return createErrorResult("Parameter 'files[$index]' ${e.message}")
                }
                val target = if (remainingBudgetMs(deadlineNanos) == null) {
                    BatchFileTarget(path, virtualFile = null, budgetExhausted = true)
                } else {
                    try {
                        BatchFileTarget(path, resolveFile(project, path))
                    } catch (e: AmbiguousFileException) {
                        // One ambiguous entry must not abort coverage for the rest of the batch.
                        BatchFileTarget(
                            path,
                            virtualFile = null,
                            unresolvedReason = e.message ?: "Ambiguous file path: $path"
                        )
                    }
                }
                val resolvedKey = target.virtualFile?.url ?: "unresolved:$lexicalKey"
                firstTargetByResolvedKey.putIfAbsent(resolvedKey, target)
            }

            val aggregateProblems = mutableListOf<ProblemInfo>()
            val perFileAnalyses = mutableListOf<FileDiagnosticsAnalysis>()
            problemsTruncated = false

            for (target in firstTargetByResolvedKey.values) {
                currentCoroutineContext().ensureActive()
                val path = target.requestedPath
                if (target.budgetExhausted) {
                    perFileAnalyses += sharedBudgetExhausted(path)
                    continue
                }
                if (target.unresolvedReason != null) {
                    perFileAnalyses += FileDiagnosticsAnalysis(
                        file = path,
                        state = ProjectDiagnosticsTool.STATE_FAILED,
                        reason = target.unresolvedReason
                    )
                    continue
                }
                val virtualFile = target.virtualFile
                if (virtualFile == null) {
                    perFileAnalyses += FileDiagnosticsAnalysis(
                        file = path,
                        state = ProjectDiagnosticsTool.STATE_NOT_FOUND,
                        reason = "File not found: $path"
                    )
                    continue
                }

                val analysisRemainingMs = remainingBudgetMs(deadlineNanos)
                if (analysisRemainingMs == null) {
                    perFileAnalyses += sharedBudgetExhausted(path)
                    continue
                }

                val remainingProblemSlots = maxProblems - aggregateProblems.size
                val analysisResult = try {
                    analysisService.analyzeFile(
                        virtualFile = virtualFile,
                        filePath = path,
                        severity = severity,
                        startLine = null,
                        endLine = null,
                        // Still probe a file when no slots remain, so hidden errors are never
                        // presented as an empty, complete result for that file.
                        maxProblems = remainingProblemSlots + 1,
                        timeoutMs = analysisRemainingMs
                    )
                } catch (failure: ProcessCanceledException) {
                    // PCE is a CancellationException on current IDEs, but it can represent only
                    // one failed analyzer invocation. Propagate it only when our coroutine was
                    // actually cancelled; otherwise retain coverage for the rest of the batch.
                    currentCoroutineContext().ensureActive()
                    perFileAnalyses += FileDiagnosticsAnalysis(
                        file = path,
                        state = ProjectDiagnosticsTool.STATE_FAILED,
                        reason = "Analysis failed: ${failure.message ?: failure.javaClass.simpleName}"
                    )
                    continue
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    perFileAnalyses += FileDiagnosticsAnalysis(
                        file = path,
                        state = ProjectDiagnosticsTool.STATE_FAILED,
                        reason = "Analysis failed: ${failure.message ?: failure.javaClass.simpleName}"
                    )
                    continue
                }

                if (!virtualFile.isValid) {
                    perFileAnalyses += FileDiagnosticsAnalysis(
                        file = path,
                        state = ProjectDiagnosticsTool.STATE_NOT_FOUND,
                        reason = "File no longer exists on disk: $path"
                    )
                    continue
                }

                val (state, coverageReason) = ProjectDiagnosticsTool.coverageStateFor(analysisResult)
                val returnedProblems = if (state == ProjectDiagnosticsTool.STATE_ANALYZED) {
                    analysisResult.problems.take(remainingProblemSlots)
                } else {
                    emptyList()
                }
                val fileProblemsTruncated = state == ProjectDiagnosticsTool.STATE_ANALYZED &&
                    analysisResult.problems.size > remainingProblemSlots
                aggregateProblems += returnedProblems
                if (fileProblemsTruncated) problemsTruncated = true
                var reason = coverageReason
                if (fileProblemsTruncated) {
                    reason = appendAnalysisMessage(
                        reason,
                        "Problems from this file were omitted by the shared $maxProblems-item response cap. " +
                            "Re-query this path using 'file', narrowing startLine/endLine if needed."
                    )
                }
                perFileAnalyses += FileDiagnosticsAnalysis(
                    file = path,
                    state = state,
                    reason = reason,
                    mode = analysisResult.analysisMode,
                    problemCount = returnedProblems.size,
                    problemsTruncated = fileProblemsTruncated
                )
            }

            problems = aggregateProblems
            fileAnalyses = perFileAnalyses
        }

        // Build errors
        var buildErrors: List<BuildMessage>? = null
        var buildErrorCount: Int? = null
        var buildWarningCount: Int? = null
        var buildErrorsTruncated: Boolean? = null
        var buildTimestamp: Long? = null

        if (includeBuildErrors) {
            val cacheService = BuildDiagnosticsCacheService.getInstance(project)
            val allBuildMessages = cacheService.getLastBuildDiagnostics()
            val filteredBuildMessages = filterBuildMessagesBySeverity(allBuildMessages, severity)
            buildErrorsTruncated = filteredBuildMessages.size > maxBuildErrors
            buildErrors = filteredBuildMessages.take(maxBuildErrors)
            buildErrorCount = filteredBuildMessages.count { it.category == "ERROR" }
            buildWarningCount = filteredBuildMessages.count { it.category == "WARNING" }
            buildTimestamp = cacheService.getLastBuildTimestamp()
        }

        // Test results
        var testResults: List<TestResultInfo>? = null
        var testSummary: TestSummary? = null
        var testResultsTruncated: Boolean? = null

        if (includeTestResults) {
            val collectionResult = TestResultsCollector.collect(project, testResultFilter, severity, maxTestResults)
            if (collectionResult != null) {
                testResults = collectionResult.testResults
                testSummary = collectionResult.testSummary
                testResultsTruncated = collectionResult.truncated
            } else {
                testResults = emptyList()
                testSummary = TestSummary(total = 0, passed = 0, failed = 0, ignored = 0, runConfigName = null)
                testResultsTruncated = false
            }
        }

        return createJsonResult(DiagnosticsResult(
            problems = problems,
            intentions = intentions,
            problemCount = problems?.size,
            problemsTruncated = problemsTruncated,
            intentionCount = intentions?.size,
            analysisFresh = analysisFresh,
            analysisTimedOut = analysisTimedOut,
            analysisMessage = analysisMessage,
            analysisMode = analysisMode,
            fileAnalyses = fileAnalyses,
            buildErrors = buildErrors,
            buildErrorCount = buildErrorCount,
            buildWarningCount = buildWarningCount,
            buildErrorsTruncated = buildErrorsTruncated,
            buildTimestamp = buildTimestamp,
            testResults = testResults,
            testSummary = testSummary,
            testResultsTruncated = testResultsTruncated
        ))
    }

    private fun remainingBudgetMs(deadlineNanos: Long): Long? {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return null
        return ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    /** Returns a filesystem-free deduplication key while rejecting relative traversal escapes. */
    private fun normalizeDiagnosticPath(requestedPath: String): String {
        val path = try {
            Path.of(requestedPath)
        } catch (_: InvalidPathException) {
            throw IllegalArgumentException("is not a valid file path.")
        }
        val hasAbsoluteSyntax = path.isAbsolute || requestedPath.startsWith('/') ||
            requestedPath.startsWith('\\') || WINDOWS_DRIVE_PATH.matches(requestedPath)
        if (hasAbsoluteSyntax && !path.isAbsolute) {
            throw IllegalArgumentException("uses absolute path syntax that is not valid on this platform.")
        }

        val normalized = path.normalize()
        if (!path.isAbsolute && normalized.nameCount > 0 && normalized.getName(0).toString() == "..") {
            throw IllegalArgumentException("must not escape the project root through path traversal.")
        }
        return if (path.isAbsolute) "absolute:$normalized" else "relative:$normalized"
    }

    private fun sharedBudgetExhausted(file: String) = FileDiagnosticsAnalysis(
        file = file,
        state = ProjectDiagnosticsTool.STATE_NOT_ANALYZED,
        reason = "Shared diagnostics analysis timeout budget was exhausted before this file could be analyzed."
    )

    private data class BatchFileTarget(
        val requestedPath: String,
        val virtualFile: VirtualFile?,
        val budgetExhausted: Boolean = false,
        /** Set when the path exists but cannot be resolved to one file (e.g. ambiguous). */
        val unresolvedReason: String? = null
    )

    private suspend fun analyzeIntentions(
        project: Project,
        fileEditorManager: FileEditorManager,
        virtualFile: VirtualFile,
        line: Int,
        column: Int,
        highlights: List<HighlightInfo>
    ): List<IntentionInfo> = suspendingReadAction {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        if (psiFile == null) {
            return@suspendingReadAction emptyList()
        }

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        if (document == null) {
            return@suspendingReadAction emptyList()
        }

        val editor = fileEditorManager.getEditors(virtualFile)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor

        if (editor == null) {
            return@suspendingReadAction emptyList()
        }

        collectIntentions(project, psiFile, document, editor, line, column, highlights)
    }

    private fun filterBuildMessagesBySeverity(messages: List<BuildMessage>, severity: String): List<BuildMessage> {
        return when (severity) {
            "errors" -> messages.filter { it.category == "ERROR" }
            "warnings" -> messages.filter { it.category == "WARNING" }
            else -> messages
        }
    }

    // ========== Intention Collection ==========

    private fun collectIntentions(
        project: Project,
        psiFile: PsiFile,
        document: Document,
        editor: Editor,
        line: Int,
        column: Int,
        highlights: List<HighlightInfo>
    ): List<IntentionInfo> {
        val intentions = mutableListOf<IntentionInfo>()

        try {
            val offset = getOffset(document, line, column) ?: 0

            // Collect quick fixes from highlights at this position
            collectQuickFixes(project, editor, psiFile, offset, highlights, intentions)

            // Collect general intention actions
            if (psiFile.findElementAt(offset) != null) {
                collectGeneralIntentions(project, editor, psiFile, intentions)
            }
        } catch (_: Exception) {
            // Intention discovery might fail
        }

        return intentions.distinctBy { it.name }
    }

    private fun collectQuickFixes(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        offset: Int,
        highlights: List<HighlightInfo>,
        intentions: MutableList<IntentionInfo>
    ) {
        highlights
            .asSequence()
            .filter { it.startOffset <= offset && it.endOffset >= offset }
            .forEach { highlightInfo ->
            highlightInfo.findRegisteredQuickFix<Any> { descriptor, _ ->
                val action = descriptor.action
                try {
                    if (action.isAvailable(project, editor, psiFile)) {
                        intentions.add(IntentionInfo(
                            name = action.text,
                            description = action.familyName.takeIf { it != action.text }
                        ))
                    }
                } catch (_: Exception) {
                    // Availability check might fail
                }
                null
            }
            }
    }

    private fun collectGeneralIntentions(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        intentions: MutableList<IntentionInfo>
    ) {
        IntentionManager.getInstance()
            .getAvailableIntentions()
            .take(MAX_INTENTIONS)
            .forEach { action ->
                try {
                    val isAvailable = action.isAvailable(project, editor, psiFile)
                    if (isAvailable) {
                        intentions.add(IntentionInfo(
                            name = action.text,
                            description = action.familyName.takeIf { it != action.text }
                        ))
                    }
                } catch (_: Exception) {
                    // Individual intention check might fail
                }
            }
    }

    private fun appendAnalysisMessage(existing: String?, additional: String): String {
        if (existing.isNullOrBlank()) return additional
        if (existing.contains(additional)) return existing
        return "$existing $additional"
    }
}

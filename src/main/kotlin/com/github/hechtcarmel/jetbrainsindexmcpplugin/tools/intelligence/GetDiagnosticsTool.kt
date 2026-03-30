package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IntentionInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import com.intellij.openapi.diagnostic.logger
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP tool that analyzes files for code problems and available intentions.
 *
 * This tool leverages IntelliJ's daemon code analyzer to detect:
 * - Compilation errors
 * - Code warnings and weak warnings
 * - Available quick fixes and intentions
 *
 * Additionally supports:
 * - Build errors/warnings from the last build
 * - Test results from open test run tabs
 *
 * For files not currently open in the editor, the tool temporarily opens them
 * to trigger daemon analysis, then closes them after collecting results.
 */
class GetDiagnosticsTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<GetDiagnosticsTool>()
        private const val MAX_PROBLEMS = 100
        private const val MAX_INTENTIONS = 50
        private const val DAEMON_ANALYSIS_WAIT_MS = 500L
    }

    override val name = "ide_diagnostics"

    override val description = """
        Get code diagnostics from multiple sources: file analysis (errors, warnings, intentions), build output (compiler errors/warnings from last build), and test results (from open test run tabs).

        Returns: problems with severity and location, available intentions/quick fixes, build errors, and test results with error messages and stack traces.

        At least one source must be active: provide 'file' for code analysis, 'includeBuildErrors' for build output, or 'includeTestResults' for test results. Can combine all three.

        Parameters: file (optional, enables code analysis), line + column (optional, for intentions, requires file), startLine/endLine (optional, requires file), includeBuildErrors (optional), includeTestResults (optional), severity (optional, default 'all'), testResultFilter (optional, default 'failed'), maxBuildErrors (optional, default 100), maxTestResults (optional, default 100).

        Example: {"file": "src/MyClass.java"} or {"includeBuildErrors": true, "severity": "errors"} or {"file": "src/MyClass.java", "includeBuildErrors": true, "includeTestResults": true}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(required = false, description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). Optional — enables per-file code analysis.")
        .intProperty("line", "1-based line number for intention lookup. Optional, defaults to 1. Requires file.")
        .intProperty("column", "1-based column number for intention lookup. Optional, defaults to 1. Requires file.")
        .intProperty("startLine", "Filter problems to start from this line. Optional. Requires file.")
        .intProperty("endLine", "Filter problems to end at this line. Optional. Requires file.")
        .booleanProperty(ParamNames.INCLUDE_BUILD_ERRORS, "Include errors/warnings from the last build. Default: false.")
        .booleanProperty(ParamNames.INCLUDE_TEST_RESULTS, "Include test results from open test run tabs. Default: false.")
        .enumProperty(ParamNames.SEVERITY, "Filter by severity across all sources. Default: all.", listOf("all", "errors", "warnings"))
        .enumProperty(ParamNames.TEST_RESULT_FILTER, "Filter test results: 'failed' (default) or 'all'.", listOf("failed", "all"))
        .intProperty(ParamNames.MAX_BUILD_ERRORS, "Max build errors to return. Default: 100, max: 500.")
        .intProperty(ParamNames.MAX_TEST_RESULTS, "Max test results to return. Default: 100, max: 500.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        // Parse arguments
        val filePath = arguments["file"]?.jsonPrimitive?.content
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

        // Validate: at least one source must be active
        if (filePath == null && !includeBuildErrors && !includeTestResults) {
            return createErrorResult("At least one source must be active: provide 'file' for code analysis, 'includeBuildErrors' for build output, or 'includeTestResults' for test results.")
        }

        // Validate: startLine/endLine require file
        if (filePath == null && (startLine != null || endLine != null)) {
            return createErrorResult("Parameters 'startLine' and 'endLine' require 'file' to be specified.")
        }

        // File diagnostics
        var problems: List<ProblemInfo>? = null
        var intentions: List<IntentionInfo>? = null

        if (filePath != null) {
            requireSmartMode(project)

            val virtualFile = resolveFile(project, filePath)
                ?: return createErrorResult("File not found: $filePath")

            val fileEditorManager = FileEditorManager.getInstance(project)
            val wasAlreadyOpen = fileEditorManager.isFileOpen(virtualFile)

            if (!wasAlreadyOpen) {
                openFileForAnalysis(fileEditorManager, virtualFile)
            }

            try {
                val (fileProblems, fileIntentions) = analyzeFile(
                    project, fileEditorManager, virtualFile, filePath, line, column, startLine, endLine, severity
                )
                problems = fileProblems
                intentions = fileIntentions
            } finally {
                if (!wasAlreadyOpen) {
                    closeFile(fileEditorManager, virtualFile)
                }
            }
        }

        // Build errors
        var buildErrors: List<BuildMessage>? = null
        var buildErrorCount: Int? = null
        var buildWarningCount: Int? = null
        var buildErrorsTruncated: Boolean? = null
        var buildTimestamp: Long? = null

        if (includeBuildErrors) {
            val cacheService = BuildDiagnosticsCacheService.getInstance(project)
            val allBuildMessages = cacheService.getLastBuildDiagnostics(severity)
            buildErrorsTruncated = allBuildMessages.size > maxBuildErrors
            buildErrors = allBuildMessages.take(maxBuildErrors)
            buildErrorCount = allBuildMessages.count { it.category == "ERROR" }
            buildWarningCount = allBuildMessages.count { it.category == "WARNING" }
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
            intentionCount = intentions?.size,
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

    // ========== File Management ==========

    private suspend fun openFileForAnalysis(fileEditorManager: FileEditorManager, virtualFile: VirtualFile) {
        withContext(Dispatchers.EDT) {
            fileEditorManager.openFile(virtualFile, false)
        }
        // Wait for daemon to start analyzing
        delay(DAEMON_ANALYSIS_WAIT_MS)
    }

    private suspend fun closeFile(fileEditorManager: FileEditorManager, virtualFile: VirtualFile) {
        withContext(Dispatchers.EDT) {
            fileEditorManager.closeFile(virtualFile)
        }
    }

    // ========== Analysis ==========

    private suspend fun analyzeFile(
        project: Project,
        fileEditorManager: FileEditorManager,
        virtualFile: VirtualFile,
        filePath: String,
        line: Int,
        column: Int,
        startLine: Int?,
        endLine: Int?,
        severity: String
    ): Pair<List<ProblemInfo>, List<IntentionInfo>> = suspendingReadAction {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        if (psiFile == null) {
            LOG.warn("Could not parse file for diagnostics: $filePath")
            return@suspendingReadAction Pair(emptyList(), emptyList())
        }

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        if (document == null) {
            LOG.warn("Could not get document for diagnostics: $filePath")
            return@suspendingReadAction Pair(emptyList(), emptyList())
        }

        val editor = fileEditorManager.getEditors(virtualFile)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor

        val problems = collectProblems(project, document, filePath, startLine, endLine, severity)
        val intentions = collectIntentions(project, psiFile, document, editor, line, column)

        Pair(problems, intentions)
    }

    // ========== Problem Collection ==========

    private fun collectProblems(
        project: Project,
        document: Document,
        filePath: String,
        startLine: Int?,
        endLine: Int?,
        severity: String
    ): List<ProblemInfo> {
        val problems = mutableListOf<ProblemInfo>()

        try {
            DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                HighlightSeverity.INFORMATION,
                0,
                document.textLength
            ) { highlightInfo ->
                if (highlightInfo.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) {
                    val matchesSeverity = when (severity) {
                        "errors" -> highlightInfo.severity.myVal >= HighlightSeverity.ERROR.myVal
                        "warnings" -> highlightInfo.severity.myVal < HighlightSeverity.ERROR.myVal
                        else -> true
                    }
                    if (matchesSeverity) {
                        val problem = highlightInfo.toProblemInfo(document, filePath)

                        // Apply line filter
                        val inRange = (startLine == null || problem.line >= startLine) &&
                                      (endLine == null || problem.line <= endLine)

                        if (inRange) {
                            problems.add(problem)
                        }
                    }
                }
                problems.size < MAX_PROBLEMS
            }
        } catch (_: Exception) {
            // Daemon analysis might not be available
        }

        return problems.distinctBy { "${it.line}:${it.column}:${it.message}" }
    }

    private fun HighlightInfo.toProblemInfo(document: Document, filePath: String): ProblemInfo {
        val problemLine = document.getLineNumber(startOffset) + 1
        val problemColumn = startOffset - document.getLineStartOffset(problemLine - 1) + 1
        val endLineNum = document.getLineNumber(endOffset) + 1
        val endColumnNum = endOffset - document.getLineStartOffset(endLineNum - 1) + 1

        val severityString = when {
            severity.myVal >= HighlightSeverity.ERROR.myVal -> "ERROR"
            severity.myVal >= HighlightSeverity.WARNING.myVal -> "WARNING"
            severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal -> "WEAK_WARNING"
            else -> "INFO"
        }

        return ProblemInfo(
            message = description ?: "Unknown problem",
            severity = severityString,
            file = filePath,
            line = problemLine,
            column = problemColumn,
            endLine = endLineNum,
            endColumn = endColumnNum
        )
    }

    // ========== Intention Collection ==========

    private fun collectIntentions(
        project: Project,
        psiFile: PsiFile,
        document: Document,
        editor: Editor?,
        line: Int,
        column: Int
    ): List<IntentionInfo> {
        val intentions = mutableListOf<IntentionInfo>()

        try {
            val offset = getOffset(document, line, column) ?: 0

            // Collect quick fixes from highlights at this position
            if (editor != null) {
                collectQuickFixes(project, document, editor, psiFile, offset, intentions)
            }

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
        document: Document,
        editor: Editor,
        psiFile: PsiFile,
        offset: Int,
        intentions: MutableList<IntentionInfo>
    ) {
        DaemonCodeAnalyzerEx.processHighlights(
            document,
            project,
            HighlightSeverity.INFORMATION,
            offset,
            offset + 1
        ) { highlightInfo ->
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
            true
        }
    }

    private fun collectGeneralIntentions(
        project: Project,
        editor: Editor?,
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
}

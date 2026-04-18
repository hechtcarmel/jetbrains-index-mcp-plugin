package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IntentionInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
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
 * File diagnostics run through explicit daemon main passes so they work
 * even when the target project window is not active.
 */
class GetDiagnosticsTool : AbstractMcpTool() {

    companion object {
        private const val MAX_PROBLEMS = 100
        private const val MAX_INTENTIONS = 50
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
        var problems: List<com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo>? = null
        var intentions: List<IntentionInfo>? = null
        var analysisFresh: Boolean? = null
        var analysisTimedOut: Boolean? = null
        var analysisMessage: String? = null

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
                maxProblems = MAX_PROBLEMS
            )
            problems = analysisResult.problems
            analysisFresh = analysisResult.analysisFresh
            analysisTimedOut = analysisResult.analysisTimedOut
            analysisMessage = analysisResult.analysisMessage
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
            analysisFresh = analysisFresh,
            analysisTimedOut = analysisTimedOut,
            analysisMessage = analysisMessage,
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

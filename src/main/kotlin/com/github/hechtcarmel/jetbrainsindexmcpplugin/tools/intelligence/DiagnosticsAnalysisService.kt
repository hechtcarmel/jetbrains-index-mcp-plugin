package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.TestOnly
import kotlin.math.max

@Service(Service.Level.PROJECT)
class DiagnosticsAnalysisService(private val project: Project) {

    companion object {
        private const val DEFAULT_ANALYSIS_TIMEOUT_MS = 30_000L
        private const val HIGHLIGHT_POLL_INTERVAL_MS = 50L
        private const val HIGHLIGHT_RESTART_GRACE_MS = 150L
        private const val HIGHLIGHT_STABLE_SNAPSHOTS_REQUIRED = 2

        fun getInstance(project: Project): DiagnosticsAnalysisService =
            project.getService(DiagnosticsAnalysisService::class.java)

        internal fun shouldFinishHighlightWait(
            completed: Boolean,
            sawIncompleteState: Boolean,
            sawRelevantEvent: Boolean,
            elapsedMs: Long
        ): Boolean {
            if (!completed) {
                return false
            }

            if (sawIncompleteState) {
                return true
            }

            return elapsedMs >= HIGHLIGHT_RESTART_GRACE_MS && (!sawIncompleteState || sawRelevantEvent)
        }

        internal fun shouldAcceptHighlightSnapshot(
            completed: Boolean,
            sawIncompleteState: Boolean,
            sawRelevantEvent: Boolean,
            sawRelevantTerminalEvent: Boolean,
            stableSnapshotCount: Int,
            elapsedMs: Long
        ): Boolean {
            if (shouldFinishHighlightWait(completed, sawIncompleteState, sawRelevantEvent, elapsedMs)) {
                return true
            }

            if (elapsedMs < HIGHLIGHT_RESTART_GRACE_MS) {
                return false
            }

            if (sawRelevantTerminalEvent) {
                return true
            }

            return stableSnapshotCount >= HIGHLIGHT_STABLE_SNAPSHOTS_REQUIRED
        }
    }

    @TestOnly
    internal var analysisTimeoutMsOverride: Long? = null

    @TestOnly
    internal var openFileAnalysisOverride: (suspend (OpenFileAnalysisRequest) -> List<HighlightInfo>)? = null

    @TestOnly
    internal var closedFileAnalysisOverride: (suspend (ClosedFileAnalysisRequest) -> List<CodeSmellInfo>)? = null

    suspend fun analyzeFile(
        virtualFile: VirtualFile,
        filePath: String,
        severity: String,
        startLine: Int?,
        endLine: Int?,
        maxProblems: Int
    ): FileAnalysisResult {
        val openTextEditor = currentTextEditor(virtualFile)
        val fileContext = ReadAction.compute<FileContext?, Throwable> {
            if (!virtualFile.isValid) {
                return@compute null
            }

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@compute null
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@compute null
            val codeAnalyzer = DaemonCodeAnalyzer.getInstance(project)

            FileContext(
                virtualFile = virtualFile,
                psiFile = psiFile,
                filePath = filePath,
                document = document,
                textEditor = openTextEditor,
                openEditorEligible = openTextEditor != null && codeAnalyzer.isHighlightingAvailable(psiFile),
                batchEligible = ProblemHighlightFilter.shouldProcessFileInBatch(psiFile)
            )
        }

        if (fileContext == null || (!fileContext.openEditorEligible && !fileContext.batchEligible)) {
            return FileAnalysisResult(
                problems = emptyList(),
                highlights = emptyList(),
                analysisFresh = false,
                analysisTimedOut = false,
                analysisMessage = "File is not eligible for IDE diagnostics analysis."
            )
        }

        val timeoutMs = analysisTimeoutMsOverride ?: DEFAULT_ANALYSIS_TIMEOUT_MS
        val minSeverity = minimumSeverityFor(severity)

        return DiagnosticsAnalysisCoordinator.getInstance().withMainPassLock {
            if (fileContext.openEditorEligible) {
                val openEditorResult = analyzeOpenEditorFile(
                    fileContext = fileContext,
                    severity = severity,
                    minSeverity = minSeverity,
                    startLine = startLine,
                    endLine = endLine,
                    maxProblems = maxProblems,
                    timeoutMs = timeoutMs
                )
                if (openEditorResult != null) {
                    return@withMainPassLock openEditorResult
                }

                if (fileContext.batchEligible) {
                    val batchFallback = analyzeClosedFile(
                        fileContext = fileContext,
                        severity = severity,
                        startLine = startLine,
                        endLine = endLine,
                        maxProblems = maxProblems,
                        timeoutMs = timeoutMs
                    )
                    if (batchFallback != null) {
                        return@withMainPassLock batchFallback.copy(
                            analysisMessage = appendAnalysisMessage(
                                batchFallback.analysisMessage,
                                "Open-editor highlighting refresh timed out; returned public batch diagnostics instead, so weak warnings and quick-fix intentions may be incomplete."
                            )
                        )
                    }
                }

                return@withMainPassLock timeoutResult(timeoutMs)
            }

            analyzeClosedFile(
                fileContext = fileContext,
                severity = severity,
                startLine = startLine,
                endLine = endLine,
                maxProblems = maxProblems,
                timeoutMs = timeoutMs
            ) ?: timeoutResult(timeoutMs)
        }
    }

    private suspend fun analyzeOpenEditorFile(
        fileContext: FileContext,
        severity: String,
        minSeverity: HighlightSeverity,
        startLine: Int?,
        endLine: Int?,
        maxProblems: Int,
        timeoutMs: Long
    ): FileAnalysisResult? {
        val highlights = withTimeoutOrNull(timeoutMs) {
            val overrideRunner = openFileAnalysisOverride
            if (overrideRunner != null) {
                overrideRunner(
                    OpenFileAnalysisRequest(
                        filePath = fileContext.filePath,
                        psiFile = fileContext.psiFile,
                        document = fileContext.document,
                        textEditor = requireNotNull(fileContext.textEditor),
                        minSeverity = minSeverity
                    )
                )
            } else {
                refreshOpenEditorHighlights(fileContext, minSeverity)
            }
        } ?: return null

        return FileAnalysisResult(
            problems = toProblemInfoList(
                spans = highlights.map { highlight ->
                    ProblemSpan(
                        message = highlight.description ?: "Unknown problem",
                        severity = highlight.severity,
                        startOffset = highlight.startOffset,
                        endOffset = highlight.endOffset
                    )
                },
                filePath = fileContext.filePath,
                document = fileContext.document,
                severity = severity,
                startLine = startLine,
                endLine = endLine,
                maxProblems = maxProblems
            ),
            highlights = highlights,
            analysisFresh = true,
            analysisTimedOut = false,
            analysisMessage = null
        )
    }

    private suspend fun analyzeClosedFile(
        fileContext: FileContext,
        severity: String,
        startLine: Int?,
        endLine: Int?,
        maxProblems: Int,
        timeoutMs: Long
    ): FileAnalysisResult? {
        val codeSmells = withTimeoutOrNull(timeoutMs) {
            val overrideRunner = closedFileAnalysisOverride
            if (overrideRunner != null) {
                overrideRunner(
                    ClosedFileAnalysisRequest(
                        filePath = fileContext.filePath,
                        virtualFile = fileContext.virtualFile,
                        psiFile = fileContext.psiFile,
                        document = fileContext.document
                    )
                )
            } else {
                withContext(Dispatchers.Default) {
                    ProgressManager.getInstance().runProcess(
                        Computable {
                            CodeSmellDetector.getInstance(project).findCodeSmells(listOf(fileContext.virtualFile))
                        },
                        ProgressIndicatorBase()
                    )
                }
            }
        } ?: return null

        val closedFileMessage = if (severity == "errors") {
            null
        } else {
            "Closed-file diagnostics use public batch analysis; weak warnings are only available when the file is open in an editor."
        }

        return FileAnalysisResult(
            problems = toProblemInfoList(
                spans = codeSmells.map { smell ->
                    val range = smell.textRange
                    ProblemSpan(
                        message = smell.description ?: "Unknown problem",
                        severity = smell.severity,
                        startOffset = range.startOffset,
                        endOffset = range.endOffset
                    )
                },
                filePath = fileContext.filePath,
                document = fileContext.document,
                severity = severity,
                startLine = startLine,
                endLine = endLine,
                maxProblems = maxProblems
            ),
            highlights = emptyList(),
            analysisFresh = true,
            analysisTimedOut = false,
            analysisMessage = closedFileMessage
        )
    }

    private suspend fun refreshOpenEditorHighlights(
        fileContext: FileContext,
        minSeverity: HighlightSeverity
    ): List<HighlightInfo> {
        val textEditor = requireNotNull(fileContext.textEditor)
        val activityTracker = DaemonActivityTracker(project, textEditor)

        try {
            edtRestart(fileContext.psiFile)
            return awaitOpenEditorHighlights(fileContext, textEditor, minSeverity, activityTracker)
        } finally {
            activityTracker.dispose()
        }
    }

    private suspend fun awaitOpenEditorHighlights(
        fileContext: FileContext,
        textEditor: TextEditor,
        minSeverity: HighlightSeverity,
        activityTracker: DaemonActivityTracker
    ): List<HighlightInfo> {
        var sawIncompleteState = false
        var previousSnapshot: List<HighlightSnapshot>? = null
        var stableSnapshotCount = 0
        val startedAt = System.nanoTime()
        var latestHighlights = emptyList<HighlightInfo>()

        while (true) {
            currentCoroutineContext().ensureActive()

            val completed = edtHighlightingCompleted(textEditor)
            if (!completed) {
                sawIncompleteState = true
            }

            latestHighlights = readCurrentHighlights(fileContext, minSeverity)
            val snapshot = latestHighlights.toSnapshot()
            stableSnapshotCount = if (snapshot == previousSnapshot) stableSnapshotCount + 1 else 0
            previousSnapshot = snapshot

            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            if (
                shouldAcceptHighlightSnapshot(
                    completed = completed,
                    sawIncompleteState = sawIncompleteState,
                    sawRelevantEvent = activityTracker.sawRelevantEvent,
                    sawRelevantTerminalEvent = activityTracker.sawRelevantTerminalEvent,
                    stableSnapshotCount = stableSnapshotCount,
                    elapsedMs = elapsedMs
                )
            ) {
                return latestHighlights
            }

            delay(HIGHLIGHT_POLL_INTERVAL_MS)
        }
    }

    private fun readCurrentHighlights(
        fileContext: FileContext,
        minSeverity: HighlightSeverity
    ): List<HighlightInfo> {
        return ReadAction.compute<List<HighlightInfo>, Throwable> {
            val highlights = mutableListOf<HighlightInfo>()
            DaemonCodeAnalyzerEx.processHighlights(
                fileContext.document,
                project,
                minSeverity,
                0,
                fileContext.document.textLength
            ) { highlight ->
                highlights.add(highlight)
                true
            }
            highlights
        }
    }

    private suspend fun edtRestart(psiFile: PsiFile) {
        invokeOnEdt {
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
        }
    }

    private suspend fun edtHighlightingCompleted(textEditor: TextEditor): Boolean {
        return invokeOnEdt {
            !textEditor.editor.isDisposed && DaemonCodeAnalyzerEx.isHighlightingCompleted(textEditor, project)
        }
    }

    private suspend fun currentTextEditor(virtualFile: VirtualFile): TextEditor? {
        return invokeOnEdt {
            FileEditorManager.getInstance(project)
                .getEditors(virtualFile)
                .filterIsInstance<TextEditor>()
                .firstOrNull { !it.editor.isDisposed }
        }
    }

    private suspend fun <T> invokeOnEdt(action: () -> T): T {
        return if (ApplicationManager.getApplication().isDispatchThread) {
            action()
        } else {
            withContext(Dispatchers.Default) {
                var result: Result<T>? = null
                ApplicationManager.getApplication().invokeAndWait {
                    result = runCatching(action)
                }
                result!!.getOrThrow()
            }
        }
    }

    private fun timeoutResult(timeoutMs: Long): FileAnalysisResult {
        return FileAnalysisResult(
            problems = emptyList(),
            highlights = emptyList(),
            analysisFresh = false,
            analysisTimedOut = true,
            analysisMessage = "File diagnostics analysis timed out after ${timeoutMs}ms."
        )
    }

    private fun minimumSeverityFor(severity: String): HighlightSeverity {
        return when (severity) {
            "errors" -> HighlightSeverity.ERROR
            else -> HighlightSeverity.WEAK_WARNING
        }
    }

    private fun toProblemInfoList(
        spans: List<ProblemSpan>,
        filePath: String,
        document: com.intellij.openapi.editor.Document,
        severity: String,
        startLine: Int?,
        endLine: Int?,
        maxProblems: Int
    ): List<ProblemInfo> {
        val problems = mutableListOf<ProblemInfo>()
        val seen = linkedSetOf<String>()

        for (span in spans) {
            if (span.severity.myVal < HighlightSeverity.WEAK_WARNING.myVal) {
                continue
            }

            val matchesSeverity = when (severity) {
                "errors" -> span.severity.myVal >= HighlightSeverity.ERROR.myVal
                "warnings" -> span.severity.myVal < HighlightSeverity.ERROR.myVal
                else -> true
            }

            if (!matchesSeverity) {
                continue
            }

            val problem = span.toProblemInfo(document, filePath)
            val inRange = (startLine == null || problem.line >= startLine) &&
                (endLine == null || problem.line <= endLine)

            if (!inRange) {
                continue
            }

            val key = "${problem.line}:${problem.column}:${problem.message}"
            if (!seen.add(key)) {
                continue
            }

            problems.add(problem)
            if (problems.size >= maxProblems) {
                break
            }
        }

        return problems
    }

    private fun ProblemSpan.toProblemInfo(
        document: com.intellij.openapi.editor.Document,
        filePath: String
    ): ProblemInfo {
        val textLength = document.textLength
        val safeStartOffset = startOffset.coerceIn(0, textLength)
        val safeEndOffset = endOffset.coerceIn(safeStartOffset, textLength)

        val problemLine = document.getLineNumber(safeStartOffset) + 1
        val problemColumn = safeStartOffset - document.getLineStartOffset(problemLine - 1) + 1
        val displayEndOffset = max(safeStartOffset, safeEndOffset - 1)
        val endLineNum = document.getLineNumber(displayEndOffset) + 1
        val endColumnNum = displayEndOffset - document.getLineStartOffset(endLineNum - 1) + 1

        val severityString = when {
            severity.myVal >= HighlightSeverity.ERROR.myVal -> "ERROR"
            severity.myVal >= HighlightSeverity.WARNING.myVal -> "WARNING"
            severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal -> "WEAK_WARNING"
            else -> "INFO"
        }

        return ProblemInfo(
            message = message,
            severity = severityString,
            file = filePath,
            line = problemLine,
            column = problemColumn,
            endLine = endLineNum,
            endColumn = endColumnNum
        )
    }

    private fun appendAnalysisMessage(existing: String?, additional: String): String {
        if (existing.isNullOrBlank()) return additional
        if (existing.contains(additional)) return existing
        return "$existing $additional"
    }

    internal data class OpenFileAnalysisRequest(
        val filePath: String,
        val psiFile: PsiFile,
        val document: com.intellij.openapi.editor.Document,
        val textEditor: TextEditor,
        val minSeverity: HighlightSeverity
    )

    internal data class ClosedFileAnalysisRequest(
        val filePath: String,
        val virtualFile: VirtualFile,
        val psiFile: PsiFile,
        val document: com.intellij.openapi.editor.Document
    )

    data class FileAnalysisResult(
        val problems: List<ProblemInfo>,
        val highlights: List<HighlightInfo>,
        val analysisFresh: Boolean,
        val analysisTimedOut: Boolean,
        val analysisMessage: String?
    )

    private data class FileContext(
        val virtualFile: VirtualFile,
        val psiFile: PsiFile,
        val filePath: String,
        val document: com.intellij.openapi.editor.Document,
        val textEditor: TextEditor?,
        val openEditorEligible: Boolean,
        val batchEligible: Boolean
    )

    private data class ProblemSpan(
        val message: String,
        val severity: HighlightSeverity,
        val startOffset: Int,
        val endOffset: Int
    )

    private data class HighlightSnapshot(
        val severityName: String,
        val startOffset: Int,
        val endOffset: Int,
        val description: String?
    )

    private fun List<HighlightInfo>.toSnapshot(): List<HighlightSnapshot> {
        return map { highlight ->
            HighlightSnapshot(
                severityName = highlight.severity.name,
                startOffset = highlight.startOffset,
                endOffset = highlight.endOffset,
                description = highlight.description
            )
        }
    }

    private class DaemonActivityTracker(
        project: Project,
        private val targetEditor: TextEditor
    ) {
        private val targetFile = targetEditor.file

        @Volatile
        var sawRelevantEvent: Boolean = false
            private set

        @Volatile
        var sawRelevantTerminalEvent: Boolean = false
            private set

        private val connection = project.messageBus.connect()

        init {
            connection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
                override fun daemonStarting(fileEditors: Collection<out com.intellij.openapi.fileEditor.FileEditor>) {
                    markIfRelevant(fileEditors)
                }

                override fun daemonFinished() = Unit

                override fun daemonFinished(fileEditors: Collection<out com.intellij.openapi.fileEditor.FileEditor>) {
                    markIfRelevant(fileEditors, terminal = true)
                }

                override fun daemonCanceled(reason: String, fileEditors: Collection<out com.intellij.openapi.fileEditor.FileEditor>) {
                    markIfRelevant(fileEditors, terminal = true)
                }

                override fun daemonCancelEventOccurred(reason: String) = Unit
            })
        }

        fun dispose() {
            connection.disconnect()
        }

        private fun markIfRelevant(
            fileEditors: Collection<out com.intellij.openapi.fileEditor.FileEditor>,
            terminal: Boolean = false
        ) {
            if (fileEditors.any { it === targetEditor || it.file == targetFile }) {
                sawRelevantEvent = true
                if (terminal) {
                    sawRelevantTerminalEvent = true
                }
            }
        }
    }
}

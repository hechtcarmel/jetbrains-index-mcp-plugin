package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInspection.InspectionProfile
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionProfileWrapper
import com.intellij.openapi.application.WriteActionListener
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.jobToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ProperTextRange
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.TestOnly
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.function.Function
import java.util.function.Consumer
import kotlin.coroutines.resume

@Service(Service.Level.PROJECT)
class DiagnosticsAnalysisService(private val project: Project) {

    companion object {
        private const val DEFAULT_ANALYSIS_TIMEOUT_MS = 15_000L
        private const val MAX_RETRIES = 100
        private val runInsideHighlightingSessionMethod: Method by lazy {
            HighlightingSessionImpl::class.java.methods.firstOrNull { method ->
                method.name == "runInsideHighlightingSession" && (method.parameterCount == 5 || method.parameterCount == 6)
            } ?: error("HighlightingSessionImpl.runInsideHighlightingSession is unavailable")
        }
        private val anyCodeInsightContext: Any by lazy {
            loadAnyCodeInsightContext()
        }

        fun getInstance(project: Project): DiagnosticsAnalysisService =
            project.getService(DiagnosticsAnalysisService::class.java)

        private fun loadAnyCodeInsightContext(): Any {
            val providers = listOf(
                "com.intellij.codeInsight.multiverse.CodeInsightContexts" to "anyContext",
                "com.intellij.codeInsight.multiverse.CodeInsightContextsKt" to "anyContext",
                "com.intellij.codeInsight.multiverse.CodeInsightContextKt" to "anyContext"
            )

            for ((className, methodName) in providers) {
                val context = runCatching {
                    Class.forName(className).getMethod(methodName).invoke(null)
                }.getOrNull()
                if (context != null) {
                    return context
                }
            }

            val singleton = runCatching {
                Class.forName("com.intellij.codeInsight.multiverse.AnyContext")
                    .getField("INSTANCE")
                    .get(null)
            }.getOrNull()
            if (singleton != null) {
                return singleton
            }

            error("Unable to resolve IntelliJ anyContext implementation for highlighting session")
        }
    }

    private val analysisMutex = Mutex()

    @TestOnly
    internal var analysisTimeoutMsOverride: Long? = null

    @TestOnly
    internal var mainPassesRunnerOverride: (suspend (MainPassesRequest) -> List<HighlightInfo>)? = null

    suspend fun analyzeFile(
        virtualFile: VirtualFile,
        filePath: String,
        severity: String,
        startLine: Int?,
        endLine: Int?,
        maxProblems: Int
    ): FileAnalysisResult {
        val fileContext = ReadAction.compute<FileContext?, Throwable> {
            if (!virtualFile.isValid) {
                return@compute null
            }

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@compute null
            if (!ProblemHighlightFilter.shouldProcessFileInBatch(psiFile)) {
                return@compute null
            }

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@compute null
            FileContext(psiFile = psiFile, filePath = filePath, document = document)
        }

        if (fileContext == null) {
            return FileAnalysisResult(
                problems = emptyList(),
                highlights = emptyList(),
                analysisFresh = false,
                analysisTimedOut = false,
                analysisMessage = "File is not eligible for batch diagnostics analysis."
            )
        }

        return analysisMutex.withLock {
            val timeoutMs = analysisTimeoutMsOverride ?: DEFAULT_ANALYSIS_TIMEOUT_MS
            val minSeverity = minimumSeverityFor(severity)
            val inspectionProfile = InspectionProjectProfileManager.getInstance(project).currentProfile
            val codeAnalyzer = DaemonCodeAnalyzer.getInstance(project) as? DaemonCodeAnalyzerImpl
                ?: return@withLock FileAnalysisResult(
                    problems = emptyList(),
                    highlights = emptyList(),
                    analysisFresh = false,
                    analysisTimedOut = false,
                    analysisMessage = "IDE daemon code analyzer is not available."
                )
            val highlights = withTimeoutOrNull(timeoutMs) {
                runMainPassesWithRetries(
                    fileContext = fileContext,
                    minSeverity = minSeverity,
                    inspectionProfile = inspectionProfile,
                    codeAnalyzer = codeAnalyzer
                )
            }

            if (highlights == null) {
                return@withLock FileAnalysisResult(
                    problems = emptyList(),
                    highlights = emptyList(),
                    analysisFresh = false,
                    analysisTimedOut = true,
                    analysisMessage = "File diagnostics analysis timed out after ${timeoutMs}ms."
                )
            }

            FileAnalysisResult(
                problems = toProblemInfoList(
                    highlights = highlights,
                    filePath = filePath,
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
    }

    private suspend fun runMainPassesWithRetries(
        fileContext: FileContext,
        minSeverity: HighlightSeverity,
        inspectionProfile: InspectionProfile,
        codeAnalyzer: DaemonCodeAnalyzerImpl
    ): List<HighlightInfo> {
        val appEx = ApplicationManagerEx.getApplicationEx()
        val profileProvider = Function<InspectionProfile, InspectionProfileWrapper> { profile ->
            InspectionProfileWrapper(inspectionProfile, (profile as InspectionProfileImpl).profileManager)
        }
        var lastProcessCanceled: ProcessCanceledException? = null

        repeat(MAX_RETRIES) { attemptIndex ->
            currentCoroutineContext().ensureActive()
            val attempt = attemptIndex + 1
            val daemonIndicator = DaemonProgressIndicator()
            val listenerDisposable = Disposer.newDisposable()
            var canceledByWriteAction = false

            appEx.addWriteActionListener(object : WriteActionListener {
                override fun beforeWriteActionStart(action: Class<*>) {
                    canceledByWriteAction = true
                    daemonIndicator.cancel("beforeWriteActionStart: $action")
                }

                override fun writeActionStarted(action: Class<*>) = Unit
                override fun writeActionFinished(action: Class<*>) = Unit
                override fun afterWriteActionFinished(action: Class<*>) = Unit
            }, listenerDisposable)

            try {
                if (appEx.isWriteActionPending || appEx.isWriteActionInProgress) {
                    canceledByWriteAction = true
                    throw ProcessCanceledException()
                }

                return runSingleMainPassAttempt(
                    fileContext = fileContext,
                    attempt = attempt,
                    minSeverity = minSeverity,
                    profileProvider = profileProvider,
                    daemonIndicator = daemonIndicator,
                    codeAnalyzer = codeAnalyzer
                )
            } catch (e: ProcessCanceledException) {
                currentCoroutineContext().ensureActive()
                if (!isRetriable(e)) {
                    throw e
                }

                lastProcessCanceled = e
                if (canceledByWriteAction || appEx.isWriteActionPending || appEx.isWriteActionInProgress) {
                    awaitWriteActionCompletion()
                }
            } finally {
                Disposer.dispose(listenerDisposable)
            }
        }

        throw lastProcessCanceled ?: ProcessCanceledException()
    }

    private suspend fun runSingleMainPassAttempt(
        fileContext: FileContext,
        attempt: Int,
        minSeverity: HighlightSeverity,
        profileProvider: Function<InspectionProfile, InspectionProfileWrapper>,
        daemonIndicator: DaemonProgressIndicator,
        codeAnalyzer: DaemonCodeAnalyzerImpl
    ): List<HighlightInfo> {
        val overrideRunner = mainPassesRunnerOverride
        if (overrideRunner != null) {
            return overrideRunner(
                MainPassesRequest(
                    filePath = fileContext.filePath,
                    psiFile = fileContext.psiFile,
                    document = fileContext.document,
                    attempt = attempt,
                    minSeverity = minSeverity
                )
            )
        }

        return withContext(Dispatchers.Default) {
            val range = ProperTextRange.create(0, fileContext.document.textLength)
            var collectedHighlights: List<HighlightInfo>? = null

            jobToIndicator(currentCoroutineContext().job, daemonIndicator) {
                ProgressManager.checkCanceled()
                runInsideHighlightingSessionCompat(fileContext.psiFile, range) { session ->
                    (session as HighlightingSessionImpl).setMinimumSeverity(minSeverity)
                    InspectionProfileWrapper.runWithCustomInspectionWrapper(fileContext.psiFile, profileProvider) {
                        collectedHighlights = codeAnalyzer.runMainPasses(
                            fileContext.psiFile,
                            fileContext.document,
                            daemonIndicator
                        )
                    }
                }
                collectedHighlights.orEmpty()
            }
        }
    }

    private fun runInsideHighlightingSessionCompat(
        psiFile: PsiFile,
        range: ProperTextRange,
        action: (session: Any) -> Unit
    ) {
        val consumer = Consumer<Any?> { session ->
            if (session != null) {
                action(session)
            }
        }

        try {
            when (runInsideHighlightingSessionMethod.parameterCount) {
                6 -> runInsideHighlightingSessionMethod.invoke(null, psiFile, anyCodeInsightContext, null, range, false, consumer)
                5 -> runInsideHighlightingSessionMethod.invoke(null, psiFile, null, range, false, consumer)
                else -> error("Unsupported HighlightingSessionImpl.runInsideHighlightingSession signature")
            }
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw RuntimeException(cause)
            }
        }
    }

    private suspend fun awaitWriteActionCompletion() {
        val appEx = ApplicationManagerEx.getApplicationEx()
        if (!appEx.isWriteActionPending && !appEx.isWriteActionInProgress) {
            return
        }

        suspendCancellableCoroutine { continuation ->
            val listenerDisposable = Disposer.newDisposable()
            val resumeIfComplete = {
                if (!appEx.isWriteActionPending && !appEx.isWriteActionInProgress && continuation.isActive) {
                    Disposer.dispose(listenerDisposable)
                    continuation.resume(Unit)
                }
            }

            appEx.addWriteActionListener(object : WriteActionListener {
                override fun beforeWriteActionStart(action: Class<*>) = Unit
                override fun writeActionStarted(action: Class<*>) = Unit
                override fun writeActionFinished(action: Class<*>) = Unit
                override fun afterWriteActionFinished(action: Class<*>) {
                    resumeIfComplete()
                }
            }, listenerDisposable)

            continuation.invokeOnCancellation {
                Disposer.dispose(listenerDisposable)
            }

            resumeIfComplete()
        }
    }

    private fun minimumSeverityFor(severity: String): HighlightSeverity {
        return when (severity) {
            "errors" -> HighlightSeverity.ERROR
            else -> HighlightSeverity.WEAK_WARNING
        }
    }

    private fun isRetriable(exception: ProcessCanceledException): Boolean {
        val cause = exception.cause
        return cause == null || cause.javaClass == Throwable::class.java
    }

    private fun toProblemInfoList(
        highlights: List<HighlightInfo>,
        filePath: String,
        document: com.intellij.openapi.editor.Document,
        severity: String,
        startLine: Int?,
        endLine: Int?,
        maxProblems: Int
    ): List<ProblemInfo> {
        val problems = mutableListOf<ProblemInfo>()
        val seen = linkedSetOf<String>()

        for (highlight in highlights) {
            if (highlight.severity.myVal < HighlightSeverity.WEAK_WARNING.myVal) {
                continue
            }

            val matchesSeverity = when (severity) {
                "errors" -> highlight.severity.myVal >= HighlightSeverity.ERROR.myVal
                "warnings" -> highlight.severity.myVal < HighlightSeverity.ERROR.myVal
                else -> true
            }

            if (!matchesSeverity) {
                continue
            }

            val problem = highlight.toProblemInfo(document, filePath)
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

    private fun HighlightInfo.toProblemInfo(
        document: com.intellij.openapi.editor.Document,
        filePath: String
    ): ProblemInfo {
        val safeStartOffset = startOffset.coerceIn(0, document.textLength)
        val safeEndOffset = endOffset.coerceIn(safeStartOffset, document.textLength)

        val problemLine = document.getLineNumber(safeStartOffset) + 1
        val problemColumn = safeStartOffset - document.getLineStartOffset(problemLine - 1) + 1
        val endLineNum = document.getLineNumber(safeEndOffset) + 1
        val endColumnNum = safeEndOffset - document.getLineStartOffset(endLineNum - 1) + 1

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

    internal data class MainPassesRequest(
        val filePath: String,
        val psiFile: PsiFile,
        val document: com.intellij.openapi.editor.Document,
        val attempt: Int,
        val minSeverity: HighlightSeverity
    )

    data class FileAnalysisResult(
        val problems: List<ProblemInfo>,
        val highlights: List<HighlightInfo>,
        val analysisFresh: Boolean,
        val analysisTimedOut: Boolean,
        val analysisMessage: String?
    )

    private data class FileContext(
        val psiFile: PsiFile,
        val filePath: String,
        val document: com.intellij.openapi.editor.Document
    )
}

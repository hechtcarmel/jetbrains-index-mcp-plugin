package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.ide.PowerSaveMode
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class GetDiagnosticsToolBehaviorTest : BasePlatformTestCase() {
    private var localSourceRootConfigured = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testBuildOnlyDiagnosticsStillAcceptsLegacyLocationArguments() = runBlocking {
        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("includeBuildErrors", true)
            put("line", 3)
            put("column", 4)
        })
        assertFalse("Legacy location arguments are ignored without file analysis: ${renderResult(result)}", result.isFailure)
        assertNull("Build-only requests must not invent file analysis", decodeDiagnostics(result).analysisMode)
    }

    fun testBuildOnlyDiagnosticsStillRejectsRangeFiltersWithoutFile() = runBlocking {
        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("includeBuildErrors", true)
            put("startLine", 2)
            put("endLine", 8)
        })
        assertTrue("Range filters have always required file analysis", result.isFailure)
        assertTrue(renderResult(result).contains("require 'file'"))
    }

    fun testTestOnlyDiagnosticsStillAcceptsLegacyLocationArguments() = runBlocking {
        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("includeTestResults", true)
            put("line", 2)
            put("column", 5)
        })
        assertFalse("Legacy location arguments are ignored without file analysis: ${renderResult(result)}", result.isFailure)
        assertNull("Test-only requests must not invent file analysis", decodeDiagnostics(result).analysisMode)
    }

    fun testReturnsFreshFileProblemsWithoutOpeningEditor() = runBlocking {
        val brokenFile = createProjectFile(
            "Broken.java",
            """
            class Broken {
                void test() {
                    UnknownType value = null;
                }
            }
            """.trimIndent()
        )

        val fileEditorManager = FileEditorManager.getInstance(project)
        assertFalse("Broken.java should start closed", fileEditorManager.isFileOpen(brokenFile.virtualFile))

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("file", "src/Broken.java")
        })

        assertFalse("Diagnostics should succeed: ${renderResult(result)}", result.isFailure)

        val diagnostics = decodeDiagnostics(result)
        assertTrue("Expected fresh file analysis", diagnostics.analysisFresh == true)
        assertFalse("Analysis should not time out", diagnostics.analysisTimedOut == true)
        assertTrue("Expected at least one problem", (diagnostics.problemCount ?: 0) > 0)
        assertTrue(
            "Expected unresolved symbol diagnostics",
            diagnostics.problems.orEmpty().any { it.message.contains("UnknownType") || it.message.contains("Cannot resolve") }
        )
        assertTrue(
            "Closed-file analysis should explain the public batch fallback",
            diagnostics.analysisMessage?.contains("Closed-file diagnostics use public batch analysis") == true
        )
        assertTrue(
            "Closed-file analysis should explain missing intentions",
            diagnostics.analysisMessage?.contains("Intentions are unavailable because the file is not open in an editor.") == true
        )
        assertEquals(
            "Closed-file analysis must report the closed_batch mode",
            DiagnosticsAnalysisService.MODE_CLOSED_BATCH,
            diagnostics.analysisMode
        )
        assertFalse("Diagnostics should not auto-open the file", fileEditorManager.isFileOpen(brokenFile.virtualFile))
    }

    fun testMarksAnalysisTimedOutWhenClosedFileAnalysisExceedsBudget() = runBlocking {
        val file = createProjectFile(
            "TimeoutExample.java",
            """
            class TimeoutExample {
                void test() {}
            }
            """.trimIndent()
        )

        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalTimeout = service.analysisTimeoutMsOverride
        val originalRunner = service.closedFileAnalysisOverride

        try {
            service.analysisTimeoutMsOverride = 1L
            service.closedFileAnalysisOverride = {
                delay(50)
                emptyList()
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/TimeoutExample.java")
            })

            assertFalse("Timeout should be reported in-band: ${renderResult(result)}", result.isFailure)

            val diagnostics = decodeDiagnostics(result)
            assertTrue("Analysis should be marked timed out", diagnostics.analysisTimedOut == true)
            assertFalse("Timed out analysis should not be marked fresh", diagnostics.analysisFresh == true)
            assertEquals(0, diagnostics.problemCount)
            assertEquals("A timeout is not output truncation", false, diagnostics.problemsTruncated)
            assertNull("A timed-out analysis produced no result, so analysisMode must be null", diagnostics.analysisMode)
            assertTrue(
                "Expected timeout explanation",
                diagnostics.analysisMessage?.contains("timed out", ignoreCase = true) == true
            )
        } finally {
            service.analysisTimeoutMsOverride = originalTimeout
            service.closedFileAnalysisOverride = originalRunner
        }
    }

    fun testBlockingDetectorTimesOutAndReleasesTheAnalysisLock() = runBlocking {
        createProjectFile("BlockingDetector.java", "class BlockingDetector {}")
        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalTimeout = service.analysisTimeoutMsOverride
        val entered = AtomicBoolean()
        val exited = AtomicBoolean()
        project.replaceService(CodeSmellDetector::class.java, object : CodeSmellDetector() {
            override fun findCodeSmells(files: List<VirtualFile>): List<CodeSmellInfo> {
                if (entered.compareAndSet(false, true)) {
                    try {
                        // Model the blocking wait inside MainPassesRunner, not a suspend delay.
                        CountDownLatch(1).await(5, TimeUnit.SECONDS)
                    } finally {
                        exited.set(true)
                    }
                }
                return emptyList()
            }

            override fun showCodeSmellErrors(smells: List<CodeSmellInfo>) = Unit
        }, testRootDisposable)
        try {
            service.analysisTimeoutMsOverride = 300L
            val startedAt = System.nanoTime()
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/BlockingDetector.java")
            })
            assertFalse("Timeout must be reported in-band: ${renderResult(result)}", result.isFailure)
            assertTrue("The detector must actually run", entered.get())
            assertTrue("The worker must exit before the analysis lock is released", exited.get())
            assertTrue(decodeDiagnostics(result).analysisTimedOut == true)
            assertTrue("Blocking analysis exceeded its budget",
                System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(3))

            service.analysisTimeoutMsOverride = 3_000L
            val next = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/BlockingDetector.java")
            })
            assertFalse("The next analysis must succeed: ${renderResult(next)}", next.isFailure)
            assertTrue("The cancelled operation must not hold the main-pass lock",
                decodeDiagnostics(next).analysisFresh == true)
        } finally {
            service.analysisTimeoutMsOverride = originalTimeout
        }
    }

    fun testSingleFileWrappedDetectorInterruptionReportsTimeout() =
        assertWrappedDetectorInterruptionReportsTimeout(batch = false)

    fun testBatchWrappedDetectorInterruptionReportsTimeout() =
        assertWrappedDetectorInterruptionReportsTimeout(batch = true)

    private fun assertWrappedDetectorInterruptionReportsTimeout(batch: Boolean) = runBlocking {
        createProjectFile("WrappedTimeout.java", "class WrappedTimeout {}")
        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalTimeout = service.analysisTimeoutMsOverride
        val entered = AtomicBoolean()
        val wrapped = AtomicBoolean()
        val exited = AtomicBoolean()
        project.replaceService(CodeSmellDetector::class.java, object : CodeSmellDetector() {
            override fun findCodeSmells(files: List<VirtualFile>): List<CodeSmellInfo> {
                entered.set(true)
                try {
                    CountDownLatch(1).await(10, TimeUnit.SECONDS)
                } catch (interrupted: InterruptedException) {
                    wrapped.set(true)
                    throw RuntimeException("Platform analysis wrapper", interrupted)
                } finally {
                    exited.set(true)
                }
                return emptyList()
            }

            override fun showCodeSmellErrors(smells: List<CodeSmellInfo>) = Unit
        }, testRootDisposable)
        try {
            service.analysisTimeoutMsOverride = 1_000L
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                if (batch) put("files", buildJsonArray { add(JsonPrimitive("src/WrappedTimeout.java")) })
                else put("file", "src/WrappedTimeout.java")
            })
            assertTrue("The real blocking bridge must reach the detector", entered.get())
            assertTrue("The detector must actually wrap an interruption", wrapped.get())
            assertTrue("The worker must exit before the request returns", exited.get())
            assertFalse("An owned timeout is an in-band result: ${renderResult(result)}", result.isFailure)
            val diagnostics = decodeDiagnostics(result)
            if (batch) {
                val analysis = diagnostics.fileAnalyses.orEmpty().single()
                assertEquals(ProjectDiagnosticsTool.STATE_TIMED_OUT, analysis.state)
                assertTrue(analysis.reason.orEmpty().contains("timed out", ignoreCase = true))
            } else {
                assertTrue("The timeout must not appear clean", diagnostics.analysisTimedOut == true)
                assertFalse(diagnostics.analysisFresh == true)
                assertTrue(diagnostics.analysisMessage.orEmpty().contains("timed out", ignoreCase = true))
                assertNull("A timeout produced no complete analysis mode", diagnostics.analysisMode)
            }
        } finally {
            service.analysisTimeoutMsOverride = originalTimeout
        }
    }

    fun testSingleFileActiveWorkerInterruptionRemainsFailure() =
        assertActiveWorkerInterruptionRemainsFailure(batch = false)

    fun testBatchActiveWorkerInterruptionRemainsFailureAndRetainsOtherFiles() =
        assertActiveWorkerInterruptionRemainsFailure(batch = true)

    private fun assertActiveWorkerInterruptionRemainsFailure(batch: Boolean) = runBlocking {
        createProjectFile("IndependentFailure.java", "class IndependentFailure {}")
        createProjectFile("Healthy.java", "class Healthy {}")
        val entered = AtomicBoolean()
        project.replaceService(CodeSmellDetector::class.java, object : CodeSmellDetector() {
            override fun findCodeSmells(files: List<VirtualFile>): List<CodeSmellInfo> {
                if (files.single().name == "IndependentFailure.java") {
                    entered.set(true)
                    throw RuntimeException("Independent detector failure", InterruptedException())
                }
                return emptyList()
            }

            override fun showCodeSmellErrors(smells: List<CodeSmellInfo>) = Unit
        }, testRootDisposable)
        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            if (batch) put("files", buildJsonArray {
                add(JsonPrimitive("src/IndependentFailure.java"))
                add(JsonPrimitive("src/Healthy.java"))
            }) else put("file", "src/IndependentFailure.java")
        })
        assertTrue("The worker failure must actually execute", entered.get())
        if (batch) {
            assertFalse(renderResult(result), result.isFailure)
            val diagnostics = decodeDiagnostics(result)
            val analyses = diagnostics.fileAnalyses.orEmpty()
            val failure = analyses.single { it.file == "src/IndependentFailure.java" }
            assertEquals(ProjectDiagnosticsTool.STATE_FAILED, failure.state)
            assertTrue(failure.reason.orEmpty().contains("Independent detector failure"))
            assertEquals(ProjectDiagnosticsTool.STATE_ANALYZED, analyses.single { it.file == "src/Healthy.java" }.state)
            assertFalse("An independent failure is not an owned timeout", diagnostics.analysisTimedOut == true)
            assertFalse("Failed coverage must not appear complete", diagnostics.analysisFresh == true)
        } else {
            assertTrue("An active worker failure remains a tool error", result.isFailure)
            assertTrue(renderResult(result).contains("Independent detector failure"))
        }
    }

    fun testUsesOpenEditorPathWhenFileIsAlreadyOpen() = runBlocking {
        val openFile = createProjectFile(
            "OpenEditorExample.java",
            """
            class OpenEditorExample {
                void test() {}
            }
            """.trimIndent()
        )

        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.openFileAnalysisOverride
        var openPathUsed = false

        try {
            ApplicationManager.getApplication().invokeAndWait {
                FileEditorManager.getInstance(project).openFile(openFile.virtualFile, true)
            }
            assertTrue("OpenEditorExample.java should be open for the editor path", FileEditorManager.getInstance(project).isFileOpen(openFile.virtualFile))

            service.openFileAnalysisOverride = {
                openPathUsed = true
                listOf(
                    HighlightInfo.newHighlightInfo(HighlightInfoType.WEAK_WARNING)
                        .range(0, 1)
                        .descriptionAndTooltip("Synthetic weak warning")
                        .createUnconditionally()
                )
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/OpenEditorExample.java")
            })

            assertFalse("Diagnostics should succeed for open-editor analysis: ${renderResult(result)}", result.isFailure)

            val diagnostics = decodeDiagnostics(result)
            assertTrue("Expected the open-editor analysis path to be used", openPathUsed)
            assertTrue("Open-editor analysis should report fresh results", diagnostics.analysisFresh == true)
            assertFalse("Open-editor analysis should not time out", diagnostics.analysisTimedOut == true)
            assertEquals("Expected one synthetic problem", 1, diagnostics.problemCount)
            assertEquals("Synthetic weak warning", diagnostics.problems?.singleOrNull()?.message)
            assertEquals("WEAK_WARNING", diagnostics.problems?.singleOrNull()?.severity)
            assertEquals(
                "Open-editor analysis must report the open_daemon mode",
                DiagnosticsAnalysisService.MODE_OPEN_DAEMON,
                diagnostics.analysisMode
            )
            assertFalse(
                "Open-editor analysis should not advertise closed-file limitations",
                diagnostics.analysisMessage?.contains("Closed-file diagnostics use public batch analysis") == true
            )
        } finally {
            service.openFileAnalysisOverride = originalRunner
        }
    }

    fun testOpenEditorTimeoutDoesNotRestartBudgetWithBatchFallback() = runBlocking {
        val openFile = createProjectFile(
            "SlowOpenEditor.java",
            "class SlowOpenEditor {}"
        )
        val fileEditorManager = FileEditorManager.getInstance(project)
        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalTimeout = service.analysisTimeoutMsOverride
        val originalOpenRunner = service.openFileAnalysisOverride
        val originalClosedRunner = service.closedFileAnalysisOverride
        var openAnalysisStarted = false
        var closedAnalysisStarted = false

        try {
            ApplicationManager.getApplication().invokeAndWait {
                fileEditorManager.openFile(openFile.virtualFile, true)
            }
            service.analysisTimeoutMsOverride = 1_000L
            service.openFileAnalysisOverride = {
                openAnalysisStarted = true
                delay(5_000L)
                emptyList()
            }
            service.closedFileAnalysisOverride = {
                closedAnalysisStarted = true
                emptyList()
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/SlowOpenEditor.java")
            })

            assertFalse("Timeout should be reported in-band: ${renderResult(result)}", result.isFailure)
            val diagnostics = decodeDiagnostics(result)
            assertTrue("The open-editor analyzer must consume the shared budget", openAnalysisStarted)
            assertFalse("A daemon timeout must not start a second batch-analysis budget", closedAnalysisStarted)
            assertTrue("The exhausted shared budget must be explicit", diagnostics.analysisTimedOut == true)
            assertNull("No analyzer completed, so analysisMode must stay null", diagnostics.analysisMode)
        } finally {
            service.analysisTimeoutMsOverride = originalTimeout
            service.openFileAnalysisOverride = originalOpenRunner
            service.closedFileAnalysisOverride = originalClosedRunner
            ApplicationManager.getApplication().invokeAndWait {
                fileEditorManager.closeFile(openFile.virtualFile)
            }
        }
    }

    fun testOpenFileWithPowerSaveModeFallsBackToBatchAnalysis() = runBlocking {
        val brokenFile = createProjectFile(
            "PsmBroken.java",
            """
            class PsmBroken {
                void test() {
                    UnknownType value = null;
                }
            }
            """.trimIndent()
        )

        val fileEditorManager = FileEditorManager.getInstance(project)
        val originalPowerSaveMode = PowerSaveMode.isEnabled()

        try {
            ApplicationManager.getApplication().invokeAndWait {
                fileEditorManager.openFile(brokenFile.virtualFile, true)
            }
            assertTrue("PsmBroken.java should be open in an editor", fileEditorManager.isFileOpen(brokenFile.virtualFile))

            PowerSaveMode.setEnabled(true)

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/PsmBroken.java")
            })

            assertFalse("Diagnostics should succeed under Power Save Mode: ${renderResult(result)}", result.isFailure)

            val diagnostics = decodeDiagnostics(result)
            assertTrue(
                "Expected at least one problem from the batch fallback, got: ${diagnostics.problems}",
                (diagnostics.problemCount ?: 0) > 0
            )
            assertTrue(
                "Expected unresolved symbol diagnostics from the batch fallback",
                diagnostics.problems.orEmpty().any { it.message.contains("UnknownType") || it.message.contains("Cannot resolve") }
            )
            assertFalse("Batch fallback should not be reported as timed out", diagnostics.analysisTimedOut == true)
            assertTrue(
                "Analysis message should disclose that Power Save Mode disabled the highlighting daemon, got: ${diagnostics.analysisMessage}",
                diagnostics.analysisMessage?.contains("Power Save Mode") == true
            )
        } finally {
            PowerSaveMode.setEnabled(originalPowerSaveMode)
            ApplicationManager.getApplication().invokeAndWait {
                fileEditorManager.closeFile(brokenFile.virtualFile)
            }
        }
    }

    fun testOpenFileWithIdleDaemonFallsBackToBatchAnalysis() = runBlocking {
        val brokenFile = createProjectFile(
            "IdleDaemonBroken.java",
            """
            class IdleDaemonBroken {
                void test() {
                    UnknownType value = null;
                }
            }
            """.trimIndent()
        )

        val fileEditorManager = FileEditorManager.getInstance(project)
        assertFalse("Power Save Mode must be off for the idle-daemon scenario", PowerSaveMode.isEnabled())

        try {
            ApplicationManager.getApplication().invokeAndWait {
                fileEditorManager.openFile(brokenFile.virtualFile, true)
            }
            assertTrue("IdleDaemonBroken.java should be open in an editor", fileEditorManager.isFileOpen(brokenFile.virtualFile))

            // In headless platform tests the highlighting daemon never self-runs, so the
            // open-editor wait sees a stable empty markup model without any proof that passes ran.
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/IdleDaemonBroken.java")
            })

            assertFalse("Diagnostics should succeed with an idle daemon: ${renderResult(result)}", result.isFailure)

            val diagnostics = decodeDiagnostics(result)
            assertTrue(
                "Expected at least one problem from the batch fallback, got: ${diagnostics.problems}",
                (diagnostics.problemCount ?: 0) > 0
            )
            assertTrue(
                "Expected unresolved symbol diagnostics from the batch fallback",
                diagnostics.problems.orEmpty().any { it.message.contains("UnknownType") || it.message.contains("Cannot resolve") }
            )
            assertFalse("Idle-daemon fallback should not be reported as timed out", diagnostics.analysisTimedOut == true)
            assertTrue(
                "Analysis message should disclose that the daemon did not run, got: ${diagnostics.analysisMessage}",
                diagnostics.analysisMessage?.contains("daemon did not run") == true
            )
            assertFalse(
                "Idle-daemon fallback must stay distinguishable from the timeout fallback",
                diagnostics.analysisMessage?.contains("timed out") == true
            )
        } finally {
            ApplicationManager.getApplication().invokeAndWait {
                fileEditorManager.closeFile(brokenFile.virtualFile)
            }
        }
    }

    fun testHighlightWaitFinishesAfterGracePeriodWhenDaemonStaysCompleted() {
        assertFalse(
            "Completed highlighting should not finish immediately before the restart grace window elapses",
            DiagnosticsAnalysisService.shouldFinishHighlightWait(
                completed = true,
                sawIncompleteState = false,
                sawRelevantEvent = false,
                elapsedMs = 100L
            )
        )

        assertTrue(
            "Completed highlighting should finish after the grace window even if the daemon never reports a visible transition",
            DiagnosticsAnalysisService.shouldFinishHighlightWait(
                completed = true,
                sawIncompleteState = false,
                sawRelevantEvent = false,
                elapsedMs = 200L
            )
        )

        assertTrue(
            "A real incomplete-to-complete transition should finish immediately",
            DiagnosticsAnalysisService.shouldFinishHighlightWait(
                completed = true,
                sawIncompleteState = true,
                sawRelevantEvent = false,
                elapsedMs = 0L
            )
        )
    }

    fun testHighlightSnapshotCanFinishWithoutCompletedSignalWhenDaemonStabilizes() {
        assertFalse(
            "Stable snapshots should not be accepted before the restart grace window elapses",
            DiagnosticsAnalysisService.shouldAcceptHighlightSnapshot(
                completed = false,
                sawIncompleteState = true,
                sawRelevantEvent = false,
                sawRelevantTerminalEvent = false,
                stableSnapshotCount = 5,
                elapsedMs = 100L
            )
        )

        assertTrue(
            "A terminal daemon event should allow returning current highlights even if completion never flips",
            DiagnosticsAnalysisService.shouldAcceptHighlightSnapshot(
                completed = false,
                sawIncompleteState = true,
                sawRelevantEvent = true,
                sawRelevantTerminalEvent = true,
                stableSnapshotCount = 0,
                elapsedMs = 200L
            )
        )

        assertTrue(
            "Stable highlight snapshots should be accepted after the grace window when completion never flips",
            DiagnosticsAnalysisService.shouldAcceptHighlightSnapshot(
                completed = false,
                sawIncompleteState = true,
                sawRelevantEvent = false,
                sawRelevantTerminalEvent = false,
                stableSnapshotCount = 2,
                elapsedMs = 200L
            )
        )
    }

    fun testRefreshesExternalDiskChangesWhenAutoSyncEnabled() = runBlocking {
        createProjectFile(
            "FreshnessExample.java",
            """
            class FreshnessExample {
                void test() {
                    String value = "";
                }
            }
            """.trimIndent()
        )

        val settings = McpSettings.getInstance()
        val originalSyncSetting = settings.syncExternalChanges
        val filePath = sourceRootPath().resolve("FreshnessExample.java")

        try {
            Files.writeString(
                filePath,
                """
                class FreshnessExample {
                    void test() {
                        UnknownType value = null;
                    }
                }
                """.trimIndent()
            )
            settings.syncExternalChanges = true

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/FreshnessExample.java")
            })

            assertFalse("Diagnostics should succeed after external edit: ${renderResult(result)}", result.isFailure)

            val diagnostics = decodeDiagnostics(result)
            assertTrue("Expected fresh file analysis after external edit", diagnostics.analysisFresh == true)
            assertTrue(
                "Expected unresolved symbol diagnostics after external edit",
                diagnostics.problems.orEmpty().any { it.message.contains("UnknownType") || it.message.contains("Cannot resolve") }
            )
        } finally {
            settings.syncExternalChanges = originalSyncSetting
        }
    }

    fun testRefreshesTargetFileFromDiskWhenAutoSyncDisabled() = runBlocking {
        createProjectFile(
            "StaleExample.java",
            """
            class StaleExample {
                void test() {
                    AlphaType value = null;
                }
            }
            """.trimIndent()
        )

        val settings = McpSettings.getInstance()
        val originalSyncSetting = settings.syncExternalChanges
        val filePath = sourceRootPath().resolve("StaleExample.java")

        try {
            settings.syncExternalChanges = false

            // The first call loads the file into the VFS content cache and the document, which is
            // what makes a later out-of-band disk write invisible.
            val baseline = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/StaleExample.java")
            })
            assertFalse("Baseline diagnostics should succeed: ${renderResult(baseline)}", baseline.isFailure)
            assertTrue(
                "Baseline should report the pre-edit symbol: ${renderResult(baseline)}",
                decodeDiagnostics(baseline).problems.orEmpty().any { it.message.contains("AlphaType") }
            )

            // An external agent rewrote the file and nothing told the VFS about it.
            Files.writeString(
                filePath,
                """
                class StaleExample {
                    void test() {
                        BetaType value = null;
                    }
                }
                """.trimIndent()
            )

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/StaleExample.java")
            })

            assertFalse("Diagnostics should succeed: ${renderResult(result)}", result.isFailure)

            val problems = decodeDiagnostics(result).problems.orEmpty()
            assertTrue(
                "Diagnostics missed the post-edit symbol, so it analyzed stale VFS content: ${renderResult(result)}",
                problems.any { it.message.contains("BetaType") }
            )
            assertFalse(
                "Diagnostics still reports the pre-edit symbol, so it analyzed stale VFS content: ${renderResult(result)}",
                problems.any { it.message.contains("AlphaType") }
            )
        } finally {
            settings.syncExternalChanges = originalSyncSetting
        }
    }

    fun testUnsavedEditorChangesSurviveTheDiskRefresh() = runBlocking {
        val psiFile = createProjectFile(
            "UnsavedExample.java",
            """
            class UnsavedExample {
                void test() {
                    DiskType value = null;
                }
            }
            """.trimIndent()
        )
        val virtualFile = psiFile.virtualFile
        val fileEditorManager = FileEditorManager.getInstance(project)
        val editorText = "class UnsavedExample { void test() { EditorOnlyType value = null; } }"

        try {
            ApplicationManager.getApplication().invokeAndWait { fileEditorManager.openFile(virtualFile, true) }
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
            WriteAction.runAndWait<Throwable> { document.setText(editorText) }
            assertTrue(
                "Precondition: the document must be unsaved",
                FileDocumentManager.getInstance().isFileModified(virtualFile)
            )

            // Disk moves underneath the unsaved editor.
            Files.writeString(
                sourceRootPath().resolve("UnsavedExample.java"),
                "class UnsavedExample { void test() { NewDiskType value = null; } }"
            )

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/UnsavedExample.java")
            })

            // Refreshing over an unsaved document raises IntelliJ's memory-vs-disk conflict —
            // a hard error under the test framework, a reload prompt in the IDE.
            assertFalse("Diagnostics must not raise a memory-disk conflict: ${renderResult(result)}", result.isFailure)
            assertEquals(
                "The editor's unsaved text is the newer copy and must win over disk",
                editorText,
                FileDocumentManager.getInstance().getDocument(virtualFile)!!.text
            )
            assertTrue(
                "The document must still be unsaved — the refresh must not have reloaded it",
                FileDocumentManager.getInstance().isFileModified(virtualFile)
            )
        } finally {
            // Deliberately NOT FileDocumentManager.reloadFiles(): that resolves the memory-disk
            // conflict, and the conflict surfacing at teardown is what proves the guard works.
            // Matching disk to the document leaves nothing divergent for later tests instead.
            ApplicationManager.getApplication().invokeAndWait { fileEditorManager.closeFile(virtualFile) }
            Files.writeString(sourceRootPath().resolve("UnsavedExample.java"), editorText)
        }
    }

    fun testReportsFileDeletedOnDiskInsteadOfAnalyzingTheStaleCopy() = runBlocking {
        createProjectFile(
            "VanishExample.java",
            """
            class VanishExample {
                void test() {
                    GoneType value = null;
                }
            }
            """.trimIndent()
        )

        // Warm the VFS/document caches through a real call, so the file survives in cache.
        val baseline = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("file", "src/VanishExample.java")
        })
        assertFalse("Baseline diagnostics should succeed: ${renderResult(baseline)}", baseline.isFailure)

        Files.delete(sourceRootPath().resolve("VanishExample.java"))

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("file", "src/VanishExample.java")
        })

        // Refreshing invalidates the VirtualFile; every PSI lookup past that point throws
        // InvalidVirtualFileAccessException unless the tool checks.
        assertTrue("A deleted file must be reported, not analyzed from cache", result.isFailure)
        assertTrue(
            "The error should name the deleted file, got: ${renderResult(result)}",
            renderResult(result).contains("no longer exists")
        )
    }

    fun testFiltersClosedFileProblemsByRequestedSeverity() = runBlocking {
        createProjectFile(
            "SeverityExample.java",
            """
            class SeverityExample {
                void test() {}
            }
            """.trimIndent()
        )

        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.closedFileAnalysisOverride

        try {
            service.closedFileAnalysisOverride = { request ->
                listOf(
                    CodeSmellInfo(
                        request.document,
                        "Synthetic warning",
                        TextRange(0, 1),
                        HighlightSeverity.WARNING
                    ),
                    CodeSmellInfo(
                        request.document,
                        "Synthetic error",
                        TextRange(0, 1),
                        HighlightSeverity.ERROR
                    )
                )
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/SeverityExample.java")
                put("severity", "errors")
            })

            assertFalse("Diagnostics should succeed for error-only severity: ${renderResult(result)}", result.isFailure)

            val diagnostics = decodeDiagnostics(result)
            assertEquals("Expected one error result after severity filtering", 1, diagnostics.problemCount)
            assertEquals("Synthetic error", diagnostics.problems?.singleOrNull()?.message)
            assertEquals("ERROR", diagnostics.problems?.singleOrNull()?.severity)
        } finally {
            service.closedFileAnalysisOverride = originalRunner
        }
    }

    fun testMultiFileDiagnosticsAggregateProblemsAndReportPerFileAnalysis() = runBlocking {
        createProjectFile("MultiA.java", "class MultiA {}")
        createProjectFile("MultiB.java", "class MultiB {}")

        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.closedFileAnalysisOverride

        try {
            service.closedFileAnalysisOverride = { request ->
                listOf(
                    CodeSmellInfo(
                        request.document,
                        "Synthetic problem in ${request.filePath}",
                        TextRange(0, 1),
                        HighlightSeverity.ERROR
                    )
                )
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("files", buildJsonArray {
                    add(JsonPrimitive("src/MultiA.java"))
                    add(JsonPrimitive("src/MultiB.java"))
                })
            })

            assertFalse("Multi-file diagnostics should succeed: ${renderResult(result)}", result.isFailure)
            val diagnostics = decodeDiagnostics(result)
            assertEquals(2, diagnostics.problemCount)
            assertEquals(
                listOf("src/MultiA.java", "src/MultiB.java"),
                diagnostics.problems.orEmpty().map { it.file }
            )
            assertEquals(
                listOf("src/MultiA.java", "src/MultiB.java"),
                diagnostics.fileAnalyses.orEmpty().map { it.file }
            )
            diagnostics.fileAnalyses.orEmpty().forEach { analysis ->
                assertEquals(DiagnosticsAnalysisService.MODE_CLOSED_BATCH, analysis.mode)
                assertEquals(ProjectDiagnosticsTool.STATE_ANALYZED, analysis.state)
            }
            assertNull("Legacy single-file mode must stay absent for multi-file calls", diagnostics.analysisMode)
            assertNull("Intentions are position-based and must stay absent for multi-file calls", diagnostics.intentions)
        } finally {
            service.closedFileAnalysisOverride = originalRunner
        }
    }

    fun testMultiFileDiagnosticsReportNotFoundWithCoverageState() = runBlocking {
        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray { add(JsonPrimitive("src/DefinitelyMissing.java")) })
        })

        assertFalse("Missing files are reported in-band: ${renderResult(result)}", result.isFailure)
        val analysis = requireNotNull(decodeDiagnostics(result).fileAnalyses).single()
        assertEquals(ProjectDiagnosticsTool.STATE_NOT_FOUND, analysis.state)
        assertTrue(analysis.reason.orEmpty().contains("File not found"))
    }

    fun testMultiFileDiagnosticsUseRealEngineForSyntaxErrors() = runBlocking {
        createProjectFile("RealBatchBroken.java", "class RealBatchBroken { void broken( { }\n")
        createProjectFile("RealBatchClean.java", "class RealBatchClean {}\n")

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray {
                add(JsonPrimitive("src/RealBatchBroken.java"))
                add(JsonPrimitive("src/RealBatchClean.java"))
            })
            put("severity", "errors")
        })

        assertFalse("Real batch diagnostics should succeed: ${renderResult(result)}", result.isFailure)
        val diagnostics = decodeDiagnostics(result)
        assertTrue(
            "The real engine should report the syntax error: ${renderResult(result)}",
            diagnostics.problems.orEmpty().any {
                it.file == "src/RealBatchBroken.java" && it.severity == "ERROR"
            }
        )
        assertTrue(
            requireNotNull(diagnostics.fileAnalyses)
                .all { it.state == ProjectDiagnosticsTool.STATE_ANALYZED }
        )
    }

    fun testMultiFileDiagnosticsRetainOtherFilesWhenOneAnalysisFails() = runBlocking {
        listOf("First.java", "Failure.java", "Last.java").forEach {
            createProjectFile(it, "class ${it.removeSuffix(".java")} {}")
        }
        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.closedFileAnalysisOverride
        try {
            service.closedFileAnalysisOverride = { request ->
                if (request.filePath == "src/Failure.java") error("Analysis provider failed")
                listOf(CodeSmellInfo(request.document, "Problem in ${request.filePath}", TextRange(0, 1), HighlightSeverity.ERROR))
            }
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("files", buildJsonArray {
                    listOf("First.java", "Failure.java", "Last.java").forEach { add(JsonPrimitive("src/$it")) }
                })
            })
            assertFalse(renderResult(result), result.isFailure)
            val diagnostics = decodeDiagnostics(result)
            assertEquals(listOf("src/First.java", "src/Last.java"), diagnostics.problems.orEmpty().map { it.file })
            val analyses = diagnostics.fileAnalyses.orEmpty()
            assertEquals(3, analyses.size)
            val failed = analyses.single { it.file == "src/Failure.java" }
            assertEquals(ProjectDiagnosticsTool.STATE_FAILED, failed.state)
            assertTrue(failed.reason.orEmpty().contains("Analysis provider failed"))
            assertTrue(
                analyses.filter { it.file != failed.file }
                    .all { it.state == ProjectDiagnosticsTool.STATE_ANALYZED }
            )
        } finally {
            service.closedFileAnalysisOverride = originalRunner
        }
    }

    fun testMultiFileDiagnosticsTreatAnalyzerPceAsPerFileFailureWhenCoroutineIsActive() = runBlocking {
        createProjectFile("Cancelled.java", "class Cancelled {}")
        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.closedFileAnalysisOverride
        val cancelled = ProcessCanceledException()
        try {
            service.closedFileAnalysisOverride = { throw cancelled }
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("files", buildJsonArray { add(JsonPrimitive("src/Cancelled.java")) })
            })

            assertFalse("A per-file PCE should not cancel the active request", result.isFailure)
            val analysis = requireNotNull(decodeDiagnostics(result).fileAnalyses).single()
            assertEquals(ProjectDiagnosticsTool.STATE_FAILED, analysis.state)
            assertTrue(analysis.reason.orEmpty().contains(ProcessCanceledException::class.java.simpleName))
        } finally {
            service.closedFileAnalysisOverride = originalRunner
        }
    }

    fun testMultiFileDiagnosticsPropagateRequestCoroutineCancellation() = runBlocking {
        createProjectFile("RequestCancellation.java", "class RequestCancellation {}")
        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.closedFileAnalysisOverride
        val analysisStarted = CompletableDeferred<Unit>()
        val returnedResult = AtomicReference<CallToolResult?>()
        try {
            service.closedFileAnalysisOverride = {
                analysisStarted.complete(Unit)
                awaitCancellation()
            }
            val request = launch {
                returnedResult.set(GetDiagnosticsTool().execute(project, buildJsonObject {
                    put("files", buildJsonArray {
                        add(JsonPrimitive("src/RequestCancellation.java"))
                    })
                }))
            }

            analysisStarted.await()
            request.cancel()
            request.join()

            assertTrue("The request coroutine must remain cancelled", request.isCancelled)
            assertNull(
                "Cancellation must propagate instead of becoming a per-file failed result",
                returnedResult.get()
            )
        } finally {
            service.closedFileAnalysisOverride = originalRunner
        }
    }

    fun testMultiFileDiagnosticsMarkPsiLessTargetsSkipped() = runBlocking {
        createProjectFile("Eligibility.java", "class Eligibility {}")

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray { add(JsonPrimitive("src")) })
        })

        assertFalse("Ineligible targets are reported in-band: ${renderResult(result)}", result.isFailure)
        val analysis = requireNotNull(decodeDiagnostics(result).fileAnalyses).single()
        assertEquals(ProjectDiagnosticsTool.STATE_SKIPPED, analysis.state)
        assertTrue(analysis.reason.orEmpty().contains("not eligible", ignoreCase = true))
    }

    fun testMultiFileDiagnosticsDoNotCollapseDistinctSymlinkParentPaths() = runBlocking {
        createProjectFile("A.java", "class First {}")
        createProjectFile("../other/A.java", "class Second {}")
        val projectRoot = Path.of(requireNotNull(project.basePath))
        val otherRoot = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectRoot.resolve("other")))
        PsiTestUtil.addSourceRoot(module, otherRoot)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        Files.createDirectories(projectRoot.resolve("other/deep"))
        val link = projectRoot.resolve("src/link")
        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.closedFileAnalysisOverride
        try {
            try {
                Files.createSymbolicLink(link, Path.of("../other/deep"))
            } catch (e: Exception) {
                Assume.assumeNoException(e)
            }
            service.closedFileAnalysisOverride = { request ->
                listOf(CodeSmellInfo(request.document, request.document.text, TextRange(0, 1), HighlightSeverity.ERROR))
            }
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("files", buildJsonArray {
                    add(JsonPrimitive("src/A.java"))
                    add(JsonPrimitive("src/link/../A.java"))
                })
            })
            assertFalse(renderResult(result), result.isFailure)
            val diagnostics = decodeDiagnostics(result)
            assertEquals("The two paths resolve to different files", 2, diagnostics.fileAnalyses.orEmpty().size)
            assertEquals(diagnostics.toString(), 2, diagnostics.problemCount)
            assertTrue(diagnostics.problems.orEmpty().any { it.message.contains("Second") })
        } finally {
            service.closedFileAnalysisOverride = originalRunner
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries.forEach { entry ->
                    entry.sourceFolders.filter { it.url == otherRoot.url }.forEach(entry::removeSourceFolder)
                }
            }
            Files.deleteIfExists(link)
            projectRoot.resolve("other").toFile().deleteRecursively()
        }
    }

    fun testMultiFileDiagnosticsReportHiddenProblemsAfterAggregateLimit() = runBlocking {
        val diagnostics = diagnoseSyntheticProblems(firstCount = 100, secondCount = 1)

        assertEquals(100, diagnostics.problemCount)
        assertEquals(true, diagnostics.problemsTruncated)
        assertTrue(diagnostics.problems.orEmpty().all { it.file == "src/CapA.java" })
        val analyses = requireNotNull(diagnostics.fileAnalyses)
        assertEquals(100, analyses[0].problemCount)
        assertFalse("Exactly 100 problems in A were all returned", analyses[0].problemsTruncated)
        assertEquals(0, analyses[1].problemCount)
        assertTrue("B's hidden problem must be explicit", analyses[1].problemsTruncated)
        assertEquals("Output truncation must not change analysis state", ProjectDiagnosticsTool.STATE_ANALYZED, analyses[1].state)
        assertTrue("Provide an actionable single-file retry", analyses[1].reason.orEmpty().contains("using 'file'"))
    }

    fun testMultiFileDiagnosticsDoNotReportTruncationForCleanFileAfterLimit() = runBlocking {
        val diagnostics = diagnoseSyntheticProblems(firstCount = 100, secondCount = 0)

        assertEquals(100, diagnostics.problemCount)
        assertEquals(false, diagnostics.problemsTruncated)
        val analyses = requireNotNull(diagnostics.fileAnalyses)
        assertEquals(listOf(100, 0), analyses.map { it.problemCount })
        assertTrue(analyses.all { it.state == ProjectDiagnosticsTool.STATE_ANALYZED && !it.problemsTruncated })
    }

    fun testMultiFileDiagnosticsReportPartiallyReturnedFile() = runBlocking {
        val diagnostics = diagnoseSyntheticProblems(firstCount = 99, secondCount = 2)

        assertEquals(100, diagnostics.problemCount)
        assertEquals(true, diagnostics.problemsTruncated)
        val analyses = requireNotNull(diagnostics.fileAnalyses)
        assertEquals(listOf(99, 1), analyses.map { it.problemCount })
        assertFalse(analyses[0].problemsTruncated)
        assertTrue(analyses[1].problemsTruncated)
        assertEquals("src/CapB.java", diagnostics.problems.orEmpty().last().file)
    }

    fun testSingleFileDiagnosticsReportProblemLimitTruncation() = runBlocking {
        val diagnostics = diagnoseSyntheticProblems(firstCount = 101)

        assertEquals(100, diagnostics.problemCount)
        assertEquals(true, diagnostics.problemsTruncated)
        assertEquals(true, diagnostics.analysisFresh)
        assertTrue(diagnostics.analysisMessage.orEmpty().contains("narrower startLine/endLine"))
        assertNull(diagnostics.fileAnalyses)
    }

    fun testSingleFileDiagnosticsDoNotReportTruncationAtExactLimit() = runBlocking {
        val diagnostics = diagnoseSyntheticProblems(firstCount = 100)

        assertEquals(100, diagnostics.problemCount)
        assertEquals(false, diagnostics.problemsTruncated)
        assertEquals(true, diagnostics.analysisFresh)
    }

    fun testDiagnosticsHonorRequestedProblemLimit() = runBlocking {
        val diagnostics = diagnoseSyntheticProblems(firstCount = 3, maxProblems = 2)

        assertEquals(2, diagnostics.problemCount)
        assertEquals(true, diagnostics.problemsTruncated)
        assertTrue(diagnostics.analysisMessage.orEmpty().contains("truncated at 2 items"))
    }

    private suspend fun diagnoseSyntheticProblems(
        firstCount: Int,
        secondCount: Int? = null,
        maxProblems: Int? = null
    ): DiagnosticsResult {
        createProjectFile("CapA.java", "class CapA {}")
        if (secondCount != null) createProjectFile("CapB.java", "class CapB {}")
        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.closedFileAnalysisOverride
        try {
            service.closedFileAnalysisOverride = { request ->
                val count = if (request.filePath == "src/CapA.java") firstCount else requireNotNull(secondCount)
                List(count) { index ->
                    CodeSmellInfo(request.document, "Synthetic error $index", TextRange(0, 1), HighlightSeverity.ERROR)
                }
            }
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("severity", "errors")
                maxProblems?.let { put("maxProblems", it) }
                if (secondCount == null) {
                    put("file", "src/CapA.java")
                } else {
                    put("files", buildJsonArray {
                        add(JsonPrimitive("src/CapA.java"))
                        add(JsonPrimitive("src/CapB.java"))
                    })
                }
            })
            assertFalse("Synthetic diagnostics should succeed: ${renderResult(result)}", result.isFailure)
            return decodeDiagnostics(result)
        } finally {
            service.closedFileAnalysisOverride = originalRunner
        }
    }

    fun testMultiFileDiagnosticsUseOneSharedTimeoutBudget() = runBlocking {
        createProjectFile("BudgetA.java", "class BudgetA {}")
        createProjectFile("BudgetB.java", "class BudgetB {}")
        createProjectFile("BudgetC.java", "class BudgetC {}")

        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalTimeout = service.analysisTimeoutMsOverride
        val originalRunner = service.closedFileAnalysisOverride

        try {
            // Each file is individually faster than the configured timeout. Only a shared budget
            // can make the second file time out.
            service.analysisTimeoutMsOverride = 1_000L
            service.closedFileAnalysisOverride = {
                delay(650L)
                emptyList()
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("files", buildJsonArray {
                    add(JsonPrimitive("src/BudgetA.java"))
                    add(JsonPrimitive("src/BudgetB.java"))
                    add(JsonPrimitive("src/BudgetC.java"))
                })
            })

            assertFalse("Multi-file diagnostics should return timeout metadata: ${renderResult(result)}", result.isFailure)
            val analyses = decodeDiagnostics(result).fileAnalyses.orEmpty()
            assertEquals(3, analyses.size)
            assertEquals(
                "The first file should finish within the shared budget",
                ProjectDiagnosticsTool.STATE_ANALYZED,
                analyses[0].state
            )
            assertEquals(
                "The second file must consume only the remaining shared budget",
                ProjectDiagnosticsTool.STATE_TIMED_OUT,
                analyses[1].state
            )
            assertEquals(0, analyses[1].problemCount)
            assertFalse("Timeout metadata is independent of the output cap", analyses[1].problemsTruncated)
            assertEquals(
                "A file not started before deadline exhaustion is not_analyzed, not timed_out",
                ProjectDiagnosticsTool.STATE_NOT_ANALYZED,
                analyses[2].state
            )
        } finally {
            service.analysisTimeoutMsOverride = originalTimeout
            service.closedFileAnalysisOverride = originalRunner
        }
    }

    fun testDiagnosticsTimeoutIncludesWaitingForAnalysisLock() = runBlocking {
        createProjectFile("LockBudget.java", "class LockBudget {}")

        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalTimeout = service.analysisTimeoutMsOverride
        val originalRunner = service.closedFileAnalysisOverride
        val lockAcquired = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val lockHolder = launch(Dispatchers.Default) {
            DiagnosticsAnalysisCoordinator.getInstance().withMainPassLock {
                lockAcquired.complete(Unit)
                releaseLock.await()
            }
        }
        lockAcquired.await()

        try {
            service.analysisTimeoutMsOverride = 100L
            service.closedFileAnalysisOverride = {
                fail("Analysis must not start while another call owns the main-pass lock")
                emptyList()
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/LockBudget.java")
            })

            assertFalse("Lock timeout should be returned as metadata: ${renderResult(result)}", result.isFailure)
            val diagnostics = decodeDiagnostics(result)
            assertTrue("Waiting for the shared lock must consume the timeout budget", diagnostics.analysisTimedOut == true)
            assertFalse(diagnostics.analysisFresh == true)
        } finally {
            service.analysisTimeoutMsOverride = originalTimeout
            service.closedFileAnalysisOverride = originalRunner
            releaseLock.complete(Unit)
            lockHolder.join()
        }
    }

    fun testMultiFileDiagnosticsRejectAmbiguousTargetsAndLocations() = runBlocking {
        createProjectFile("Validation.java", "class Validation {}")

        val bothTargets = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("file", "src/Validation.java")
            put("files", buildJsonArray { add(JsonPrimitive("src/Validation.java")) })
        })
        assertTrue("file and files must be mutually exclusive", bothTargets.isFailure)
        assertTrue(renderResult(bothTargets).contains("mutually exclusive"))

        val multiFileLocation = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray { add(JsonPrimitive("src/Validation.java")) })
            put("line", 1)
        })
        assertTrue("Location parameters require the singular file target", multiFileLocation.isFailure)
        assertTrue(renderResult(multiFileLocation).contains("single 'file'"))
    }

    fun testDiagnosticsDoNotResolveAProjectRootAsAFile() = runBlocking {
        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("file", ".")
        })

        assertTrue("A project root is not a file target", result.isFailure)
        assertTrue(renderResult(result).contains("File not found"))
    }

    fun testMultiFileDiagnosticsBoundAndNormalizeRequestedPaths() = runBlocking {
        val tooMany = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray {
                repeat(101) { index -> add(JsonPrimitive("src/File$index.java")) }
            })
        })
        assertTrue("An unbounded diagnostics request must be rejected", tooMany.isFailure)
        assertTrue(renderResult(tooMany).contains("at most 100"))

        createProjectFile("Normalized.java", "class Normalized {}")
        val duplicateAliases = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray {
                add(JsonPrimitive("src/Normalized.java"))
                add(JsonPrimitive("src/./Normalized.java"))
                add(JsonPrimitive("src/dir/../Normalized.java"))
            })
        })
        assertFalse("Lexical aliases should be analyzed once: ${renderResult(duplicateAliases)}", duplicateAliases.isFailure)
        assertEquals(
            listOf("src/Normalized.java"),
            decodeDiagnostics(duplicateAliases).fileAnalyses.orEmpty().map { it.file }
        )

        val traversal = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray { add(JsonPrimitive("../Normalized.java")) })
        })
        assertTrue("Traversal outside the project must be rejected", traversal.isFailure)
        assertTrue(renderResult(traversal).contains("traversal"))

        val absolutePath = Path.of(requireNotNull(project.basePath), "src/Normalized.java").toString()
        val absolute = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("files", buildJsonArray {
                add(JsonPrimitive(absolutePath))
            })
        })
        assertFalse("Absolute paths inside project roots must be accepted", absolute.isFailure)
        val absoluteAnalysis = requireNotNull(decodeDiagnostics(absolute).fileAnalyses).single()
        assertEquals(absolutePath, absoluteAnalysis.file)
        assertEquals(ProjectDiagnosticsTool.STATE_ANALYZED, absoluteAnalysis.state)
    }

    fun testFiltersBuildDiagnosticsByRequestedSeverity() = runBlocking {
        seedBuildDiagnostics(
            compilerMessages = listOf(
                BuildMessage(
                    category = "ERROR",
                    message = "Cannot resolve symbol MissingType",
                    file = "src/Broken.java",
                    line = 4,
                    column = 9
                ),
                BuildMessage(
                    category = "WARNING",
                    message = "Unchecked assignment",
                    file = "src/Broken.java",
                    line = 6,
                    column = 13
                )
            )
        )

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("includeBuildErrors", true)
            put("severity", "errors")
        })

        assertFalse("Build diagnostics should succeed: ${renderResult(result)}", result.isFailure)

        val diagnostics = decodeDiagnostics(result)
        assertEquals("Expected only error diagnostics", 1, diagnostics.buildErrors?.size)
        assertEquals("Expected filtered error count", 1, diagnostics.buildErrorCount)
        assertEquals("Expected filtered warning count", 0, diagnostics.buildWarningCount)
        assertEquals("ERROR", diagnostics.buildErrors?.singleOrNull()?.category)
    }

    fun testReportsBuildDiagnosticsRecordedAfterBuildCompletes() = runBlocking {
        val cacheService = BuildDiagnosticsCacheService.getInstance(project)
        cacheService.recordBuildResult(
            listOf(
                BuildMessage(
                    category = "ERROR",
                    message = "Recorded build failure",
                    file = "src/Recorded.java",
                    line = 12,
                    column = 4
                )
            )
        )

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("includeBuildErrors", true)
        })

        assertFalse("Build diagnostics should succeed: ${renderResult(result)}", result.isFailure)

        val diagnostics = decodeDiagnostics(result)
        assertEquals("Expected one recorded build diagnostic", 1, diagnostics.buildErrors?.size)
        assertEquals("Expected recorded build error count", 1, diagnostics.buildErrorCount)
        assertEquals(0, diagnostics.buildWarningCount)
        assertEquals("Recorded build failure", diagnostics.buildErrors?.singleOrNull()?.message)
        assertNotNull("Expected build timestamp after recording build results", diagnostics.buildTimestamp)
    }

    fun testPrefersCompilerMessagesOverDuplicateBuildEventMessages() = runBlocking {
        seedBuildDiagnostics(
            compilerMessages = listOf(
                BuildMessage(
                    category = "ERROR",
                    message = "Cannot resolve symbol MissingType",
                    file = "src/Broken.java",
                    line = 4,
                    column = 9
                )
            ),
            buildEventMessages = listOf(
                BuildMessage(
                    category = "ERROR",
                    message = "java: cannot find symbol\n  symbol:   class MissingType",
                    file = "src/Broken.java",
                    line = 4,
                    column = 9
                )
            )
        )

        val result = GetDiagnosticsTool().execute(project, buildJsonObject {
            put("includeBuildErrors", true)
        })

        assertFalse("Build diagnostics should succeed: ${renderResult(result)}", result.isFailure)

        val diagnostics = decodeDiagnostics(result)
        assertEquals("Expected duplicated compiler/build event diagnostics to collapse to one entry", 1, diagnostics.buildErrors?.size)
        assertEquals("Expected one error count after source preference", 1, diagnostics.buildErrorCount)
        assertEquals("Cannot resolve symbol MissingType", diagnostics.buildErrors?.singleOrNull()?.message)
    }

    private fun createProjectFile(relativePath: String, content: String): com.intellij.psi.PsiFile {
        val basePath = project.basePath ?: error("Project base path is required for diagnostics tests")
        val sourceRootPath = sourceRootPath()
        Files.createDirectories(sourceRootPath)
        val sourceRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceRootPath)
            ?: error("Failed to refresh source root into LocalFileSystem")

        if (!localSourceRootConfigured) {
            val projectRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(basePath))
                ?: error("Failed to refresh project root into LocalFileSystem")
            PsiTestUtil.addContentRoot(module, projectRoot)
            PsiTestUtil.addSourceRoot(module, sourceRoot)
            localSourceRootConfigured = true
        }

        val filePath = sourceRootPath.resolve(relativePath)
        Files.createDirectories(filePath.parent ?: Path.of(basePath))
        Files.writeString(filePath, content)

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
            ?: error("Failed to refresh $relativePath into LocalFileSystem")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return PsiManager.getInstance(project).findFile(virtualFile)
            ?: error("Failed to create PSI for $relativePath")
    }

    private fun sourceRootPath(): Path {
        val basePath = project.basePath ?: error("Project base path is required for diagnostics tests")
        return Path.of(basePath).resolve("src")
    }

    private fun seedBuildDiagnostics(
        compilerMessages: List<BuildMessage> = emptyList(),
        buildEventMessages: List<BuildMessage> = emptyList()
    ) {
        val cacheService = BuildDiagnosticsCacheService.getInstance(project)

        setFieldIfPresent(cacheService, "compilerMessages", AtomicReference(compilerMessages))
        setFieldIfPresent(cacheService, "buildEventMessages", AtomicReference(buildEventMessages))
        setFieldIfPresent(
            cacheService,
            "publishedMessages",
            AtomicReference(if (compilerMessages.isNotEmpty()) compilerMessages else buildEventMessages)
        )

        @Suppress("UNCHECKED_CAST")
        val legacyCache = cacheService.javaClass.getDeclaredFieldOrNull("cachedMessages")
            ?.apply { isAccessible = true }
            ?.get(cacheService) as? CopyOnWriteArrayList<BuildMessage>
        legacyCache?.apply {
            clear()
            addAll(compilerMessages + buildEventMessages)
        }

        val timestamp = System.currentTimeMillis()
        @Suppress("UNCHECKED_CAST")
        val timestampField = cacheService.javaClass.getDeclaredFieldOrNull("buildTimestamp")
            ?.apply { isAccessible = true }
            ?.get(cacheService)
        when (timestampField) {
            is AtomicLong -> timestampField.set(timestamp)
            is AtomicReference<*> -> {
                @Suppress("UNCHECKED_CAST")
                (timestampField as AtomicReference<Any?>).set(timestamp)
            }
        }
    }

    private fun setFieldIfPresent(target: Any, fieldName: String, value: Any) {
        val field = target.javaClass.getDeclaredFieldOrNull(fieldName) ?: return
        field.isAccessible = true
        field.set(target, value)
    }

    private fun Class<*>.getDeclaredFieldOrNull(name: String): java.lang.reflect.Field? =
        runCatching { getDeclaredField(name) }.getOrNull()

    private fun decodeDiagnostics(result: CallToolResult): DiagnosticsResult {
        val content = result.content.first() as TextContent
        return json.decodeFromString(content.text)
    }

    private fun renderResult(result: CallToolResult): String =
        result.content.joinToString(separator = " | ") { block ->
            when (block) {
                is TextContent -> block.text
                else -> block.toString()
            }
        }
}

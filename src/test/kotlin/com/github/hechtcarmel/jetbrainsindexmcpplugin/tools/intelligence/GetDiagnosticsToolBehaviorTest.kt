package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

class GetDiagnosticsToolBehaviorTest : BasePlatformTestCase() {
    private var localSourceRootConfigured = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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

        assertFalse("Diagnostics should succeed: ${renderResult(result)}", result.isError)

        val diagnostics = decodeDiagnostics(result)
        assertTrue("Expected fresh file analysis", diagnostics.analysisFresh == true)
        assertFalse("Analysis should not time out", diagnostics.analysisTimedOut == true)
        assertTrue("Expected at least one problem", (diagnostics.problemCount ?: 0) > 0)
        assertTrue(
            "Expected unresolved symbol diagnostics",
            diagnostics.problems.orEmpty().any { it.message.contains("UnknownType") || it.message.contains("Cannot resolve") }
        )
        assertFalse("Diagnostics should not auto-open the file", fileEditorManager.isFileOpen(brokenFile.virtualFile))
    }

    fun testMarksAnalysisTimedOutWhenFreshAnalysisExceedsBudget() = runBlocking {
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
        val originalRunner = service.mainPassesRunnerOverride

        try {
            service.analysisTimeoutMsOverride = 1L
            service.mainPassesRunnerOverride = {
                delay(50)
                emptyList()
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/TimeoutExample.java")
            })

            assertFalse("Timeout should be reported in-band: ${renderResult(result)}", result.isError)

            val diagnostics = decodeDiagnostics(result)
            assertTrue("Analysis should be marked timed out", diagnostics.analysisTimedOut == true)
            assertFalse("Timed out analysis should not be marked fresh", diagnostics.analysisFresh == true)
            assertTrue(
                "Expected timeout explanation",
                diagnostics.analysisMessage?.contains("timed out", ignoreCase = true) == true
            )
        } finally {
            service.analysisTimeoutMsOverride = originalTimeout
            service.mainPassesRunnerOverride = originalRunner
        }
    }

    fun testRetriesRetriableCanceledAnalysis() = runBlocking {
        createProjectFile(
            "RetryExample.java",
            """
            class RetryExample {
                void test() {}
            }
            """.trimIndent()
        )

        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalTimeout = service.analysisTimeoutMsOverride
        val originalRunner = service.mainPassesRunnerOverride
        var attempts = 0

        try {
            service.mainPassesRunnerOverride = { request ->
                attempts++
                if (attempts == 1) {
                    throw ProcessCanceledException()
                }

                listOf(
                    HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                        .range(0, 1)
                        .descriptionAndTooltip("Synthetic retryable error")
                        .createUnconditionally()
                )
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/RetryExample.java")
            })

            assertFalse("Diagnostics should succeed after retry: ${renderResult(result)}", result.isError)

            val diagnostics = decodeDiagnostics(result)
            assertEquals("Expected one retried analysis rerun", 2, attempts)
            assertTrue("Retry path should still report fresh analysis", diagnostics.analysisFresh == true)
            assertFalse("Retry path should not time out", diagnostics.analysisTimedOut == true)
            assertEquals("Expected one synthetic problem", 1, diagnostics.problemCount)
            assertEquals("Synthetic retryable error", diagnostics.problems?.singleOrNull()?.message)
        } finally {
            service.analysisTimeoutMsOverride = originalTimeout
            service.mainPassesRunnerOverride = originalRunner
        }
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

            assertFalse("Diagnostics should succeed after external edit: ${renderResult(result)}", result.isError)

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

    fun testPassesErrorSeverityToFreshAnalysisRunner() = runBlocking {
        createProjectFile(
            "SeverityExample.java",
            """
            class SeverityExample {
                void test() {}
            }
            """.trimIndent()
        )

        val service = DiagnosticsAnalysisService.getInstance(project)
        val originalRunner = service.mainPassesRunnerOverride
        var capturedSeverity: HighlightSeverity? = null

        try {
            service.mainPassesRunnerOverride = { request ->
                capturedSeverity = request.minSeverity
                listOf(
                    HighlightInfo.newHighlightInfo(HighlightInfoType.WARNING)
                        .range(0, 1)
                        .descriptionAndTooltip("Synthetic warning")
                        .createUnconditionally()
                )
            }

            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", "src/SeverityExample.java")
                put("severity", "errors")
            })

            assertFalse("Diagnostics should succeed for error-only severity: ${renderResult(result)}", result.isError)

            val diagnostics = decodeDiagnostics(result)
            assertEquals("Fresh analysis should request error severity", HighlightSeverity.ERROR, capturedSeverity)
            assertEquals("Warning highlight should be filtered from error-only results", 0, diagnostics.problemCount)
        } finally {
            service.mainPassesRunnerOverride = originalRunner
        }
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

    private fun decodeDiagnostics(result: ToolCallResult): DiagnosticsResult {
        val content = result.content.first() as ContentBlock.Text
        return json.decodeFromString(content.text)
    }

    private fun renderResult(result: ToolCallResult): String =
        result.content.joinToString(separator = " | ") { block ->
            when (block) {
                is ContentBlock.Text -> block.text
                else -> block.toString()
            }
        }
}

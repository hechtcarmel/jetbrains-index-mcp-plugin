package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

        assertFalse("Build diagnostics should succeed: ${renderResult(result)}", result.isError)

        val diagnostics = decodeDiagnostics(result)
        assertEquals("Expected only error diagnostics", 1, diagnostics.buildErrors?.size)
        assertEquals("Expected filtered error count", 1, diagnostics.buildErrorCount)
        assertEquals("Expected filtered warning count", 0, diagnostics.buildWarningCount)
        assertEquals("ERROR", diagnostics.buildErrors?.singleOrNull()?.category)
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

        assertFalse("Build diagnostics should succeed: ${renderResult(result)}", result.isError)

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

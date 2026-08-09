package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProjectDiagnosticsInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProjectDiagnosticsResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * End-to-end coverage of ide_project_diagnostics (issue #246). The tool's contract is that an
 * empty problems list can never be mistaken for "the project is clean": every file in scope must
 * reach a coverage state and `complete` must be false whenever any of them was not analyzed.
 *
 * Runs off the EDT because the analysis loop executes on a background coroutine and dispatches
 * to the EDT internally (the same shape as production MCP calls on Ktor worker threads).
 */
class ProjectDiagnosticsToolBehaviorTest : McpPlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    private val tool = ProjectDiagnosticsTool()
    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        registerSourceRoot("src")
    }

    override fun tearDown() {
        try {
            val service = DiagnosticsAnalysisService.getInstance(project)
            service.analysisTimeoutMsOverride = null
            service.closedFileAnalysisOverride = null
            service.openFileAnalysisOverride = null

            val registry = ActiveProjectAnalysisRegistry.getInstance(project)
            registry.mostRecent?.let { analysis ->
                if (!analysis.result.isCompleted) {
                    analysis.result.completeExceptionally(RuntimeException("test teardown"))
                }
                registry.remove(analysis.id)
            }
        } finally {
            super.tearDown()
        }
    }

    private fun callTool(argsBuilder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): CallToolResult =
        runBlocking { tool.execute(project, buildJsonObject(argsBuilder)) }

    private fun decodeFinal(result: CallToolResult): ProjectDiagnosticsResult =
        json.decodeFromString(ProjectDiagnosticsResult.serializer(), toolText(result))

    private fun decodeRunning(result: CallToolResult): ProjectDiagnosticsInProgressResult =
        json.decodeFromString(ProjectDiagnosticsInProgressResult.serializer(), toolText(result))

    private fun statusOf(result: CallToolResult): String =
        Json.parseToJsonElement(toolText(result)).jsonObject["status"]?.jsonPrimitive?.content.orEmpty()

    /** Polls a running analysis until it reaches a terminal payload. */
    private fun pollUntilFinal(analysisId: String): ProjectDiagnosticsResult {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val result = callTool {
                put("analysisId", analysisId)
                put("waitSeconds", 2)
            }
            assertToolSucceeded("polling a known analysisId must not fail", result)
            if (statusOf(result) != ProjectDiagnosticsTool.STATUS_RUNNING) {
                return decodeFinal(result)
            }
        }
        throw AssertionError("Analysis $analysisId did not finish within 60s")
    }

    fun testAnalyzesClosedFilesInScopeAndReportsCompleteCoverage() {
        writeProjectFile(
            "src/covscope/Broken.java",
            """
            class Broken {
                void test() {
                    UnknownType value = null;
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "src/covscope/Clean.java",
            """
            class Clean {
                void test() {}
            }
            """.trimIndent()
        )

        val result = callTool { putJsonArray("paths") { add("src/covscope") } }

        assertToolSucceeded("project diagnostics over src must succeed", result)
        val payload = decodeFinal(result)

        assertEquals(ProjectDiagnosticsTool.STATUS_COMPLETED, payload.status)
        assertTrue("all files analyzed, so the result must be complete", payload.complete)
        assertEquals("both files must be considered", 2, payload.filesConsidered)
        assertEquals("both files must be analyzed", 2, payload.filesAnalyzed)
        assertEquals("closed files must use the batch mode", 2, payload.filesAnalyzedClosedBatch)
        assertEquals(0, payload.filesAnalyzedOpenDaemon)
        assertTrue("complete coverage must leave incompleteFiles empty", payload.incompleteFiles.isEmpty())
        assertTrue("the broken file's unresolved symbol must be reported", payload.problemCount > 0)
        assertTrue(
            "expected an unresolved-symbol problem attributed to src/covscope/Broken.java, got: ${payload.problems}",
            payload.problems.any {
                it.file == "src/covscope/Broken.java" && (it.message.contains("UnknownType") || it.message.contains("Cannot resolve"))
            }
        )
        assertTrue("unresolved symbols must count as errors", payload.errorCount > 0)
        assertFalse(payload.problemsTruncated)
    }

    fun testEmptyProblemsWithTimedOutFileIsNotComplete() {
        writeProjectFile("src/tmoscope/CleanA.java", "class CleanA {}")
        writeProjectFile("src/tmoscope/TimeoutB.java", "class TimeoutB {}")

        val service = DiagnosticsAnalysisService.getInstance(project)
        service.analysisTimeoutMsOverride = 200L
        service.closedFileAnalysisOverride = { request ->
            if (request.filePath.endsWith("TimeoutB.java")) {
                delay(5_000)
            }
            emptyList()
        }

        val result = callTool { putJsonArray("paths") { add("src/tmoscope") } }

        assertToolSucceeded("a per-file timeout is reported in-band, not as a tool error", result)
        val payload = decodeFinal(result)

        assertTrue("no problems were found", payload.problems.isEmpty())
        assertEquals(0, payload.problemCount)
        assertFalse(
            "an empty problems list with a timed-out file must NOT be reported complete — " +
                    "this is the fail-closed contract of issue #246",
            payload.complete
        )
        assertEquals(2, payload.filesConsidered)
        assertEquals(1, payload.filesAnalyzed)
        assertEquals(1, payload.filesTimedOut)
        val entry = payload.incompleteFiles.single()
        assertEquals("src/tmoscope/TimeoutB.java", entry.file)
        assertEquals(ProjectDiagnosticsTool.STATE_TIMED_OUT, entry.state)
        assertEquals(ProjectDiagnosticsTool.STATUS_COMPLETED, payload.status)
    }

    fun testAnalysisExceptionMarksFileFailedAndKeepsGoing() {
        writeProjectFile("src/failscope/Boom.java", "class Boom {}")
        writeProjectFile("src/failscope/Fine.java", "class Fine {}")

        val service = DiagnosticsAnalysisService.getInstance(project)
        service.closedFileAnalysisOverride = { request ->
            if (request.filePath.endsWith("Boom.java")) {
                throw RuntimeException("synthetic analysis crash")
            }
            emptyList()
        }

        val result = callTool { putJsonArray("paths") { add("src/failscope") } }

        assertToolSucceeded("a per-file crash is reported in-band, not as a tool error", result)
        val payload = decodeFinal(result)

        assertFalse("a failed file must fail the completeness gate", payload.complete)
        assertEquals(2, payload.filesConsidered)
        assertEquals(1, payload.filesAnalyzed)
        assertEquals(1, payload.filesFailed)
        val entry = payload.incompleteFiles.single()
        assertEquals("src/failscope/Boom.java", entry.file)
        assertEquals(ProjectDiagnosticsTool.STATE_FAILED, entry.state)
        assertTrue(
            "failure reason must carry the exception message, got: ${entry.reason}",
            entry.reason.orEmpty().contains("synthetic analysis crash")
        )
    }

    fun testMaxFilesOverflowIsReportedNotAnalyzed() {
        writeProjectFile("src/capscope/A.java", "class A {}")
        writeProjectFile("src/capscope/B.java", "class B {}")
        writeProjectFile("src/capscope/C.java", "class C {}")

        val result = callTool {
            putJsonArray("paths") { add("src/capscope") }
            put("maxFiles", 1)
        }

        assertToolSucceeded("maxFiles overflow is reported in-band", result)
        val payload = decodeFinal(result)

        assertFalse("files beyond maxFiles must fail the completeness gate", payload.complete)
        assertEquals(3, payload.filesConsidered)
        assertEquals(1, payload.filesAnalyzed)
        assertEquals(2, payload.filesNotAnalyzed)
        assertEquals(2, payload.incompleteFiles.size)
        payload.incompleteFiles.forEach { entry ->
            assertEquals(ProjectDiagnosticsTool.STATE_NOT_ANALYZED, entry.state)
            assertTrue(
                "not_analyzed reason must name the maxFiles limit, got: ${entry.reason}",
                entry.reason.orEmpty().contains("maxFiles")
            )
        }
        // Deterministic order: files are analyzed sorted by path, so A is the analyzed one.
        assertTrue(payload.incompleteFiles.none { it.file == "src/capscope/A.java" })
    }

    fun testOperationTimeoutMarksRemainingFilesNotAnalyzed() {
        writeProjectFile("src/slowscope/Slow.java", "class Slow {}")
        writeProjectFile("src/slowscope/Y.java", "class Y {}")
        writeProjectFile("src/slowscope/Z.java", "class Z {}")

        val service = DiagnosticsAnalysisService.getInstance(project)
        service.closedFileAnalysisOverride = { request ->
            if (request.filePath.endsWith("Slow.java")) {
                delay(1_500)
            }
            emptyList()
        }

        val result = callTool {
            putJsonArray("paths") { add("src/slowscope") }
            put("timeoutSeconds", 1)
            put("waitSeconds", 10)
        }

        assertToolSucceeded("an operation timeout is reported in-band", result)
        val payload = decodeFinal(result)

        assertEquals(ProjectDiagnosticsTool.STATUS_TIMED_OUT, payload.status)
        assertFalse("a timed-out run must not claim complete coverage", payload.complete)
        assertTrue("files cut off by the deadline must be counted", payload.filesNotAnalyzed >= 2)
        assertTrue("at most the slow file was analyzed", payload.filesAnalyzed <= 1)
        assertTrue(
            "not_analyzed reasons must name timeoutSeconds, got: ${payload.incompleteFiles}",
            payload.incompleteFiles.filter { it.state == ProjectDiagnosticsTool.STATE_NOT_ANALYZED }
                .all { it.reason.orEmpty().contains("timeoutSeconds") }
        )
    }

    fun testLongPollReturnsRunningRefusesConcurrentStartThenDeliversResult() {
        writeProjectFile("src/gatescope/Gated.java", "class Gated {}")

        val gate = CompletableDeferred<Unit>()
        val service = DiagnosticsAnalysisService.getInstance(project)
        service.closedFileAnalysisOverride = {
            gate.await()
            emptyList()
        }

        val started = callTool {
            putJsonArray("paths") { add("src/gatescope") }
            put("waitSeconds", 0)
        }
        assertToolSucceeded("a zero-wait start must return a running status", started)
        val running = decodeRunning(started)
        assertEquals(ProjectDiagnosticsTool.STATUS_RUNNING, running.status)
        assertEquals(1, running.filesConsidered)
        assertTrue("the running message must tell the agent how to poll", running.message.contains(running.analysisId))

        val concurrent = callTool { putJsonArray("paths") { add("src/gatescope") } }
        assertToolFailed("starting a second analysis while one runs must be refused", concurrent)
        assertTrue(
            "the refusal must carry the running analysisId, got: ${toolText(concurrent)}",
            toolText(concurrent).contains(running.analysisId)
        )

        gate.complete(Unit)
        val payload = pollUntilFinal(running.analysisId)
        assertTrue(payload.complete)
        assertEquals(1, payload.filesAnalyzed)

        val afterCollection = callTool { put("analysisId", running.analysisId) }
        assertToolFailed("collected analyses must be removed from the registry", afterCollection)
    }

    fun testUnknownAnalysisIdIsRejected() {
        val result = callTool { put("analysisId", "no-such-analysis") }
        assertToolFailed("polling an unknown analysisId must fail", result)
        assertTrue(
            "error must echo the analysisId so the agent can see what it asked for",
            toolText(result).contains("no-such-analysis")
        )
    }

    fun testPathNotFoundFailsFast() {
        val result = callTool { putJsonArray("paths") { add("does/not/exist") } }
        assertToolFailed("an unresolvable path must fail before any analysis starts", result)
        assertTrue(toolText(result).contains("does/not/exist"))
    }

    fun testDefaultScopeCoversContentRootsWithoutExplicitPaths() {
        writeProjectFile(
            "src/DefaultScopeBroken.java",
            """
            class DefaultScopeBroken {
                void test() {
                    UnknownType value = null;
                }
            }
            """.trimIndent()
        )
        writeProjectFile("src/DefaultScopeClean.java", "class DefaultScopeClean {}")

        val result = callTool { }

        assertToolSucceeded("default project scope must succeed", result)
        val payload = decodeFinal(result)

        assertTrue("default scope must cover the registered content root", payload.filesConsidered >= 2)
        assertTrue(payload.filesAnalyzed >= 2)
        assertTrue(
            "default scope must surface problems from unopened files, got: ${payload.problems}",
            payload.problems.any {
                it.file == "src/DefaultScopeBroken.java" &&
                        (it.message.contains("UnknownType") || it.message.contains("Cannot resolve"))
            }
        )
    }
}

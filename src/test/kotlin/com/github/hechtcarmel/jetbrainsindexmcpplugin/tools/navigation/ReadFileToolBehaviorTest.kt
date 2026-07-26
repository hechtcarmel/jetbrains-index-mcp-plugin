package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ReadFileResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReadFileToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseResult(result: CallToolResult): ReadFileResult =
        json.decodeFromString(toolText(result))

    private fun writeFiveLineFile(relativePath: String) {
        writeProjectFile(relativePath, "line1\nline2\nline3\nline4\nline5")
    }

    fun testStartLineBeyondEndOfFileReturnsErrorNamingLineCount() = runBlocking {
        writeFiveLineFile("src/Five.txt")

        val result = ReadFileTool().execute(project, buildJsonObject {
            put("file", "src/Five.txt")
            put("startLine", 100)
        })

        assertToolFailed("startLine beyond EOF must be an error, not an empty success", result)
        assertEquals("startLine 100 is beyond end of file (5 lines)", toolText(result))
    }

    fun testOverhangingEndLineIsClampedInResult() = runBlocking {
        writeFiveLineFile("src/FiveMore.txt")

        val result = ReadFileTool().execute(project, buildJsonObject {
            put("file", "src/FiveMore.txt")
            put("startLine", 3)
            put("endLine", 200)
        })

        assertToolSucceeded("Overhanging range should still succeed", result)
        val payload = parseResult(result)
        assertEquals(3, payload.startLine)
        assertEquals("endLine must echo the clamped last line, not the raw request", 5, payload.endLine)
        assertEquals(5, payload.lineCount)
        assertEquals("Content must be exactly the real trailing lines", "line3\nline4\nline5", payload.content)
    }

    fun testInRangeRequestEchoesRequestedRange() = runBlocking {
        writeFiveLineFile("src/FiveExact.txt")

        val result = ReadFileTool().execute(project, buildJsonObject {
            put("file", "src/FiveExact.txt")
            put("startLine", 2)
            put("endLine", 4)
        })

        assertToolSucceeded("In-range read should succeed", result)
        val payload = parseResult(result)
        assertEquals(2, payload.startLine)
        assertEquals(4, payload.endLine)
        assertEquals("line2\nline3\nline4", payload.content)
    }

    fun testStartLineAtLastLineSucceeds() = runBlocking {
        writeFiveLineFile("src/FiveLast.txt")

        val result = ReadFileTool().execute(project, buildJsonObject {
            put("file", "src/FiveLast.txt")
            put("startLine", 5)
        })

        assertToolSucceeded("startLine equal to the line count is in range", result)
        val payload = parseResult(result)
        assertEquals(5, payload.startLine)
        assertEquals(5, payload.endLine)
        assertEquals("line5", payload.content)
    }
}

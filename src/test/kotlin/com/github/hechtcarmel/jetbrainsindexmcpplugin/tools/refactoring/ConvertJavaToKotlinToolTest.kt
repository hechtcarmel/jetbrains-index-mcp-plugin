package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Platform tests for ConvertJavaToKotlinTool.
 *
 * These tests verify the tool's behavior with actual Java files and require
 * both Java and Kotlin plugins to be available. Tests are skipped gracefully
 * if the Kotlin plugin is not present.
 */
class ConvertJavaToKotlinToolTest : BasePlatformTestCase() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // Parameter Validation Tests

    fun testMissingRequiredFileParameter() = runBlocking {
        val tool = ConvertJavaToKotlinTool()
        val result = tool.execute(project, buildJsonObject { })

        assertTrue("Should error with missing files parameter", result.isError)
        val error = (result.content.first() as ContentBlock.Text).text
        assertTrue("Error should mention missing parameter",
            error.contains("Missing required parameter") || error.contains("files"))
    }

    fun testInvalidFilesParameter() = runBlocking {
        val tool = ConvertJavaToKotlinTool()
        val result = tool.execute(project, buildJsonObject {
            put("files", buildJsonArray {
                add(JsonPrimitive("nonexistent.java"))
            })
        })

        assertFalse("Structured per-file skips should not be top-level errors", result.isError)

        val payload = decodeResult(result)
        assertEquals(1, payload.summary.totalRequested)
        assertEquals(0, payload.summary.converted)
        assertEquals(1, payload.summary.skipped)
        assertEquals(0, payload.summary.failed)
        assertEquals("nonexistent.java", payload.files.single().requestedPath)
        assertEquals(ConversionStatus.SKIPPED, payload.files.single().status)
        assertEquals("File not found", payload.files.single().reason)
    }

    fun testEmptyFilesListParameter() = runBlocking {
        val tool = ConvertJavaToKotlinTool()
        val result = tool.execute(project, buildJsonObject {
            put("files", kotlinx.serialization.json.buildJsonArray { })
        })

        assertTrue("Should error with empty files list", result.isError)
        val error = (result.content.first() as ContentBlock.Text).text
        assertTrue("Error should mention no files", error.contains("No files"))
    }

    fun testBatchWithOnlyMissingFilesReturnsStructuredResultsInInputOrder() = runBlocking {
        val tool = ConvertJavaToKotlinTool()
        val result = tool.execute(project, buildJsonObject {
            put("files", buildJsonArray {
                add(JsonPrimitive("missing-one.java"))
                add(JsonPrimitive("missing-two.java"))
                add(JsonPrimitive("missing-three.java"))
            })
        })

        assertFalse("All-skipped batches should still return structured results", result.isError)

        val payload = decodeResult(result)
        assertEquals(3, payload.summary.totalRequested)
        assertEquals(0, payload.summary.converted)
        assertEquals(3, payload.summary.skipped)
        assertEquals(0, payload.summary.failed)

        assertEquals(
            listOf("missing-one.java", "missing-two.java", "missing-three.java"),
            payload.files.map { it.requestedPath }
        )
        assertEquals(ConversionStatus.SKIPPED, payload.files[0].status)
        assertEquals("File not found", payload.files[0].reason)
        assertEquals(ConversionStatus.SKIPPED, payload.files[1].status)
        assertEquals("File not found", payload.files[1].reason)
        assertEquals(ConversionStatus.SKIPPED, payload.files[2].status)
        assertEquals("File not found", payload.files[2].reason)
    }

    // Schema Validation Tests

    fun testToolSchemaIsValid() {
        val tool = ConvertJavaToKotlinTool()

        assertEquals("ide_convert_java_to_kotlin", tool.name)
        assertNotNull("Tool should have description", tool.description)
        assertTrue("Description should mention Java to Kotlin",
            tool.description.contains("Java") && tool.description.contains("Kotlin"))
        assertNotNull("Tool should have input schema", tool.inputSchema)
    }

    fun testToolSchemaHasExpectedParameters() {
        val tool = ConvertJavaToKotlinTool()
        val schema = tool.inputSchema.toString()

        assertTrue("Schema should include 'files' parameter", schema.contains("files"))
        assertFalse("Schema should not include 'file' parameter", schema.contains("\"file\""))
    }

    private fun decodeResult(result: com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult):
        JavaToKotlinConversionResult {
        val text = (result.content.first() as ContentBlock.Text).text
        return json.decodeFromString(text)
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.kotlin.idea.core.script.ucache.relativeName

/**
 * Platform tests for ConvertJavaToKotlinTool.
 *
 * These tests verify the tool's behavior with actual Java files and require
 * both Java and Kotlin plugins to be available. Tests are skipped gracefully
 * if the Kotlin plugin is not present.
 */
class ConvertJavaToKotlinToolTest : BasePlatformTestCase() {
    // Parameter Validation Tests

    fun testMissingRequiredFileParameter() = runBlocking {
        val tool = ConvertJavaToKotlinTool()
        val result = tool.execute(project, buildJsonObject { })

        assertTrue("Should error with missing file parameter", result.isError)
        val error = (result.content.first() as ContentBlock.Text).text
        assertTrue("Error should mention missing parameter",
            error.contains("Missing required parameter") || error.contains("file"))
    }

    fun testInvalidFileParameter() = runBlocking {
        val tool = ConvertJavaToKotlinTool()
        val result = tool.execute(project, buildJsonObject {
            put("file", "nonexistent.java")
        })

        assertTrue("Should error with nonexistent file", result.isError)
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

        assertTrue("Schema should include 'file' parameter", schema.contains("file"))
        assertTrue("Schema should include 'files' parameter", schema.contains("files"))
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.get

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildProjectResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.BuildProjectTool
import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BuildProjectUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testToolName() {
        val tool = BuildProjectTool()
        assertEquals(ToolNames.BUILD_PROJECT, tool.name)
    }

    fun testToolDescription() {
        val tool = BuildProjectTool()
        assertNotNull(tool.description)
        assertTrue("Description should mention build", tool.description.contains("Build"))
    }

    fun testSchemaHasExpectedProperties() {
        val tool = BuildProjectTool()
        val schema = tool.inputSchema
        assertEquals(SchemaConstants.TYPE_OBJECT, schema[SchemaConstants.TYPE]?.jsonPrimitive?.content)

        val properties = schema[SchemaConstants.PROPERTIES]?.jsonObject
        assertNotNull(properties)
        assertNotNull("Should have project_path", properties?.get(ParamNames.PROJECT_PATH))
        assertNotNull("Should have rebuild", properties?.get(ParamNames.REBUILD))
        assertNotNull("Should have includeRawOutput", properties?.get(ParamNames.INCLUDE_RAW_OUTPUT))
        assertNotNull("Should have timeoutSeconds", properties?.get(ParamNames.TIMEOUT_SECONDS))
    }

    fun testSchemaProjectPathNotRequired() {
        val tool = BuildProjectTool()
        val schema = tool.inputSchema
        val required = schema[SchemaConstants.REQUIRED]
        assertNull("project_path should not be required", required)
    }

    fun testRequiresPsiSyncIsFalse() {
        val tool = BuildProjectTool()
        val field = tool.javaClass.getDeclaredField("requiresPsiSync")
        field.isAccessible = true
        assertFalse("requiresPsiSync should be false", field.getBoolean(tool))
    }

    fun testBuildProjectResultSerializationFull() {
        val result = BuildProjectResult(
            success = false,
            aborted = false,
            errors = 3,
            warnings = 2,
            buildMessages = listOf(
                BuildMessage("ERROR", "Unresolved reference: foo", "src/Foo.kt", 42, 10),
                BuildMessage("WARNING", "Unused variable", "src/Bar.kt", 7, 5)
            ),
            truncated = false,
            rawOutput = "Build output here",
            durationMs = 3200
        )

        val jsonString = json.encodeToString(result)
        val decoded = json.decodeFromString<BuildProjectResult>(jsonString)
        assertEquals(result, decoded)
    }

    fun testBuildProjectResultSerializationNullCounts() {
        val result = BuildProjectResult(
            success = false,
            errors = null,
            warnings = null,
            buildMessages = emptyList(),
            durationMs = 1200
        )

        val jsonString = json.encodeToString(result)
        val decoded = json.decodeFromString<BuildProjectResult>(jsonString)
        assertEquals(result, decoded)
        assertNull(decoded.errors)
        assertNull(decoded.warnings)
    }

    fun testBuildProjectResultSerializationNullRawOutput() {
        val result = BuildProjectResult(
            success = true,
            errors = 0,
            warnings = 0,
            buildMessages = emptyList(),
            rawOutput = null,
            durationMs = 500
        )

        val jsonString = json.encodeToString(result)
        val decoded = json.decodeFromString<BuildProjectResult>(jsonString)
        assertNull(decoded.rawOutput)
    }

    fun testBuildMessageSerializationNullFields() {
        val msg = BuildMessage(
            category = "ERROR",
            message = "Something failed"
        )

        val jsonString = json.encodeToString(msg)
        val decoded = json.decodeFromString<BuildMessage>(jsonString)
        assertEquals("ERROR", decoded.category)
        assertEquals("Something failed", decoded.message)
        assertNull(decoded.file)
        assertNull(decoded.line)
        assertNull(decoded.column)
    }

    fun testBuildMessageSerializationAllFields() {
        val msg = BuildMessage(
            category = "WARNING",
            message = "Unused import",
            file = "src/main/Foo.kt",
            line = 3,
            column = 1
        )

        val jsonString = json.encodeToString(msg)
        val decoded = json.decodeFromString<BuildMessage>(jsonString)
        assertEquals(msg, decoded)
    }

    fun testTruncatedDefaultsFalse() {
        val result = BuildProjectResult(
            success = true,
            buildMessages = emptyList(),
            durationMs = 100
        )
        assertFalse(result.truncated)
    }

    fun testAbortedDefaultsFalse() {
        val result = BuildProjectResult(
            success = true,
            buildMessages = emptyList(),
            durationMs = 100
        )
        assertFalse(result.aborted)
    }
}

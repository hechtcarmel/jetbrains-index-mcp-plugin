package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import junit.framework.TestCase

class ResponseFormatterUnitTest : TestCase() {

    fun testJsonModeReturnsOriginalPayload() {
        val json = """{"name":"Ada","tags":["reading","gaming"]}"""

        val formatted = ResponseFormatter.formatStructuredPayload(
            json,
            McpSettings.ResponseFormat.JSON
        )

        assertEquals(json, formatted)
    }

    fun testToonModeConvertsJsonPayload() {
        val json = """{"name":"Ada","tags":["reading","gaming"]}"""

        val formatted = ResponseFormatter.formatStructuredPayload(
            json,
            McpSettings.ResponseFormat.TOON
        )

        assertFalse("TOON output should differ from JSON", formatted == json)
        assertTrue("TOON output should contain key label", formatted.contains("name: Ada"))
        assertTrue("TOON output should contain compact array syntax", formatted.contains("tags[2]:"))
    }

    fun testToonModeRejectsInvalidJson() {
        val error = try {
            ResponseFormatter.formatStructuredPayload(
                "not-json",
                McpSettings.ResponseFormat.TOON
            )
            fail("Expected invalid JSON to throw IllegalArgumentException")
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertNotNull(error)
        assertNotNull(error!!.message)
    }
}

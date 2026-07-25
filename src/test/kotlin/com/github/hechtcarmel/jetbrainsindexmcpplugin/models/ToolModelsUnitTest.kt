package com.github.hechtcarmel.jetbrainsindexmcpplugin.models

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestRunEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ToolModelsUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testTestRunEntryStatusSerializesToLowercaseWireValue() {
        val expectedWire = mapOf(
            TestStatus.PASSED to "passed",
            TestStatus.FAILED to "failed",
            TestStatus.ERROR to "error",
            TestStatus.SKIPPED to "skipped"
        )

        expectedWire.forEach { (status, wire) ->
            val entry = TestRunEntry(name = "com.example.MyTest.test", status = status, errorMessage = null)
            val serialized = json.encodeToString(entry)

            assertTrue(
                "TestStatus.$status should serialize as \"$wire\", got: $serialized",
                serialized.contains("\"status\":\"$wire\"")
            )
            assertEquals(status, json.decodeFromString<TestRunEntry>(serialized).status)
        }
    }
}

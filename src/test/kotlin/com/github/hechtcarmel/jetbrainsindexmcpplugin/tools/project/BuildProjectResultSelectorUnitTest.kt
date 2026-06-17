package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import junit.framework.TestCase

class BuildProjectResultSelectorUnitTest : TestCase() {

    fun testSuccessWithEmptyMessagesDoesNotUseFallbackOutput() {
        val messages = BuildProjectResultSelector.selectMessages(
            buildFailed = false,
            currentMessages = emptyList(),
            failureMessages = emptyList(),
            rawOutput = "/repo/src/main.cpp:7:5: error: use of undeclared identifier 'x'",
            relativizePath = { it.removePrefix("/repo/") }
        )

        assertTrue(messages.isEmpty())
    }

    fun testFailedBuildWithCurrentCompilerMessagesReturnsCurrentMessages() {
        val current = listOf(BuildMessage("ERROR", "existing compiler message", file = "src/Main.kt"))
        val fallback = listOf(BuildMessage("ERROR", "fallback message"))

        val messages = BuildProjectResultSelector.selectMessages(
            buildFailed = true,
            currentMessages = current,
            failureMessages = fallback,
            rawOutput = "/repo/src/main.cpp:7:5: error: fallback output",
            relativizePath = { it }
        )

        assertEquals(current, messages)
    }

    fun testFailedBuildWithGenericErrorAddsParsedCompilerDiagnostics() {
        val current = listOf(BuildMessage("ERROR", "Build failed"))

        val messages = BuildProjectResultSelector.selectMessages(
            buildFailed = true,
            currentMessages = current,
            failureMessages = emptyList(),
            rawOutput = "D:/Project/app/src/VideoInfo.cpp:42:13: error: use of undeclared identifier 'foo'",
            relativizePath = { it.removePrefix("D:/Project/app/") }
        )

        assertEquals(2, messages.size)
        assertEquals(current.single(), messages[0])
        assertEquals(
            BuildMessage("ERROR", "use of undeclared identifier 'foo'", "src/VideoInfo.cpp", 42, 13),
            messages[1]
        )
    }

    fun testFailedBuildWithWarningsOnlyAddsFallbackError() {
        val current = listOf(BuildMessage("WARNING", "existing warning"))
        val fallback = listOf(BuildMessage("ERROR", "build failed"))

        val messages = BuildProjectResultSelector.selectMessages(
            buildFailed = true,
            currentMessages = current,
            failureMessages = fallback,
            rawOutput = "",
            relativizePath = { it }
        )

        assertEquals(listOf(current.single(), fallback.single()), messages)
    }

    fun testFailedEmptyBuildUsesFailureMessagesWhenRawOutputHasNoCompilerDiagnostics() {
        val failure = listOf(BuildMessage("ERROR", "finish failure"))

        val messages = BuildProjectResultSelector.selectMessages(
            buildFailed = true,
            currentMessages = emptyList(),
            failureMessages = failure,
            rawOutput = "FAILED: build stopped without a compiler location",
            relativizePath = { it }
        )

        assertEquals(failure, messages)
    }

    fun testFailedEmptyBuildPrefersParsedCompilerDiagnosticsOverGenericFailureMessages() {
        val failure = listOf(BuildMessage("ERROR", "build failed"))

        val messages = BuildProjectResultSelector.selectMessages(
            buildFailed = true,
            currentMessages = emptyList(),
            failureMessages = failure,
            rawOutput = "/repo/src/main.cpp:7:5: error: use of undeclared identifier 'x'",
            relativizePath = { it.removePrefix("/repo/") }
        )

        assertEquals(2, messages.size)
        assertEquals(BuildMessage("ERROR", "use of undeclared identifier 'x'", "src/main.cpp", 7, 5), messages[0])
        assertEquals(failure.single(), messages[1])
    }

    fun testFailedEmptyBuildParsesRecognizedFallbackOutput() {
        val messages = BuildProjectResultSelector.selectMessages(
            buildFailed = true,
            currentMessages = emptyList(),
            failureMessages = emptyList(),
            rawOutput = "/repo/src/main.cpp:7:5: error: use of undeclared identifier 'x'",
            relativizePath = { it.removePrefix("/repo/") }
        )

        assertEquals(1, messages.size)
        assertEquals("ERROR", messages[0].category)
        assertEquals("src/main.cpp", messages[0].file)
    }

    fun testFailedEmptyBuildWithUnrecognizedFallbackOutputStaysEmpty() {
        val messages = BuildProjectResultSelector.selectMessages(
            buildFailed = true,
            currentMessages = emptyList(),
            failureMessages = emptyList(),
            rawOutput = "FAILED: build stopped without a compiler location",
            relativizePath = { it }
        )

        assertTrue(messages.isEmpty())
    }
}

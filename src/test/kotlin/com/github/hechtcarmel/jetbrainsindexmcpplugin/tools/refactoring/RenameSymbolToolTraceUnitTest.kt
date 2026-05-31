package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import junit.framework.TestCase
import java.io.File

class RenameSymbolToolTraceUnitTest : TestCase() {

    fun testTraceLogDefaultsToJavaIoTmpdirFile() {
        val expected = File(System.getProperty("java.io.tmpdir"), "indexmcp-rename-trace.log").canonicalPath

        assertEquals(expected, RenameSymbolTool.defaultRenameTraceLogFile().canonicalPath)
    }

    fun testTraceGateIsDisabledByDefault() {
        assertFalse(RenameSymbolTool.isRenameTraceEnabled(systemPropertyValue = null, envValue = null))
        assertFalse(RenameSymbolTool.isRenameTraceEnabled(systemPropertyValue = "", envValue = ""))
        assertFalse(RenameSymbolTool.isRenameTraceEnabled(systemPropertyValue = "false", envValue = null))
        assertFalse(RenameSymbolTool.isRenameTraceEnabled(systemPropertyValue = null, envValue = "0"))
    }

    fun testTraceGateAcceptsSystemPropertyOrEnvVar() {
        assertTrue(RenameSymbolTool.isRenameTraceEnabled(systemPropertyValue = "true", envValue = null))
        assertTrue(RenameSymbolTool.isRenameTraceEnabled(systemPropertyValue = null, envValue = "1"))
        assertTrue(RenameSymbolTool.isRenameTraceEnabled(systemPropertyValue = null, envValue = "yes"))
    }

    fun testTraceSessionDoesNotWriteWhenGateIsDisabled() {
        val logFile = createTempFile("rename-trace", ".log")
        assertTrue(logFile.delete())

        val session = RenameSymbolTool.newRenameTraceSession(enabled = false, logFile = logFile)
        session.event("request.start", "file" to "src/TextSample.cs")

        assertFalse(logFile.exists())
    }

    fun testTraceLineFormatsCompactEventWithCorrelationAndTruncation() {
        val line = RenameSymbolTool.formatRenameTraceLine(
            correlationId = "[IndexMcp.Rename #7]",
            event = "dialog.snapshot",
            fields = linkedMapOf(
                "title" to "Rename",
                "labels" to listOf("Header", "Body with\nnewline", "x".repeat(220))
            )
        )

        assertTrue(line.contains("[IndexMcp.Rename #7] dialog.snapshot"))
        assertTrue(line.contains("title=Rename"))
        assertTrue(line.contains("Body with\\nnewline"))
        assertTrue(line.contains("…"))
    }

    fun testTraceSessionWritesWhenGateIsEnabled() {
        val logFile = createTempFile("rename-trace", ".log")
        assertTrue(logFile.delete())

        val session = RenameSymbolTool.newRenameTraceSession(enabled = true, logFile = logFile)
        session.event("dialog.snapshot", "title" to "Rename", "labels" to listOf("Header", "Body"))

        assertTrue(logFile.exists())
        val content = logFile.readText()
        assertTrue(content.contains("dialog.snapshot"))
        assertTrue(content.contains("title=Rename"))
        assertTrue(content.contains("labels=[Header, Body]"))
    }

    fun testTraceWriterSwallowsWriteErrors() {
        val tempDir = createTempFile("rename-trace", ".dir")
        assertTrue(tempDir.delete())
        assertTrue(tempDir.mkdirs())

        val session = RenameSymbolTool.newRenameTraceSession(enabled = true, logFile = tempDir)
        session.event("request.start", "file" to "src/TextSample.cs")

        assertTrue(tempDir.isDirectory)
    }
}

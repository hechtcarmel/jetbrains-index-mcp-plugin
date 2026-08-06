package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

class WatchdogInstallerUnitTest : TestCase() {

    fun testWatchdogDirIsUnderUserHome() {
        val dir = WatchdogInstaller.getWatchdogDir()
        assertTrue(dir.path.contains(".ide-index-mcp"))
        assertTrue(dir.path.contains("watchdog"))
    }

    fun testScriptResourceExists() {
        // Both scripts should be bundled
        assertNotNull(WatchdogInstaller::class.java.getResourceAsStream("/watchdog/watchdog.sh"))
        assertNotNull(WatchdogInstaller::class.java.getResourceAsStream("/watchdog/watchdog.ps1"))
    }

    fun testScriptContainsPlaceholders() {
        val shContent = WatchdogInstaller::class.java.getResourceAsStream("/watchdog/watchdog.sh")!!
            .bufferedReader().readText()
        assertTrue(shContent.contains("__JSTACK_PATH__"))
        assertTrue(shContent.contains("__IDE_LAUNCH_CMD__"))
        assertTrue(shContent.contains("__IDE_PID_PATTERN__"))

        val ps1Content = WatchdogInstaller::class.java.getResourceAsStream("/watchdog/watchdog.ps1")!!
            .bufferedReader().readText()
        assertTrue(ps1Content.contains("__JSTACK_PATH__"))
        assertTrue(ps1Content.contains("__IDE_LAUNCH_CMD__"))
        assertTrue(ps1Content.contains("__IDE_PID_PATTERN__"))
    }

    fun testJstackPathResolution() {
        val path = WatchdogInstaller.resolveJstackPath()
        // In test environment, JBR may or may not be present
        // but the path should at least be attempted
        if (path != null) {
            assertTrue("Should contain jbr", path.contains("jbr"))
            assertTrue("Should end with jstack",
                path.endsWith("jstack") || path.endsWith("jstack.exe"))
        }
    }

    fun testIdeLaunchCmdIsNotEmpty() {
        val cmd = WatchdogInstaller.resolveIdeLaunchCmd()
        assertTrue("Launch command should not be blank", cmd.isNotBlank())
    }

    fun testIdePidPatternIsNotEmpty() {
        val pattern = WatchdogInstaller.resolveIdePidPattern()
        assertTrue("PID pattern should not be blank", pattern.isNotBlank())
        assertTrue("PID pattern should contain 'idea'", pattern.lowercase().contains("idea"))
    }

    fun testLogFilePath() {
        val logFile = WatchdogInstaller.getLogFile()
        assertTrue(logFile.path.contains("watchdog"))
        assertTrue(logFile.name == "watchdog.log")
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Installs or removes the EDT freeze watchdog script and its OS-level scheduler entry.
 *
 * The watchdog detects frozen IntelliJ EDT threads via jstack and auto-restarts the IDE.
 * Script templates are bundled as JAR resources under /watchdog/ and configured at install
 * time with paths specific to the running IDE instance.
 */
object WatchdogInstaller {

    private val LOG = logger<WatchdogInstaller>()

    private const val WATCHDOG_DIR_NAME = "watchdog"
    private const val BASE_DIR_NAME = ".ide-index-mcp"
    private const val CRONTAB_MARKER = "# ide-index-mcp-watchdog"

    data class WatchdogResult(val success: Boolean, val message: String)

    /**
     * Returns the watchdog directory: ~/.ide-index-mcp/watchdog/
     */
    fun getWatchdogDir(): File {
        return File(System.getProperty("user.home"), "$BASE_DIR_NAME/$WATCHDOG_DIR_NAME")
    }

    /**
     * Returns the watchdog log file path.
     */
    fun getLogFile(): File {
        return File(getWatchdogDir(), "watchdog.log")
    }

    /**
     * Resolves the jstack binary path from the JBR bundled with the running IDE.
     * Returns null if the binary does not exist on disk.
     */
    fun resolveJstackPath(): String? {
        val homePath = PathManager.getHomePath()
        val jstackRelative = when {
            SystemInfo.isMac -> "jbr/Contents/Home/bin/jstack"
            SystemInfo.isWindows -> "jbr\\bin\\jstack.exe"
            else -> "jbr/bin/jstack" // Linux and other Unix
        }
        val jstack = File(homePath, jstackRelative)
        return if (jstack.exists()) jstack.absolutePath else null
    }

    /**
     * Returns the OS-specific command to launch the current IDE.
     */
    fun resolveIdeLaunchCmd(): String {
        val homePath = PathManager.getHomePath()
        return when {
            SystemInfo.isMac -> {
                val appName = extractMacAppName(homePath)
                "open -a \"$appName\""
            }
            SystemInfo.isWindows -> {
                "\"${homePath}\\bin\\idea64.exe\""
            }
            else -> {
                "nohup \"${homePath}/bin/idea\" > /dev/null 2>&1"
            }
        }
    }

    /**
     * Returns the OS-specific process name pattern used to find the IDE process via pgrep/Get-Process.
     */
    fun resolveIdePidPattern(): String {
        val homePath = PathManager.getHomePath()
        return when {
            SystemInfo.isMac -> {
                val appName = extractMacAppName(homePath)
                "$appName.app/Contents/MacOS/idea"
            }
            SystemInfo.isWindows -> "idea64"
            else -> "$homePath/bin/idea"
        }
    }

    /**
     * Returns true if the watchdog script exists on disk.
     */
    fun isInstalled(): Boolean {
        val scriptFile = getScriptFile()
        return scriptFile.exists()
    }

    /**
     * Installs the watchdog: extracts the script template, configures it with
     * IDE-specific paths, writes it to disk, and registers an OS-level scheduled task.
     */
    fun install(): WatchdogResult {
        return try {
            val jstackPath = resolveJstackPath()
                ?: return WatchdogResult(false, "jstack not found in JBR at ${PathManager.getHomePath()}")

            val watchdogDir = getWatchdogDir()
            watchdogDir.mkdirs()

            val resourcePath = if (SystemInfo.isWindows) "/watchdog/watchdog.ps1" else "/watchdog/watchdog.sh"
            val templateContent = javaClass.getResourceAsStream(resourcePath)
                ?.bufferedReader()?.readText()
                ?: return WatchdogResult(false, "Watchdog script resource not found: $resourcePath")

            val configuredContent = templateContent
                .replace("__JSTACK_PATH__", jstackPath)
                .replace("__IDE_LAUNCH_CMD__", resolveIdeLaunchCmd())
                .replace("__IDE_PID_PATTERN__", resolveIdePidPattern())

            val scriptFile = getScriptFile()
            scriptFile.writeText(configuredContent)

            if (!SystemInfo.isWindows) {
                scriptFile.setExecutable(true)
            }

            val scheduleResult = registerScheduledTask(scriptFile)
            if (!scheduleResult.success) {
                return scheduleResult
            }

            LOG.info("Watchdog installed to ${scriptFile.absolutePath}")
            WatchdogResult(true, "Watchdog installed to ${scriptFile.absolutePath}")
        } catch (e: Exception) {
            LOG.error("Failed to install watchdog", e)
            WatchdogResult(false, "Failed to install watchdog: ${e.message}")
        }
    }

    /**
     * Uninstalls the watchdog: removes the OS-level scheduled task and deletes
     * the watchdog directory contents.
     */
    fun uninstall(): WatchdogResult {
        return try {
            val unscheduleResult = unregisterScheduledTask()
            if (!unscheduleResult.success) {
                return unscheduleResult
            }

            val watchdogDir = getWatchdogDir()
            if (watchdogDir.exists()) {
                watchdogDir.listFiles()?.forEach { it.delete() }
            }

            LOG.info("Watchdog uninstalled")
            WatchdogResult(true, "Watchdog uninstalled")
        } catch (e: Exception) {
            LOG.error("Failed to uninstall watchdog", e)
            WatchdogResult(false, "Failed to uninstall watchdog: ${e.message}")
        }
    }

    // --- Private helpers ---

    private fun getScriptFile(): File {
        val fileName = if (SystemInfo.isWindows) "watchdog.ps1" else "watchdog.sh"
        return File(getWatchdogDir(), fileName)
    }

    /**
     * Extracts the macOS application name from PathManager.getHomePath().
     * Example: /Applications/IntelliJ IDEA.app/Contents -> IntelliJ IDEA
     */
    private fun extractMacAppName(homePath: String): String {
        val appIndex = homePath.indexOf(".app/")
        if (appIndex == -1) return "IntelliJ IDEA"
        val beforeApp = homePath.substring(0, appIndex)
        val lastSlash = beforeApp.lastIndexOf('/')
        return if (lastSlash >= 0) beforeApp.substring(lastSlash + 1) else beforeApp
    }

    private fun registerScheduledTask(scriptFile: File): WatchdogResult {
        return if (SystemInfo.isWindows) {
            registerWindowsTask(scriptFile)
        } else {
            registerCrontab(scriptFile)
        }
    }

    private fun unregisterScheduledTask(): WatchdogResult {
        return if (SystemInfo.isWindows) {
            unregisterWindowsTask()
        } else {
            unregisterCrontab()
        }
    }

    private fun registerCrontab(scriptFile: File): WatchdogResult {
        return try {
            val scriptPath = scriptFile.absolutePath
            // Remove any existing watchdog entries first
            val existing = ProcessBuilder("crontab", "-l")
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()

            val filtered = existing.lines()
                .filter { !it.contains(CRONTAB_MARKER) }
                .joinToString("\n")
                .trimEnd('\n')

            val newEntries = listOf(
                "* * * * * \"$scriptPath\" $CRONTAB_MARKER",
                "* * * * * sleep 30 && \"$scriptPath\" $CRONTAB_MARKER"
            )

            val newCrontab = if (filtered.isBlank()) {
                newEntries.joinToString("\n") + "\n"
            } else {
                filtered + "\n" + newEntries.joinToString("\n") + "\n"
            }

            val process = ProcessBuilder("crontab", "-")
                .redirectErrorStream(true).start()
            process.outputStream.use { it.write(newCrontab.toByteArray()) }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                WatchdogResult(true, "Crontab entries registered")
            } else {
                WatchdogResult(false, "Failed to register crontab (exit code $exitCode)")
            }
        } catch (e: Exception) {
            WatchdogResult(false, "Failed to register crontab: ${e.message}")
        }
    }

    private fun unregisterCrontab(): WatchdogResult {
        return try {
            val existing = ProcessBuilder("crontab", "-l")
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()

            val filtered = existing.lines()
                .filter { !it.contains(CRONTAB_MARKER) }
                .joinToString("\n")
                .trimEnd('\n')

            val newCrontab = if (filtered.isBlank()) "" else filtered + "\n"

            val process = ProcessBuilder("crontab", "-")
                .redirectErrorStream(true).start()
            process.outputStream.use { it.write(newCrontab.toByteArray()) }
            process.waitFor()

            WatchdogResult(true, "Crontab entries removed")
        } catch (e: Exception) {
            WatchdogResult(false, "Failed to remove crontab entries: ${e.message}")
        }
    }

    private fun registerWindowsTask(scriptFile: File): WatchdogResult {
        return try {
            val taskName = "IdeIndexMcpWatchdog"
            val process = ProcessBuilder(
                "schtasks", "/Create",
                "/TN", taskName,
                "/TR", "powershell.exe -ExecutionPolicy Bypass -File \"${scriptFile.absolutePath}\"",
                "/SC", "MINUTE",
                "/MO", "1",
                "/F"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                WatchdogResult(true, "Windows Task Scheduler task registered")
            } else {
                WatchdogResult(false, "Failed to register Task Scheduler task: $output")
            }
        } catch (e: Exception) {
            WatchdogResult(false, "Failed to register Task Scheduler task: ${e.message}")
        }
    }

    private fun unregisterWindowsTask(): WatchdogResult {
        return try {
            val taskName = "IdeIndexMcpWatchdog"
            val process = ProcessBuilder(
                "schtasks", "/Delete",
                "/TN", taskName,
                "/F"
            ).redirectErrorStream(true).start()

            process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Don't fail if task didn't exist
            WatchdogResult(true, "Windows Task Scheduler task removed")
        } catch (e: Exception) {
            WatchdogResult(false, "Failed to remove Task Scheduler task: ${e.message}")
        }
    }
}

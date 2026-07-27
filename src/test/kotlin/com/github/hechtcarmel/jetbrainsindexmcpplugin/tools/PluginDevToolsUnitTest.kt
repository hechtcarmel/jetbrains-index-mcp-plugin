package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.get

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.InstallPluginTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.RestartIdeTool
import junit.framework.TestCase
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginDevToolsUnitTest : TestCase() {

    fun testInstallPluginToolName() {
        assertEquals(ToolNames.INSTALL_PLUGIN, InstallPluginTool().name)
    }

    fun testInstallPluginToolPathIsOptional() {
        val required = InstallPluginTool().inputSchema["required"]
            ?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse("path must be optional — auto-detection is the default", required.contains("path"))
    }

    fun testInstallPluginToolHasProjectPath() {
        val properties = InstallPluginTool().inputSchema["properties"]?.jsonObject
        assertNotNull(properties?.get("project_path"))
    }

    fun testRestartIdeToolName() {
        assertEquals(ToolNames.RESTART_IDE, RestartIdeTool().name)
    }

    fun testRestartIdeToolHasProjectPath() {
        val properties = RestartIdeTool().inputSchema["properties"]?.jsonObject
        assertNotNull(properties?.get("project_path"))
    }

    fun testPluginDevToolsAreDisabledByDefault() {
        val defaults = McpSettings.State().disabledTools
        assertTrue("ide_install_plugin must be opt-in", defaults.contains(ToolNames.INSTALL_PLUGIN))
        assertTrue("ide_restart must be opt-in", defaults.contains(ToolNames.RESTART_IDE))
    }

    // ── Zip-slip protection (Bug: extractZip resolved entry names without ────
    // normalization, so an entry named "../x" escaped the plugins directory)

    fun testFindUnsafeZipEntryDetectsPathTraversal() = withTempDir { dir ->
        val zip = createZip(
            dir.resolve("evil.zip"),
            "my-plugin/lib/ok.jar" to "ok",
            "../evil.txt" to "pwned"
        )
        val dest = Files.createDirectory(dir.resolve("plugins"))

        assertEquals("../evil.txt", InstallPluginTool().findUnsafeZipEntry(zip, dest))
    }

    fun testFindUnsafeZipEntryDetectsAbsolutePathEntry() = withTempDir { dir ->
        val zip = createZip(dir.resolve("abs.zip"), "/tmp/abs-evil.txt" to "pwned")
        val dest = Files.createDirectory(dir.resolve("plugins"))

        assertNotNull(
            "absolute entry names must be rejected",
            InstallPluginTool().findUnsafeZipEntry(zip, dest)
        )
    }

    fun testFindUnsafeZipEntryAcceptsLegitimatePluginArchive() = withTempDir { dir ->
        // Shapes real plugin zips take: nested directories, explicit dir entries,
        // "./" prefixes, and internal ".." segments that stay inside the destination.
        val zip = createZip(
            dir.resolve("plugin.zip"),
            "my-plugin/" to null,
            "my-plugin/lib/plugin.jar" to "jar-bytes",
            "./my-plugin/META-INF/plugin.xml" to "<idea-plugin/>",
            "my-plugin/lib/../help/index.html" to "help"
        )
        val dest = Files.createDirectory(dir.resolve("plugins"))

        assertNull(InstallPluginTool().findUnsafeZipEntry(zip, dest))
    }

    fun testExtractZipRefusesTraversalEntryAndWritesNothingOutside() = withTempDir { dir ->
        val zip = createZip(dir.resolve("evil.zip"), "../evil.txt" to "pwned")
        val dest = Files.createDirectory(dir.resolve("plugins"))

        try {
            InstallPluginTool().extractZip(zip, dest)
            fail("extractZip must refuse an entry that escapes the destination directory")
        } catch (e: IllegalStateException) {
            assertTrue(
                "error must name the offending entry",
                e.message?.contains("../evil.txt") == true
            )
        }
        assertFalse(
            "traversal entry must not be written outside the destination",
            Files.exists(dir.resolve("evil.txt"))
        )
    }

    fun testExtractZipExtractsLegitimatePluginArchive() = withTempDir { dir ->
        val zip = createZip(
            dir.resolve("plugin.zip"),
            "my-plugin/" to null,
            "./my-plugin/lib/plugin.jar" to "jar-bytes",
            "my-plugin/META-INF/plugin.xml" to "<idea-plugin/>"
        )
        val dest = Files.createDirectory(dir.resolve("plugins"))

        InstallPluginTool().extractZip(zip, dest)

        assertEquals("jar-bytes", Files.readString(dest.resolve("my-plugin/lib/plugin.jar")))
        assertEquals("<idea-plugin/>", Files.readString(dest.resolve("my-plugin/META-INF/plugin.xml")))
    }

    /** Creates a zip at [target]; a null content marks a directory entry. */
    private fun createZip(target: Path, vararg entries: Pair<String, String?>): Path {
        ZipOutputStream(Files.newOutputStream(target)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(if (content == null && !name.endsWith("/")) "$name/" else name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return target
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("install-plugin-zip-slip")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

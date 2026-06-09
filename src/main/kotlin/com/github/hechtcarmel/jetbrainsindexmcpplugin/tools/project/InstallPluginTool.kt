package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.application.PathManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class InstallPluginTool : AbstractMcpTool() {

    override val requiresPsiSync = false

    override val name = "ide_install_plugin"

    override val description = """
        Install a plugin into the IDE, replacing any existing version.

        Locates the plugin zip, removes the previously installed version (if any),
        and extracts the new version into the IDE plugins directory. A restart is
        required for the change to take effect — call ide_restart after this tool
        returns, or ask the user to restart manually.

        If path is omitted, the tool searches build/distributions/*.zip in the
        active project directory (the output of ./gradlew buildPlugin). When multiple
        zip files are found, the most recently modified one is used.

        Parameters:
        - path (optional): Absolute path to the plugin zip. Defaults to auto-detection
          from the active project's build/distributions/ directory.
        - project_path (optional): Required when multiple projects are open and path
          is omitted, to identify which project's build output to use.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .stringProperty("path", "Absolute path to the plugin zip to install.")
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val zipPath = arguments["path"]?.jsonPrimitive?.content
            ?.let { Path.of(it) }
            ?: findBuildOutput(project)
            ?: return createErrorResult(
                "No plugin zip found. Run ./gradlew buildPlugin first, or supply an explicit path."
            )

        if (!zipPath.toString().endsWith(".zip")) {
            return createErrorResult("Expected a .zip file, got: $zipPath")
        }

        if (!Files.exists(zipPath)) {
            return createErrorResult("File not found: $zipPath")
        }

        val pluginId = readPluginId(zipPath)
            ?: return createErrorResult(
                "Could not read plugin ID from META-INF/plugin.xml inside $zipPath"
            )

        val pluginsDir = Path.of(PathManager.getPluginsPath())
        removeExistingInstallation(pluginsDir, pluginId)
        extractZip(zipPath, pluginsDir)

        LOG.info("installed plugin $pluginId from $zipPath")
        return createSuccessResult(
            "Plugin '$pluginId' installed from ${zipPath.fileName}. " +
            "Restart the IDE to load the updated plugin (call ide_restart)."
        )
    }

    private fun findBuildOutput(project: Project): Path? {
        val distDir = Path.of(project.basePath ?: return null, "build", "distributions")
        if (!Files.isDirectory(distDir)) return null

        return Files.list(distDir)
            .filter { it.toString().endsWith(".zip") }
            .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
            .orElse(null)
    }

    private fun readPluginId(zipPath: Path): String? = ZipFile(zipPath.toFile()).use { zip ->
        zip.entries().asSequence().find { it.name.endsWith("META-INF/plugin.xml") }?.let { entry ->
            extractPluginId(zip.getInputStream(entry).bufferedReader().readText())?.let { return it }
        }
        // Gradle Plugin 2.x+: plugin.xml is inside the main jar in lib/
        zip.entries().asSequence().filter { it.name.endsWith(".jar") }
            .firstNotNullOfOrNull { pluginIdFromJar(zip.getInputStream(it)) }
    }

    private fun pluginIdFromJar(input: InputStream): String? = ZipInputStream(input).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name == "META-INF/plugin.xml") return extractPluginId(zis.bufferedReader().readText())
            zis.closeEntry()
            entry = zis.nextEntry
        }
        null
    }

    private fun extractPluginId(pluginXml: String): String? =
        Regex("<id>([^<]+)</id>").find(pluginXml)?.groupValues?.get(1)?.trim()

    private fun removeExistingInstallation(pluginsDir: Path, pluginId: String) {
        val existing = pluginsDir.toFile().listFiles() ?: return
        for (dir in existing) {
            if (pluginIdFromInstallation(dir) == pluginId) {
                FileUtil.delete(dir)
                LOG.info("removed previous installation of $pluginId at ${dir.absolutePath}")
                return
            }
        }
    }

    private fun pluginIdFromInstallation(dir: File): String? {
        val pluginXml = File(dir, "META-INF/plugin.xml")
        if (pluginXml.exists()) return extractPluginId(pluginXml.readText())
        return File(dir, "lib").takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.extension == "jar" }
            ?.firstNotNullOfOrNull { pluginIdFromJar(it.inputStream()) }
    }

    private fun extractZip(zipPath: Path, destDir: Path) {
        ZipFile(zipPath.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val dest = destDir.resolve(entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    companion object {
        private val LOG = logger<InstallPluginTool>()
    }
}

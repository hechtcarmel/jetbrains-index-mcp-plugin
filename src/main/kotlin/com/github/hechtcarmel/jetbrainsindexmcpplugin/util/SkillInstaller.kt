package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Installs or exports the bundled companion skill for AI coding agents.
 *
 * The skill files are bundled as JAR resources under /skill/ide-index-mcp/.
 * This class reads them at runtime and writes them to disk as either:
 * - A directory (for direct installation into .claude/skills/)
 * - A zip archive with .skill or .zip extension (for export/sharing)
 */
object SkillInstaller {

    private val LOG = logger<SkillInstaller>()

    private const val SKILL_NAME = "ide-index-mcp"
    private const val RESOURCE_BASE = "/skill/$SKILL_NAME"

    private val SKILL_FILES = listOf(
        "SKILL.md",
        "references/tools-reference.md"
    )

    /**
     * Installs the skill to a directory by extracting all resource files.
     * Creates the directory structure: targetDir/ide-index-mcp/SKILL.md, etc.
     *
     * @param targetDir Parent directory (e.g., projectRoot/.claude/skills)
     * @return The created skill directory, or null on failure
     */
    fun installToDirectory(targetDir: File): File? {
        val skillDir = File(targetDir, SKILL_NAME)
        return try {
            for (relativePath in SKILL_FILES) {
                val targetFile = File(skillDir, relativePath)
                targetFile.parentFile.mkdirs()

                val resourcePath = "$RESOURCE_BASE/$relativePath"
                val content = javaClass.getResourceAsStream(resourcePath)
                    ?: run {
                        LOG.error("Skill resource not found: $resourcePath")
                        return null
                    }

                content.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            LOG.info("Installed companion skill to ${skillDir.absolutePath}")
            skillDir
        } catch (e: Exception) {
            LOG.error("Failed to install companion skill to ${skillDir.absolutePath}", e)
            null
        }
    }

    /**
     * Writes the skill as a zip archive to the given output file.
     * The zip contains: ide-index-mcp/SKILL.md, ide-index-mcp/references/tools-reference.md
     *
     * @param outputFile Target file (.skill or .zip)
     * @return true on success
     */
    fun writeZip(outputFile: File): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            outputFile.outputStream().use { fos ->
                writeZipToStream(fos)
            }
            LOG.info("Exported companion skill to ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            LOG.error("Failed to export companion skill to ${outputFile.absolutePath}", e)
            false
        }
    }

    private fun writeZipToStream(outputStream: OutputStream) {
        ZipOutputStream(outputStream).use { zos ->
            for (relativePath in SKILL_FILES) {
                val resourcePath = "$RESOURCE_BASE/$relativePath"
                val content = javaClass.getResourceAsStream(resourcePath)
                    ?: throw IllegalStateException("Skill resource not found: $resourcePath")

                val entryName = "$SKILL_NAME/$relativePath"
                zos.putNextEntry(ZipEntry(entryName))
                content.use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}

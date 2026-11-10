package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RenameModuleResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class RenameModuleTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = ToolNames.RENAME_MODULE

    override val description = """
        Rename a Maven module: directory, artifactId, parent pom module entry,
        dependency references, and child parent references across all pom.xml files.

        When renaming a parent module, child modules' <parent><artifactId> references
        are updated automatically. Use ide_refactor_rename on Java package directories
        afterwards to cascade package and import renames.
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .stringProperty("modulePath", "Absolute path to the Maven module directory to rename.", required = true)
        .stringProperty("newName", "New directory and artifactId name.", required = true)
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val modulePath = arguments["modulePath"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: modulePath")
        val newName = arguments["newName"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: newName")

        if (newName.isBlank()) return createErrorResult("newName must not be blank.")
        if (newName.contains('/') || newName.contains('\\')) {
            return createErrorResult("newName must not contain path separators.")
        }

        val moduleDir = File(modulePath)
        if (!moduleDir.exists() || !moduleDir.isDirectory) {
            return createErrorResult("Module directory does not exist: $modulePath")
        }

        val pomFile = File(moduleDir, "pom.xml")
        if (!pomFile.exists()) {
            return createErrorResult("No pom.xml found in module directory: $modulePath. This tool requires a Maven module.")
        }

        val oldDirName = moduleDir.name
        if (oldDirName == newName) {
            return createErrorResult("Module is already named '$newName'.")
        }

        val parentDir = moduleDir.parentFile
        val newDir = File(parentDir ?: moduleDir.parentFile, newName)
        if (newDir.exists()) {
            return createErrorResult("Destination already exists: ${newDir.absolutePath}")
        }

        // Sync all pom.xml files and read from Documents (not disk) to avoid stale content
        val allPomFiles = mutableListOf(pomFile)
        val parentPomFile = if (parentDir != null) File(parentDir, "pom.xml") else null
        if (parentPomFile?.exists() == true) allPomFiles.add(parentPomFile)
        val projectBase = project.basePath
        if (projectBase != null) allPomFiles.addAll(findAllPomFiles(File(projectBase)))
        val uniquePoms = allPomFiles.distinctBy { it.absolutePath }

        // Sync all POMs and read their current document content
        val documentContents = mutableMapOf<String, String>()
        for (pom in uniquePoms) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(pom.absolutePath) ?: continue
            val syncError = syncFileForEdit(project, vf)
            if (syncError != null) return syncError
            val doc = FileDocumentManager.getInstance().getDocument(vf)
            documentContents[pom.absolutePath] = doc?.text
                ?: return createErrorResult("Cannot read document for ${pom.absolutePath}")
        }

        val pomContent = documentContents[pomFile.absolutePath]
            ?: return createErrorResult("Module pom.xml not in synced documents: ${pomFile.absolutePath}")
        val oldArtifactId = extractModuleArtifactId(pomContent)
            ?: return createErrorResult("Cannot parse <artifactId> from ${pomFile.absolutePath}")
        val moduleGroupId = extractModuleGroupId(pomContent)
        val parentGroupId = extractParentGroupId(pomContent)
        val effectiveGroupId = moduleGroupId ?: parentGroupId
            ?: return createErrorResult("Cannot determine groupId for module (checked both <groupId> and <parent><groupId>)")

        // Accumulate changes per file — multiple edits to the same file are combined
        val pendingChanges = mutableMapOf<String, String>()
        var parentPomUpdated = false
        var dependencyUpdates = 0
        var childParentUpdates = 0

        // 1. Update parent pom's <module> entry
        if (parentPomFile != null && parentPomFile.exists()) {
            val text = pendingChanges[parentPomFile.absolutePath]
                ?: documentContents[parentPomFile.absolutePath]
                ?: return createErrorResult("Parent pom.xml not in synced documents: ${parentPomFile.absolutePath}")
            val updated = text.replace("<module>$oldDirName</module>", "<module>$newName</module>")
            if (updated != text) {
                pendingChanges[parentPomFile.absolutePath] = updated
                parentPomUpdated = true
            }
        }

        // 2. Update the module's own artifactId
        val currentModulePom = pendingChanges[pomFile.absolutePath] ?: pomContent
        val updatedModulePom = replaceFirstArtifactId(currentModulePom, oldArtifactId, newName)
        if (updatedModulePom != currentModulePom) {
            pendingChanges[pomFile.absolutePath] = updatedModulePom
        }

        // 3. Update child modules' <parent><artifactId> references
        val childModuleDirs = extractChildModuleNames(pomContent)
        for (childName in childModuleDirs) {
            val childPomFile = File(moduleDir, "$childName/pom.xml")
            if (!childPomFile.exists()) continue
            val childContent = pendingChanges[childPomFile.absolutePath]
                ?: documentContents[childPomFile.absolutePath]
                ?: continue
            val updated = updateParentArtifactId(childContent, oldArtifactId, newName)
            if (updated != childContent) {
                pendingChanges[childPomFile.absolutePath] = updated
                childParentUpdates++
            }
        }

        // 4. Update dependency references across all project pom.xml files
        if (projectBase != null) {
            for (otherPom in uniquePoms) {
                if (otherPom.absolutePath == pomFile.absolutePath) continue

                val otherContent = pendingChanges[otherPom.absolutePath]
                    ?: documentContents[otherPom.absolutePath]
                    ?: continue
                if (!otherContent.contains(oldArtifactId)) continue

                val updated = updateDependencyArtifactId(otherContent, effectiveGroupId, oldArtifactId, newName)
                if (updated != otherContent) {
                    pendingChanges[otherPom.absolutePath] = updated
                    dependencyUpdates++
                }
            }
        }

        // Phase 1: Apply all pom.xml changes via Document API
        val saveErrors = mutableListOf<String>()
        for ((filePath, newContent) in pendingChanges) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
            if (vf == null) {
                saveErrors.add("Cannot find VFS entry for $filePath")
                continue
            }
            val doc = FileDocumentManager.getInstance().getDocument(vf)
            if (doc == null) {
                saveErrors.add("Cannot get document for $filePath")
                continue
            }
            val writeError = suspendingWriteActionAndSave(project, "Rename module: update ${File(filePath).name}", doc) {
                doc.setText(newContent)
            }
            if (writeError != null) return writeError
        }

        if (saveErrors.isNotEmpty()) {
            return createErrorResult("Failed to update pom files: ${saveErrors.joinToString("; ")}")
        }

        // Phase 2: Rename directory via VFS
        val moduleVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(modulePath)
            ?: return createErrorResult("Cannot find module directory in VFS: $modulePath")

        val renameError = edtAction {
            try {
                WriteAction.compute<String?, Exception> {
                    moduleVf.rename(this, newName)
                    null
                }
            } catch (e: Exception) {
                "Failed to rename directory: ${e.message}"
            }
        }
        if (renameError != null) {
            val updatedFiles = pendingChanges.keys.joinToString(", ") { File(it).name }
            return createErrorResult(
                "$renameError WARNING: pom.xml files ($updatedFiles) were already updated with " +
                    "the new name '$newName'. Revert those changes or rename the directory manually."
            )
        }

        val newPath = newDir.absolutePath

        return createJsonResult(RenameModuleResult(
            oldPath = modulePath,
            newPath = newPath,
            oldArtifactId = oldArtifactId,
            newArtifactId = newName,
            groupId = effectiveGroupId,
            parentPomUpdated = parentPomUpdated,
            dependencyUpdates = dependencyUpdates,
            childParentUpdates = childParentUpdates
        ))
    }

    private fun extractModuleArtifactId(pomXml: String): String? {
        val parentEnd = pomXml.indexOf("</parent>")
        val searchIn = if (parentEnd >= 0) pomXml.substring(parentEnd) else pomXml
        return Regex("<artifactId>\\s*([^<]+?)\\s*</artifactId>").find(searchIn)?.groupValues?.get(1)
    }

    private fun extractModuleGroupId(pomXml: String): String? {
        val parentEnd = pomXml.indexOf("</parent>")
        val searchFrom = if (parentEnd >= 0) parentEnd + "</parent>".length else 0
        val depStart = pomXml.indexOf("<dependencies>", searchFrom)
        val depMgmtStart = pomXml.indexOf("<dependencyManagement>", searchFrom)
        val buildStart = pomXml.indexOf("<build>", searchFrom)
        val profilesStart = pomXml.indexOf("<profiles>", searchFrom)
        val searchEnd = listOf(depStart, depMgmtStart, buildStart, profilesStart).filter { it >= 0 }.minOrNull() ?: pomXml.length
        val topLevel = pomXml.substring(searchFrom, searchEnd)
        return Regex("<groupId>\\s*([^<]+?)\\s*</groupId>").find(topLevel)?.groupValues?.get(1)
    }

    private fun extractParentGroupId(pomXml: String): String? {
        val parentBlock = Regex("<parent>[\\s\\S]*?</parent>").find(pomXml)?.value ?: return null
        return Regex("<groupId>\\s*([^<]+?)\\s*</groupId>").find(parentBlock)?.groupValues?.get(1)
    }

    private fun extractChildModuleNames(pomXml: String): List<String> =
        Regex("<module>\\s*([^<]+?)\\s*</module>").findAll(pomXml).map { it.groupValues[1] }.toList()

    private fun replaceFirstArtifactId(pomXml: String, oldId: String, newId: String): String {
        val parentEnd = pomXml.indexOf("</parent>")
        val searchFrom = if (parentEnd >= 0) parentEnd else 0
        val target = "<artifactId>$oldId</artifactId>"
        val idx = pomXml.indexOf(target, searchFrom)
        if (idx < 0) return pomXml
        return pomXml.substring(0, idx) +
            "<artifactId>$newId</artifactId>" +
            pomXml.substring(idx + target.length)
    }

    private fun updateParentArtifactId(pomXml: String, oldParentArtifactId: String, newParentArtifactId: String): String {
        val parentPattern = Regex(
            "(<parent>[\\s\\S]*?)<artifactId>\\s*${Regex.escape(oldParentArtifactId)}\\s*</artifactId>"
        )
        val match = parentPattern.find(pomXml) ?: return pomXml
        return pomXml.substring(0, match.range.first) +
            "${match.groupValues[1]}<artifactId>$newParentArtifactId</artifactId>" +
            pomXml.substring(match.range.last + 1)
    }

    private fun updateDependencyArtifactId(pomXml: String, groupId: String, oldArtifactId: String, newArtifactId: String): String {
        val groupPattern = Regex("<groupId>\\s*${Regex.escape(groupId)}\\s*</groupId>")
        val artifactPattern = Regex("<artifactId>\\s*${Regex.escape(oldArtifactId)}\\s*</artifactId>")
        var result = replaceInBlocks(pomXml, "<dependency>", "</dependency>", groupPattern, artifactPattern, newArtifactId)
        result = replaceInBlocks(result, "<plugin>", "</plugin>", groupPattern, artifactPattern, newArtifactId)
        return result
    }

    private fun replaceInBlocks(
        xml: String, openTag: String, closeTag: String,
        groupPattern: Regex, artifactPattern: Regex, newArtifactId: String
    ): String {
        val blockPattern = Regex("${Regex.escape(openTag)}([\\s\\S]*?)${Regex.escape(closeTag)}")
        return blockPattern.replace(xml) { match ->
            val block = match.value
            if (groupPattern.containsMatchIn(block) && artifactPattern.containsMatchIn(block)) {
                artifactPattern.replace(block) { "<artifactId>$newArtifactId</artifactId>" }
            } else {
                block
            }
        }
    }

    private fun findAllPomFiles(root: File): List<File> {
        val skipDirs = setOf("target", ".git", ".idea", "node_modules")
        val result = mutableListOf<File>()
        fun walk(dir: File) {
            val pom = File(dir, "pom.xml")
            if (pom.exists()) result.add(pom)
            dir.listFiles()?.filter { it.isDirectory && it.name !in skipDirs }?.forEach { walk(it) }
        }
        walk(root)
        return result
    }
}

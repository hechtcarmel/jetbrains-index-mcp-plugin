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
import com.intellij.psi.PsiDocumentManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class RenameModuleTool : AbstractMcpTool() {

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

        val pomContent = pomFile.readText()
        val oldArtifactId = extractModuleArtifactId(pomContent)
            ?: return createErrorResult("Cannot parse <artifactId> from ${pomFile.absolutePath}")
        val moduleGroupId = extractModuleGroupId(pomContent)
        val parentGroupId = extractParentGroupId(pomContent)
        val effectiveGroupId = moduleGroupId ?: parentGroupId
            ?: return createErrorResult("Cannot determine groupId for module (checked both <groupId> and <parent><groupId>)")

        val parentPomFile = if (parentDir != null) File(parentDir, "pom.xml") else null
        var parentPomUpdated = false
        var dependencyUpdates = 0
        var childParentUpdates = 0

        val pomFilesToUpdate = mutableListOf<Pair<File, String>>()

        // 1. Update parent pom's <module> entry
        if (parentPomFile != null && parentPomFile.exists()) {
            val text = parentPomFile.readText()
            val updated = text.replace("<module>$oldDirName</module>", "<module>$newName</module>")
            if (updated != text) {
                pomFilesToUpdate.add(parentPomFile to updated)
                parentPomUpdated = true
            }
        }

        // 2. Update the module's own artifactId
        val updatedModulePom = replaceFirstArtifactId(pomContent, oldArtifactId, newName)
        if (updatedModulePom != pomContent) {
            pomFilesToUpdate.add(pomFile to updatedModulePom)
        }

        // 3. Update child modules' <parent><artifactId> references
        val childModuleDirs = extractChildModuleNames(pomContent)
        for (childName in childModuleDirs) {
            val childPomFile = File(moduleDir, "$childName/pom.xml")
            if (!childPomFile.exists()) continue
            val childContent = childPomFile.readText()
            val updated = updateParentArtifactId(childContent, oldArtifactId, newName)
            if (updated != childContent) {
                pomFilesToUpdate.add(childPomFile to updated)
                childParentUpdates++
            }
        }

        // 4. Update dependency references across all project pom.xml files
        val projectBase = project.basePath
        val processedPaths = pomFilesToUpdate.map { it.first.absolutePath }.toSet() + pomFile.absolutePath
        if (projectBase != null) {
            val allPoms = findAllPomFiles(File(projectBase))
            for (otherPom in allPoms) {
                if (otherPom.absolutePath in processedPaths) continue

                val otherContent = otherPom.readText()
                if (!otherContent.contains(oldArtifactId)) continue

                val updated = updateDependencyArtifactId(otherContent, effectiveGroupId, oldArtifactId, newName)
                if (updated != otherContent) {
                    pomFilesToUpdate.add(otherPom to updated)
                    dependencyUpdates++
                }
            }
        }

        // Phase 1: Update all pom.xml files via Document API
        val saveErrors = mutableListOf<String>()
        for ((file, newContent) in pomFilesToUpdate) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
            if (vf == null) {
                saveErrors.add("Cannot find VFS entry for ${file.absolutePath}")
                continue
            }
            val syncError = syncFileForEdit(project, vf)
            if (syncError != null) return syncError

            val doc = FileDocumentManager.getInstance().getDocument(vf)
            if (doc == null) {
                saveErrors.add("Cannot get document for ${file.absolutePath}")
                continue
            }
            val writeError = suspendingWriteActionAndSave(project, "Rename module: update ${file.name}", doc) {
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
        if (renameError != null) return createErrorResult(renameError)

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
        val searchIn = if (parentEnd >= 0) pomXml.substring(parentEnd) else pomXml
        return Regex("<groupId>\\s*([^<]+?)\\s*</groupId>").find(searchIn)?.groupValues?.get(1)
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
        val depPattern = Regex(
            "(<dependency>[\\s\\S]*?<groupId>\\s*${Regex.escape(groupId)}\\s*</groupId>[\\s\\S]*?)<artifactId>\\s*${Regex.escape(oldArtifactId)}\\s*</artifactId>"
        )
        return depPattern.replace(pomXml) { match ->
            "${match.groupValues[1]}<artifactId>$newArtifactId</artifactId>"
        }
    }

    private fun findAllPomFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File) {
            val pom = File(dir, "pom.xml")
            if (pom.exists()) result.add(pom)
            dir.listFiles()?.filter { it.isDirectory && it.name != "target" && it.name != ".git" && it.name != "node_modules" }
                ?.forEach { walk(it) }
        }
        walk(root)
        return result
    }
}

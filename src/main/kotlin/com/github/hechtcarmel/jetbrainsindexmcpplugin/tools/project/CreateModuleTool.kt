package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.io.File

class CreateModuleTool : AbstractMcpTool() {

    companion object {
        private const val MODULE_TYPE_WEB = "WEB_MODULE"
    }

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_create_module"

    override val description = """
        Add a directory as an IntelliJ module with a content root, enabling code intelligence
        for non-Maven projects (TypeScript, plain directories, etc.).

        Use this when ide_import_modules (Maven-only) does not apply. After adding a module,
        ide_find_references, ide_diagnostics, and other tools will cover files in that directory.

        For Maven projects, prefer ide_import_modules which sets up the full reactor relationship.
        For Gradle projects, use ide_open_project to open the Gradle project directly.

        Parameters:
        - path (required): absolute directory path to add as a module content root
        - name (optional): module name, defaults to the directory name
        - excludes (optional): array of relative directory paths to mark as excluded
          (e.g. ["node_modules", "dist", ".next"])
        - project_path (optional): selects which project window to add the module to

        Example: {"path": "/Users/dev/my-app", "excludes": ["node_modules", "dist"]}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .stringProperty("path",
            "Absolute directory path to add as a module content root.", required = true)
        .stringProperty("name",
            "Module name. Defaults to the directory name if omitted.")
        .property("excludes", kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("array"))
            put("description", kotlinx.serialization.json.JsonPrimitive(
                "Relative directory paths within the module to mark as excluded " +
                "(e.g. [\"node_modules\", \"dist\"]). Excluded directories are " +
                "ignored by indexing and code intelligence."
            ))
            put("items", kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("string"))
            })
        })
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val path = requiredStringArg(arguments, "path").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: path")
        }

        if (!File(path).isAbsolute) {
            return createErrorResult("path must be an absolute path, got: $path")
        }
        val dir = File(path).canonicalFile
        if (!dir.exists()) return createErrorResult("Path does not exist: $path")
        if (!dir.isDirectory) return createErrorResult("Path is not a directory: $path")

        if (File(dir, "pom.xml").exists() && com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors.maven.isAvailable) {
            return createErrorResult(
                "Directory contains pom.xml — use ide_import_modules instead, " +
                "which sets up the full Maven reactor relationship."
            )
        }

        val moduleName = optionalStringArg(arguments, "name") ?: dir.name
        if (moduleName.isBlank() || moduleName.contains("..") || moduleName.contains("/") || moduleName.contains("\\")) {
            return createErrorResult("Invalid module name: '$moduleName'. Must not be blank or contain path separators.")
        }
        val excludes = arguments["excludes"]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

        for (exclude in excludes) {
            if (exclude.isBlank()) {
                return createErrorResult("Exclude path must not be blank.")
            }
            if (exclude.contains("..")) {
                return createErrorResult("Exclude path must not contain '..': $exclude")
            }
        }

        val existingRoots = ProjectUtils.getModuleContentRoots(project)
            .map { ProjectUtils.canonicalNormalizedPath(it) }.toSet()
        val canonicalPath = ProjectUtils.canonicalNormalizedPath(dir.absolutePath)
        if (canonicalPath in existingRoots) {
            return createSuccessResult("Directory already registered as a module content root: $path")
        }

        if (ModuleManager.getInstance(project).findModuleByName(moduleName) != null) {
            return createErrorResult(
                "A module named '$moduleName' already exists in this project. " +
                "Pass a different 'name' to create a module for this directory."
            )
        }

        val dirVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.absolutePath)
            ?: return createErrorResult("Could not resolve directory in VFS: $path")

        val imlPath = File(dir, "$moduleName.iml").absolutePath
        val excludedCount = edtAction {
            WriteAction.compute<Int, Exception> {
                createModule(project, moduleName, imlPath, dirVf, dir, excludes)
            }
        }

        val msg = buildString {
            append("Module '$moduleName' created with content root: $path")
            append("\nModule file: $imlPath")
            if (excludedCount > 0) {
                append("\nExcluded $excludedCount director${if (excludedCount == 1) "y" else "ies"}")
            }
            append("\nNote: indexing is async — call ide_index_status if subsequent tools hit dumb mode.")
        }
        return createSuccessResult(msg)
    }

    private fun createModule(
        project: Project,
        moduleName: String,
        imlPath: String,
        dirVf: VirtualFile,
        dir: File,
        excludes: List<String>
    ): Int {
        val moduleManager = ModuleManager.getInstance(project)
        val module = moduleManager.newModule(imlPath, MODULE_TYPE_WEB)

        val rootModel = ModuleRootManager.getInstance(module).modifiableModel
        try {
            val contentEntry = rootModel.addContentEntry(dirVf)

            var excludedCount = 0
            for (exclude in excludes) {
                val excludeUrl = com.intellij.openapi.vfs.VfsUtilCore.pathToUrl(
                    File(dir, exclude).absolutePath
                )
                contentEntry.addExcludeFolder(excludeUrl)
                excludedCount++
            }

            rootModel.commit()
            return excludedCount
        } catch (e: Exception) {
            rootModel.dispose()
            throw e
        }
    }
}

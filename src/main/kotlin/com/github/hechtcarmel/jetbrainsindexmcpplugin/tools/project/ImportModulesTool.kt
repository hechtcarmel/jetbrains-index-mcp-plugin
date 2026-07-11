package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.MavenImportResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class ImportModulesTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_import_modules"

    override val description = """
        Import one or more external project directories as modules into the current
        IntelliJ project window, enabling cross-project code intelligence and refactoring.

        Use this when you need to refactor across multiple repos that share a parent POM
        or have cross-project dependencies. After importing, ide_find_references,
        ide_refactor_rename, ide_move_file and other tools will work across all imported
        modules as if they were part of one project.

        Equivalent to File → New → Module from Existing Sources, repeated for each path.

        Each path must be an absolute directory path containing a pom.xml. Paths that are
        already imported as modules are skipped. The import triggers a Maven project reload
        so dependencies are resolved across all modules.

        Parameters:
        - paths (required): array of absolute directory paths to import as modules
        - project_path (optional): selects which IntelliJ project window to import into

        Example: { "paths": ["/Users/dev/casehub/drafthouse", "/Users/dev/casehub/worker"] }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .property("paths", kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("array"))
            put("description", kotlinx.serialization.json.JsonPrimitive(
                "Absolute directory paths to import as modules into the current project window."
            ))
            put("items", kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("string"))
            })
        }, required = true)
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val pathsJson = arguments["paths"]?.jsonArray
            ?: return createErrorResult("Missing required parameter: paths (array of directory paths)")

        if (pathsJson.isEmpty()) {
            return createErrorResult("paths array is empty — provide at least one directory path to import.")
        }

        val paths = pathsJson.map { it.jsonPrimitive.content }

        for (path in paths) {
            if (!File(path).isAbsolute) return createErrorResult("Path must be absolute: $path")
            val dir = File(path)
            if (!dir.exists()) return createErrorResult("Path does not exist: $path")
            if (!dir.isDirectory) return createErrorResult("Path is not a directory: $path")
            if (!File(dir, "pom.xml").exists()) {
                return createErrorResult("No pom.xml found in $path — only Maven projects are supported.")
            }
        }

        val existingRoots = ProjectUtils.getModuleContentRoots(project).map { it.trimEnd('/') }.toSet()

        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (path in paths) {
            if (existingRoots.any { it == path.trimEnd('/') }) {
                skipped.add("$path: already imported as a module")
                continue
            }
            val dirVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            if (dirVf == null) {
                failed.add("$path: could not resolve directory in VFS")
                continue
            }
            when (val result = ProjectUtils.importMavenModule(project, dirVf)) {
                is MavenImportResult.Success -> imported.add(path)
                is MavenImportResult.MavenUnavailable ->
                    failed.add("$path: Maven plugin not available — is it enabled?")
                is MavenImportResult.Failed ->
                    failed.add("$path: ${result.error}")
            }
        }

        val lines = mutableListOf<String>()
        if (imported.isNotEmpty()) {
            lines.add("Imported ${imported.size} module(s):")
            imported.forEach { lines.add("  + $it") }
        }
        if (skipped.isNotEmpty()) {
            if (lines.isNotEmpty()) lines.add("")
            lines.add("Skipped ${skipped.size}:")
            skipped.forEach { lines.add("  - $it") }
        }
        if (failed.isNotEmpty()) {
            if (lines.isNotEmpty()) lines.add("")
            lines.add("Failed ${failed.size}:")
            failed.forEach { lines.add("  ! $it") }
        }

        return if (imported.isEmpty() && failed.isNotEmpty()) {
            createErrorResult(lines.joinToString("\n"))
        } else {
            createSuccessResult(lines.joinToString("\n"))
        }
    }
}

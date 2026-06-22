package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import java.io.File

class ReloadProjectTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_reload_project"

    override val description = """
        Force-reload the project's build model (Maven, Gradle, or both).

        Equivalent to clicking "Reload All Maven Projects" or "Reload Gradle Project"
        in the IDE. Use this after modifying pom.xml, build.gradle, build.gradle.kts,
        settings.gradle, or any dependency configuration file so that IntelliJ resolves
        the updated dependencies before running diagnostics or builds.

        Only reloads build systems that are actually linked in IntelliJ — if a pom.xml
        exists but the Maven project was never imported, it reports that and skips it.

        The reload is scheduled asynchronously — IntelliJ resolves dependencies in the
        background. On large projects allow 10-30 seconds before calling ide_build_project
        or ide_diagnostics.

        Parameters:
        - project_path (optional): selects the project when multiple are open

        Example: { }
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val dataManager = ProjectDataManager.getInstance()
        val lines = mutableListOf<String>()
        var anyScheduled = false

        for ((label, systemId, buildFiles) in BUILD_SYSTEMS) {
            val linked = dataManager.getExternalProjectsData(project, systemId)
            if (linked.isNotEmpty()) {
                ExternalSystemUtil.refreshProjects(ImportSpecBuilder(project, systemId))
                lines.add("$label: reload scheduled (${linked.size} linked project(s))")
                anyScheduled = true
            } else {
                // Check disk to give a more helpful skip reason
                val hasBuildFile = buildFiles.any {
                    File((project.basePath ?: ""), it).exists()
                }
                if (hasBuildFile) {
                    lines.add(
                        "$label: skipped — build file found on disk but project is not linked in IntelliJ. " +
                        "Open the ${label} tool window and click 'Import' or 'Link' to register it first."
                    )
                }
                // if no build file and not linked, simply don't mention it
            }
        }

        return if (!anyScheduled && lines.isEmpty()) {
            createErrorResult(
                "No Maven or Gradle build system found or linked in '${project.name}'. " +
                "Neither pom.xml/build.gradle.kts exists in the project root, " +
                "nor is any external build system linked in IntelliJ."
            )
        } else {
            createSuccessResult(lines.joinToString("\n"))
        }
    }

    private data class BuildSystemSpec(
        val label: String,
        val systemId: ProjectSystemId,
        val buildFiles: List<String>
    )

    private companion object {
        val BUILD_SYSTEMS = listOf(
            BuildSystemSpec("Maven",  ProjectSystemId("MAVEN"),  listOf("pom.xml")),
            BuildSystemSpec("Gradle", ProjectSystemId("GRADLE"), listOf(
                "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
            )),
        )
    }
}

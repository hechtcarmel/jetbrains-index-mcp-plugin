package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.LongPoll
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.LinkInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildSystemLinker
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.LinkResult
import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

class LinkBuildSystemTool : AbstractMcpTool() {

    override val requiresPsiSync = false
    override val participatesInLifecycle = false

    override val name = "ide_link_build_system"

    override val description = """
        Link an unlinked Maven or Gradle project so the IDE resolves its dependencies.

        Use this when ide_reload_project reports "build file found on disk but project
        is not linked" — this tool does the equivalent of clicking "Load Maven/Gradle
        Project" in the IDE notification bar.

        Detects the build system automatically from build files in the project directory.
        If the project is already linked, returns successfully without re-linking.
        If neither Maven nor Gradle plugin is available, reports which plugins are missing.

        Two modes:
        - Start: provide path (or omit to use current project). If the link completes within
          waitSeconds, returns the result directly. Otherwise returns {"status": "running",
          "linkId": ...} — call again with that linkId to poll.
        - Poll: provide linkId from a previous call.

        Parameters:
        - path (optional): absolute path of the project directory to link. Defaults to the resolved project's base path.
        - linkId (optional): poll a link operation started by a previous call. Mutually exclusive with path.
        - waitSeconds (optional): max seconds to wait per call. Default: ${LongPoll.DEFAULT_WAIT_SECONDS}, max: ${LongPoll.MAX_WAIT_SECONDS}.
        - project_path (optional): selects the project when multiple are open.

        Example: { "path": "/Users/dev/myproject" }
        Example: { "linkId": "abc123" }
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .stringProperty("path", "Absolute path of the project directory to link. Defaults to the resolved project's base path.")
        .stringProperty("linkId", "Poll a link operation started by a previous call.")
        .intProperty(ParamNames.WAIT_SECONDS, "Max seconds to wait per call. Default: ${LongPoll.DEFAULT_WAIT_SECONDS}, max: ${LongPoll.MAX_WAIT_SECONDS}.")
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val linkId = LongPoll.optionalTrimmedString(arguments, "linkId")
        val path = optionalStringArg(arguments, "path")
        val waitSeconds = LongPoll.resolveWaitSeconds(arguments)
        val callStartMs = System.currentTimeMillis()

        if (linkId != null && path != null) {
            return createErrorResult("Provide either 'path' (to start linking) or 'linkId' (to poll), not both.")
        }

        if (linkId != null) {
            return pollLink(project, linkId, waitSeconds, callStartMs)
        }

        val targetPath = path ?: project.basePath
            ?: return createErrorResult("Cannot determine project path.")

        if (!File(targetPath).isDirectory) {
            return createErrorResult("Path is not a directory: $targetPath")
        }

        val systemName = detectBuildSystem(project, targetPath)
            ?: return createErrorResult("No recognized build file found in $targetPath, or build system plugin not available.")

        val existing = BuildSystemLinker.checkLinked(project, targetPath)
        if (existing != null) {
            return createSuccessResult("$existing project is already linked.")
        }

        val id = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<LinkResult>()
        val registry = ActiveLinkRegistry.getInstance(project)
        val op = ActiveLinkRegistry.ActiveLink(id, systemName, targetPath, callStartMs, deferred)
        registry.register(op)
        registry.launchLink(project, op) { BuildSystemLinker.linkBuildSystem(project, targetPath) }

        return awaitAndRespond(op, waitSeconds, callStartMs)
    }

    private suspend fun pollLink(
        project: Project,
        linkId: String,
        waitSeconds: Int,
        callStartMs: Long
    ): CallToolResult {
        val op = ActiveLinkRegistry.getInstance(project).get(linkId)
            ?: return createErrorResult("No active link operation with id '$linkId'. It may have completed and been evicted.")
        return awaitAndRespond(op, waitSeconds, callStartMs)
    }

    private suspend fun awaitAndRespond(
        op: ActiveLinkRegistry.ActiveLink,
        waitSeconds: Int,
        callStartMs: Long
    ): CallToolResult {
        val result = op.awaitWithinBudget(op.result, waitSeconds, callStartMs)
        return if (result != null) {
            formatResult(result)
        } else {
            val elapsed = (System.currentTimeMillis() - op.startedAtMs) / 1000
            createJsonResult(LinkInProgressResult(
                status = "running",
                linkId = op.id,
                systemName = op.systemName,
                elapsedSeconds = elapsed,
                message = "${op.systemName} link in progress (${elapsed}s elapsed). Poll with linkId: ${op.id}"
            ))
        }
    }

    private fun formatResult(result: LinkResult): CallToolResult = when (result) {
        is LinkResult.Linked ->
            createSuccessResult("${result.systemName} project linked.")
        is LinkResult.AlreadyLinked ->
            createSuccessResult("${result.systemName} project is already linked.")
        is LinkResult.NoBuildFile ->
            createErrorResult("No recognized build file found. Checked for: ${result.checkedSystems.joinToString(", ")} build files.")
        is LinkResult.PluginUnavailable ->
            createErrorResult("${result.systemName} plugin is not available in this IDE.")
        is LinkResult.Failed ->
            createErrorResult("Failed to link ${result.systemName} project: ${result.error}")
    }

    private suspend fun detectBuildSystem(project: Project, projectPath: String): String? {
        for ((name, systemId) in SYSTEM_IDS) {
            val aware = ExternalSystemUnlinkedProjectAware.getInstance(systemId) ?: continue
            val hasBuild = readAction {
                val dir = LocalFileSystem.getInstance().findFileByPath(projectPath) ?: return@readAction false
                dir.children.any { aware.isBuildFile(project, it) }
            }
            if (hasBuild) return name
        }
        return null
    }

    companion object {
        private val SYSTEM_IDS = listOf(
            "Maven" to ProjectSystemId("MAVEN"),
            "Gradle" to ProjectSystemId("GRADLE"),
        )
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.LongPoll
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildProjectResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildListenerUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class BuildProjectTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<BuildProjectTool>()
        private const val MAX_BUILD_MESSAGES = 200
        private const val MAX_RAW_OUTPUT_CHARS = 100_000

        internal fun buildInProgressResult(
            buildId: String,
            elapsedSeconds: Long,
            timeoutSeconds: Int?
        ): BuildInProgressResult = BuildInProgressResult(
            status = "running",
            buildId = buildId,
            elapsedSeconds = elapsedSeconds,
            timeoutSeconds = timeoutSeconds,
            message = "Build is still executing (${elapsedSeconds}s elapsed" +
                    (timeoutSeconds?.let { ", ${it}s limit" } ?: ", no limit") +
                    "). The build continues in the IDE. Call ide_build_project again with " +
                    "{\"buildId\": \"$buildId\"} to keep waiting for its results (include the same " +
                    "project_path if you provided one)."
        )
    }

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.BUILD_PROJECT

    override val description = """
        Build the project using the IDE's build system (supports JPS, Gradle, Maven).
        Use after making code changes to check for compilation errors.

        Long-running builds: each call blocks at most waitSeconds (default ${LongPoll.DEFAULT_WAIT_SECONDS}) so your MCP
        client's request timeout is never hit. If the build is still executing when the wait budget
        ends, the call returns {"status": "running", "buildId": "..."} while the build continues
        inside the IDE — call this tool again with that buildId to keep waiting.

        Returns: success status, error/warning counts, and structured build messages with file locations.
        Note: errors/warnings are null when no compiler messages were captured (e.g. no-op incremental build where nothing was recompiled, or build system without compiler message integration). null does NOT mean 0.

        When project_path points to a workspace sub-project, that module and its dependencies are built.

        Parameters: project_path (optional), rebuild (optional, default false), includeRawOutput (optional, default false),
        timeoutSeconds (optional, max seconds for the whole build before it is reported timed out; no limit if omitted),
        waitSeconds (optional, default ${LongPoll.DEFAULT_WAIT_SECONDS}, max ${LongPoll.MAX_WAIT_SECONDS}), buildId (optional, polls a running build; excludes all other parameters).

        Example: {} or {"rebuild": true} — then if a "running" status comes back: {"buildId": "<buildId from that response>"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .booleanProperty(ParamNames.REBUILD, "Full rebuild instead of incremental build. Default: false.")
        .booleanProperty(ParamNames.INCLUDE_RAW_OUTPUT, "Include raw build output log in response. Default: false.")
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds the whole build may take before it is reported timed out (enforced across " +
                    "polls). Must be a positive integer. No limit if omitted. Ignored when buildId is given."
        )
        .stringProperty(
            ParamNames.BUILD_ID,
            "buildId from a previous {\"status\": \"running\"} response: attaches to that build and keeps " +
                    "waiting instead of starting a new one."
        )
        .intProperty(
            ParamNames.WAIT_SECONDS,
            "Maximum seconds this call may block before returning results or a \"running\" status. " +
                    "Default: ${LongPoll.DEFAULT_WAIT_SECONDS}, max: ${LongPoll.MAX_WAIT_SECONDS}. Keep below your MCP client's request timeout."
        )
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val callStartMs = System.currentTimeMillis()
        val waitSeconds = LongPoll.resolveWaitSeconds(arguments)

        LongPoll.optionalTrimmedString(arguments, ParamNames.BUILD_ID)?.let { buildId ->
            val build = ActiveBuildRegistry.getInstance(project).get(buildId)
                ?: return createErrorResult(
                    "No active build with id '$buildId'. Its results may have already been collected, " +
                            "the build may have been evicted after completing, or the IDE was restarted. " +
                            "Start a new build by omitting buildId."
                )
            return awaitBuildResult(project, build, waitSeconds, callStartMs)
        }

        if (!TrustedProjects.isProjectTrusted(project)) {
            return createErrorResult("Cannot build: project is not trusted. Open project settings to mark it as trusted.")
        }

        val rebuild = arguments[ParamNames.REBUILD]?.jsonPrimitive?.booleanOrNull ?: false
        val includeRawOutput = arguments[ParamNames.INCLUDE_RAW_OUTPUT]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutSeconds = arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull

        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer, or omit for no timeout.")
        }

        BuildDiagnosticsCacheService.getInstance(project).initialize()

        val projectPathArg = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.content
        val targetModule = resolveTargetModule(project, projectPathArg)

        val sessionId = UUID.randomUUID().toString()
        val context = ProjectTaskContext(sessionId)

        val taskManager = ProjectTaskManager.getInstance(project)
        val isIncremental = !rebuild
        val task = if (targetModule != null) {
            taskManager.createModulesBuildTask(arrayOf(targetModule), isIncremental, true, true, true)
        } else {
            taskManager.createAllModulesBuildTask(isIncremental, project)
        }

        val buildDeferred = CompletableDeferred<ProjectTaskManager.Result>()

        val connection = project.messageBus.connect()
        val parentDisposable = Disposer.newDisposable("BuildProjectTool-parent")
        val build = ActiveBuildRegistry.ActiveBuild(
            id = UUID.randomUUID().toString(),
            startedAtMs = callStartMs,
            timeoutSeconds = timeoutSeconds,
            includeRawOutput = includeRawOutput,
            result = buildDeferred,
            connection = connection,
            listenersDisposable = parentDisposable
        )

        // From here to registration nothing may leak the connection or the listener disposable:
        // an exception (e.g. the project closing mid-call) must tear both down.
        try {
            connection.subscribe(ProjectTaskListener.TOPIC, object : ProjectTaskListener {
                override fun finished(result: ProjectTaskManager.Result) {
                    if (result.context.sessionId == sessionId) {
                        buildDeferred.complete(result)
                    }
                }
            })
            subscribeToCompilerMessages(project, connection, build)
            subscribeToBuildEvents(project, parentDisposable, build)

            val promise = taskManager.run(context, task)
            promise.onError { error ->
                if (!buildDeferred.isCompleted) {
                    buildDeferred.completeExceptionally(error)
                }
            }

            ActiveBuildRegistry.getInstance(project).register(build)
        } catch (t: Throwable) {
            connection.disconnect()
            Disposer.dispose(parentDisposable)
            if (t is Exception && t !is CancellationException) {
                LOG.warn("Build failed to start", t)
                return createErrorResult("Build failed: ${t.message}")
            }
            throw t
        }

        return awaitBuildResult(project, build, waitSeconds, callStartMs)
    }

    /**
     * Waits for the build within what remains of this call's wait budget, then returns either the
     * final [BuildProjectResult] (removing the build from the registry and disposing its
     * listeners) or a [BuildInProgressResult] carrying the buildId to poll with.
     */
    private suspend fun awaitBuildResult(
        project: Project,
        build: ActiveBuildRegistry.ActiveBuild,
        waitSeconds: Int,
        callStartMs: Long
    ): CallToolResult {
        val result: ProjectTaskManager.Result? = try {
            build.awaitWithinBudget(build.result, waitSeconds, callStartMs)
        } catch (e: CancellationException) {
            // The MCP call was aborted (client hung up) — the build must stay pollable.
            throw e
        } catch (e: Exception) {
            ActiveBuildRegistry.getInstance(project).remove(build.id)
            LOG.warn("Build failed with exception", e)
            return createErrorResult("Build failed: ${e.message}")
        }

        if (result == null && !build.timedOutByWatchdog) {
            val elapsedSeconds = (System.currentTimeMillis() - build.startedAtMs) / 1000
            return createJsonResult(buildInProgressResult(build.id, elapsedSeconds, build.timeoutSeconds))
        }

        // Terminal: the build finished, or the watchdog marked it timed out (the IDE build itself
        // cannot be cancelled programmatically and may keep running, as before the long-poll rework).
        val durationMs = System.currentTimeMillis() - build.startedAtMs
        val timedOut = result == null
        val success = if (timedOut) false else !result!!.hasErrors() && !result.isAborted

        val rawOutputStr = if (build.includeRawOutput) {
            when {
                build.compilerRawOutput.isNotEmpty() -> build.compilerRawOutput.toString()
                build.buildEventRawOutput.isNotEmpty() -> build.buildEventRawOutput.toString()
                else -> null
            }
        } else null

        val selectedMessages = BuildProjectResultSelector.selectMessages(
            buildFailed = !success,
            currentMessages = snapshot(build.compilerMessages).ifEmpty { snapshot(build.buildEventMessages) },
            failureMessages = snapshot(build.failureMessages),
            rawOutput = build.buildEventRawOutput.toString(),
            relativizePath = { path -> ProjectUtils.getRelativePath(project, path) }
        )
        val truncated = selectedMessages.size > MAX_BUILD_MESSAGES
        val messages = if (truncated) {
            selectedMessages.take(MAX_BUILD_MESSAGES)
        } else {
            selectedMessages
        }
        BuildDiagnosticsCacheService.getInstance(project).recordBuildResult(messages)
        val errorCount = messages.count { it.category == "ERROR" }.takeIf { selectedMessages.isNotEmpty() }
        val warningCount = messages.count { it.category == "WARNING" }.takeIf { selectedMessages.isNotEmpty() }

        ActiveBuildRegistry.getInstance(project).remove(build.id)

        return createJsonResult(
            BuildProjectResult(
                success = success,
                aborted = timedOut || result?.isAborted == true,
                errors = errorCount,
                warnings = warningCount,
                buildMessages = messages,
                truncated = truncated,
                rawOutput = rawOutputStr,
                durationMs = durationMs
            )
        )
    }

    private fun resolveTargetModule(project: Project, projectPathArg: String?): Module? {
        if (projectPathArg == null) return null

        val normalizedPath = ProjectResolver.normalizePath(projectPathArg)
        val projectBasePath = project.basePath?.let { ProjectResolver.normalizePath(it) }

        if (normalizedPath == projectBasePath) return null

        try {
            val modules = ModuleManager.getInstance(project).modules
            for (module in modules) {
                val contentRoots = ModuleRootManager.getInstance(module).contentRoots
                for (root in contentRoots) {
                    val rootPath = ProjectResolver.normalizePath(root.path)
                    if (normalizedPath == rootPath) return module
                    if (normalizedPath.startsWith("$rootPath/")) return module
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to resolve target module from project_path", e)
        }

        return null
    }

    private fun subscribeToBuildEvents(
        project: Project,
        parentDisposable: Disposable,
        build: ActiveBuildRegistry.ActiveBuild
    ) {
        BuildListenerUtils.subscribeToBuildProgressListener(project, parentDisposable) { _, event ->
            val buildMessage = BuildListenerUtils.extractBuildMessage(event, project)
            if (buildMessage != null) {
                build.buildEventMessages.add(buildMessage)
            }

            build.failureMessages.addAll(BuildListenerUtils.extractFailureMessages(event))

            val text = BuildListenerUtils.extractRawOutput(event)
            if (text != null && build.buildEventRawOutput.length < MAX_RAW_OUTPUT_CHARS) {
                build.buildEventRawOutput.append(text)
            }
        }
    }

    private fun snapshot(messages: MutableList<BuildMessage>): List<BuildMessage> =
        synchronized(messages) { ArrayList(messages) }

    private fun subscribeToCompilerMessages(
        project: Project,
        connection: com.intellij.util.messages.MessageBusConnection,
        build: ActiveBuildRegistry.ActiveBuild
    ) {
        BuildListenerUtils.subscribeToCompilationStatus(connection) { compileContext ->
            build.compilerMessages.addAll(BuildListenerUtils.extractCompilerMessages(compileContext, project))

            if (build.includeRawOutput) {
                build.compilerRawOutput.append(BuildListenerUtils.extractCompilerRawOutput(compileContext))
            }
        }
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildProjectResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildListenerUtils
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import java.util.UUID

class BuildProjectTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<BuildProjectTool>()
        private const val MAX_BUILD_MESSAGES = 200
        private const val MAX_RAW_OUTPUT_CHARS = 100_000
    }

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.BUILD_PROJECT

    override val description = """
        Build the project using the IDE's build system (supports JPS, Gradle, Maven).
        Use after making code changes to check for compilation errors.

        Returns: success status, error/warning counts, and structured build messages with file locations.
        Note: errors/warnings are null when no compiler messages were captured (e.g. no-op incremental build where nothing was recompiled, or build system without compiler message integration). null does NOT mean 0.

        When project_path points to a workspace sub-project, that module and its dependencies are built.

        Parameters: project_path (optional), rebuild (optional, default false), includeRawOutput (optional, default false), timeoutSeconds (optional, must be positive).

        Example: {} or {"rebuild": true} or {"includeRawOutput": true, "timeoutSeconds": 120}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .booleanProperty(ParamNames.REBUILD, "Full rebuild instead of incremental build. Default: false.")
        .booleanProperty(ParamNames.INCLUDE_RAW_OUTPUT, "Include raw build output log in response. Default: false.")
        .intProperty(ParamNames.TIMEOUT_SECONDS, "Timeout in seconds. Must be a positive integer. No timeout if omitted.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
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

        val startTime = System.currentTimeMillis()

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

        // JPS compiler messages (Java/Kotlin via Java plugin - high fidelity)
        val compilerMessages = Collections.synchronizedList(mutableListOf<BuildMessage>())
        val compilerRawOutput = StringBuffer()

        // Build events (Gradle/Maven/universal via BuildViewManager - fallback)
        val buildEventMessages = Collections.synchronizedList(mutableListOf<BuildMessage>())
        val buildEventRawOutput = StringBuffer()

        val connection = project.messageBus.connect()
        connection.subscribe(ProjectTaskListener.TOPIC, object : ProjectTaskListener {
            override fun finished(result: ProjectTaskManager.Result) {
                if (result.context.sessionId == sessionId) {
                    buildDeferred.complete(result)
                }
            }
        })

        subscribeToCompilerMessages(project, connection, compilerMessages, compilerRawOutput, includeRawOutput)
        val parentDisposable = Disposer.newDisposable("BuildProjectTool-parent")
        val buildEventsDisposable = subscribeToBuildEvents(project, parentDisposable, buildEventMessages, buildEventRawOutput, includeRawOutput)

        try {
            val promise = taskManager.run(context, task)

            promise.onError { error ->
                if (!buildDeferred.isCompleted) {
                    buildDeferred.completeExceptionally(error)
                }
            }

            val result = if (timeoutSeconds != null) {
                withTimeoutOrNull(timeoutSeconds * 1000L) {
                    buildDeferred.await()
                }
            } else {
                buildDeferred.await()
            }

            val durationMs = System.currentTimeMillis() - startTime
            val timedOut = result == null

            // Prefer JPS compiler messages if available, fall back to build events
            val allMessages = if (compilerMessages.isNotEmpty()) compilerMessages else buildEventMessages
            val rawOutputStr = if (includeRawOutput) {
                when {
                    compilerRawOutput.isNotEmpty() -> compilerRawOutput.toString()
                    buildEventRawOutput.isNotEmpty() -> buildEventRawOutput.toString()
                    else -> null
                }
            } else null

            val truncated = allMessages.size > MAX_BUILD_MESSAGES
            val messages = synchronized(allMessages) {
                if (truncated) ArrayList(allMessages.subList(0, MAX_BUILD_MESSAGES)) else ArrayList(allMessages)
            }
            BuildDiagnosticsCacheService.getInstance(project).recordBuildResult(messages)
            val errorCount = messages.count { it.category == "ERROR" }.takeIf { allMessages.isNotEmpty() }
            val warningCount = messages.count { it.category == "WARNING" }.takeIf { allMessages.isNotEmpty() }

            return createJsonResult(BuildProjectResult(
                success = if (timedOut) false else !result!!.hasErrors() && !result.isAborted,
                aborted = timedOut || result?.isAborted == true,
                errors = errorCount,
                warnings = warningCount,
                buildMessages = messages,
                truncated = truncated,
                rawOutput = rawOutputStr,
                durationMs = durationMs
            ))
        } catch (e: Exception) {
            LOG.warn("Build failed with exception", e)
            return createErrorResult("Build failed: ${e.message}")
        } finally {
            connection.disconnect()
            if (buildEventsDisposable != null) {
                Disposer.dispose(buildEventsDisposable)
            }
            Disposer.dispose(parentDisposable)
        }
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
        messages: MutableList<BuildMessage>,
        rawOutput: StringBuffer,
        includeRawOutput: Boolean
    ): Disposable? {
        return BuildListenerUtils.subscribeToBuildProgressListener(project, parentDisposable) { _, event ->
            val buildMessage = BuildListenerUtils.extractBuildMessage(event, project)
            if (buildMessage != null) {
                messages.add(buildMessage)
            }

            if (includeRawOutput) {
                val text = BuildListenerUtils.extractRawOutput(event)
                if (text != null && rawOutput.length < MAX_RAW_OUTPUT_CHARS) {
                    rawOutput.append(text)
                }
            }
        }
    }

    private fun subscribeToCompilerMessages(
        project: Project,
        connection: com.intellij.util.messages.MessageBusConnection,
        messages: MutableList<BuildMessage>,
        rawOutput: StringBuffer,
        includeRawOutput: Boolean
    ) {
        BuildListenerUtils.subscribeToCompilationStatus(connection) { compileContext ->
            messages.addAll(BuildListenerUtils.extractCompilerMessages(compileContext, project))

            if (includeRawOutput) {
                rawOutput.append(BuildListenerUtils.extractCompilerRawOutput(compileContext))
            }
        }
    }
}

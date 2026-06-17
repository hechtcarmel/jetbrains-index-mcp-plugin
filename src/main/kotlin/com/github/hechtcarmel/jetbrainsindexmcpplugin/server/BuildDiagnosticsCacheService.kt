package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.BuildProjectResultSelector
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildListenerUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class BuildDiagnosticsCacheService(private val project: Project) : Disposable {

    companion object {
        private val LOG = logger<BuildDiagnosticsCacheService>()
        private const val MAX_CACHED_MESSAGES = 500
        private const val MAX_RAW_OUTPUT_CHARS = 100_000

        fun getInstance(project: Project): BuildDiagnosticsCacheService =
            project.getService(BuildDiagnosticsCacheService::class.java)
    }

    private var buildEventMessages = AtomicReference<List<BuildMessage>>(emptyList())
    private var failureMessages = AtomicReference<List<BuildMessage>>(emptyList())
    private var compilerMessages = AtomicReference<List<BuildMessage>>(emptyList())
    private var publishedMessages = AtomicReference<List<BuildMessage>>(emptyList())
    private val buildRawOutput = AtomicReference("")
    private val buildTimestamp = AtomicLong(0L)
    private val currentBuildId = AtomicReference<Any?>(null)
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        val serviceDisposable = Disposer.newDisposable(this, "BuildDiagnosticsCacheService-listeners")

        BuildListenerUtils.subscribeToBuildProgressListener(project, serviceDisposable) { buildId, event ->
            handleBuildEvent(buildId, event)
        }

        val connection = project.messageBus.connect(serviceDisposable)
        BuildListenerUtils.subscribeToCompilationStatus(connection) { compileContext ->
            val messages = BuildListenerUtils.extractCompilerMessages(compileContext, project)
            if (currentBuildId.get() == null) {
                buildEventMessages.set(emptyList())
                failureMessages.set(emptyList())
                buildRawOutput.set("")
            }
            compilerMessages.set(messages.take(MAX_CACHED_MESSAGES))
            publishActiveMessages()
        }

        LOG.debug("BuildDiagnosticsCacheService initialized for project: ${project.name}")
    }

    private fun handleBuildEvent(buildId: Any, event: Any) {
        val previousBuildId = currentBuildId.get()
        if (previousBuildId == null || previousBuildId != buildId) {
            currentBuildId.set(buildId)
            buildEventMessages.set(emptyList())
            failureMessages.set(emptyList())
            compilerMessages.set(emptyList())
            publishedMessages.set(emptyList())
            buildRawOutput.set("")
            buildTimestamp.set(0L)
        }

        val message = BuildListenerUtils.extractBuildMessage(event, project)
        if (message != null) {
            addBuildEventMessage(message)
        }

        val failures = BuildListenerUtils.extractFailureMessages(event)
        if (failures.isNotEmpty()) {
            addFailureMessages(failures)
        }

        val rawOutput = BuildListenerUtils.extractRawOutput(event)
        if (rawOutput != null) {
            appendRawOutput(rawOutput)
        }

        val eventClassName = event.javaClass.simpleName
        if (eventClassName.contains("Finish") || eventClassName.contains("Success") || eventClassName.contains("Failure")) {
            publishActiveMessages(BuildListenerUtils.isFailureResultEvent(event))
        }
    }

    private fun addBuildEventMessage(message: BuildMessage) {
        buildEventMessages.updateAndGet { existing ->
            if (existing.size >= MAX_CACHED_MESSAGES) {
                existing
            } else {
                existing + message
            }
        }
    }

    private fun addFailureMessages(messages: List<BuildMessage>) {
        failureMessages.updateAndGet { existing ->
            if (existing.size >= MAX_CACHED_MESSAGES) {
                existing
            } else {
                (existing + messages).take(MAX_CACHED_MESSAGES)
            }
        }
    }

    private fun appendRawOutput(text: String) {
        buildRawOutput.updateAndGet { existing ->
            if (existing.length >= MAX_RAW_OUTPUT_CHARS) {
                existing
            } else {
                (existing + text).take(MAX_RAW_OUTPUT_CHARS)
            }
        }
    }

    private fun publishActiveMessages(buildFailed: Boolean = false) {
        val currentMessages = compilerMessages.get().ifEmpty { buildEventMessages.get() }
        val activeMessages = BuildProjectResultSelector.selectMessages(
            buildFailed = buildFailed,
            currentMessages = currentMessages,
            failureMessages = failureMessages.get(),
            rawOutput = buildRawOutput.get(),
            relativizePath = { path ->
                ProjectUtils.getRelativePath(project, path)
            }
        )
        publishedMessages.set(activeMessages.take(MAX_CACHED_MESSAGES))
        buildTimestamp.set(System.currentTimeMillis())
    }

    fun getLastBuildDiagnostics(): List<BuildMessage> {
        initialize()
        return ArrayList(publishedMessages.get())
    }

    fun getLastBuildTimestamp(): Long? {
        initialize()
        val ts = buildTimestamp.get()
        return if (ts == 0L) null else ts
    }

    fun recordBuildResult(messages: List<BuildMessage>) {
        initialize()
        val cappedMessages = messages.take(MAX_CACHED_MESSAGES)
        publishedMessages.set(cappedMessages)
        buildTimestamp.set(System.currentTimeMillis())
    }

    override fun dispose() {
        buildEventMessages.set(emptyList())
        failureMessages.set(emptyList())
        compilerMessages.set(emptyList())
        publishedMessages.set(emptyList())
        buildRawOutput.set("")
    }
}

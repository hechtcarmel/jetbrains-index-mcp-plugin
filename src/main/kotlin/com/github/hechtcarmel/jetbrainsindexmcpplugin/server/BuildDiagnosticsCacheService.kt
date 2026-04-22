package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildListenerUtils
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

        fun getInstance(project: Project): BuildDiagnosticsCacheService =
            project.getService(BuildDiagnosticsCacheService::class.java)
    }

    private var buildEventMessages = AtomicReference<List<BuildMessage>>(emptyList())
    private var compilerMessages = AtomicReference<List<BuildMessage>>(emptyList())
    private var publishedMessages = AtomicReference<List<BuildMessage>>(emptyList())
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
            compilerMessages.set(emptyList())
            publishedMessages.set(emptyList())
            buildTimestamp.set(0L)
        }

        val message = BuildListenerUtils.extractBuildMessage(event, project)
        if (message != null) {
            addBuildEventMessage(message)
        }

        val eventClassName = event.javaClass.simpleName
        if (eventClassName.contains("Finish") || eventClassName.contains("Success") || eventClassName.contains("Failure")) {
            publishActiveMessages()
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

    private fun publishActiveMessages() {
        val activeMessages = compilerMessages.get().ifEmpty { buildEventMessages.get() }
        publishedMessages.set(activeMessages)
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
        compilerMessages.set(emptyList())
        publishedMessages.set(emptyList())
    }
}

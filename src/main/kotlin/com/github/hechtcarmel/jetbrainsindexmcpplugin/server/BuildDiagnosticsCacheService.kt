package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.BuildListenerUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList
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

    private val cachedMessages = CopyOnWriteArrayList<BuildMessage>()
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
            if (messages.isNotEmpty()) {
                addMessagesInternal(messages)
                buildTimestamp.set(System.currentTimeMillis())
            }
        }

        LOG.debug("BuildDiagnosticsCacheService initialized for project: ${project.name}")
    }

    private fun handleBuildEvent(buildId: Any, event: Any) {
        val previousBuildId = currentBuildId.get()
        if (previousBuildId == null || previousBuildId != buildId) {
            currentBuildId.set(buildId)
            cachedMessages.clear()
            buildTimestamp.set(0L)
        }

        val message = BuildListenerUtils.extractBuildMessage(event, project)
        if (message != null) {
            addMessagesInternal(listOf(message))
        }

        val eventClassName = event.javaClass.simpleName
        if (eventClassName.contains("Finish") || eventClassName.contains("Success") || eventClassName.contains("Failure")) {
            buildTimestamp.set(System.currentTimeMillis())
        }
    }

    private fun addMessagesInternal(messages: List<BuildMessage>) {
        if (cachedMessages.size + messages.size <= MAX_CACHED_MESSAGES) {
            cachedMessages.addAll(messages)
        } else {
            for (msg in messages) {
                if (cachedMessages.size >= MAX_CACHED_MESSAGES) break
                cachedMessages.add(msg)
            }
        }
    }

    fun getLastBuildDiagnostics(severity: String?): List<BuildMessage> {
        initialize()
        val all = ArrayList(cachedMessages)
        if (severity == null || severity == "all") return all
        return all.filter { it.category == severity.uppercase() }
    }

    fun getLastBuildTimestamp(): Long? {
        initialize()
        val ts = buildTimestamp.get()
        return if (ts == 0L) null else ts
    }

    override fun dispose() {
        cachedMessages.clear()
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.LinkResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class ActiveLinkRegistry(private val scope: CoroutineScope) : LongPollRegistry<ActiveLinkRegistry.ActiveLink>(scope) {

    fun launchLink(project: Project, op: ActiveLink, linker: suspend () -> LinkResult) {
        scope.launch {
            try {
                val result = linker()
                op.result.complete(result)
            } catch (e: Exception) {
                op.result.completeExceptionally(e)
            }
        }
    }

    class ActiveLink(
        id: String,
        val systemName: String,
        val projectPath: String,
        val startedAtMs: Long,
        val result: CompletableDeferred<LinkResult>
    ) : LongPollOperation(id) {

        override val deadlineMs: Long? = null

        override val completion: CompletableDeferred<LinkResult> get() = result
    }

    companion object {
        fun getInstance(project: Project): ActiveLinkRegistry = project.service()
    }
}

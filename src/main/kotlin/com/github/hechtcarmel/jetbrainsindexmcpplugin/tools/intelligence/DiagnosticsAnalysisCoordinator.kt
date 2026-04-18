package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Service(Service.Level.APP)
class DiagnosticsAnalysisCoordinator {

    companion object {
        fun getInstance(): DiagnosticsAnalysisCoordinator =
            ApplicationManager.getApplication().getService(DiagnosticsAnalysisCoordinator::class.java)
    }

    private val mainPassesMutex = Mutex()

    suspend fun <T> withMainPassLock(action: suspend () -> T): T {
        return mainPassesMutex.withLock {
            action()
        }
    }
}

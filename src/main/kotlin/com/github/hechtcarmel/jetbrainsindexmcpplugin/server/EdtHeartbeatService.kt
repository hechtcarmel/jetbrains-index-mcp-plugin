package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.Alarm
import org.jetbrains.annotations.TestOnly

@Service(Service.Level.APP)
class EdtHeartbeatService : Disposable {

    @Volatile
    private var lastHeartbeatNs: Long = System.nanoTime()

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val UNRESPONSIVE_THRESHOLD_MS = 90_000L

        fun getInstance(): EdtHeartbeatService = service()
    }

    init {
        val app = ApplicationManager.getApplication()
        if (app != null && !app.isUnitTestMode) {
            scheduleHeartbeat()
        }
    }

    private fun scheduleHeartbeat() {
        alarm.addRequest({
            ApplicationManager.getApplication()?.invokeLater({
                lastHeartbeatNs = System.nanoTime()
            }, ModalityState.any())
            if (!alarm.isDisposed) {
                scheduleHeartbeat()
            }
        }, HEARTBEAT_INTERVAL_MS)
    }

    fun edtUnresponsiveDurationMs(): Long? {
        val elapsedMs = (System.nanoTime() - lastHeartbeatNs) / 1_000_000
        return if (elapsedMs >= UNRESPONSIVE_THRESHOLD_MS) elapsedMs else null
    }

    @TestOnly
    fun setLastHeartbeat(offsetMs: Long) {
        lastHeartbeatNs = System.nanoTime() - offsetMs * 1_000_000
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }
}

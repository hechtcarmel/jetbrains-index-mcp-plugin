package com.github.hechtcarmel.jetbrainsindexmcpplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class ToolUsageTracker {

    data class ToolStats(
        val calls: AtomicInteger = AtomicInteger(0),
        val totalChars: AtomicLong = AtomicLong(0)
    ) {
        val averageChars: Long
            get() {
                val c = calls.get()
                return if (c > 0) totalChars.get() / c else 0
            }
    }

    private val stats = ConcurrentHashMap<String, ToolStats>()

    fun record(toolName: String, responseChars: Int) {
        val toolStats = stats.computeIfAbsent(toolName) { ToolStats() }
        toolStats.calls.incrementAndGet()
        toolStats.totalChars.addAndGet(responseChars.toLong())
    }

    fun getStats(): Map<String, ToolStats> = stats.toMap()

    fun getTotalCalls(): Int = stats.values.sumOf { it.calls.get() }

    fun getTotalChars(): Long = stats.values.sumOf { it.totalChars.get() }

    fun reset() = stats.clear()

    companion object {
        fun getInstance(project: Project): ToolUsageTracker = project.service()
    }
}

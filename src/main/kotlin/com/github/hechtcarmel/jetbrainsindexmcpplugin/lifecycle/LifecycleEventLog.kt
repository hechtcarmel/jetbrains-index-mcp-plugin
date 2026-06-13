package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Design note: the ring buffer is always populated — it is cheap (one lock + deque write per
// event) and makes ide_lifecycle_log useful without any setup. The file write is gated on
// LOG.isDebugEnabled, which maps to IntelliJ's own debug-logging infrastructure: enable it
// per-session via Help → Diagnostic Tools → Debug Log Settings, adding the category
// "#com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle". No IDE restart required.
// This is the standard IntelliJ approach: zero overhead in production, full tracing on demand.
@Service(Service.Level.APP)
class LifecycleEventLog {

    data class Entry(
        val timestampMs: Long = System.currentTimeMillis(),
        val project: String,
        val path: String,
        /** open, closed, transition, enroll, release, wake */
        val event: String,
        val from: String? = null,
        val to: String? = null,
        /** focus_gained, focus_lost, timer:focus, timer:inactivity, timer:close, mcp_call, auto_open, user */
        val trigger: String
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("timestamp", ISO.format(Instant.ofEpochMilli(timestampMs).atOffset(ZoneOffset.UTC)))
            put("project", project)
            put("path", path)
            put("event", event)
            from?.let { put("from", it) }
            to?.let { put("to", it) }
            put("trigger", trigger)
        }

        fun toLogLine(): String {
            val ts = ISO.format(Instant.ofEpochMilli(timestampMs).atOffset(ZoneOffset.UTC))
            val modeChange = if (from != null && to != null) ": $from→$to" else ""
            return "$ts [$trigger] $project$modeChange  ($path)"
        }
    }

    private val buffer = ArrayDeque<Entry>()
    val logFilePath: Path = Path.of(PathManager.getLogPath(), "mcp-lifecycle.log")

    fun log(entry: Entry) {
        val capacity = runCatching { McpSettings.getInstance().lifecycleLogBufferSize }
            .getOrDefault(DEFAULT_CAPACITY)
        synchronized(buffer) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(entry)
        }
        val writeToFile = LOG.isDebugEnabled ||
            runCatching { McpSettings.getInstance().lifecycleLogToFile }.getOrDefault(false)
        if (writeToFile) {
            runCatching {
                Files.writeString(
                    logFilePath, entry.toLogLine() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
                )
            }
        }
    }

    fun recent(limit: Int = 50, pathFilter: String? = null): List<Entry> {
        val capacity = runCatching { McpSettings.getInstance().lifecycleLogBufferSize }
            .getOrDefault(DEFAULT_CAPACITY)
        return synchronized(buffer) { buffer.toList() }
            .asReversed()
            .let { if (pathFilter != null) it.filter { e -> e.path.contains(pathFilter) } else it }
            .take(limit.coerceIn(1, capacity))
    }

    val size: Int get() = synchronized(buffer) { buffer.size }

    companion object {
        const val DEFAULT_CAPACITY = 500
        private val LOG = logger<LifecycleEventLog>()
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun getInstance(): LifecycleEventLog = service()
    }
}

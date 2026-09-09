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
        /** open, closed, transition, enroll, release, wake, editors_closed, editors_restored */
        val event: String,
        val from: String? = null,
        val to: String? = null,
        /** focus_gained, focus_lost, timer:focus, timer:inactivity, timer:close, mcp_call, auto_open, user */
        val trigger: String,
        /**
         * Why it happened and what it did, in words — e.g. how long a project was idle when the
         * inactivity timer fired, or how many editor tabs a dormant transition closed. Optional:
         * absent from the JSON when there is nothing to add.
         */
        val detail: String? = null
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("timestamp", ISO.format(Instant.ofEpochMilli(timestampMs).atOffset(ZoneOffset.UTC)))
            put("project", project)
            put("path", path)
            put("event", event)
            from?.let { put("from", it) }
            to?.let { put("to", it) }
            put("trigger", trigger)
            detail?.let { put("detail", it) }
        }

        /**
         * One human-readable line for `mcp-lifecycle.log`.
         *
         * A mode change is rendered as `from→to`; any other event is named explicitly, so an
         * `enroll` entry no longer reads exactly like a per-call marker (issue #369: the bare
         * line carrying only the `mcp_call` trigger and the project name was mistaken for
         * "the timer was reset here").
         */
        fun toLogLine(): String {
            val ts = ISO.format(Instant.ofEpochMilli(timestampMs).atOffset(ZoneOffset.UTC))
            val what = when {
                from != null && to != null -> ": $from→$to"
                event != trigger -> ": $event"
                else -> ""
            }
            val extra = detail?.let { " — $it" } ?: ""
            return "$ts [$trigger] $project$what$extra  ($path)"
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
        private val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss'Z'")
        fun getInstance(): LifecycleEventLog = service()

        /** `09:51:08Z` — the time of day in UTC, matching the timestamps of the log lines. */
        fun formatTimeOfDay(epochMs: Long): String =
            TIME_OF_DAY.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))

        /** `45s`, `2m 1s`, `1h 3m` — coarse enough for a log line, never negative. */
        fun formatDuration(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0) / 1000)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}

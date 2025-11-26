package com.github.hechtcarmel.jetbrainsindexmcpplugin.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

data class CommandEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
    val toolName: String,
    val parameters: JsonObject,
    var status: CommandStatus = CommandStatus.PENDING,
    var result: String? = null,
    var error: String? = null,
    var durationMs: Long? = null,
    var affectedFiles: List<String>? = null
)

enum class CommandStatus {
    PENDING,
    SUCCESS,
    ERROR
}

data class CommandFilter(
    val toolName: String? = null,
    val status: CommandStatus? = null,
    val searchText: String? = null
) {
    fun isEmpty(): Boolean = toolName == null && status == null && searchText == null
}

@Serializable
data class CommandEntryExport(
    val id: String,
    val timestamp: String,
    val toolName: String,
    val parameters: JsonObject,
    val status: String,
    val result: String?,
    val error: String?,
    val durationMs: Long?,
    val affectedFiles: List<String>?
)

fun CommandEntry.toExport(): CommandEntryExport = CommandEntryExport(
    id = id,
    timestamp = timestamp.toString(),
    toolName = toolName,
    parameters = parameters,
    status = status.name,
    result = result,
    error = error,
    durationMs = durationMs,
    affectedFiles = affectedFiles
)

sealed class CommandHistoryEvent {
    data class CommandAdded(val entry: CommandEntry) : CommandHistoryEvent()
    data class CommandUpdated(val entry: CommandEntry) : CommandHistoryEvent()
    data object HistoryCleared : CommandHistoryEvent()
}

interface CommandHistoryListener {
    fun onCommandAdded(entry: CommandEntry)
    fun onCommandUpdated(entry: CommandEntry)
    fun onHistoryCleared()
}

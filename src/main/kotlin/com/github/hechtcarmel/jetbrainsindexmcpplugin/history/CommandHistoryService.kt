package com.github.hechtcarmel.jetbrainsindexmcpplugin.history

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class CommandHistoryService(private val project: Project) {

    companion object {
        private val LOG = logger<CommandHistoryService>()

        fun getInstance(project: Project): CommandHistoryService = project.service()
    }

    private val history = CopyOnWriteArrayList<CommandEntry>()
    private val listeners = CopyOnWriteArrayList<CommandHistoryListener>()

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    val entries: List<CommandEntry>
        get() = history.toList()

    fun recordCommand(entry: CommandEntry) {
        history.add(0, entry)
        trimHistoryIfNeeded()
        notifyListeners(CommandHistoryEvent.CommandAdded(entry))
        LOG.debug("Recorded command: ${entry.toolName}")
    }

    fun updateCommandStatus(
        id: String,
        status: CommandStatus,
        result: String?,
        durationMs: Long? = null,
        affectedFiles: List<String>? = null
    ) {
        val entry = history.find { it.id == id } ?: return

        entry.status = status
        entry.result = result
        entry.durationMs = durationMs
        entry.affectedFiles = affectedFiles

        notifyListeners(CommandHistoryEvent.CommandUpdated(entry))
        LOG.debug("Updated command status: ${entry.toolName} -> $status")
    }

    fun clearHistory() {
        history.clear()
        notifyListeners(CommandHistoryEvent.HistoryCleared)
        LOG.info("Command history cleared")
    }

    fun getFilteredHistory(filter: CommandFilter): List<CommandEntry> {
        return history.filter { entry ->
            val matchesTool = filter.toolName == null || entry.toolName == filter.toolName
            val matchesStatus = filter.status == null || entry.status == filter.status
            val matchesSearch = filter.searchText == null ||
                entry.toolName.contains(filter.searchText, ignoreCase = true) ||
                entry.parameters.toString().contains(filter.searchText, ignoreCase = true) ||
                entry.result?.contains(filter.searchText, ignoreCase = true) == true

            matchesTool && matchesStatus && matchesSearch
        }
    }

    fun getUniqueToolNames(): List<String> {
        return history.map { it.toolName }.distinct().sorted()
    }

    fun exportToJson(): String {
        val exports = history.map { it.toExport() }
        return json.encodeToString(exports)
    }

    fun exportToCsv(): String {
        val header = "ID,Timestamp,Tool,Status,Duration(ms),Result,Error"
        val rows = history.map { entry ->
            listOf(
                entry.id,
                entry.timestamp.toString(),
                entry.toolName,
                entry.status.name,
                entry.durationMs?.toString() ?: "",
                entry.result?.replace(",", ";")?.replace("\n", " ")?.take(100) ?: "",
                entry.error?.replace(",", ";")?.replace("\n", " ") ?: ""
            ).joinToString(",") { "\"$it\"" }
        }

        return (listOf(header) + rows).joinToString("\n")
    }

    fun addListener(listener: CommandHistoryListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CommandHistoryListener) {
        listeners.remove(listener)
    }

    private fun trimHistoryIfNeeded() {
        val maxSize = try {
            McpSettings.getInstance().maxHistorySize
        } catch (e: Exception) {
            100
        }

        while (history.size > maxSize) {
            history.removeAt(history.size - 1)
        }
    }

    private fun notifyListeners(event: CommandHistoryEvent) {
        ApplicationManager.getApplication().invokeLater {
            when (event) {
                is CommandHistoryEvent.CommandAdded -> {
                    listeners.forEach { it.onCommandAdded(event.entry) }
                }
                is CommandHistoryEvent.CommandUpdated -> {
                    listeners.forEach { it.onCommandUpdated(event.entry) }
                }
                is CommandHistoryEvent.HistoryCleared -> {
                    listeners.forEach { it.onHistoryCleared() }
                }
            }
        }
    }
}

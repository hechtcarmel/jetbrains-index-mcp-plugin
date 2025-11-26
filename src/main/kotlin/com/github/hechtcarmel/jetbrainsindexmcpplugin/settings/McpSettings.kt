package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "McpPluginSettings",
    storages = [Storage("mcp-plugin.xml")]
)
class McpSettings : PersistentStateComponent<McpSettings.State> {

    data class State(
        var maxHistorySize: Int = 100,
        var autoScroll: Boolean = true,
        var showTimestamps: Boolean = true,
        var confirmWriteOperations: Boolean = true,
        var logToFile: Boolean = false,
        var logFilePath: String = ""
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var maxHistorySize: Int
        get() = state.maxHistorySize
        set(value) { state.maxHistorySize = value }

    var autoScroll: Boolean
        get() = state.autoScroll
        set(value) { state.autoScroll = value }

    var showTimestamps: Boolean
        get() = state.showTimestamps
        set(value) { state.showTimestamps = value }

    var confirmWriteOperations: Boolean
        get() = state.confirmWriteOperations
        set(value) { state.confirmWriteOperations = value }

    var logToFile: Boolean
        get() = state.logToFile
        set(value) { state.logToFile = value }

    var logFilePath: String
        get() = state.logFilePath
        set(value) { state.logFilePath = value }

    companion object {
        fun getInstance(): McpSettings = service()
    }
}

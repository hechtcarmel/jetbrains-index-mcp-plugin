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
        var syncExternalChanges: Boolean = false,
        var disabledTools: MutableSet<String> = mutableSetOf()
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var maxHistorySize: Int
        get() = state.maxHistorySize
        set(value) { state.maxHistorySize = value }

    var syncExternalChanges: Boolean
        get() = state.syncExternalChanges
        set(value) { state.syncExternalChanges = value }

    var disabledTools: Set<String>
        get() = state.disabledTools.toSet()
        set(value) { state.disabledTools = value.toMutableSet() }

    fun isToolEnabled(toolName: String): Boolean = toolName !in state.disabledTools

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        if (enabled) {
            state.disabledTools.remove(toolName)
        } else {
            state.disabledTools.add(toolName)
        }
    }

    companion object {
        fun getInstance(): McpSettings = service()
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
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

    enum class AvailableProjectsMode {
        EXPANDED,
        COMPACT
    }

    /**
     * Persistent state for MCP settings.
     * Note: serverPort defaults to -1 (unset), which means "use IDE-specific default".
     * This allows different IDEs to have different default ports.
     */
    data class State(
        var maxHistorySize: Int = 100,
        var syncExternalChanges: Boolean = false,
        var availableProjectsMode: AvailableProjectsMode = AvailableProjectsMode.EXPANDED,
        var disabledTools: MutableSet<String> = mutableSetOf("ide_build_project", "ide_file_structure", "ide_find_symbol", "ide_read_file", "ide_get_active_file", "ide_open_file", "ide_reformat_code", "ide_optimize_imports", "ide_convert_java_to_kotlin"),
        var serverPort: Int = -1, // -1 means use IDE-specific default
        var serverHost: String = McpConstants.DEFAULT_SERVER_HOST
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

    var availableProjectsMode: AvailableProjectsMode
        get() = state.availableProjectsMode
        set(value) { state.availableProjectsMode = value }

    var disabledTools: Set<String>
        get() = state.disabledTools.toSet()
        set(value) { state.disabledTools = value.toMutableSet() }

    var serverPort: Int
        get() = if (state.serverPort == -1) McpConstants.getDefaultServerPort() else state.serverPort
        set(value) { state.serverPort = value }

    var serverHost: String
        get() = state.serverHost
        set(value) { state.serverHost = value }

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

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

    /**
     * Persistent state for MCP settings.
     * Note: serverPort defaults to -1 (unset), which means "use IDE-specific default".
     * This allows different IDEs to have different default ports.
     */
    data class State(
        var maxHistorySize: Int = 100,
        var syncExternalChanges: Boolean = false,
        var disabledTools: MutableSet<String> = mutableSetOf("ide_file_structure"),
        var serverPort: Int = -1, // -1 means use IDE-specific default
        var migratedToVersion: Int = 0 // Track migration status (2 = v2.0.0 migration done)
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

    var serverPort: Int
        get() = if (state.serverPort == -1) McpConstants.getDefaultServerPort() else state.serverPort
        set(value) { state.serverPort = value }

    fun isToolEnabled(toolName: String): Boolean = toolName !in state.disabledTools

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        if (enabled) {
            state.disabledTools.remove(toolName)
        } else {
            state.disabledTools.add(toolName)
        }
    }

    /**
     * Checks if migration to v2.0.0 is needed (user upgrading from v1.x).
     * Returns true if user had the plugin installed before v2.0.0.
     */
    fun needsV2Migration(): Boolean {
        // If already migrated to v2, no need
        if (state.migratedToVersion >= 2) return false

        // If this is a fresh install (all defaults), no migration needed
        // A fresh install would have: serverPort=-1, maxHistorySize=100, no disabled tools
        val isFreshInstall = state.serverPort == -1 &&
            state.maxHistorySize == 100 &&
            state.disabledTools.isEmpty() &&
            !state.syncExternalChanges

        return !isFreshInstall
    }

    /**
     * Marks the v2.0.0 migration as complete.
     */
    fun markV2MigrationComplete() {
        state.migratedToVersion = 2
    }

    companion object {
        fun getInstance(): McpSettings = service()
    }
}

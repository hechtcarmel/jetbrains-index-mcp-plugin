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

    enum class ResponseFormat {
        JSON,
        TOON
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
        var responseFormat: ResponseFormat = ResponseFormat.JSON,
        var disabledTools: MutableSet<String> = mutableSetOf(
            "ide_build_project", "ide_close_project", "ide_file_structure", "ide_find_symbol",
            "ide_open_project", "ide_read_file", "ide_get_active_file", "ide_open_file",
            "ide_reformat_code", "ide_optimize_imports", "ide_convert_java_to_kotlin",
            "ide_set_power_save_mode", "ide_install_plugin", "ide_restart",
            "ide_enroll_all_projects", "ide_get_project_modes", "ide_lifecycle_log",
            "ide_set_lifecycle_log_file", "ide_release_all_projects",
            "ide_release_project", "ide_set_all_project_modes", "ide_set_project_mode"
        ),
        var serverPort: Int = -1, // -1 means use IDE-specific default
        var serverHost: String = McpConstants.DEFAULT_SERVER_HOST,
        // Lifecycle management
        var lifecycleEnabled: Boolean = false,
        var focusToBackgroundMinutes: Int = 2,
        var backgroundToDormantMinutes: Int = 2,
        var dormantToClosedMinutes: Int = 10,
        var lifecycleLogBufferSize: Int = 500,
        // Set true to write events to mcp-lifecycle.log (also enabled by LOG.isDebugEnabled).
        // Introduced in the stateless-tools PR; declared here so LifecycleEventLog can read it.
        var lifecycleLogToFile: Boolean = false,
        var minimumOpenProjects: Int = 4,
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

    var responseFormat: ResponseFormat
        get() = state.responseFormat
        set(value) { state.responseFormat = value }

    var disabledTools: Set<String>
        get() = state.disabledTools.toSet()
        set(value) { state.disabledTools = value.toMutableSet() }

    var serverPort: Int
        get() = if (state.serverPort == -1) McpConstants.getDefaultServerPort() else state.serverPort
        set(value) { state.serverPort = value }

    var serverHost: String
        get() = state.serverHost
        set(value) { state.serverHost = value }

    var lifecycleEnabled: Boolean
        get() = state.lifecycleEnabled
        set(value) { state.lifecycleEnabled = value }

    var focusToBackgroundMinutes: Int
        get() = state.focusToBackgroundMinutes
        set(value) { state.focusToBackgroundMinutes = value }

    var backgroundToDormantMinutes: Int
        get() = state.backgroundToDormantMinutes
        set(value) { state.backgroundToDormantMinutes = value }

    var dormantToClosedMinutes: Int
        get() = state.dormantToClosedMinutes
        set(value) { state.dormantToClosedMinutes = value }

    var lifecycleLogBufferSize: Int
        get() = state.lifecycleLogBufferSize
        set(value) { state.lifecycleLogBufferSize = value }

    var lifecycleLogToFile: Boolean
        get() = state.lifecycleLogToFile
        set(value) { state.lifecycleLogToFile = value }

    var minimumOpenProjects: Int
        get() = state.minimumOpenProjects
        set(value) { state.minimumOpenProjects = value }


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

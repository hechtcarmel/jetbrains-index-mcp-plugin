package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

private object ToolSettingsDefaults {
    const val CURRENT_SCHEMA_VERSION = 6

    val DEFAULT_DISABLED_TOOLS: Set<String> = setOf(
        ToolNames.BUILD_PROJECT,
        ToolNames.CLOSE_PROJECT,
        ToolNames.CREATE_MODULE,
        ToolNames.IMPORT_MODULES,
        ToolNames.LIST_TESTS,
        ToolNames.RELOAD_PROJECT,
        ToolNames.FILE_STRUCTURE,
        ToolNames.FIND_SYMBOL,
        ToolNames.OPEN_PROJECT,
        ToolNames.OPEN_WORKSPACE,
        ToolNames.READ_FILE,
        ToolNames.GET_ACTIVE_FILE,
        ToolNames.OPEN_FILE,
        ToolNames.REFORMAT_CODE,
        ToolNames.OPTIMIZE_IMPORTS,
        ToolNames.CONVERT_JAVA_TO_KOTLIN,
        ToolNames.RUN_TESTS,
        ToolNames.SET_POWER_SAVE_MODE,
        ToolNames.INSTALL_PLUGIN,
        ToolNames.RESTART_IDE,
        ToolNames.ENROLL_ALL_PROJECTS,
        ToolNames.GET_PROJECT_MODES,
        ToolNames.LIFECYCLE_LOG,
        ToolNames.LIFECYCLE_LOG_FILE,
        ToolNames.RELEASE_ALL_PROJECTS,
        ToolNames.RELEASE_PROJECT,
        ToolNames.SET_ALL_PROJECT_MODES,
        ToolNames.SET_PROJECT_MODE,
        ToolNames.CHANGE_SIGNATURE,
        ToolNames.CREATE_FILE,
        ToolNames.REPLACE_TEXT_IN_FILE,
        ToolNames.STRUCTURAL_SEARCH_REPLACE,
        ToolNames.EDIT_MEMBER,
        ToolNames.INSERT_MEMBER,
        ToolNames.REPLACE_MEMBER,
    )

    // Add only newly introduced default-disabled tools here; old entries are snapshots
    // so legacy states keep explicit enables for older tools.
    val DEFAULT_DISABLED_TOOL_MIGRATIONS: List<Pair<Int, Set<String>>> = listOf(
        1 to setOf(ToolNames.IMPORT_MODULES),
        2 to setOf(ToolNames.OPEN_WORKSPACE),
        3 to setOf(ToolNames.CHANGE_SIGNATURE, ToolNames.CREATE_FILE, ToolNames.REPLACE_TEXT_IN_FILE, ToolNames.STRUCTURAL_SEARCH_REPLACE),
        4 to setOf(ToolNames.LIST_TESTS, ToolNames.RUN_TESTS),
        5 to setOf(ToolNames.EDIT_MEMBER, ToolNames.INSERT_MEMBER, ToolNames.REPLACE_MEMBER),
        6 to setOf(ToolNames.CREATE_MODULE)
    )
}

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
        var disabledTools: MutableSet<String> = ToolSettingsDefaults.DEFAULT_DISABLED_TOOLS.toMutableSet(),
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
        var settingsSchemaVersion: Int = 0,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = migrateState(state)
    }

    private fun migrateState(loaded: State): State {
        val disabledTools = loaded.disabledTools.toMutableSet()

        for ((version, tools) in ToolSettingsDefaults.DEFAULT_DISABLED_TOOL_MIGRATIONS) {
            if (loaded.settingsSchemaVersion < version) {
                disabledTools.addAll(tools)
            }
        }

        return loaded.copy(
            disabledTools = disabledTools,
            settingsSchemaVersion = maxOf(
                loaded.settingsSchemaVersion,
                ToolSettingsDefaults.CURRENT_SCHEMA_VERSION
            )
        )
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
        set(value) {
            state.disabledTools = value.toMutableSet()
            state.settingsSchemaVersion = ToolSettingsDefaults.CURRENT_SCHEMA_VERSION
        }

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
        state.settingsSchemaVersion = ToolSettingsDefaults.CURRENT_SCHEMA_VERSION
    }

    fun updateToolEnabledStates(toolStates: Map<String, Boolean>) {
        val disabledTools = state.disabledTools.toMutableSet()
        for ((toolName, enabled) in toolStates) {
            if (enabled) {
                disabledTools.remove(toolName)
            } else {
                disabledTools.add(toolName)
            }
        }
        state.disabledTools = disabledTools
        state.settingsSchemaVersion = ToolSettingsDefaults.CURRENT_SCHEMA_VERSION
    }

    companion object {
        val DEFAULT_DISABLED_TOOLS: Set<String> get() = ToolSettingsDefaults.DEFAULT_DISABLED_TOOLS

        fun getInstance(): McpSettings = service()
    }
}

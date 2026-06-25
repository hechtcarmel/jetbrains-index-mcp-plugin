package com.github.hechtcarmel.jetbrainsindexmcpplugin.constants

object ToolNames {
    // Navigation tools
    const val FIND_REFERENCES = "ide_find_references"
    const val FIND_DEFINITION = "ide_find_definition"
    const val TYPE_HIERARCHY = "ide_type_hierarchy"
    const val CALL_HIERARCHY = "ide_call_hierarchy"
    const val FIND_IMPLEMENTATIONS = "ide_find_implementations"
    const val FIND_SYMBOL = "ide_find_symbol"
    const val FIND_SUPER_METHODS = "ide_find_super_methods"
    const val FILE_STRUCTURE = "ide_file_structure"
    const val FIND_CLASS = "ide_find_class"
    const val FIND_FILE = "ide_find_file"
    const val SEARCH_TEXT = "ide_search_text"
    const val READ_FILE = "ide_read_file"

    // Intelligence tools
    const val DIAGNOSTICS = "ide_diagnostics"

    // Project tools
    const val INDEX_STATUS = "ide_index_status"
    const val SYNC_FILES = "ide_sync_files"
    const val BUILD_PROJECT = "ide_build_project"
    const val IMPORT_MODULES = "ide_import_modules"
    const val RELOAD_PROJECT = "ide_reload_project"

    // Refactoring tools
    const val REFACTOR_RENAME = "ide_refactor_rename"
    const val REFACTOR_SAFE_DELETE = "ide_refactor_safe_delete"
    const val REFACTOR_MOVE = "ide_move_file"
    const val REFORMAT_CODE = "ide_reformat_code"
    const val OPTIMIZE_IMPORTS = "ide_optimize_imports"
    const val CONVERT_JAVA_TO_KOTLIN = "ide_convert_java_to_kotlin"

    // Editor tools
    const val GET_ACTIVE_FILE = "ide_get_active_file"
    const val OPEN_FILE = "ide_open_file"

    // Plugin development tools
    const val INSTALL_PLUGIN = "ide_install_plugin"
    const val RESTART_IDE = "ide_restart"
    // Project window management
    const val CLOSE_PROJECT = "ide_close_project"
    const val OPEN_PROJECT = "ide_open_project"
    const val SET_POWER_SAVE_MODE = "ide_set_power_save_mode"

    // Lifecycle management
    const val ENROLL_ALL_PROJECTS = "ide_enroll_all_projects"
    const val GET_PROJECT_MODES = "ide_get_project_modes"
    const val LIFECYCLE_LOG = "ide_lifecycle_log"
    const val LIFECYCLE_LOG_FILE = "ide_set_lifecycle_log_file"
    const val PROJECT_STATUS = "ide_project_status"
    const val RELEASE_ALL_PROJECTS = "ide_release_all_projects"
    const val RELEASE_PROJECT = "ide_release_project"
    const val SET_ALL_PROJECT_MODES = "ide_set_all_project_modes"
    const val SET_PROJECT_MODE = "ide_set_project_mode"

    /**
     * All known tool names, sorted alphabetically.
     * Keep this list in sync when adding or removing tool name constants.
     */
    val ALL: List<String> = listOf(
        BUILD_PROJECT,
        CALL_HIERARCHY,
        CLOSE_PROJECT,
        CONVERT_JAVA_TO_KOTLIN,
        DIAGNOSTICS,
        ENROLL_ALL_PROJECTS,
        FILE_STRUCTURE,
        FIND_CLASS,
        FIND_DEFINITION,
        FIND_FILE,
        FIND_IMPLEMENTATIONS,
        FIND_REFERENCES,
        FIND_SUPER_METHODS,
        FIND_SYMBOL,
        GET_ACTIVE_FILE,
        GET_PROJECT_MODES,
        IMPORT_MODULES,
        INDEX_STATUS,
        INSTALL_PLUGIN,
        LIFECYCLE_LOG,
        REFACTOR_MOVE,
        OPEN_FILE,
        OPEN_PROJECT,
        OPTIMIZE_IMPORTS,
        PROJECT_STATUS,
        READ_FILE,
        REFACTOR_RENAME,
        REFACTOR_SAFE_DELETE,
        REFORMAT_CODE,
        RELEASE_ALL_PROJECTS,
        RELEASE_PROJECT,
        RELOAD_PROJECT,
        RESTART_IDE,
        SEARCH_TEXT,
        SET_ALL_PROJECT_MODES,
        LIFECYCLE_LOG_FILE,
        SET_POWER_SAVE_MODE,
        SET_PROJECT_MODE,
        SYNC_FILES,
        TYPE_HIERARCHY
    )
}

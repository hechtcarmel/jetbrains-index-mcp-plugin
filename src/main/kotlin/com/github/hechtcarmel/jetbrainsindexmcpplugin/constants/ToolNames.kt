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

    // Refactoring tools
    const val REFACTOR_RENAME = "ide_refactor_rename"
    const val REFACTOR_SAFE_DELETE = "ide_refactor_safe_delete"

    // Editor tools
    const val GET_ACTIVE_FILE = "ide_get_active_file"
    const val OPEN_FILE = "ide_open_file"

    /**
     * All known tool names, sorted alphabetically.
     * Keep this list in sync when adding or removing tool name constants.
     */
    val ALL: List<String> = listOf(
        CALL_HIERARCHY,
        DIAGNOSTICS,
        FILE_STRUCTURE,
        FIND_CLASS,
        FIND_DEFINITION,
        FIND_FILE,
        FIND_IMPLEMENTATIONS,
        FIND_REFERENCES,
        FIND_SUPER_METHODS,
        FIND_SYMBOL,
        GET_ACTIVE_FILE,
        INDEX_STATUS,
        OPEN_FILE,
        READ_FILE,
        REFACTOR_RENAME,
        REFACTOR_SAFE_DELETE,
        SEARCH_TEXT,
        SYNC_FILES,
        TYPE_HIERARCHY
    )
}

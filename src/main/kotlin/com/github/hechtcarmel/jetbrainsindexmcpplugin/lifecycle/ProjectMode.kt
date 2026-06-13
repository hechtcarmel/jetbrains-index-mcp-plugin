package com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle

enum class ProjectMode {
    /** Full IntelliJ capabilities. User has the project window focused. */
    ACTIVE,

    /** Power Save Mode ON. Index and MCP fully functional. Background inspections off. */
    BACKGROUND,

    /** Power Save ON + editors closed. PSI references released via GC; index stays loaded. */
    DORMANT,

    /** Project fully closed. All memory freed. Auto-reopens on next MCP call. */
    CLOSED
}

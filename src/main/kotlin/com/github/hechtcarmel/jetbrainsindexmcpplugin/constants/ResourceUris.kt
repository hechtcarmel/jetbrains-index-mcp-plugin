package com.github.hechtcarmel.jetbrainsindexmcpplugin.constants

object ResourceUris {
    // Fixed URIs
    const val INDEX_STATUS = "index://status"
    const val PROJECT_STRUCTURE = "project://structure"

    // Parameterized URI patterns
    const val FILE_CONTENT_PATTERN = "file://content/{path}"
    const val SYMBOL_INFO_PATTERN = "symbol://info/{fqn}"

    // Prefixes for pattern matching in handlers
    const val FILE_CONTENT_PREFIX = "file://content/"
    const val SYMBOL_INFO_PREFIX = "symbol://info/"
}

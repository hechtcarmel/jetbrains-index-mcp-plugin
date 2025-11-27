package com.github.hechtcarmel.jetbrainsindexmcpplugin.constants

object ErrorMessages {
    // Parameter validation errors
    fun missingRequiredParam(param: String) = "Missing required parameter: $param"

    // File errors
    fun fileNotFound(path: String) = "File not found: $path"
    const val DOCUMENT_NOT_FOUND = "Could not get document for file"
    const val DEFINITION_FILE_NOT_FOUND = "Definition file not found"

    // Symbol/element errors
    fun noElementAtPosition(file: String, line: Int, column: Int) =
        "No element found at position $file:$line:$column"
    const val SYMBOL_NOT_RESOLVED = "Could not resolve symbol definition"
    const val NO_NAMED_ELEMENT = "No named element at position"
    const val COULD_NOT_RESOLVE_SYMBOL = "Could not resolve symbol"
    const val DEFINITION_DOCUMENT_NOT_FOUND = "Could not get document for definition"

    // Project resolution errors (used as error codes in JSON)
    const val ERROR_NO_PROJECT_OPEN = "no_project_open"
    const val ERROR_PROJECT_NOT_FOUND = "project_not_found"
    const val ERROR_MULTIPLE_PROJECTS = "multiple_projects_open"

    // Project resolution messages
    const val MSG_NO_PROJECT_OPEN = "No project is currently open in the IDE."
    fun msgProjectNotFound(path: String) = "No open project matches the specified path: $path"
    const val MSG_MULTIPLE_PROJECTS = "Multiple projects are open. Please specify 'project_path' parameter with one of the available project paths."

    // Index errors
    const val INDEX_NOT_READY = "IDE is in dumb mode, indexes not available"

    // Tool/method errors
    fun toolNotFound(name: String) = "Tool not found: $name"
    fun resourceNotFound(uri: String) = "Resource not found: $uri"
    fun methodNotFound(method: String) = "Method not found: $method"

    // JSON-RPC errors
    const val PARSE_ERROR = "Failed to parse JSON-RPC request"
    const val MISSING_PARAMS = "Missing params"
    const val MISSING_TOOL_NAME = "Missing tool name"
    const val MISSING_RESOURCE_URI = "Missing resource URI"
    const val UNKNOWN_ERROR = "Unknown error"
}

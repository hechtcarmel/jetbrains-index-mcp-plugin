package com.github.hechtcarmel.jetbrainsindexmcpplugin.constants

object SchemaConstants {
    // Schema structure keys
    const val TYPE = "type"
    const val DESCRIPTION = "description"
    const val PROPERTIES = "properties"
    const val REQUIRED = "required"
    const val ITEMS = "items"
    const val ENUM = "enum"

    // Schema types
    const val TYPE_OBJECT = "object"
    const val TYPE_STRING = "string"
    const val TYPE_INTEGER = "integer"
    const val TYPE_BOOLEAN = "boolean"
    const val TYPE_ARRAY = "array"

    // Common parameter descriptions
    const val DESC_PROJECT_PATH = "Absolute project root; required with multiple open projects. For workspaces use the sub-project path."
    const val DESC_FILE = "Path to the file relative to project root"
    const val DESC_LINE = "1-based line number"
    const val DESC_COLUMN = "1-based column number"
    const val DESC_START_LINE = "1-based start line number"
    const val DESC_END_LINE = "1-based end line number"
    const val DESC_START_COLUMN = "1-based start column number"
    const val DESC_END_COLUMN = "1-based end column number"
    const val DESC_NEW_NAME = "The new name for the symbol"
    const val DESC_METHOD_NAME = "Name for the extracted method"
    const val DESC_VARIABLE_NAME = "Name for the extracted variable"
    const val DESC_TARGET_PACKAGE = "Target package for the move operation"
    const val DESC_DIRECTION = "Direction for hierarchy traversal"
    const val DESC_MAX_RESULTS = "Maximum number of results to return"
    const val DESC_SYMBOL = "Fully qualified symbol reference. Format: 'com.example.ClassName' or 'com.example.ClassName#memberName'. Omit generics parameters."
    const val DESC_LANGUAGE = "Language of the symbol. Required when using 'symbol' parameter."
    const val DESC_PATHS = "Globs over result paths (project-relative; absolute outside the project), e.g. " +
        "[\"src/main/**\", \"!**/*Test.kt\"]. '*' stays within a segment, '**' crosses directories, plain directories " +
        "include descendants, '!' excludes. Union includes, then subtract excludes; exclusion-only starts with all " +
        "results. Combines with scope/filePattern. Includes omit library/jar hits; exclusion-only keeps them. Include " +
        "globs need an existing literal prefix spelled as results show it."
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models

import kotlinx.serialization.Serializable

/**
 * Represents a single node in the file structure tree.
 *
 * @property name The name of the element (class name, method name, etc.)
 * @property kind The type of structure element (class, method, field, etc.)
 * @property modifiers List of modifiers (public, private, static, etc.)
 * @property signature Optional signature information (method parameters, return type, etc.)
 * @property line The line number where this element is defined
 * @property children Child elements (e.g., methods within a class)
 */
@Serializable
data class StructureNode(
    val name: String,
    val kind: StructureKind,
    val modifiers: List<String>,
    val signature: String?,
    val line: Int,
    val children: List<StructureNode> = emptyList()
)

/**
 * Defines the type of structure element.
 */
@Serializable
enum class StructureKind {
    // Type declarations
    CLASS, INTERFACE, ENUM, ANNOTATION, RECORD, OBJECT, TRAIT,

    // Members
    METHOD, FIELD, PROPERTY, CONSTRUCTOR,

    // Language-specific
    FUNCTION, VARIABLE, TYPE_ALIAS,

    // Containers
    NAMESPACE, PACKAGE, MODULE,

    // Other
    UNKNOWN
}

/**
 * Output model for file structure tool.
 *
 * @property file The file path relative to project root
 * @property language The language ID (e.g., "JAVA", "Python", "kotlin")
 * @property structure The formatted tree string
 */
@Serializable
data class FileStructureResult(
    val file: String,
    val language: String,
    val structure: String
)

package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models

import com.intellij.psi.PsiElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents a single node in the file structure tree.
 *
 * @property name The name of the element (class name, method name, etc.)
 * @property kind The type of structure element (class, method, field, etc.)
 * @property modifiers List of modifiers (public, private, static, etc.)
 * @property signature Optional signature information (method parameters, return type, etc.)
 * @property line The line number where this element is defined
 * @property endLine The final source line covered by this element, when known
 * @property children Child elements (e.g., methods within a class)
 * @property symbolId Opaque session handle for the exact PSI declaration, when one exists
 */
@Serializable
data class StructureNode(
    val name: String,
    val kind: StructureKind,
    val modifiers: List<String>,
    val signature: String?,
    val line: Int,
    val endLine: Int? = null,
    val children: List<StructureNode> = emptyList(),
    val symbolId: String? = null,
    @Transient internal val pointerTarget: PsiElement? = null
)

/**
 * Defines the type of structure element.
 */
@Serializable
enum class StructureKind {
    // Type declarations
    CLASS, INTERFACE, ENUM, ANNOTATION, RECORD, OBJECT, TRAIT,

    // Members
    METHOD, FIELD, PROPERTY, CONSTRUCTOR, CONSTANT, ENUM_CASE,

    // Language-specific (e.g. TypeScript type aliases, PHP includes, Markdown headings)
    FUNCTION, VARIABLE, TYPE_ALIAS, HEADING, INCLUDE,

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
 * @property nodes The same hierarchy as structured data when includeNodes/includeSymbolIds is requested
 * @property symbolIdsTruncated Whether the per-response handle budget omitted eligible nodes
 * @property symbolIdsOmitted Number of eligible nodes without a handle because of that budget
 */
@Serializable
data class FileStructureResult(
    val file: String,
    val language: String,
    val structure: String,
    val nodes: List<StructureNode> = emptyList(),
    val symbolIdsTruncated: Boolean = false,
    val symbolIdsOmitted: Int = 0
)

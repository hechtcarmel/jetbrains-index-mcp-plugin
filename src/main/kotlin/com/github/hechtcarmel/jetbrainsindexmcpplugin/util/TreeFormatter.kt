package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode

/**
 * Utility for formatting structure nodes as a tree string.
 *
 * Uses 2-space indentation per nesting level to show hierarchical relationships.
 */
object TreeFormatter {

    /**
     * Formats structure nodes as a tree string.
     *
     * @param nodes List of top-level structure nodes
     * @param fileName The file name to display as header
     * @param language Language ID for language-specific formatting (e.g., "Java", "kotlin", "Python")
     * @return Formatted tree string
     */
    fun format(nodes: List<StructureNode>, fileName: String, language: String = ""): String {
        val lines = mutableListOf<String>()

        // Add file header
        lines.add("$fileName")
        lines.add("")

        // Format all top-level nodes
        nodes.forEach { node ->
            formatNode(node, indent = 0, output = lines, language = language)
        }

        return lines.joinToString("\n")
    }

    /**
     * Recursively formats a structure node and its children.
     *
     * @param node The node to format
     * @param indent The indentation level (number of 2-space units)
     * @param output The output list to append formatted lines to
     * @param language Language ID for language-specific formatting
     */
    private fun formatNode(
        node: StructureNode,
        indent: Int,
        output: MutableList<String>,
        language: String
    ) {
        val indentStr = "  ".repeat(indent)
        val line = buildNodeLine(node, language)
        output.add(indentStr + line)

        // Format children with increased indentation
        if (node.children.isNotEmpty()) {
            node.children.forEach { child ->
                formatNode(child, indent + 1, output, language)
            }
        }
    }

    /**
     * Builds a single line representing a structure node.
     *
     * @param node The structure node to format
     * @param language Language ID for language-specific formatting
     * @return Formatted line string
     */
    private fun buildNodeLine(node: StructureNode, language: String): String {
        val modifiers = if (node.modifiers.isNotEmpty()) {
            "${node.modifiers.joinToString(" ")} "
        } else ""

        val kind = kindToString(node.kind, language)
        val signature = if (!node.signature.isNullOrBlank()) {
            " ${node.signature}"
        } else ""

        return "$kind $modifiers${node.name}$signature (line ${node.line})"
    }

    /**
     * Converts StructureKind to a language-specific readable string.
     *
     * @param kind The structure kind to convert
     * @param language Language ID for language-specific formatting
     * @return Language-specific string representation of the kind
     */
    private fun kindToString(kind: StructureKind, language: String): String {
        // Normalize language ID (handle case-insensitive matching)
        val normalizedLanguage = language.lowercase()

        return when (kind) {
            StructureKind.CLASS -> "class"
            StructureKind.INTERFACE -> "interface"
            StructureKind.ENUM -> "enum"
            StructureKind.ANNOTATION -> "@interface"
            StructureKind.RECORD -> "record"
            StructureKind.OBJECT -> "object"
            StructureKind.TRAIT -> "trait"
            StructureKind.CONSTRUCTOR -> "constructor"
            StructureKind.NAMESPACE -> "namespace"
            StructureKind.PACKAGE -> "package"
            StructureKind.MODULE -> "module"
            StructureKind.TYPE_ALIAS -> "typealias"
            StructureKind.VARIABLE -> "var"
            StructureKind.UNKNOWN -> "unknown"

            // Language-specific keywords for methods, functions, fields, and properties
            StructureKind.METHOD -> when {
                normalizedLanguage == "java" -> "method"
                normalizedLanguage == "python" -> "method"
                normalizedLanguage == "kotlin" -> "fun"
                else -> "method"
            }
            StructureKind.FUNCTION -> when {
                normalizedLanguage == "java" -> "method"
                normalizedLanguage == "python" -> "def"
                normalizedLanguage == "kotlin" -> "fun"
                else -> "function"
            }
            StructureKind.FIELD -> when {
                normalizedLanguage == "java" -> "field"
                normalizedLanguage == "python" -> "variable"
                normalizedLanguage == "kotlin" -> "val"
                else -> "field"
            }
            StructureKind.PROPERTY -> when {
                normalizedLanguage == "java" -> "property"
                normalizedLanguage == "python" -> "property"
                normalizedLanguage == "kotlin" -> "val"
                else -> "property"
            }
        }
    }
}

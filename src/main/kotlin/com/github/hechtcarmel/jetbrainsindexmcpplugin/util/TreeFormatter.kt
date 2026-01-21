package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode

/**
 * Utility for formatting structure nodes as a tree string.
 *
 * Uses tree-drawing characters to show hierarchical relationships:
 * - ├── for non-last children
 * - └── for last children
 * - │   for vertical continuation lines
 * -     (4 spaces) for indentation after last child
 */
object TreeFormatter {

    /**
     * Formats structure nodes as a tree string.
     *
     * @param nodes List of top-level structure nodes
     * @param fileName The file name to display as header
     * @return Formatted tree string
     */
    fun format(nodes: List<StructureNode>, fileName: String): String {
        val lines = mutableListOf<String>()

        // Add file header
        lines.add("$fileName")
        lines.add("")

        // Format top-level nodes
        if (nodes.size == 1) {
            formatNode(nodes[0], "", lines, isLast = true)
        } else {
            nodes.forEachIndexed { index, node ->
                formatNode(node, "", lines, isLast = index == nodes.size - 1)
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Recursively formats a structure node and its children.
     *
     * @param node The node to format
     * @param prefix The prefix to add before this node (for indentation)
     * @param output The output list to append formatted lines to
     * @param isLast Whether this node is the last child of its parent
     */
    private fun formatNode(
        node: StructureNode,
        prefix: String,
        output: MutableList<String>,
        isLast: Boolean
    ) {
        // Build line for this node
        val connector = if (prefix.isEmpty()) "" else if (isLast) "└── " else "├── "
        val line = buildNodeLine(node, connector)
        output.add(prefix + line)

        // Format children
        if (node.children.isNotEmpty()) {
            val childPrefix = if (prefix.isEmpty()) "" else {
                if (isLast) "$prefix    " else "$prefix│   "
            }

            node.children.forEachIndexed { index, child ->
                formatNode(
                    child,
                    childPrefix,
                    output,
                    isLast = index == node.children.size - 1
                )
            }
        }
    }

    /**
     * Builds a single line representing a structure node.
     */
    private fun buildNodeLine(node: StructureNode, connector: String): String {
        val modifiers = if (node.modifiers.isNotEmpty()) {
            "${node.modifiers.joinToString(" ")} "
        } else ""

        val kind = kindToString(node.kind)
        val signature = if (!node.signature.isNullOrBlank()) {
            " ${node.signature}"
        } else ""

        return "$connector$kind $modifiers${node.name}$signature (line ${node.line})"
    }

    /**
     * Converts StructureKind to a readable string.
     */
    private fun kindToString(kind: StructureKind): String {
        return when (kind) {
            StructureKind.CLASS -> "class"
            StructureKind.INTERFACE -> "interface"
            StructureKind.ENUM -> "enum"
            StructureKind.ANNOTATION -> "@interface"
            StructureKind.RECORD -> "record"
            StructureKind.OBJECT -> "object"
            StructureKind.TRAIT -> "trait"
            StructureKind.METHOD -> "fun"
            StructureKind.FUNCTION -> "fun"
            StructureKind.FIELD -> "val"
            StructureKind.PROPERTY -> "val"
            StructureKind.CONSTRUCTOR -> "constructor"
            StructureKind.NAMESPACE -> "namespace"
            StructureKind.PACKAGE -> "package"
            StructureKind.MODULE -> "module"
            StructureKind.TYPE_ALIAS -> "typealias"
            StructureKind.VARIABLE -> "var"
            StructureKind.UNKNOWN -> "unknown"
        }
    }
}

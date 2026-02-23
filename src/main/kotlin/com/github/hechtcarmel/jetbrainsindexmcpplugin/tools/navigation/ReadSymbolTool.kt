package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ReadSymbolResult
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for reading a symbol's source code with depth-controlled progressive disclosure.
 *
 * This is the key "progressive disclosure" tool. It reads a symbol's source with depth control:
 * - depth=0: Returns the full element text
 * - depth>=1: Collapses children's bodies at the leaf depth level to `{...}`
 *
 * Supports both position-based (file+line+column) and name-based (file+symbolName) lookup.
 */
class ReadSymbolTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_MAX_PREVIEW_LINES = 200
        private const val MAX_ALLOWED_PREVIEW_LINES = 500
        private const val MAX_DEPTH = 3
    }

    override val name = ToolNames.READ_SYMBOL

    override val description = """
        Read a symbol's source code with depth-controlled preview. The key tool for progressive code disclosure.

        At depth=0: returns the full source code of the symbol.
        At depth=1+: collapses nested symbol bodies to `{...}` at the leaf depth level, showing structure without full implementation.

        Lookup: Either provide file + line + column (position-based) OR file + symbolName (name-based).

        Parameters: file (required), line + column (optional), symbolName (optional), depth (0-3, default 0), maxPreviewLines (default 200, max 500).

        Example: {"file": "src/Main.java", "line": 10, "column": 14} or {"file": "src/Main.java", "symbolName": "MyClass", "depth": 1}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject(ParamNames.FILE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
            }
            putJsonObject(ParamNames.LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based line number where the symbol is located. Required if symbolName is not provided.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number. Required if symbolName is not provided.")
            }
            putJsonObject(ParamNames.SYMBOL_NAME) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Name of the symbol to find in the file. Used when line/column are not provided.")
            }
            putJsonObject(ParamNames.DEPTH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Depth of nested symbol expansion. 0 = full source, 1+ = collapse children at leaf depth. Default: 0, Max: 3.")
            }
            putJsonObject(ParamNames.MAX_PREVIEW_LINES) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum lines for the preview. Truncates large symbols. Default: 200, Max: 500.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.FILE))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.FILE))
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
        val symbolName = arguments[ParamNames.SYMBOL_NAME]?.jsonPrimitive?.content
        val depth = (arguments[ParamNames.DEPTH]?.jsonPrimitive?.int ?: 0).coerceIn(0, MAX_DEPTH)
        val maxPreviewLines = (arguments[ParamNames.MAX_PREVIEW_LINES]?.jsonPrimitive?.int ?: DEFAULT_MAX_PREVIEW_LINES)
            .coerceIn(1, MAX_ALLOWED_PREVIEW_LINES)

        // Validate: either line+column or symbolName must be provided
        if (line == null && column == null && symbolName == null) {
            return createErrorResult("Either line+column or symbolName must be provided.")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            val psiFile = getPsiFile(project, file)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.fileNotFound(file))

            // Resolve the target element
            val targetElement = if (line != null && column != null) {
                resolveByPosition(project, file, line, column)
            } else if (symbolName != null) {
                resolveByName(psiFile, symbolName)
            } else {
                return@suspendingReadAction createErrorResult("Either line+column or symbolName must be provided.")
            }

            if (targetElement == null) {
                return@suspendingReadAction if (symbolName != null) {
                    createErrorResult("Symbol '$symbolName' not found in file: $file")
                } else {
                    createErrorResult(ErrorMessages.noElementAtPosition(file, line!!, column!!))
                }
            }

            val targetFile = targetElement.containingFile?.virtualFile
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.DEFINITION_FILE_NOT_FOUND)

            val document = PsiDocumentManager.getInstance(project)
                .getDocument(targetElement.containingFile)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.DEFINITION_DOCUMENT_NOT_FOUND)

            val targetLine = document.getLineNumber(targetElement.textOffset) + 1
            val targetColumn = targetElement.textOffset -
                document.getLineStartOffset(targetLine - 1) + 1

            // Generate the preview with depth control
            val fullText = targetElement.text
            val totalLines = fullText.lines().size

            val preview = if (depth == 0) {
                fullText
            } else {
                buildDepthControlledPreview(targetElement, depth)
            }

            // Truncate to maxPreviewLines
            val previewLines = preview.lines()
            val truncatedPreview = if (previewLines.size > maxPreviewLines) {
                previewLines.take(maxPreviewLines).joinToString("\n") +
                    "\n// ... truncated (${totalLines} total lines, showing $maxPreviewLines)"
            } else {
                preview
            }

            val elementName = (targetElement as? PsiNamedElement)?.name ?: "unknown"
            val elementKind = getElementKind(targetElement)

            createJsonResult(ReadSymbolResult(
                file = getRelativePath(project, targetFile),
                symbol = elementName,
                kind = elementKind,
                line = targetLine,
                column = targetColumn,
                depth = depth,
                preview = truncatedPreview,
                totalLines = totalLines
            ))
        }
    }

    /**
     * Resolves a symbol by position (line+column). Walks up to the nearest PsiNamedElement.
     */
    private fun resolveByPosition(project: Project, file: String, line: Int, column: Int): PsiNamedElement? {
        val rawElement = findPsiElement(project, file, line, column) ?: return null
        return PsiTreeUtil.getParentOfType(rawElement, PsiNamedElement::class.java, false)
    }

    /**
     * Resolves a symbol by name within a file. Finds the first PsiNamedElement with matching name.
     */
    private fun resolveByName(psiFile: PsiFile, symbolName: String): PsiNamedElement? {
        var result: PsiNamedElement? = null
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (result != null) return
                if (element is PsiNamedElement && element !is PsiFile && element.name == symbolName) {
                    result = element
                    return
                }
                super.visitElement(element)
            }
        })
        return result
    }

    /**
     * Builds a depth-controlled preview of a PSI element.
     *
     * At depth >= 1, children that are PsiNamedElements at the leaf depth level
     * have their bodies collapsed to `{...}` or `...`.
     *
     * Works by taking the full element text and replacing child text regions
     * with collapsed versions (processing children in reverse offset order to preserve positions).
     */
    private fun buildDepthControlledPreview(element: PsiElement, maxDepth: Int): String {
        return collapseAtDepth(element, maxDepth, 1)
    }

    /**
     * Recursively processes the element text, collapsing children at the target depth.
     */
    private fun collapseAtDepth(element: PsiElement, maxDepth: Int, currentDepth: Int): String {
        // Find direct named children
        val namedChildren = mutableListOf<PsiNamedElement>()
        for (child in element.children) {
            if (child is PsiNamedElement && child !is PsiFile) {
                namedChildren.add(child)
            }
        }

        if (namedChildren.isEmpty()) {
            return element.text
        }

        val elementText = element.text
        val elementStartOffset = element.textOffset

        // If we're at the leaf depth level, collapse children
        if (currentDepth >= maxDepth) {
            // Process children in reverse offset order to preserve positions
            val sortedChildren = namedChildren.sortedByDescending { it.textOffset }
            var result = elementText

            for (child in sortedChildren) {
                val childRelativeStart = child.textOffset - elementStartOffset
                val childRelativeEnd = childRelativeStart + child.textLength

                if (childRelativeStart < 0 || childRelativeEnd > result.length) continue

                val collapsed = collapseElement(child)
                result = result.substring(0, childRelativeStart) + collapsed + result.substring(childRelativeEnd)
            }

            return result
        }

        // Not yet at leaf depth: recurse into children
        val sortedChildren = namedChildren.sortedByDescending { it.textOffset }
        var result = elementText

        for (child in sortedChildren) {
            val childRelativeStart = child.textOffset - elementStartOffset
            val childRelativeEnd = childRelativeStart + child.textLength

            if (childRelativeStart < 0 || childRelativeEnd > result.length) continue

            val childPreview = collapseAtDepth(child, maxDepth, currentDepth + 1)
            result = result.substring(0, childRelativeStart) + childPreview + result.substring(childRelativeEnd)
        }

        return result
    }

    /**
     * Collapses an element's body to a short form.
     *
     * - If the element text contains `{`, takes everything before the first `{` + ` {...}`
     * - Otherwise, takes the first line + ` ...` if multi-line
     */
    private fun collapseElement(element: PsiElement): String {
        val text = element.text
        val braceIndex = text.indexOf('{')

        if (braceIndex >= 0) {
            val beforeBrace = text.substring(0, braceIndex).trimEnd()
            return "$beforeBrace { ... }"
        }

        // No brace found - take first line
        val lines = text.lines()
        return if (lines.size > 1) {
            "${lines.first()} ..."
        } else {
            text
        }
    }

    /**
     * Returns a human-readable kind string for the PSI element.
     */
    private fun getElementKind(element: PsiElement): String {
        // Check platform PsiClass (available without Java plugin)
        if (element is PsiClass) {
            return when {
                element.isInterface -> "interface"
                element.isEnum -> "enum"
                else -> "class"
            }
        }

        if (element is PsiMethod) {
            return "method"
        }

        // For other languages, inspect the class simple name
        val simpleName = element.javaClass.simpleName.lowercase()
        return when {
            "function" in simpleName -> "function"
            "method" in simpleName -> "method"
            "class" in simpleName -> "class"
            "interface" in simpleName -> "interface"
            "enum" in simpleName -> "enum"
            "property" in simpleName -> "property"
            "field" in simpleName -> "field"
            "variable" in simpleName -> "variable"
            "module" in simpleName -> "module"
            "package" in simpleName -> "package"
            "struct" in simpleName -> "struct"
            "trait" in simpleName -> "trait"
            "object" in simpleName -> "object"
            else -> simpleName.removeSuffix("impl").removeSuffix("element").ifEmpty { "symbol" }
        }
    }
}

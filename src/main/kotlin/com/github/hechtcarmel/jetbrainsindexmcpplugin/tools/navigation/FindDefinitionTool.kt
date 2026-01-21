package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class FindDefinitionTool : AbstractMcpTool() {

    override val name = ToolNames.FIND_DEFINITION

    override val description = """
        Navigate to where a symbol is defined (Go to Definition). Use when you see a symbol reference and need to find its declaration—works for classes, methods, variables, imports.

        Returns: file path, line/column of definition, code preview, and symbol name.

        Parameters: file + line + column (required).

        Example: {"file": "src/Main.java", "line": 15, "column": 10}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Absolute path to project root. Only needed when multiple projects are open in IDE.")
            }
            putJsonObject(ParamNames.FILE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
            }
            putJsonObject(ParamNames.LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based line number where the symbol reference is located. REQUIRED.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number within the line. REQUIRED.")
            }
            putJsonObject(ParamNames.FULL_ELEMENT_PREVIEW) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_BOOLEAN)
                put(SchemaConstants.DESCRIPTION, "If true, returns the complete element code instead of a preview snippet. Optional, defaults to false.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.FILE))
            add(JsonPrimitive(ParamNames.LINE))
            add(JsonPrimitive(ParamNames.COLUMN))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.FILE))
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.LINE))
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.COLUMN))
        val fullElementPreview = arguments[ParamNames.FULL_ELEMENT_PREVIEW]?.jsonPrimitive?.content?.toBoolean() ?: false

        requireSmartMode(project)

        return readAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult(ErrorMessages.noElementAtPosition(file, line, column))

            // Try to find a reference at this position
            val reference = element.reference ?: findReferenceInParent(element)

            val targetElement = if (reference != null) {
                reference.resolve()
            } else {
                // If the element itself is a declaration, return it
                PsiUtils.findNamedElement(element)
            }

            if (targetElement == null) {
                return@readAction createErrorResult(ErrorMessages.SYMBOL_NOT_RESOLVED)
            }

            val targetFile = targetElement.containingFile?.virtualFile
                ?: return@readAction createErrorResult(ErrorMessages.DEFINITION_FILE_NOT_FOUND)

            val document = PsiDocumentManager.getInstance(project)
                .getDocument(targetElement.containingFile)
                ?: return@readAction createErrorResult(ErrorMessages.DEFINITION_DOCUMENT_NOT_FOUND)

            val targetLine = document.getLineNumber(targetElement.textOffset) + 1
            val targetColumn = targetElement.textOffset -
                document.getLineStartOffset(targetLine - 1) + 1

            // Get preview - either full element code or a few lines around the definition
            val preview = if (fullElementPreview) {
                // Extract the complete element code
                targetElement.text
            } else {
                // Original behavior: a few lines around the definition
                val previewStartLine = maxOf(0, targetLine - 2)
                val previewEndLine = minOf(document.lineCount - 1, targetLine + 2)

                (previewStartLine until previewEndLine).joinToString("\n") { lineIndex ->
                    val startOffset = document.getLineStartOffset(lineIndex)
                    val endOffset = document.getLineEndOffset(lineIndex)
                    "${lineIndex + 1}: ${document.getText(TextRange(startOffset, endOffset))}"
                }
            }

            val symbolName = if (targetElement is PsiNamedElement) {
                targetElement.name ?: "unknown"
            } else {
                targetElement.text.take(50)
            }

            createJsonResult(DefinitionResult(
                file = getRelativePath(project, targetFile),
                line = targetLine,
                column = targetColumn,
                preview = preview,
                symbolName = symbolName
            ))
        }
    }

    private fun findReferenceInParent(element: PsiElement): PsiReference? {
        var current: PsiElement? = element
        repeat(3) {
            current = current?.parent ?: return null
            current?.reference?.let { return it }
        }
        return null
    }
}

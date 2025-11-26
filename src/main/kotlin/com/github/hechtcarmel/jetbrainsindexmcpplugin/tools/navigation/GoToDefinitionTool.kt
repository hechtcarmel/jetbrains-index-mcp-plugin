package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
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

class GoToDefinitionTool : AbstractMcpTool() {

    override val name = "go_to_definition"

    override val description = """
        Navigate to the definition of a symbol. Returns the file path, line number,
        and a code preview of the definition.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to the file relative to project root")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("line"))
            add(JsonPrimitive("column"))
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")

        requireSmartMode(project)

        return readAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult("No element found at position $file:$line:$column")

            // Try to find a reference at this position
            val reference = element.reference ?: findReferenceInParent(element)

            val targetElement = if (reference != null) {
                reference.resolve()
            } else {
                // If the element itself is a declaration, return it
                findNamedElement(element)
            }

            if (targetElement == null) {
                return@readAction createErrorResult("Could not resolve symbol definition")
            }

            val targetFile = targetElement.containingFile?.virtualFile
                ?: return@readAction createErrorResult("Definition file not found")

            val document = PsiDocumentManager.getInstance(project)
                .getDocument(targetElement.containingFile)
                ?: return@readAction createErrorResult("Could not get document for definition")

            val targetLine = document.getLineNumber(targetElement.textOffset) + 1
            val targetColumn = targetElement.textOffset -
                document.getLineStartOffset(targetLine - 1) + 1

            // Get preview - a few lines around the definition
            val previewStartLine = maxOf(0, targetLine - 2)
            val previewEndLine = minOf(document.lineCount - 1, targetLine + 2)

            val preview = (previewStartLine until previewEndLine).joinToString("\n") { lineIndex ->
                val startOffset = document.getLineStartOffset(lineIndex)
                val endOffset = document.getLineEndOffset(lineIndex)
                "${lineIndex + 1}: ${document.getText(TextRange(startOffset, endOffset))}"
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

    private fun findReferenceInParent(element: com.intellij.psi.PsiElement): PsiReference? {
        var current: com.intellij.psi.PsiElement? = element
        repeat(3) {
            current = current?.parent ?: return null
            current?.reference?.let { return it }
        }
        return null
    }

    private fun findNamedElement(element: com.intellij.psi.PsiElement): PsiNamedElement? {
        var current: com.intellij.psi.PsiElement? = element
        while (current != null) {
            if (current is PsiNamedElement && current.name != null) {
                return current
            }
            current = current.parent
        }
        return null
    }
}

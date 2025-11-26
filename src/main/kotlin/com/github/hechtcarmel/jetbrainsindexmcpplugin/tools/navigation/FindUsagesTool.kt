package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.UsageLocation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class FindUsagesTool : AbstractMcpTool() {

    override val name = "find_usages"

    override val description = """
        Find all usages of a symbol in the project using IntelliJ's semantic index.
        Returns a list of locations where the symbol is referenced.
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

            // Find the named element (go up the tree if needed)
            val targetElement = findNamedElement(element)
                ?: return@readAction createErrorResult("No named element at position")

            val usages = ReferencesSearch.search(targetElement)
                .findAll()
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val refFile = refElement.containingFile?.virtualFile
                        ?: return@mapNotNull null

                    val document = PsiDocumentManager.getInstance(project)
                        .getDocument(refElement.containingFile)
                        ?: return@mapNotNull null

                    val lineNumber = document.getLineNumber(refElement.textOffset) + 1
                    val columnNumber = refElement.textOffset -
                        document.getLineStartOffset(lineNumber - 1) + 1

                    val lineText = document.getText(
                        TextRange(
                            document.getLineStartOffset(lineNumber - 1),
                            document.getLineEndOffset(lineNumber - 1)
                        )
                    ).trim()

                    UsageLocation(
                        file = getRelativePath(project, refFile),
                        line = lineNumber,
                        column = columnNumber,
                        context = lineText,
                        type = classifyUsage(refElement)
                    )
                }

            createJsonResult(FindUsagesResult(
                usages = usages,
                totalCount = usages.size
            ))
        }
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

    private fun classifyUsage(element: com.intellij.psi.PsiElement): String {
        val parent = element.parent
        val parentClass = parent?.javaClass?.simpleName ?: "Unknown"

        return when {
            parentClass.contains("MethodCall") -> "METHOD_CALL"
            parentClass.contains("Reference") -> "REFERENCE"
            parentClass.contains("Field") -> "FIELD_ACCESS"
            parentClass.contains("Import") -> "IMPORT"
            parentClass.contains("Parameter") -> "PARAMETER"
            parentClass.contains("Variable") -> "VARIABLE"
            else -> "REFERENCE"
        }
    }
}

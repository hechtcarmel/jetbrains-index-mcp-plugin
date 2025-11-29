package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.UsageTypes
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.UsageLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
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

    override val name = ToolNames.FIND_REFERENCES

    override val description = """
        Finds all references to a symbol across the entire project using IntelliJ's semantic index.

        REQUIRED: file + line + column to identify the symbol to search for.

        RETURNS: All locations where the symbol is referenced, with context snippets and reference types.

        EXAMPLE: {"file": "src/main/java/com/example/UserService.java", "line": 25, "column": 18}
        This finds all places where the symbol at line 25, column 18 is used.
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
                put(SchemaConstants.DESCRIPTION, "1-based line number where the symbol is located. REQUIRED.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number within the line. REQUIRED.")
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

        requireSmartMode(project)

        return readAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@readAction createErrorResult(ErrorMessages.noElementAtPosition(file, line, column))

            // Find the named element (go up the tree if needed)
            val targetElement = PsiUtils.findNamedElement(element)
                ?: return@readAction createErrorResult(ErrorMessages.NO_NAMED_ELEMENT)

            val usages = mutableListOf<UsageLocation>()

            // Process references with cancellation support
            ReferencesSearch.search(targetElement).forEach { reference ->
                ProgressManager.checkCanceled() // Allow cancellation between iterations

                val refElement = reference.element
                val refFile = refElement.containingFile?.virtualFile ?: return@forEach

                val document = PsiDocumentManager.getInstance(project)
                    .getDocument(refElement.containingFile) ?: return@forEach

                val lineNumber = document.getLineNumber(refElement.textOffset) + 1
                val columnNumber = refElement.textOffset -
                    document.getLineStartOffset(lineNumber - 1) + 1

                val lineText = document.getText(
                    TextRange(
                        document.getLineStartOffset(lineNumber - 1),
                        document.getLineEndOffset(lineNumber - 1)
                    )
                ).trim()

                usages.add(UsageLocation(
                    file = getRelativePath(project, refFile),
                    line = lineNumber,
                    column = columnNumber,
                    context = lineText,
                    type = classifyUsage(refElement)
                ))
            }

            createJsonResult(FindUsagesResult(
                usages = usages,
                totalCount = usages.size
            ))
        }
    }

    private fun classifyUsage(element: PsiElement): String {
        val parent = element.parent
        val parentClass = parent?.javaClass?.simpleName ?: "Unknown"

        return when {
            parentClass.contains("MethodCall") -> UsageTypes.METHOD_CALL
            parentClass.contains("Reference") -> UsageTypes.REFERENCE
            parentClass.contains("Field") -> UsageTypes.FIELD_ACCESS
            parentClass.contains("Import") -> UsageTypes.IMPORT
            parentClass.contains("Parameter") -> UsageTypes.PARAMETER
            parentClass.contains("Variable") -> UsageTypes.VARIABLE
            else -> UsageTypes.REFERENCE
        }
    }
}

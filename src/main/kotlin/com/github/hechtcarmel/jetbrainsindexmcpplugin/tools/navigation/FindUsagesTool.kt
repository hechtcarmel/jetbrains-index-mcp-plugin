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
        Use when locating where a method, class, variable, or field is called or accessed.
        Use when understanding code dependencies or preparing for refactoring.
        Returns file locations with line numbers, column positions, context snippets, and reference types (METHOD_CALL, FIELD_ACCESS, IMPORT, etc.).
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
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_FILE)
            }
            putJsonObject(ParamNames.LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_LINE)
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_COLUMN)
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.FILE))
            add(JsonPrimitive(ParamNames.LINE))
            add(JsonPrimitive(ParamNames.COLUMN))
        }
    }

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
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

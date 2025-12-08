package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Safe delete tool that checks for usages before deletion.
 *
 * This implementation uses a two-phase approach to avoid UI freezes:
 * 1. **Background Phase**: Find element and check for usages (in read action)
 * 2. **EDT Phase**: Apply deletion quickly (in write action)
 */
class SafeDeleteTool : AbstractRefactoringTool() {

    override val name = "ide_refactor_safe_delete"

    override val description = """
        Delete a symbol safely by first checking for usages. Use when removing code to avoid breaking references.

        Behavior: If usages exist and force=false, returns the usage list instead of deleting. Use force=true to delete anyway (may break compilation).

        Returns: success status and affected files, OR blocking usages list. Modifies source files.

        Parameters: file + line + column (required), force (optional, default: false).

        Example: {"file": "src/OldClass.java", "line": 10, "column": 14}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("project_path") {
                put("type", "string")
                put("description", "Absolute path to project root. Only needed when multiple projects are open.")
            }
            putJsonObject("file") {
                put("type", "string")
                put("description", "Path to file relative to project root. REQUIRED.")
            }
            putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line number where the element is located. REQUIRED.")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number. REQUIRED.")
            }
            putJsonObject("force") {
                put("type", "boolean")
                put("description", "Force deletion even if usages exist. Optional, default: false. Use with caution!")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("file"))
            add(JsonPrimitive("line"))
            add(JsonPrimitive("column"))
        }
    }

    /**
     * Data class to hold all information collected in background for delete operation.
     */
    private data class DeletePreparation(
        val element: PsiNamedElement,
        val elementName: String,
        val elementType: String,
        val usages: List<UsageInfo>,
        val affectedFile: String
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")
        val force = arguments["force"]?.jsonPrimitive?.content?.toBoolean() ?: false

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Find element and check usages (read action)
        // ═══════════════════════════════════════════════════════════════════════
        val preparation = readAction {
            prepareDelete(project, file, line, column)
        } ?: return createErrorResult("No deletable element found at the specified position")

        // If there are usages and force is false, return them without deleting
        if (preparation.usages.isNotEmpty() && !force) {
            return createJsonResult(
                SafeDeleteBlockedResult(
                    canDelete = false,
                    elementName = preparation.elementName,
                    elementType = preparation.elementType,
                    usageCount = preparation.usages.size,
                    blockingUsages = preparation.usages.take(20),
                    message = "Cannot delete '${preparation.elementName}': found ${preparation.usages.size} usage(s). Use force=true to delete anyway."
                )
            )
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Apply deletion quickly (write action)
        // ═══════════════════════════════════════════════════════════════════════
        var success = false
        var errorMessage: String? = null

        withContext(Dispatchers.EDT) {
            WriteCommandAction.writeCommandAction(project)
                .withName("Safe Delete: ${preparation.elementName}")
                .withGroupId("MCP Refactoring")
                .run<Throwable> {
                    try {
                        if (preparation.element.isValid) {
                            preparation.element.delete()
                        }

                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        FileDocumentManager.getInstance().saveAllDocuments()

                        success = true
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                }
        }

        return if (success) {
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = listOf(preparation.affectedFile),
                    changesCount = 1,
                    message = if (force && preparation.usages.isNotEmpty()) {
                        "Force-deleted '${preparation.elementName}' (had ${preparation.usages.size} usage(s) that may now be broken)"
                    } else {
                        "Successfully deleted '${preparation.elementName}'"
                    }
                )
            )
        } else {
            createErrorResult("Safe delete failed: ${errorMessage ?: "Unknown error"}")
        }
    }

    /**
     * Prepares all data needed for delete in a read action.
     * This is the potentially slow part that runs in background.
     */
    private fun prepareDelete(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): DeletePreparation? {
        val element = findNamedElement(project, file, line, column) ?: return null

        val elementName = element.name ?: "unnamed"
        val elementType = getElementType(element)
        val affectedFile = element.containingFile?.virtualFile?.let {
            getRelativePath(project, it)
        } ?: file

        // Find usages (POTENTIALLY SLOW - but in background!)
        val usages = findUsages(project, element)

        return DeletePreparation(
            element = element,
            elementName = elementName,
            elementType = elementType,
            usages = usages,
            affectedFile = affectedFile
        )
    }

    private fun findUsages(project: Project, element: PsiNamedElement): List<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()

        try {
            ReferencesSearch.search(element).forEach { reference ->
                ProgressManager.checkCanceled() // Allow cancellation

                val refElement = reference.element
                val refFile = refElement.containingFile?.virtualFile

                if (refFile != null) {
                    val document = PsiDocumentManager.getInstance(project).getDocument(refElement.containingFile)
                    val lineNumber = document?.getLineNumber(refElement.textOffset)?.plus(1) ?: 0
                    val columnNumber = if (document != null && lineNumber > 0) {
                        val lineStart = document.getLineStartOffset(lineNumber - 1)
                        refElement.textOffset - lineStart + 1
                    } else {
                        0
                    }

                    usages.add(
                        UsageInfo(
                            file = getRelativePath(project, refFile),
                            line = lineNumber,
                            column = columnNumber,
                            context = getContextLine(document, lineNumber)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // If we can't find usages, assume there are none
        }

        return usages
    }

    private fun getContextLine(document: com.intellij.openapi.editor.Document?, line: Int): String {
        if (document == null || line < 1 || line > document.lineCount) return ""
        val lineIndex = line - 1
        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        return document.getText(TextRange(startOffset, endOffset)).trim()
    }

    private fun getElementType(element: PsiElement): String {
        return when {
            element is com.intellij.psi.PsiMethod -> "method"
            element is com.intellij.psi.PsiClass -> "class"
            element is com.intellij.psi.PsiField -> "field"
            element is com.intellij.psi.PsiLocalVariable -> "variable"
            element is com.intellij.psi.PsiParameter -> "parameter"
            else -> element.javaClass.simpleName.removePrefix("Psi").lowercase()
        }
    }
}

@Serializable
data class SafeDeleteBlockedResult(
    val canDelete: Boolean,
    val elementName: String,
    val elementType: String,
    val usageCount: Int,
    val blockingUsages: List<UsageInfo>,
    val message: String
)

@Serializable
data class UsageInfo(
    val file: String,
    val line: Int,
    val column: Int,
    val context: String
)

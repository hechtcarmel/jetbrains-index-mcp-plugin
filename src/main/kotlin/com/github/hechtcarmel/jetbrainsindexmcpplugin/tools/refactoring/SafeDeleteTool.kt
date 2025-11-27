package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class SafeDeleteTool : AbstractRefactoringTool() {

    override val name = "ide_refactor_safe_delete"

    override val description = """
        Safely deletes a symbol after checking for usages. Supports Ctrl+Z undo.

        REQUIRED: file + line + column to identify the element to delete.
        OPTIONAL: force=true to delete even when usages exist (may break compilation).

        If usages exist and force=false, returns the usage list instead of deleting.

        EXAMPLE: {"file": "src/main/java/com/example/OldClass.java", "line": 10, "column": 14}
        EXAMPLE with force: {"file": "src/main/java/OldClass.java", "line": 10, "column": 14, "force": true}
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

    override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")
        val force = arguments["force"]?.jsonPrimitive?.content?.toBoolean() ?: false

        requireSmartMode(project)

        // Find the element to delete
        val element = readAction {
            findNamedElement(project, file, line, column)
        } ?: return createErrorResult("No deletable element found at the specified position")

        val elementName = readAction { element.name } ?: "unnamed"
        val elementType = readAction { getElementType(element) }

        // First, check for usages
        val usages = readAction {
            findUsages(project, element)
        }

        // If there are usages and force is false, return them
        if (usages.isNotEmpty() && !force) {
            return createJsonResult(
                SafeDeleteBlockedResult(
                    canDelete = false,
                    elementName = elementName,
                    elementType = elementType,
                    usageCount = usages.size,
                    blockingUsages = usages.take(20), // Limit to 20 usages
                    message = "Cannot delete '$elementName': found ${usages.size} usage(s). Use force=true to delete anyway."
                )
            )
        }

        // Proceed with deletion
        val affectedFiles = mutableSetOf<String>()
        var success = false
        var errorMessage: String? = null

        try {
            // Track the file being modified
            readAction {
                element.containingFile?.virtualFile?.let { vf ->
                    trackAffectedFile(project, vf, affectedFiles)
                }
            }

            ApplicationManager.getApplication().invokeAndWait {
                try {
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Safe Delete: $elementName")
                        .withGroupId("MCP Refactoring")
                        .run<Throwable> {
                            // Perform deletion
                            if (element.isValid) {
                                element.delete()
                            }

                            // Commit and save
                            PsiDocumentManager.getInstance(project).commitAllDocuments()
                            FileDocumentManager.getInstance().saveAllDocuments()

                            success = true
                        }
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            }

            return if (success) {
                createJsonResult(
                    RefactoringResult(
                        success = true,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = 1,
                        message = if (force && usages.isNotEmpty()) {
                            "Force-deleted '$elementName' (had ${usages.size} usage(s) that may now be broken)"
                        } else {
                            "Successfully deleted '$elementName'"
                        }
                    )
                )
            } else {
                createErrorResult("Safe delete failed: ${errorMessage ?: "Unknown error"}")
            }
        } catch (e: Exception) {
            return createErrorResult("Safe delete failed: ${e.message}")
        }
    }

    private fun findUsages(project: Project, element: PsiNamedElement): List<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()

        try {
            val references = ReferencesSearch.search(element).findAll()
            for (reference in references) {
                val refElement = reference.element
                val refFile = refElement.containingFile?.virtualFile

                if (refFile != null) {
                    val document = PsiDocumentManager.getInstance(project).getDocument(refElement.containingFile)
                    val lineNumber = document?.getLineNumber(refElement.textOffset)?.plus(1) ?: 0
                    val columnNumber = if (document != null) {
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
        return document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset)).trim()
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

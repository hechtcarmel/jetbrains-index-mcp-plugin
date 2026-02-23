package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolEditResult
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.codeStyle.CodeStyleManager
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
 * Tool that inserts content before a symbol (class, method, function, field).
 *
 * The symbol is identified by file + line + column position. The provided body text is inserted
 * immediately before the start of the symbol's definition. The insertion is auto-reformatted
 * and supports undo (Ctrl+Z).
 *
 * Uses a two-phase approach:
 * 1. **Read Phase**: Find the PSI element at the position and walk up to a named element
 * 2. **Write Phase**: Insert body text before the element using document-level insertion
 */
class InsertBeforeSymbolTool : AbstractRefactoringTool() {

    override val name = ToolNames.INSERT_BEFORE_SYMBOL

    override val description = """
        Insert content before a symbol (class, method, function, field).
        The symbol is identified by file + line + column. The body text is inserted
        immediately before the symbol's definition. Auto-reformats after insertion. Supports undo (Ctrl+Z).

        A typical use case is to insert a new class, function, method, field, variable assignment,
        or an import statement before the first symbol in a file.

        Returns: success status with file path and new line range.

        Parameters: file + line + column + body (all required).

        Example: {"file": "src/UserService.java", "line": 25, "column": 10, "body": "public void delete(User u) {\n    repo.delete(u);\n}"}
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
                put(SchemaConstants.DESCRIPTION, "Path to file relative to project root. REQUIRED.")
            }
            putJsonObject(ParamNames.LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based line number where the symbol is located. REQUIRED.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number. REQUIRED.")
            }
            putJsonObject(ParamNames.BODY) {
                put(
                    SchemaConstants.TYPE, SchemaConstants.TYPE_STRING
                )
                put(
                    SchemaConstants.DESCRIPTION,
                    "The source code to insert before the symbol. Must be valid for the target language. REQUIRED."
                )
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.FILE))
            add(JsonPrimitive(ParamNames.LINE))
            add(JsonPrimitive(ParamNames.COLUMN))
            add(JsonPrimitive(ParamNames.BODY))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.FILE))
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.LINE))
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.COLUMN))
        val body = arguments[ParamNames.BODY]?.jsonPrimitive?.content
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.BODY))

        if (body.isBlank()) {
            return createErrorResult("body cannot be blank")
        }

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Find element and validate (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val anchorElement = suspendingReadAction {
            val rawElement = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction null

            PsiTreeUtil.getParentOfType(rawElement, PsiNamedElement::class.java, false)
        }

        if (anchorElement == null) {
            return createErrorResult(ErrorMessages.noElementAtPosition(file, line, column))
        }

        val elementName = suspendingReadAction { anchorElement.name }
            ?: return createErrorResult(ErrorMessages.NO_NAMED_ELEMENT)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Insert content before the symbol (suspending write action)
        // ═══════════════════════════════════════════════════════════════════════
        var startLine = 0
        var endLine = 0
        var errorMessage: String? = null

        suspendingWriteAction(project, "Insert before symbol") {
            try {
                val containingFile = anchorElement.containingFile
                    ?: throw IllegalStateException("Anchor element has no containing file")

                val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                    ?: throw IllegalStateException("Could not get document for file")

                val insertOffset = anchorElement.textRange.startOffset
                val textToInsert = "$body\n"

                document.insertString(insertOffset, textToInsert)

                // Commit the document to synchronize PSI with text changes
                PsiDocumentManager.getInstance(project).commitDocument(document)

                // Reformat the inserted text region
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                if (psiFile != null) {
                    CodeStyleManager.getInstance(project).reformatText(
                        psiFile,
                        insertOffset,
                        insertOffset + textToInsert.length
                    )
                }

                // Commit again after reformatting to get final line numbers
                PsiDocumentManager.getInstance(project).commitDocument(document)

                // Compute line numbers (1-based)
                startLine = document.getLineNumber(insertOffset) + 1
                val insertEnd = insertOffset + textToInsert.length
                endLine = document.getLineNumber(insertEnd.coerceAtMost(document.textLength - 1).coerceAtLeast(0)) + 1

                // Save all documents
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error during insertion"
            }
        }

        return if (errorMessage != null) {
            createErrorResult("Insert before failed: $errorMessage")
        } else {
            createJsonResult(
                SymbolEditResult(
                    success = true,
                    file = file,
                    startLine = startLine,
                    endLine = endLine,
                    message = "Successfully inserted content before symbol '$elementName'"
                )
            )
        }
    }
}

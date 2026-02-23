package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolEditResult
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Insertion position relative to the anchor symbol.
 */
enum class InsertPosition { BEFORE, AFTER }

/**
 * Abstract base class for tools that insert content before or after a symbol.
 *
 * Subclasses only need to provide [name], [description], [inputSchema], and [insertPosition].
 * All insertion logic (element lookup, validation, document modification, reformatting)
 * is handled by this base class.
 *
 * Uses a two-phase approach:
 * 1. **Read Phase**: Find the PSI element at the position and walk up to a named element
 * 2. **Write Phase**: Insert body text at the appropriate offset using document-level insertion
 */
abstract class AbstractInsertSymbolTool : AbstractRefactoringTool() {

    /**
     * Whether to insert before or after the anchor symbol.
     */
    protected abstract val insertPosition: InsertPosition

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
        // PHASE 2: EDT - Insert content (suspending write action)
        // ═══════════════════════════════════════════════════════════════════════
        var startLine = 0
        var endLine = 0
        var errorMessage: String? = null

        val commandName = when (insertPosition) {
            InsertPosition.AFTER -> "Insert after symbol"
            InsertPosition.BEFORE -> "Insert before symbol"
        }

        suspendingWriteAction(project, commandName) {
            try {
                if (!anchorElement.isValid) {
                    errorMessage = "Anchor element is no longer valid. The file may have been modified between read and write phases."
                    return@suspendingWriteAction
                }

                val containingFile = anchorElement.containingFile
                    ?: throw IllegalStateException("Anchor element has no containing file")

                val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                    ?: throw IllegalStateException("Could not get document for file")

                val insertOffset: Int
                val textToInsert: String
                when (insertPosition) {
                    InsertPosition.AFTER -> {
                        insertOffset = anchorElement.textRange.endOffset
                        textToInsert = "\n$body"
                    }
                    InsertPosition.BEFORE -> {
                        insertOffset = anchorElement.textRange.startOffset
                        textToInsert = "$body\n"
                    }
                }

                // Track document length to compute actual insert extent after reformat
                val docLengthBefore = document.textLength

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

                // Compute line numbers using actual document delta (handles reformat length changes)
                val actualInsertLength = document.textLength - docLengthBefore
                startLine = document.getLineNumber(insertOffset) + 1
                val insertEnd = insertOffset + actualInsertLength
                endLine = document.getLineNumber(insertEnd.coerceAtMost(document.textLength - 1).coerceAtLeast(0)) + 1

                // Save all documents
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error during insertion"
            }
        }

        val posLabel = when (insertPosition) {
            InsertPosition.AFTER -> "after"
            InsertPosition.BEFORE -> "before"
        }

        return if (errorMessage != null) {
            createErrorResult("Insert $posLabel failed: $errorMessage")
        } else {
            createJsonResult(
                SymbolEditResult(
                    success = true,
                    file = file,
                    startLine = startLine,
                    endLine = endLine,
                    message = "Successfully inserted content $posLabel symbol '$elementName'"
                )
            )
        }
    }
}

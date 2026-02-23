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
import com.intellij.psi.PsiFileFactory
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
 * Tool that replaces the entire body of a symbol (class, method, function, field) with new source code.
 *
 * The symbol is identified by file + line + column position. The provided body text replaces
 * the complete element text. The replacement is auto-reformatted and supports undo (Ctrl+Z).
 *
 * Uses a two-phase approach:
 * 1. **Read Phase**: Find the PSI element at the position and walk up to a named element
 * 2. **Write Phase**: Create a replacement element from the body text and swap it in
 */
class ReplaceSymbolBodyTool : AbstractRefactoringTool() {

    override val name = ToolNames.REPLACE_SYMBOL_BODY

    override val description = """
        Replace the entire body of a symbol (class, method, function, field) with new source code.
        The symbol is identified by file + line + column. The new body replaces the complete element text.
        Auto-reformats after replacement. Supports undo (Ctrl+Z).

        IMPORTANT: The body must start with the declaration (not a comment or import). Leading comments
        will cause the replacement to only use the comment as the new element.

        Returns: success status with file path and new line range.

        Parameters: file + line + column + body (all required).

        Example: {"file": "src/UserService.java", "line": 25, "column": 10, "body": "public void save(User u) {\n    repo.save(u);\n}"}
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
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(
                    SchemaConstants.DESCRIPTION,
                    "The new source code to replace the symbol with. Must be complete and valid for the target language. REQUIRED."
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

    /**
     * Data class holding the validated target element from Phase 1.
     */
    private data class SymbolValidation(
        val element: PsiNamedElement?,
        val elementName: String,
        val error: String? = null
    )

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
        val validation = suspendingReadAction {
            validateTarget(project, file, line, column)
        }

        if (validation.error != null) {
            return createErrorResult(validation.error)
        }

        val targetElement = validation.element
            ?: return createErrorResult(validation.error ?: "Unknown error")
        val elementName = validation.elementName

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Replace the symbol body (suspending write action)
        // ═══════════════════════════════════════════════════════════════════════
        var startLine = 0
        var endLine = 0
        var errorMessage: String? = null

        suspendingWriteAction(project, "Replace symbol body") {
            try {
                if (!targetElement.isValid) {
                    errorMessage = "Target element is no longer valid. The file may have been modified between read and write phases."
                    return@suspendingWriteAction
                }
                val result = replaceElement(project, targetElement, body)
                startLine = result.first
                endLine = result.second
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error during symbol replacement"
            }
        }

        return if (errorMessage != null) {
            createErrorResult("Replace failed: $errorMessage")
        } else {
            createJsonResult(
                SymbolEditResult(
                    success = true,
                    file = file,
                    startLine = startLine,
                    endLine = endLine,
                    message = "Successfully replaced symbol '$elementName'"
                )
            )
        }
    }

    /**
     * Validates the target element at the given position.
     * Runs in a read action (background thread).
     */
    private fun validateTarget(
        project: Project,
        file: String,
        line: Int,
        column: Int
    ): SymbolValidation {
        val rawElement = findPsiElement(project, file, line, column)
            ?: return SymbolValidation(
                element = null,
                elementName = "",
                error = ErrorMessages.noElementAtPosition(file, line, column)
            )

        val namedElement = PsiTreeUtil.getParentOfType(rawElement, PsiNamedElement::class.java, false)
            ?: return SymbolValidation(
                element = null,
                elementName = "",
                error = ErrorMessages.NO_NAMED_ELEMENT
            )

        val name = namedElement.name
            ?: return SymbolValidation(
                element = null,
                elementName = "",
                error = "Element has no name"
            )

        return SymbolValidation(
            element = namedElement,
            elementName = name
        )
    }

    /**
     * Replaces the target element with new content parsed from the body text.
     * Must be called in a write action on EDT.
     *
     * @return Pair of (startLine, endLine) for the replaced element (1-based)
     */
    private fun replaceElement(
        project: Project,
        targetElement: PsiNamedElement,
        body: String
    ): Pair<Int, Int> {
        val containingFile = targetElement.containingFile
            ?: throw IllegalStateException("Target element has no containing file")

        val language = containingFile.language
        val fileExtension = containingFile.virtualFile?.extension ?: "txt"

        // Create a temporary file from the body text using the same language
        val tempFile = PsiFileFactory.getInstance(project)
            .createFileFromText("_temp.$fileExtension", language, body)

        val newElement = tempFile.firstChild
            ?: throw IllegalStateException("Could not parse replacement body")

        // Replace the target element with the new element
        val replaced = targetElement.replace(newElement)

        // Reformat the replaced element
        CodeStyleManager.getInstance(project).reformat(replaced)

        // Commit the document to synchronize PSI and text
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
        if (document != null) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        // Save all documents
        FileDocumentManager.getInstance().saveAllDocuments()

        // Compute the line range of the replaced element (1-based)
        val replacedDocument = PsiDocumentManager.getInstance(project).getDocument(replaced.containingFile)
        return if (replacedDocument != null) {
            val startLine = replacedDocument.getLineNumber(replaced.textRange.startOffset) + 1
            val endLine = replacedDocument.getLineNumber(replaced.textRange.endOffset) + 1
            Pair(startLine, endLine)
        } else {
            Pair(1, 1)
        }
    }

}

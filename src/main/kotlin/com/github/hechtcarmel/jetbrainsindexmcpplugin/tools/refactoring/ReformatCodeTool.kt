package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reformats code in a file according to the project's code style settings.
 *
 * Equivalent to the IDE's "Reformat Code" action (Ctrl+Alt+L / Cmd+Opt+L).
 * Uses IntelliJ's [ReformatCodeProcessor] with optional chaining to
 * [OptimizeImportsProcessor] and [RearrangeCodeProcessor].
 *
 * Respects .editorconfig, project code style, and language-specific formatting rules.
 *
 * Does NOT require smart mode -- formatting doesn't need indexes.
 */
class ReformatCodeTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<ReformatCodeTool>()
    }

    override val name = ToolNames.REFORMAT_CODE

    override val description = """
        Reformat code in a file according to the project's code style settings (.editorconfig, IDE code style). Equivalent to the IDE's "Reformat Code" action (Ctrl+Alt+L / Cmd+Opt+L). Supports undo (Ctrl+Z).

        By default also optimizes imports and rearranges code members. Use optimizeImports=false and rearrangeCode=false to only reformat whitespace/indentation.

        Respects: .editorconfig, project code style, language-specific formatting rules.

        Returns: success status, affected file, and description of operations performed.

        Parameters: file (required), startLine/endLine (optional range), optimizeImports (default: true), rearrangeCode (default: true).

        Example: {"file": "src/MyClass.java"}
        Example: {"file": "src/MyClass.java", "startLine": 10, "endLine": 50, "optimizeImports": false}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file()
        .intProperty(ParamNames.START_LINE, "Start line for partial formatting (1-based). If provided, endLine is also required.")
        .intProperty(ParamNames.END_LINE, "End line for partial formatting (1-based). If provided, startLine is also required.")
        .booleanProperty(ParamNames.OPTIMIZE_IMPORTS, "Optimize imports (remove unused, organize). Default: true.")
        .booleanProperty(ParamNames.REARRANGE_CODE, "Rearrange code members according to arrangement rules. Default: true.")
        .build()

    /**
     * Data class holding validated reformat parameters from Phase 1.
     */
    private data class ReformatValidation(
        val psiFile: PsiFile? = null,
        val textRange: TextRange? = null,
        val error: String? = null
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, ParamNames.FILE).getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }
        val startLine = arguments[ParamNames.START_LINE]?.jsonPrimitive?.int
        val endLine = arguments[ParamNames.END_LINE]?.jsonPrimitive?.int
        val optimizeImports = arguments[ParamNames.OPTIMIZE_IMPORTS]?.jsonPrimitive?.boolean ?: true
        val rearrangeCode = arguments[ParamNames.REARRANGE_CODE]?.jsonPrimitive?.boolean ?: true

        // Validate startLine/endLine pairing
        if ((startLine != null) != (endLine != null)) {
            return createErrorResult("Both startLine and endLine must be provided together, or neither.")
        }
        if (startLine != null && endLine != null) {
            if (startLine < 1) return createErrorResult("startLine must be >= 1")
            if (endLine < startLine) return createErrorResult("endLine must be >= startLine")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Resolve file and validate (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val validation = suspendingReadAction {
            validateAndPrepare(project, file, startLine, endLine)
        }

        if (validation.error != null) {
            return createErrorResult(validation.error)
        }

        val psiFile = validation.psiFile
            ?: return createErrorResult("Failed to resolve file: $file")
        val textRange = validation.textRange

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Execute reformat using processor chaining
        // ═══════════════════════════════════════════════════════════════════════
        var errorMessage: String? = null

        edtAction {
            try {
                executeReformat(project, psiFile, textRange, optimizeImports, rearrangeCode)
            } catch (e: Exception) {
                LOG.warn("Reformat failed for $file", e)
                errorMessage = e.message ?: "Unknown error during reformat"
            }
        }

        // Commit and save outside EDT block — commitDocuments uses
        // TransactionGuard.submitTransactionAndWait for write-safe context
        if (errorMessage == null) {
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
        }

        return if (errorMessage != null) {
            createErrorResult("Reformat failed: $errorMessage")
        } else {
            val operations = buildList {
                add("reformatted")
                if (optimizeImports) add("import optimization")
                if (rearrangeCode) add("code rearrangement")
            }
            val rangeNote = if (startLine != null && endLine != null) {
                " (lines $startLine-$endLine)"
            } else ""
            val operationsNote = if (operations.size > 1) {
                " (with ${operations.drop(1).joinToString(", ")})"
            } else ""

            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = listOf(file),
                    changesCount = 1,
                    message = "Reformatted $file$rangeNote$operationsNote"
                )
            )
        }
    }

    /**
     * Validates parameters and resolves the PSI file and text range.
     * Runs in a read action (background thread).
     */
    private fun validateAndPrepare(
        project: Project,
        file: String,
        startLine: Int?,
        endLine: Int?
    ): ReformatValidation {
        val virtualFile = resolveFile(project, file)
            ?: return ReformatValidation(error = "File not found: $file")

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return ReformatValidation(error = "Cannot parse file: $file")

        // Calculate text range if line range specified
        val textRange = if (startLine != null && endLine != null) {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return ReformatValidation(error = "Cannot get document for file: $file")

            val lineCount = document.lineCount
            if (startLine > lineCount) {
                return ReformatValidation(
                    error = "startLine ($startLine) exceeds file line count ($lineCount)"
                )
            }
            val clampedEndLine = minOf(endLine, lineCount)

            val startOffset = document.getLineStartOffset(startLine - 1)
            val endOffset = document.getLineEndOffset(clampedEndLine - 1)
            TextRange(startOffset, endOffset)
        } else null

        return ReformatValidation(psiFile = psiFile, textRange = textRange)
    }

    /**
     * Executes the reformat operation using IntelliJ's processor chaining.
     * Must run on EDT.
     *
     * Uses [AbstractLayoutCodeProcessor.runWithoutProgress] instead of
     * [AbstractLayoutCodeProcessor.run] because `run()` dispatches via `ProgressManager`
     * as a background task in non-headless mode, returning before processing completes.
     * `runWithoutProgress()` executes synchronously, ensuring the document is fully
     * updated before we commit and save. Undo (Ctrl+Z) works automatically.
     */
    private fun executeReformat(
        project: Project,
        psiFile: PsiFile,
        textRange: TextRange?,
        optimizeImports: Boolean,
        rearrangeCode: Boolean
    ) {
        var processor: AbstractLayoutCodeProcessor = if (textRange != null) {
            ReformatCodeProcessor(psiFile, arrayOf(textRange))
        } else {
            ReformatCodeProcessor(psiFile, false)
        }

        if (optimizeImports) {
            processor = OptimizeImportsProcessor(processor)
        }
        if (rearrangeCode) {
            processor = RearrangeCodeProcessor(processor)
        }

        processor.runWithoutProgress()
    }
}

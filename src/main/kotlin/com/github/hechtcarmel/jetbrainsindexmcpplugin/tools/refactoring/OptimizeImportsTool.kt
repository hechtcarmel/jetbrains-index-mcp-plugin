package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Optimizes imports in a file without reformatting code.
 *
 * Equivalent to the IDE's "Optimize Imports" action (Ctrl+Alt+O / Cmd+Opt+O).
 * Removes unused imports and organizes remaining imports according to project style.
 *
 * Does NOT require smart mode -- import optimization doesn't need indexes.
 */
class OptimizeImportsTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<OptimizeImportsTool>()
    }

    override val name = ToolNames.OPTIMIZE_IMPORTS

    override val description = """
        Optimize imports in a file: remove unused imports and organize remaining imports according to project code style. Equivalent to the IDE's "Optimize Imports" action (Ctrl+Alt+O / Cmd+Opt+O). Does NOT reformat code. Supports undo (Ctrl+Z).

        Returns: success status, affected file, and description of operation performed.

        Parameters: file (required).

        Example: {"file": "src/MyClass.java"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, ParamNames.FILE).getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Resolve file (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val psiFile = suspendingReadAction {
            val virtualFile = resolveFile(project, file)
                ?: return@suspendingReadAction null
            PsiManager.getInstance(project).findFile(virtualFile)
        }

        if (psiFile == null) {
            return createErrorResult("File not found: $file")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Execute optimize imports
        // ═══════════════════════════════════════════════════════════════════════
        var errorMessage: String? = null

        edtAction {
            try {
                executeOptimizeImports(project, psiFile)
            } catch (e: Exception) {
                LOG.warn("Optimize imports failed for $file", e)
                errorMessage = e.message ?: "Unknown error during import optimization"
            }
        }

        if (errorMessage == null) {
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
        }

        return if (errorMessage != null) {
            createErrorResult("Optimize imports failed: $errorMessage")
        } else {
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = listOf(file),
                    changesCount = 1,
                    message = "Optimized imports in $file"
                )
            )
        }
    }

    /**
     * Executes import optimization using IntelliJ's [OptimizeImportsProcessor].
     * Must run on EDT.
     *
     * Uses [OptimizeImportsProcessor.runWithoutProgress] instead of [OptimizeImportsProcessor.run]
     * because `run()` dispatches via `ProgressManager` as a background task in non-headless mode,
     * returning before processing completes. `runWithoutProgress()` executes synchronously,
     * ensuring the document is fully updated before we commit and save.
     */
    private fun executeOptimizeImports(project: Project, psiFile: PsiFile) {
        OptimizeImportsProcessor(project, psiFile).runWithoutProgress()
    }
}

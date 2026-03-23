package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.util.containers.MultiMap
import com.intellij.usageView.UsageInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Move file tool that uses the IDE's Move refactoring to relocate files
 * while automatically updating all references, imports, and package declarations.
 *
 * This uses [MoveFilesOrDirectoriesProcessor] which is a platform-level API
 * that works across all languages and JetBrains IDEs.
 *
 * Three-phase approach:
 * 1. **Read Action**: Validate source file exists
 * 2. **EDT Write Action**: Ensure destination directory exists (create if needed)
 * 3. **EDT**: Execute the move processor (handles reference updates internally)
 */
class MoveFileTool : AbstractRefactoringTool() {

    override val name = "ide_move_file"

    override val description = """
        Move a file to a new directory using the IDE's refactoring engine. Automatically updates all references, imports, and package declarations across the project.

        Use when relocating files to maintain correct imports and references.

        Parameters:
        - file (REQUIRED): Source file path relative to project root
        - destination (REQUIRED): Target directory path relative to project root. Created automatically if it doesn't exist.
        - update_references (optional, default: true): Whether to update references, imports, and package declarations for the moved file.

        Returns: success status, list of affected files, and result message.

        Examples:
        - Move file: {"file": "src/main/java/com/old/MyClass.java", "destination": "src/main/java/com/new"}
        - Move without updating refs: {"file": "config/old.yml", "destination": "config/archive", "update_references": false}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to the source file to move, relative to project root. REQUIRED.")
        .stringProperty("destination", "Target directory path relative to project root. The file will be moved into this directory. Created automatically if it doesn't exist. REQUIRED.", required = true)
        .booleanProperty("update_references", "Whether to update references, imports, and package declarations for the moved file. Default: true.")
        .build()

    private data class MovePreparation(
        val psiFile: PsiFile,
        val targetDirectory: PsiDirectory,
        val sourceRelativePath: String,
        val destinationRelativePath: String
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val destination = arguments["destination"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: destination")
        val updateReferences = arguments["update_references"]?.jsonPrimitive?.content?.toBoolean() ?: true

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: READ ACTION - Validate source file
        // ═══════════════════════════════════════════════════════════════════════
        val sourceInfo = suspendingReadAction {
            val psiFile = getPsiFile(project, file)
            if (psiFile == null || !psiFile.isPhysical) {
                return@suspendingReadAction null
            }
            val relativePath = psiFile.virtualFile?.let { getRelativePath(project, it) } ?: file
            Triple(psiFile, psiFile.name, relativePath)
        } ?: return createErrorResult("Source file not found: $file")

        val (_, fileName, sourceRelativePath) = sourceInfo

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT WRITE ACTION - Ensure destination directory exists
        // ═══════════════════════════════════════════════════════════════════════
        val targetDir = ensureDestinationDirectory(project, destination)
            ?: return createErrorResult("Invalid destination '$destination': could not resolve or create directory")

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 3: READ ACTION - Validate move (same dir, name conflict, get PSI dir)
        // ═══════════════════════════════════════════════════════════════════════
        val preparation = suspendingReadAction {
            val psiFile = getPsiFile(project, file)
                ?: return@suspendingReadAction null to "Source file no longer valid"

            val targetPsiDir = PsiManager.getInstance(project).findDirectory(targetDir)
                ?: return@suspendingReadAction null to "Could not find PSI directory for destination"

            val currentDir = psiFile.containingDirectory
            if (currentDir != null && currentDir.virtualFile.path == targetDir.path) {
                return@suspendingReadAction null to "File '$file' is already in the destination directory"
            }

            if (targetPsiDir.findFile(fileName) != null) {
                return@suspendingReadAction null to "A file named '$fileName' already exists in '$destination'"
            }

            val destinationRelativePath = getRelativePath(project, targetDir)
            MovePreparation(psiFile, targetPsiDir, sourceRelativePath, destinationRelativePath) to null
        }

        val (movePrep, error) = preparation
        if (movePrep == null) {
            return createErrorResult(error ?: "Unknown validation error")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 4: EDT - Execute move processor (manages its own write actions)
        // ═══════════════════════════════════════════════════════════════════════
        return executeMove(project, movePrep, updateReferences)
    }

    /**
     * Ensures the destination directory exists, creating it on EDT if needed.
     * Uses [VfsUtil.createDirectoryIfMissing] which is the standard IDE API for this.
     */
    private suspend fun ensureDestinationDirectory(project: Project, destination: String): VirtualFile? {
        // First try resolving without creating (fast path, no write action needed)
        val existing = resolveFile(project, destination)
        if (existing != null && existing.isDirectory) {
            return existing
        }

        // Create directory on EDT inside a write action
        val basePath = project.basePath ?: return null
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null

        var created: VirtualFile? = null
        edtAction {
            WriteCommandAction.writeCommandAction(project)
                .withName("Create Directory: $destination")
                .withGroupId("MCP Refactoring")
                .run<Throwable> {
                    created = try {
                        VfsUtil.createDirectoryIfMissing(baseDir, destination)
                    } catch (_: Exception) {
                        null
                    }
                }
        }
        return created
    }

    /**
     * Executes the move refactoring on EDT using MoveFilesOrDirectoriesProcessor.
     *
     * The processor manages its own WriteCommandAction internally, so we do NOT
     * wrap it in another WriteCommandAction. We only ensure it runs on EDT.
     */
    private suspend fun executeMove(
        project: Project,
        preparation: MovePreparation,
        updateReferences: Boolean
    ): ToolCallResult {
        var success = false
        var errorMessage: String? = null
        val affectedFiles = mutableSetOf<String>()
        val fileName = preparation.psiFile.name

        affectedFiles.add(preparation.sourceRelativePath)

        edtAction {
            try {
                if (!preparation.psiFile.isValid || !preparation.targetDirectory.isValid) {
                    errorMessage = "Source file or target directory is no longer valid"
                    return@edtAction
                }

                val processor = HeadlessMoveProcessor(
                    project,
                    arrayOf<PsiElement>(preparation.psiFile),
                    preparation.targetDirectory,
                    updateReferences,
                    false, // searchInComments
                    false, // searchInNonJavaFiles
                    null,  // moveCallback
                    null   // prepareSuccessfulCallback
                )

                processor.setPreviewUsages(false)
                processor.run()

                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()

                val newFilePath = preparation.targetDirectory.virtualFile.path + "/" + fileName
                val newVf = LocalFileSystem.getInstance().findFileByPath(newFilePath)
                if (newVf != null) {
                    affectedFiles.add(getRelativePath(project, newVf))
                }

                success = true
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }

        return if (success) {
            val newPath = "${preparation.destinationRelativePath}/$fileName"
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = affectedFiles.toList(),
                    changesCount = affectedFiles.size,
                    message = "Successfully moved '${preparation.sourceRelativePath}' to '$newPath'" +
                        if (updateReferences) " (references updated)" else " (references not updated)"
                )
            )
        } else {
            createErrorResult("Move failed: ${errorMessage ?: "Unknown error"}")
        }
    }
}

/**
 * Headless move processor that suppresses conflict dialogs for autonomous operation.
 *
 * Overrides [showConflicts] to always proceed (return true) instead of showing
 * a modal dialog that would block the MCP tool execution.
 */
private class HeadlessMoveProcessor(
    project: Project,
    elements: Array<PsiElement>,
    newParent: PsiDirectory,
    searchForReferences: Boolean,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean,
    moveCallback: com.intellij.refactoring.move.MoveCallback?,
    prepareSuccessfulCallback: Runnable?
) : MoveFilesOrDirectoriesProcessor(
    project, elements, newParent, searchForReferences,
    searchInComments, searchInNonJavaFiles, moveCallback, prepareSuccessfulCallback
) {
    override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean {
        return true
    }
}

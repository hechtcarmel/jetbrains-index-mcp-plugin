package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
 * Two-phase approach:
 * 1. **Background Phase**: Validate source file and destination directory
 * 2. **EDT Phase**: Execute the move processor (handles reference updates internally)
 */
class MoveFileTool : AbstractRefactoringTool() {

    override val name = "ide_move_file"

    override val description = """
        Move a file to a new directory using the IDE's refactoring engine. Automatically updates all references, imports, and package declarations across the project.

        Use when relocating files to maintain correct imports and references.

        Parameters:
        - file (REQUIRED): Source file path relative to project root
        - destination (REQUIRED): Target directory path relative to project root. Created automatically if it doesn't exist.
        - search_for_references (optional, default: true): Whether to search for and update references to the moved file.

        Returns: success status, list of affected files, and result message.

        Examples:
        - Move file: {"file": "src/main/java/com/old/MyClass.java", "destination": "src/main/java/com/new"}
        - Move without updating refs: {"file": "config/old.yml", "destination": "config/archive", "search_for_references": false}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to the source file to move, relative to project root. REQUIRED.")
        .stringProperty("destination", "Target directory path relative to project root. The file will be moved into this directory. Created automatically if it doesn't exist. REQUIRED.", required = true)
        .booleanProperty("search_for_references", "Whether to search for and update references to the moved file. Default: true.")
        .build()

    /**
     * Data collected in background phase for validation.
     */
    private data class MovePreparation(
        val psiFile: PsiFile,
        val targetDirectory: PsiDirectory,
        val sourceRelativePath: String,
        val destinationRelativePath: String
    )

    private sealed class PreparationResult {
        data class Success(val data: MovePreparation) : PreparationResult()
        data class SourceNotFound(val file: String) : PreparationResult()
        data class DestinationInvalid(val destination: String, val reason: String) : PreparationResult()
        data class AlreadyExists(val fileName: String, val destination: String) : PreparationResult()
        data class SameDirectory(val file: String) : PreparationResult()
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val destination = arguments["destination"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: destination")
        val searchForReferences = arguments["search_for_references"]?.jsonPrimitive?.content?.toBoolean() ?: true

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Validate source and destination
        // ═══════════════════════════════════════════════════════════════════════
        val preparationResult = suspendingReadAction {
            prepareMove(project, file, destination)
        }

        return when (preparationResult) {
            is PreparationResult.Success -> {
                executeMove(project, preparationResult.data, searchForReferences)
            }
            is PreparationResult.SourceNotFound -> {
                createErrorResult("Source file not found: ${preparationResult.file}")
            }
            is PreparationResult.DestinationInvalid -> {
                createErrorResult("Invalid destination '${preparationResult.destination}': ${preparationResult.reason}")
            }
            is PreparationResult.AlreadyExists -> {
                createErrorResult("A file named '${preparationResult.fileName}' already exists in '${preparationResult.destination}'")
            }
            is PreparationResult.SameDirectory -> {
                createErrorResult("File '${preparationResult.file}' is already in the destination directory")
            }
        }
    }

    /**
     * Validates the move operation in a read action.
     */
    private fun prepareMove(
        project: Project,
        file: String,
        destination: String
    ): PreparationResult {
        val psiFile = getPsiFile(project, file)
            ?: return PreparationResult.SourceNotFound(file)

        if (!psiFile.isPhysical) {
            return PreparationResult.SourceNotFound(file)
        }

        val sourceRelativePath = psiFile.virtualFile?.let { getRelativePath(project, it) } ?: file

        // Resolve destination directory
        val targetDir = resolveOrCreateTargetDirectory(project, destination)
            ?: return PreparationResult.DestinationInvalid(destination, "could not resolve or create directory")

        val targetPsiDir = PsiManager.getInstance(project).findDirectory(targetDir)
            ?: return PreparationResult.DestinationInvalid(destination, "could not find PSI directory")

        // Check if file is already in the destination
        val currentDir = psiFile.containingDirectory
        if (currentDir != null && currentDir.virtualFile.path == targetDir.path) {
            return PreparationResult.SameDirectory(file)
        }

        // Check for name conflict
        if (targetPsiDir.findFile(psiFile.name) != null) {
            return PreparationResult.AlreadyExists(psiFile.name, destination)
        }

        val destinationRelativePath = getRelativePath(project, targetDir)

        return PreparationResult.Success(
            MovePreparation(
                psiFile = psiFile,
                targetDirectory = targetPsiDir,
                sourceRelativePath = sourceRelativePath,
                destinationRelativePath = destinationRelativePath
            )
        )
    }

    /**
     * Resolves the target directory VirtualFile, creating it if necessary.
     */
    private fun resolveOrCreateTargetDirectory(project: Project, destination: String): com.intellij.openapi.vfs.VirtualFile? {
        // Try resolving as existing path first
        val existing = resolveFile(project, destination)
        if (existing != null && existing.isDirectory) {
            return existing
        }

        // Try to create the directory path
        val basePath = project.basePath ?: return null
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null

        return try {
            com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(baseDir, destination)
        } catch (_: Exception) {
            null
        }
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
        searchForReferences: Boolean
    ): ToolCallResult {
        var success = false
        var errorMessage: String? = null
        val affectedFiles = mutableSetOf<String>()
        val fileName = preparation.psiFile.name

        affectedFiles.add(preparation.sourceRelativePath)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Execute move processor (manages its own write actions)
        // ═══════════════════════════════════════════════════════════════════════
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
                    searchForReferences,
                    false, // searchInComments
                    false, // searchInNonJavaFiles
                    null,  // moveCallback
                    null   // prepareSuccessfulCallback
                )

                processor.setPreviewUsages(false)
                processor.run()

                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()

                // Record the new file path
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
                        if (searchForReferences) " (references updated)" else " (references not updated)"
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

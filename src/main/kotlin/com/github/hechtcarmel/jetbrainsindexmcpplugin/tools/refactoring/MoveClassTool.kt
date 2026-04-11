package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.PackageWrapper
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.moveClassesOrPackages.AutocreatingSingleSourceRootMoveDestination
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Java-only move-class refactoring that mirrors IntelliJ's "Move Class" workflow.
 *
 * This is separate from [MoveFileTool]:
 * - Use this tool to move a Java class to another package by package name
 * - Use [MoveFileTool] for whole-file/directory moves across all languages
 */
class MoveClassTool : AbstractRefactoringTool() {

    override val name = ToolNames.REFACTOR_MOVE_CLASS

    override val description = """
        Move a Java class to a different package using IntelliJ's class refactoring engine. Use this when changing a class's package or reorganizing Java packages semantically.

        Preferred over ide_refactor_rename for package moves. Rename changes identifiers; this tool relocates the class, updates the package declaration, and rewrites imports/references.
        Preferred over ide_move_file when the intent is "move this Java class to package X" rather than "move this file to directory Y".

        Target the class by either:
        - file + line + column (position anywhere inside the class), OR
        - language + symbol (Java fully qualified class reference)

        Parameters:
        - targetPackage (REQUIRED): Destination package name, e.g. "com.example.services". Use empty string for the default package.
        - targetSourceRoot (optional): Project-relative or absolute directory under which the target package should be created. Defaults to the source root or package base directory containing the class.
        - searchInComments (optional, default: false): Update textual matches in comments.
        - searchInNonJavaFiles (optional, default: false): Update textual matches in non-Java files.

        Examples:
        - Position-based: {"file": "src/com/oldpkg/Service.java", "line": 3, "column": 14, "targetPackage": "com.newpkg"}
        - Symbol-based: {"language": "Java", "symbol": "com.oldpkg.Service", "targetPackage": "com.newpkg"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(required = false, description = "Relative path to the Java file containing the class. Use with line+column for position-based targeting.")
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .stringProperty(ParamNames.TARGET_PACKAGE, "Destination Java package name. REQUIRED. Example: 'com.example.services'. Use empty string for the default package.", required = true)
        .stringProperty(ParamNames.TARGET_SOURCE_ROOT, "Optional project-relative or absolute directory under which the target package should be created. Defaults to the source root or package base directory containing the class.")
        .booleanProperty("searchInComments", "Whether to update textual matches in comments. Default: false.")
        .booleanProperty("searchInNonJavaFiles", "Whether to update textual matches in non-Java files. Default: false.")
        .build()

    private data class MoveClassPreparation(
        val psiClass: PsiClass,
        val oldQualifiedName: String,
        val className: String,
        val sourceRelativePath: String,
        val sourceRoot: VirtualFile,
        val targetPackage: String
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val targetPackage = arguments[ParamNames.TARGET_PACKAGE]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.TARGET_PACKAGE}")
        val targetSourceRoot = arguments[ParamNames.TARGET_SOURCE_ROOT]?.jsonPrimitive?.content
        val searchInComments = arguments["searchInComments"]?.jsonPrimitive?.content?.toBoolean() ?: false
        val searchInNonJavaFiles = arguments["searchInNonJavaFiles"]?.jsonPrimitive?.content?.toBoolean() ?: false

        requireSmartMode(project)

        val preparation = suspendingReadAction {
            validateAndPrepare(project, arguments, targetPackage, targetSourceRoot)
        } ?: return createErrorResult("Unable to resolve move target")

        if (preparation is MoveValidation.Error) {
            return createErrorResult(preparation.message)
        }

        return executeMove(project, (preparation as MoveValidation.Success).preparation, searchInComments, searchInNonJavaFiles)
    }

    private sealed class MoveValidation {
        data class Success(val preparation: MoveClassPreparation) : MoveValidation()
        data class Error(val message: String) : MoveValidation()
    }

    private fun validateAndPrepare(
        project: Project,
        arguments: JsonObject,
        targetPackage: String,
        targetSourceRoot: String?
    ): MoveValidation {
        if (targetPackage.isNotEmpty() && !PsiNameHelper.getInstance(project).isQualifiedName(targetPackage)) {
            return MoveValidation.Error("Invalid targetPackage: '$targetPackage'. Expected a Java package name like 'com.example.services'.")
        }

        val targetElement = resolveElementFromArguments(project, arguments).getOrElse {
            return MoveValidation.Error(it.message ?: "Could not resolve class target")
        }

        val psiClass = findTopLevelJavaClass(targetElement)
            ?: return MoveValidation.Error("No top-level Java class found at the specified target")

        val oldQualifiedName = psiClass.qualifiedName
            ?: return MoveValidation.Error("Only named top-level Java classes can be moved")
        val className = psiClass.name
            ?: return MoveValidation.Error("Class has no name")
        val sourceFile = psiClass.containingFile?.virtualFile
            ?: return MoveValidation.Error("Could not resolve containing file for class '$className'")

        val currentPackage = oldQualifiedName.substringBeforeLast('.', "")
        if (currentPackage == targetPackage) {
            return MoveValidation.Error("Class '$oldQualifiedName' is already in package '$targetPackage'")
        }

        val sourceRoot = resolveSourceRoot(project, sourceFile, currentPackage, targetSourceRoot)
            ?: return MoveValidation.Error(
                if (targetSourceRoot == null) {
                    "Could not determine the package base directory for '$oldQualifiedName'. Specify ${ParamNames.TARGET_SOURCE_ROOT} explicitly."
                } else {
                    "Invalid ${ParamNames.TARGET_SOURCE_ROOT}: '$targetSourceRoot'. It must resolve to a directory inside the project."
                }
            )

        val destinationValidationError = AutocreatingSingleSourceRootMoveDestination(
            PackageWrapper(PsiManager.getInstance(project), targetPackage),
            sourceRoot
        ).verify(psiClass.containingFile)
        if (destinationValidationError != null) {
            return MoveValidation.Error(destinationValidationError)
        }

        val existingDestinationFile = findDestinationClassFile(sourceRoot, targetPackage, className)
        if (existingDestinationFile != null && existingDestinationFile.path != sourceFile.path) {
            return MoveValidation.Error(
                "A class file named '$className.java' already exists in target package '$targetPackage'"
            )
        }

        return MoveValidation.Success(
            MoveClassPreparation(
                psiClass = psiClass,
                oldQualifiedName = oldQualifiedName,
                className = className,
                sourceRelativePath = getRelativePath(project, sourceFile),
                sourceRoot = sourceRoot,
                targetPackage = targetPackage
            )
        )
    }

    private fun findTopLevelJavaClass(element: PsiElement): PsiClass? {
        val psiClass = when (element) {
            is PsiClass -> element
            else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
        } ?: return null

        var current = psiClass
        while (current.containingClass != null) {
            current = current.containingClass!!
        }
        return current
    }

    private fun resolveSourceRoot(
        project: Project,
        sourceFile: VirtualFile,
        currentPackage: String,
        targetSourceRoot: String?
    ): VirtualFile? {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex

        if (targetSourceRoot != null) {
            val resolved = resolveFile(project, targetSourceRoot) ?: return null
            return resolved.takeIf { it.isDirectory }
        }

        return fileIndex.getSourceRootForFile(sourceFile)
            ?: inferPackageBaseDirectory(sourceFile, currentPackage)
            ?: fileIndex.getContentRootForFile(sourceFile)
    }

    private fun inferPackageBaseDirectory(sourceFile: VirtualFile, currentPackage: String): VirtualFile? {
        val sourceDir = sourceFile.parent ?: return null
        if (currentPackage.isBlank()) {
            return sourceDir
        }

        var currentDir = sourceDir
        for (segment in currentPackage.split('.').asReversed()) {
            if (segment.isBlank() || currentDir.name != segment) {
                return null
            }
            currentDir = currentDir.parent ?: return null
        }
        return currentDir
    }

    private fun findDestinationClassFile(sourceRoot: VirtualFile, targetPackage: String, className: String): VirtualFile? {
        val packageSegments = targetPackage.split('.').filter { it.isNotBlank() }
        val destinationDir = if (packageSegments.isEmpty()) {
            sourceRoot
        } else {
            VfsUtil.findRelativeFile(sourceRoot, *packageSegments.toTypedArray()) ?: return null
        }
        return destinationDir.findChild("$className.java")
    }

    private suspend fun executeMove(
        project: Project,
        preparation: MoveClassPreparation,
        searchInComments: Boolean,
        searchInNonJavaFiles: Boolean
    ): ToolCallResult {
        var errorMessage: String? = null
        val affectedFiles = linkedSetOf(preparation.sourceRelativePath)

        edtAction {
            try {
                if (!preparation.psiClass.isValid) {
                    errorMessage = "Class is no longer valid"
                    return@edtAction
                }

                val destination = AutocreatingSingleSourceRootMoveDestination(
                    PackageWrapper(PsiManager.getInstance(project), preparation.targetPackage),
                    preparation.sourceRoot
                )
                val processor = HeadlessMoveClassProcessor(
                    project,
                    arrayOf(preparation.psiClass),
                    destination,
                    searchInComments,
                    searchInNonJavaFiles,
                    null
                )

                processor.setPreviewUsages(false)
                processor.run()

                if (processor.conflictMessages.isNotEmpty()) {
                    val preview = processor.conflictMessages.take(3).joinToString("; ")
                    val more = processor.conflictMessages.size - 3
                    val suffix = if (more > 0) " (and $more more)" else ""
                    errorMessage = "Move blocked by conflicts: $preview$suffix"
                    return@edtAction
                }

                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()

                val destinationRelativePath = buildDestinationRelativePath(
                    project,
                    preparation.sourceRoot,
                    preparation.targetPackage,
                    preparation.className
                )
                destinationRelativePath?.let(affectedFiles::add)

                VfsUtil.markDirtyAndRefresh(false, true, true, preparation.sourceRoot)
                destinationRelativePath
                    ?.let { resolveFile(project, it) }
                    ?.let { vf -> affectedFiles.add(getRelativePath(project, vf)) }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error during move class"
            }
        }

        return if (errorMessage != null) {
            createErrorResult("Move class failed: $errorMessage")
        } else {
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = affectedFiles.toList(),
                    changesCount = affectedFiles.size,
                    message = "Successfully moved '${preparation.oldQualifiedName}' to package '${preparation.targetPackage}'"
                )
            )
        }
    }

    private fun buildDestinationRelativePath(
        project: Project,
        sourceRoot: VirtualFile,
        targetPackage: String,
        className: String
    ): String? {
        val rootPath = getRelativePath(project, sourceRoot).trim('/').takeIf { it.isNotBlank() }
        val packagePath = targetPackage.replace('.', '/').trim('/').takeIf { it.isNotBlank() }
        return listOfNotNull(rootPath, packagePath, "$className.java").joinToString("/")
            .takeIf { it.isNotBlank() }
    }
}

private class HeadlessMoveClassProcessor(
    project: Project,
    elementsToMove: Array<PsiClass>,
    moveDestination: AutocreatingSingleSourceRootMoveDestination,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean,
    moveCallback: MoveCallback?
) : MoveClassesOrPackagesProcessor(
    project,
    elementsToMove,
    moveDestination,
    searchInComments,
    searchInNonJavaFiles,
    moveCallback
) {
    val conflictMessages = mutableListOf<String>()

    override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean {
        conflictMessages.addAll(conflicts.values())
        return conflicts.isEmpty
    }
}

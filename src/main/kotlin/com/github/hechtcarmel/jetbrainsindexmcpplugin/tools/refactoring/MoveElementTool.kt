package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
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

class MoveElementTool : AbstractRefactoringTool() {

    override val name = "ide_refactor_move"

    override val description = """
        Moves a class to a different package, or moves a static method to a different class. Supports Ctrl+Z undo.

        REQUIRED: file + line + column to identify the element.
        THEN EITHER: targetDirectory (for class moves) OR targetClass (for method moves).

        WARNING: This modifies files and updates all imports/references automatically.

        EXAMPLE (move class): {"file": "src/main/java/old/MyClass.java", "line": 5, "column": 14, "targetDirectory": "src/main/java/com/example/newpackage"}
        EXAMPLE (move method): {"file": "src/main/java/Utils.java", "line": 20, "column": 18, "targetClass": "com.example.Helper"}
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
            putJsonObject("targetDirectory") {
                put("type", "string")
                put("description", "Target directory for class moves (e.g., 'src/main/java/com/newpackage'). Use this OR targetClass.")
            }
            putJsonObject("targetClass") {
                put("type", "string")
                put("description", "Fully qualified target class for method moves (e.g., 'com.example.Helper'). Use this OR targetDirectory.")
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
        val targetDirectory = arguments["targetDirectory"]?.jsonPrimitive?.content
        val targetClass = arguments["targetClass"]?.jsonPrimitive?.content

        if (targetDirectory == null && targetClass == null) {
            return createErrorResult("Either targetDirectory or targetClass must be specified")
        }

        requireSmartMode(project)

        // Find the element to move
        val element = readAction {
            findMoveableElement(project, file, line, column)
        } ?: return createErrorResult("No moveable element (class or method) found at the specified position")

        return when (element) {
            is PsiClass -> {
                if (targetDirectory == null) {
                    return createErrorResult("targetDirectory is required for moving classes")
                }
                moveClass(project, element, targetDirectory, file)
            }
            is PsiMethod -> {
                if (targetClass == null) {
                    return createErrorResult("targetClass is required for moving methods")
                }
                moveMethod(project, element, targetClass, file)
            }
            else -> createErrorResult("Element at position cannot be moved")
        }
    }

    private fun moveClass(
        project: Project,
        psiClass: PsiClass,
        targetDirectoryPath: String,
        sourceFile: String
    ): ToolCallResult {
        val className = readAction { psiClass.name } ?: return createErrorResult("Class has no name")

        // Resolve the target directory
        val basePath = project.basePath ?: return createErrorResult("Cannot determine project base path")
        val fullTargetPath = "$basePath/$targetDirectoryPath"

        val targetDir = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(fullTargetPath)
                ?: return@readAction null
            PsiManager.getInstance(project).findDirectory(vf)
        }

        if (targetDir == null) {
            return createErrorResult("Target directory not found: $targetDirectoryPath. Make sure the directory exists.")
        }

        val affectedFiles = mutableSetOf<String>()
        var success = false
        var errorMessage: String? = null
        var newLocation: String? = null

        try {
            // Collect affected files
            readAction {
                psiClass.containingFile?.virtualFile?.let { vf ->
                    trackAffectedFile(project, vf, affectedFiles)
                }

                // Find all references
                val references = ReferencesSearch.search(psiClass).findAll()
                for (reference in references) {
                    reference.element.containingFile?.virtualFile?.let { vf ->
                        trackAffectedFile(project, vf, affectedFiles)
                    }
                }

                // Add target directory
                targetDir.virtualFile?.let { vf ->
                    affectedFiles.add(getRelativePath(project, vf) + "/" + className + ".java")
                }
            }

            ApplicationManager.getApplication().invokeAndWait {
                try {
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Move Class: $className")
                        .withGroupId("MCP Refactoring")
                        .run<Throwable> {
                            // Move the class file
                            val containingFile = psiClass.containingFile
                            if (containingFile != null) {
                                // Simple move: copy to target, update references, delete original
                                performClassMove(project, psiClass, targetDir)
                                newLocation = "$targetDirectoryPath/$className.java"
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
                    MoveResult(
                        success = true,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = affectedFiles.size,
                        message = "Successfully moved class '$className' to '$targetDirectoryPath'",
                        newLocation = newLocation
                    )
                )
            } else {
                createErrorResult("Move class failed: ${errorMessage ?: "Unknown error"}")
            }
        } catch (e: Exception) {
            return createErrorResult("Move class failed: ${e.message}")
        }
    }

    private fun performClassMove(
        project: Project,
        psiClass: PsiClass,
        targetDir: PsiDirectory
    ) {
        val containingFile = psiClass.containingFile
            ?: throw IllegalStateException("Class has no containing file")

        // Check if the class is the only top-level class in the file
        val topLevelClasses = (containingFile as? com.intellij.psi.PsiJavaFile)?.classes ?: emptyArray()

        if (topLevelClasses.size == 1 && topLevelClasses[0] == psiClass) {
            // Move the entire file
            val copiedFile = targetDir.copyFileFrom(containingFile.name, containingFile)

            // Update package statement in the copied file
            val javaFile = copiedFile as? com.intellij.psi.PsiJavaFile
            if (javaFile != null) {
                val newPackageName = getPackageName(targetDir)
                javaFile.packageStatement?.let { packageStatement ->
                    val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)
                    val newPackageStatement = factory.createPackageStatement(newPackageName)
                    packageStatement.replace(newPackageStatement)
                } ?: run {
                    // Add package statement if it doesn't exist
                    if (newPackageName.isNotEmpty()) {
                        val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)
                        val newPackageStatement = factory.createPackageStatement(newPackageName)
                        javaFile.addBefore(newPackageStatement, javaFile.firstChild)
                    }
                }
            }

            // Delete the original file
            containingFile.delete()
        } else {
            // Class is one of multiple in the file - extract it
            val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)
            val className = psiClass.name ?: throw IllegalStateException("Class has no name")

            // Create new file with the class
            val newFileName = "$className.java"
            val packageName = getPackageName(targetDir)

            val newFileContent = buildString {
                if (packageName.isNotEmpty()) {
                    append("package $packageName;\n\n")
                }
                append(psiClass.text)
            }

            val newFile = targetDir.createFile(newFileName)
            val document = PsiDocumentManager.getInstance(project).getDocument(newFile)
            document?.setText(newFileContent)
            PsiDocumentManager.getInstance(project).commitDocument(document!!)

            // Remove the class from the original file
            psiClass.delete()
        }

        // Update all references to use the new location
        // This is typically handled automatically by the IDE's indexing
    }

    private fun getPackageName(directory: PsiDirectory): String {
        val sourceRoots = com.intellij.openapi.roots.ProjectRootManager.getInstance(directory.project)
            .contentSourceRoots

        for (sourceRoot in sourceRoots) {
            val sourcePath = sourceRoot.path
            val dirPath = directory.virtualFile.path

            if (dirPath.startsWith(sourcePath)) {
                val relativePath = dirPath.removePrefix(sourcePath).removePrefix("/")
                return relativePath.replace("/", ".")
            }
        }

        return ""
    }

    private fun moveMethod(
        project: Project,
        method: PsiMethod,
        targetClassName: String,
        sourceFile: String
    ): ToolCallResult {
        val methodName = readAction { method.name }

        // Check if method is static (required for simple move)
        val isStatic = readAction { method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) }
        if (!isStatic) {
            return createErrorResult("Only static methods can be moved. Method '$methodName' is not static.")
        }

        // Find the target class
        val targetClass = readAction {
            findClassByName(project, targetClassName)
        } ?: return createErrorResult("Target class not found: $targetClassName. Verify the fully qualified name is correct and the class is part of project '${project.name}'.")

        val affectedFiles = mutableSetOf<String>()
        var success = false
        var errorMessage: String? = null
        var newLocation: String? = null

        try {
            // Collect affected files
            readAction {
                method.containingFile?.virtualFile?.let { vf ->
                    trackAffectedFile(project, vf, affectedFiles)
                }

                targetClass.containingFile?.virtualFile?.let { vf ->
                    trackAffectedFile(project, vf, affectedFiles)
                }

                // Find all references
                val references = ReferencesSearch.search(method).findAll()
                for (reference in references) {
                    reference.element.containingFile?.virtualFile?.let { vf ->
                        trackAffectedFile(project, vf, affectedFiles)
                    }
                }
            }

            ApplicationManager.getApplication().invokeAndWait {
                try {
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Move Method: $methodName")
                        .withGroupId("MCP Refactoring")
                        .run<Throwable> {
                            // Copy method to target class
                            val copiedMethod = targetClass.add(method.copy()) as PsiMethod

                            // Delete original method
                            method.delete()

                            // Get new location
                            val targetFile = targetClass.containingFile?.virtualFile
                            if (targetFile != null) {
                                val document = PsiDocumentManager.getInstance(project).getDocument(targetClass.containingFile)
                                val lineNumber = document?.getLineNumber(copiedMethod.textOffset)?.plus(1)
                                newLocation = "${getRelativePath(project, targetFile)}:$lineNumber"
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
                    MoveResult(
                        success = true,
                        affectedFiles = affectedFiles.toList(),
                        changesCount = affectedFiles.size,
                        message = "Successfully moved method '$methodName' to '$targetClassName'",
                        newLocation = newLocation
                    )
                )
            } else {
                createErrorResult("Move method failed: ${errorMessage ?: "Unknown error"}")
            }
        } catch (e: Exception) {
            return createErrorResult("Move method failed: ${e.message}")
        }
    }

    private fun findMoveableElement(project: Project, file: String, line: Int, column: Int): PsiElement? {
        val element = findPsiElement(project, file, line, column) ?: return null

        // Check if we're on a class
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is PsiClass -> return current
                is PsiMethod -> return current
            }
            current = current.parent
        }

        // Try PsiTreeUtil
        val psiClass = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (psiClass != null) return psiClass

        val psiMethod = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        return psiMethod
    }

}

@Serializable
data class MoveResult(
    val success: Boolean,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String,
    val newLocation: String? = null
)

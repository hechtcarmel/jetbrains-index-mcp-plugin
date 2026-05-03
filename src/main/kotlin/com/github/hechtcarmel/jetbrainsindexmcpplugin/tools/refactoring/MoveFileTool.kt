package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.dotnet.RiderEnvironment
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.util.containers.MultiMap
import com.intellij.usageView.UsageInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Move file tool that uses the IDE's Move refactoring to relocate files
 * while delegating semantic updates to the IDE language plugin when supported.
 *
 * Most languages use [MoveFilesOrDirectoriesProcessor], which delegates file-move
 * semantics to language-specific [com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler]s.
 * PHP class files are special: PhpStorm exposes a dedicated class-move refactoring
 * processor. We route those files through that processor instead of the generic
 * file-move backend.
 *
 * Three-phase approach:
 * 1. **Read Action**: Validate source file exists
 * 2. **EDT Write Action**: Ensure destination directory exists (create if needed)
 * 3. **EDT**: Execute the appropriate move backend
 */
open class MoveFileTool : AbstractRefactoringTool() {

    private val LOG = logger<MoveFileTool>()

    override val name = "ide_move_file"

    override val description = """
        Move a file to a new directory using the IDE's refactoring engine. Applies language-aware reference and namespace/package updates when the IDE provides a semantic move backend for that file type.

        Use when relocating files to maintain correct imports and references.

        Parameters:
        - file (REQUIRED): Source file path relative to project root
        - destination (REQUIRED): Target directory path relative to project root. Created automatically if it doesn't exist.

        Returns: success status, list of affected files, and result message.

        Examples:
        - Move file: {"file": "src/main/java/com/old/MyClass.java", "destination": "src/main/java/com/new"}
        - Move config file: {"file": "config/old.yml", "destination": "config/archive"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to the source file to move, relative to project root. REQUIRED.")
        .stringProperty("destination", "Target directory path relative to project root. The file will be moved into this directory. Created automatically if it doesn't exist. REQUIRED.", required = true)
        .build()

    internal enum class MoveBackend {
        GENERIC_FILE_MOVE,
        PHP_SEMANTIC_MOVE
    }

    internal sealed class MoveBackendSelection {
        object GenericFileMove : MoveBackendSelection()
        data class PhpSemanticMove(
            val declarationPointer: SmartPsiElementPointer<PsiElement>,
            val declarationName: String
        ) : MoveBackendSelection()
        data class Unsupported(val message: String) : MoveBackendSelection()
    }

    internal data class MovePreparation(
        val psiFile: PsiFile,
        val targetDirectory: PsiDirectory,
        val sourceRelativePath: String,
        val destinationRelativePath: String,
        val backend: MoveBackend,
        val phpDeclarationPointer: SmartPsiElementPointer<PsiElement>? = null,
        val phpDeclarationName: String? = null
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, "file").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }
        val destination = requiredStringArg(arguments, "destination").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: destination")
        }

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: VFS + READ ACTION - Validate source file
        // ═══════════════════════════════════════════════════════════════════════
        val sourceVirtualFile = resolveFile(project, file)
            ?: return createErrorResult("Source file not found: $file")
        val sourceInfo = suspendingReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(sourceVirtualFile)
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
            val psiFile = PsiManager.getInstance(project).findFile(sourceVirtualFile)
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

            when (val backendSelection = selectMoveBackend(project, psiFile)) {
                is MoveBackendSelection.Unsupported -> null to backendSelection.message
                MoveBackendSelection.GenericFileMove -> {
                    val destinationRelativePath = getRelativePath(project, targetDir)
                    MovePreparation(
                        psiFile = psiFile,
                        targetDirectory = targetPsiDir,
                        sourceRelativePath = sourceRelativePath,
                        destinationRelativePath = destinationRelativePath,
                        backend = MoveBackend.GENERIC_FILE_MOVE
                    ) to null
                }
                is MoveBackendSelection.PhpSemanticMove -> {
                    val destinationRelativePath = getRelativePath(project, targetDir)
                    MovePreparation(
                        psiFile = psiFile,
                        targetDirectory = targetPsiDir,
                        sourceRelativePath = sourceRelativePath,
                        destinationRelativePath = destinationRelativePath,
                        backend = MoveBackend.PHP_SEMANTIC_MOVE,
                        phpDeclarationPointer = backendSelection.declarationPointer,
                        phpDeclarationName = backendSelection.declarationName
                    ) to null
                }
            }
        }

        val (movePrep, error) = preparation
        if (movePrep == null) {
            return createErrorResult(error ?: "Unknown validation error")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 4: EDT - Execute move processor (manages its own write actions)
        // ═══════════════════════════════════════════════════════════════════════
        return executeMove(project, movePrep)
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
        preparation: MovePreparation
    ): ToolCallResult {
        var success = false
        var errorMessage: String? = null
        var affectedFiles = linkedSetOf<String>()
        var namespaceUpdated = false
        val fileName = preparation.psiFile.name

        edtAction {
            try {
                if (!preparation.psiFile.isValid || !preparation.targetDirectory.isValid) {
                    errorMessage = "Source file or target directory is no longer valid"
                    return@edtAction
                }

                val filePointer = SmartPointerManager.createPointer(preparation.psiFile)
                val modifiedFilesBeforeMove = collectUnsavedProjectFiles(project)

                when (preparation.backend) {
                    MoveBackend.GENERIC_FILE_MOVE -> executeGenericFileMove(preparation)
                    MoveBackend.PHP_SEMANTIC_MOVE -> executePhpSemanticMove(project, preparation)
                }

                if (preparation.backend == MoveBackend.PHP_SEMANTIC_MOVE) {
                    cleanupMovedPhpFileImports(filePointer.element)
                }
                namespaceUpdated = updateMovedCSharpNamespace(project, preparation, fileName)

                PsiDocumentManager.getInstance(project).commitAllDocuments()
                affectedFiles = collectAffectedFiles(project, preparation, filePointer, fileName, modifiedFilesBeforeMove)
                if (namespaceUpdated) {
                    preparation.targetDirectory.findFile(fileName)?.virtualFile?.let { affectedFiles.add(getRelativePath(project, it)) }
                }
                FileDocumentManager.getInstance().saveAllDocuments()

                success = true
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }

        return if (success) {
            val newPath = if (preparation.destinationRelativePath.isBlank()) {
                fileName
            } else {
                "${preparation.destinationRelativePath}/$fileName"
            }
            val backendNote = when (preparation.backend) {
                MoveBackend.GENERIC_FILE_MOVE -> if (fileName.endsWith(".cs", ignoreCase = true)) {
                    val riderSuffix = if (RiderEnvironment.isAvailable) {
                        ", and cross-file using/import updates depend on Rider frontend support"
                    } else {
                        ""
                    }
                    if (namespaceUpdated) {
                        " using IDE file move semantics; C# namespace updated$riderSuffix"
                    } else {
                        " using IDE file move semantics; no C# namespace change was needed$riderSuffix"
                    }
                } else {
                    " using IDE file move semantics"
                }
                MoveBackend.PHP_SEMANTIC_MOVE -> " using PhpStorm semantic PHP move"
            }
            refreshProjectRootsAndCommit(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = affectedFiles.toList(),
                    changesCount = affectedFiles.size,
                    message = "Successfully moved '${preparation.sourceRelativePath}' to '$newPath'$backendNote"
                )
            )
        } else {
            createErrorResult("Move failed: ${errorMessage ?: "Unknown error"}")
        }
    }

    internal open fun selectMoveBackend(
        project: Project,
        psiFile: PsiFile
    ): MoveBackendSelection {
        if (!PluginDetectors.php.isAvailable || !isPhpFile(psiFile)) {
            return MoveBackendSelection.GenericFileMove
        }

        val phpDeclarations = findNamedPhpDeclarations(psiFile)
        if (phpDeclarations.isEmpty()) {
            return MoveBackendSelection.GenericFileMove
        }
        if (phpDeclarations.size > 1) {
            return MoveBackendSelection.Unsupported(
                "PHP semantic move is ambiguous for '${psiFile.name}' because it contains multiple named PHP declarations. " +
                    "Use PhpStorm's interactive Move refactoring for this file."
            )
        }

        val declaration = phpDeclarations.single()
        val declarationName = (declaration as? PsiNamedElement)?.name ?: psiFile.name
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(declaration)
        return MoveBackendSelection.PhpSemanticMove(pointer, declarationName)
    }

    internal open fun executeGenericFileMove(
        preparation: MovePreparation
    ) {
        val processor = HeadlessMoveProcessor(
            preparation.psiFile.project,
            arrayOf<PsiElement>(preparation.psiFile),
            preparation.targetDirectory,
            true,
            false, // searchInComments
            false, // searchInNonJavaFiles
            null,  // moveCallback
            null   // prepareSuccessfulCallback
        )

        processor.setPreviewUsages(false)
        processor.run()
    }

    private fun updateMovedCSharpNamespace(
        project: Project,
        preparation: MovePreparation,
        fileName: String
    ): Boolean {
        val movedFile = preparation.targetDirectory.findFile(fileName)
        if (movedFile == null || movedFile.virtualFile?.extension?.lowercase() != "cs") return false

        val document = PsiDocumentManager.getInstance(project).getDocument(movedFile) ?: return false
        val text = document.text
        val namespaceRegex = Regex("""(?m)^(\s*namespace\s+)([A-Za-z_][A-Za-z0-9_.]*)(\s*[;{])""")
        val matches = namespaceRegex.findAll(text).toList()
        if (matches.isEmpty()) return false
        // Refuse to silently rewrite only the first namespace when the file declares multiple
        // (e.g. multiple top-level scoped namespaces). Rider's refactoring engine is the right
        // place to handle that; the heuristic is only safe for the common single-namespace case.
        if (matches.size > 1) {
            LOG.info(
                "Skipping C# namespace update for '${preparation.destinationRelativePath}/$fileName': " +
                    "file declares ${matches.size} namespaces; manual or Rider-driven update required."
            )
            return false
        }
        val match = matches.single()
        val oldNamespace = match.groupValues[2]
        val newNamespace = inferMovedCSharpNamespace(oldNamespace, preparation) ?: return false
        if (newNamespace == oldNamespace) return false

        WriteCommandAction.writeCommandAction(project)
            .withName("Update C# Namespace")
            .withGroupId("MCP Refactoring")
            .run<Throwable> {
                document.replaceString(
                    match.groups[2]!!.range.first,
                    match.groups[2]!!.range.last + 1,
                    newNamespace
                )
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        return true
    }

    /**
     * Infers a destination C# namespace from the file's source namespace and the move's
     * old/new directory paths. Heuristic — does not consult `.csproj`/RootNamespace and assumes:
     *   * `src`, `main`, `test`, `csharp`, and `cs` are infrastructure folders (filtered out).
     *   * The original namespace's trailing segments mirror the source directory's trailing
     *     segments (common .NET convention). When that holds, the matching tail is replaced
     *     by the new directory tail; otherwise a single trailing segment is dropped.
     * Returns null if no plausible new namespace can be derived (e.g. moving to a project root).
     */
    private fun inferMovedCSharpNamespace(
        oldNamespace: String,
        preparation: MovePreparation
    ): String? {
        val oldDirSegments = preparation.sourceRelativePath
            .substringBeforeLast('/', "")
            .split('/', '\\')
            .filter { it.isNotBlank() && it != "src" && it != "main" && it != "test" }
            .filterNot { it.equals("csharp", ignoreCase = true) || it.equals("cs", ignoreCase = true) }
        val newDirSegments = preparation.destinationRelativePath
            .split('/', '\\')
            .filter { it.isNotBlank() && it != "src" && it != "main" && it != "test" }
            .filterNot { it.equals("csharp", ignoreCase = true) || it.equals("cs", ignoreCase = true) }

        if (newDirSegments.isEmpty()) return null
        val oldNamespaceSegments = oldNamespace.split('.').filter { it.isNotBlank() }
        val trailingMatchCount = commonTrailingSegmentCount(oldNamespaceSegments, oldDirSegments)
        val namespacePrefixSegments = if (trailingMatchCount > 0) {
            oldNamespaceSegments.dropLast(trailingMatchCount)
        } else {
            oldNamespaceSegments.dropLast(1)
        }
        val newNamespaceSegments = if (
            namespacePrefixSegments.isNotEmpty() &&
            newDirSegments.take(namespacePrefixSegments.size) == namespacePrefixSegments
        ) {
            newDirSegments
        } else {
            namespacePrefixSegments + newDirSegments
        }
        return newNamespaceSegments.distinctAdjacent().joinToString(".")
    }

    private fun commonTrailingSegmentCount(left: List<String>, right: List<String>): Int {
        var count = 0
        while (
            count < left.size &&
            count < right.size &&
            left[left.lastIndex - count] == right[right.lastIndex - count]
        ) {
            count++
        }
        return count
    }

    private fun List<String>.distinctAdjacent(): List<String> =
        filterIndexed { index, segment -> index == 0 || this[index - 1] != segment }

    internal open fun executePhpSemanticMove(project: Project, preparation: MovePreparation) {
        val declaration = preparation.phpDeclarationPointer?.element
            ?: error("PHP declaration for semantic move is no longer valid")
        val phpClassClass = loadPhpClassClass()
        if (!phpClassClass.isInstance(declaration)) {
            error(
                "PhpStorm semantic move is unavailable for PHP declaration '${preparation.phpDeclarationName ?: preparation.psiFile.name}'. " +
                    "Only PHP class-like declarations are supported."
            )
        }

        val destinationNamespace = determinePhpDestinationNamespace(project, preparation)
            ?: error(
                "PhpStorm semantic move could not determine the destination namespace for '${preparation.destinationRelativePath}'. " +
                    "Use a PSR-mapped source directory or PhpStorm's interactive Move refactoring."
            )

        runPhpMoveClassProcessor(project, preparation, phpClassClass.cast(declaration), destinationNamespace)
    }

    private fun runPhpMoveClassProcessor(
        project: Project,
        preparation: MovePreparation,
        phpClass: PsiElement,
        destinationNamespace: String
    ) {
        try {
            val phpClassClass = loadPhpClassClass()
            val className = (phpClass as? PsiNamedElement)?.name
                ?: error("PHP declaration name is no longer available")

            val phpFileCreationInfoClass = Class.forName("com.jetbrains.php.refactoring.PhpFileCreationInfo")
            val moveClassSettingsClass = Class.forName("com.jetbrains.php.refactoring.move.clazz.PhpMoveClassSettings")
            val moveClassProcessorClass = Class.forName("com.jetbrains.php.refactoring.move.clazz.PhpMoveClassProcessor")
            val moveClassDialogClass = Class.forName("com.jetbrains.php.refactoring.move.clazz.PhpMoveClassDialog")

            val generateConfiguration = phpFileCreationInfoClass.getMethod(
                "generateConfiguration",
                Project::class.java,
                String::class.java,
                String::class.java
            )
            val fileCreationInfo = generateConfiguration.invoke(
                null,
                project,
                preparation.targetDirectory.virtualFile.path,
                "$className.php"
            )

            val scopeContainsMultipleClasses = moveClassDialogClass
                .getMethod("isScopeHolderContainsMultipleClasses", phpClassClass)
                .invoke(null, phpClass) as Boolean

            val settings = moveClassSettingsClass
                .getConstructor(
                    phpClassClass,
                    String::class.java,
                    phpFileCreationInfoClass,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .newInstance(phpClass, destinationNamespace, fileCreationInfo, scopeContainsMultipleClasses, true)

            val processor = moveClassProcessorClass
                .getConstructor(
                    Project::class.java,
                    moveClassSettingsClass,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .newInstance(project, settings, false, false)

            processor.javaClass.methods.firstOrNull { it.name == "setPreviewUsages" && it.parameterCount == 1 }
                ?.invoke(processor, false)
            processor.javaClass.getMethod("run").invoke(processor)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException(
                "PhpStorm semantic move is unavailable in this IDE build: ${e.cause?.message ?: e.message}",
                e
            )
        }
    }

    private fun determinePhpDestinationNamespace(project: Project, preparation: MovePreparation): String? {
        val suggested = suggestPhpNamespaceFromProvider(preparation.targetDirectory)
        if (!suggested.isNullOrBlank()) {
            return normalizePhpNamespace(suggested)
        }

        val composerNamespace = determinePhpNamespaceFromComposer(project, preparation.targetDirectory)
        if (!composerNamespace.isNullOrBlank()) {
            return composerNamespace
        }

        return derivePhpNamespaceFromSourceRoot(project, preparation)
    }

    private fun suggestPhpNamespaceFromProvider(targetDirectory: PsiDirectory): String? {
        return try {
            val providerClass = Class.forName("com.jetbrains.php.roots.PhpNamespaceByPsrProvider")
            val suggestMethod = providerClass.getMethod("suggestNamespaceWithPsrRootsDetection", PsiDirectory::class.java)
            suggestMethod.invoke(null, targetDirectory) as? String
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun determinePhpNamespaceFromComposer(project: Project, targetDirectory: PsiDirectory): String? {
        val projectBasePath = project.basePath ?: return null
        val composerFile = Path.of(projectBasePath, "composer.json")
        if (!Files.isRegularFile(composerFile)) {
            return null
        }

        return try {
            val root = Json.parseToJsonElement(Files.readString(composerFile)).jsonObject
            val mappings = buildList {
                addAll(extractComposerPsrMappings(root["autoload"] as? JsonObject, projectBasePath))
                addAll(extractComposerPsrMappings(root["autoload-dev"] as? JsonObject, projectBasePath))
            }
            val targetPath = targetDirectory.virtualFile.path
            val mapping = mappings
                .filter { (namespacePrefix, directoryPath) ->
                    targetPath == directoryPath || targetPath.startsWith("$directoryPath/")
                }
                .maxByOrNull { (_, directoryPath) -> directoryPath.length }
                ?: return null

            val relativePath = targetPath.removePrefix(mapping.second).trimStart('/')
            val suffix = relativePath
                .split('/')
                .filter { it.isNotBlank() }
                .joinToString("\\")

            normalizePhpNamespace(
                listOf(mapping.first, suffix)
                    .filter { it.isNotBlank() }
                    .joinToString("\\")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractComposerPsrMappings(section: JsonObject?, projectBasePath: String): List<Pair<String, String>> {
        val psr4 = section?.get("psr-4") as? JsonObject ?: return emptyList()
        return buildList {
            for ((namespacePrefix, locationValue) in psr4) {
                val namespace = normalizePhpNamespace(namespacePrefix)
                when (locationValue) {
                    is JsonPrimitive -> {
                        add(namespace to resolveComposerPath(projectBasePath, locationValue.content))
                    }
                    else -> {
                        locationValue.jsonArray.forEach { entry ->
                            val entryValue = entry as? JsonPrimitive ?: return@forEach
                            add(namespace to resolveComposerPath(projectBasePath, entryValue.content))
                        }
                    }
                }
            }
        }
    }

    private fun resolveComposerPath(projectBasePath: String, rawPath: String): String {
        return Path.of(projectBasePath)
            .resolve(rawPath)
            .normalize()
            .toString()
            .replace('\\', '/')
            .removeSuffix("/")
    }

    private fun derivePhpNamespaceFromSourceRoot(project: Project, preparation: MovePreparation): String? {
        val currentDirectory = preparation.psiFile.containingDirectory ?: return null
        val currentNamespace = extractPhpNamespace(preparation.phpDeclarationPointer?.element ?: preparation.psiFile) ?: return null

        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val currentSourceRoot = fileIndex.getSourceRootForFile(currentDirectory.virtualFile) ?: return null
        if (!VfsUtil.isAncestor(currentSourceRoot, preparation.targetDirectory.virtualFile, false)) {
            return null
        }

        val currentRelativeDir = VfsUtil.getRelativePath(currentDirectory.virtualFile, currentSourceRoot, '/')
            ?.takeIf { it.isNotEmpty() }
        val targetRelativeDir = VfsUtil.getRelativePath(preparation.targetDirectory.virtualFile, currentSourceRoot, '/')
            ?: return null

        val suffix = currentRelativeDir?.replace('/', '\\')
        val prefix = when {
            suffix.isNullOrEmpty() -> currentNamespace
            currentNamespace == suffix -> ""
            currentNamespace.endsWith("\\$suffix") -> currentNamespace.removeSuffix("\\$suffix")
            else -> return null
        }

        val targetSuffix = targetRelativeDir
            .takeIf { it.isNotEmpty() }
            ?.replace('/', '\\')
            .orEmpty()
        return normalizePhpNamespace(listOf(prefix, targetSuffix).filter { it.isNotBlank() }.joinToString("\\"))
    }

    private fun extractPhpNamespace(element: PsiElement): String? {
        return try {
            val method = element.javaClass.methods.firstOrNull {
                it.name == "getNamespaceName" && it.parameterCount == 0 && it.returnType == String::class.java
            } ?: return null
            method.invoke(element) as? String
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun normalizePhpNamespace(namespace: String): String {
        return namespace.trim().removePrefix("\\").removeSuffix("\\")
    }

    private fun loadPhpClassClass(): Class<out PsiElement> {
        @Suppress("UNCHECKED_CAST")
        return Class.forName("com.jetbrains.php.lang.psi.elements.PhpClass") as Class<out PsiElement>
    }

    private fun collectAffectedFiles(
        project: Project,
        preparation: MovePreparation,
        movedFilePointer: SmartPsiElementPointer<PsiFile>,
        fileName: String,
        modifiedFilesBeforeMove: Set<String>
    ): LinkedHashSet<String> {
        val affectedFiles = linkedSetOf<String>()
        affectedFiles.add(preparation.sourceRelativePath)

        val modifiedFilesAfterMove = collectUnsavedProjectFiles(project)
        affectedFiles.addAll(modifiedFilesAfterMove - modifiedFilesBeforeMove)

        val movedFile = movedFilePointer.element?.virtualFile
            ?: LocalFileSystem.getInstance().findFileByPath("${preparation.targetDirectory.virtualFile.path}/$fileName")
        if (movedFile != null) {
            affectedFiles.add(getRelativePath(project, movedFile))
        }

        return affectedFiles
    }

    private fun collectUnsavedProjectFiles(project: Project): Set<String> {
        val fileDocumentManager = FileDocumentManager.getInstance()
        return fileDocumentManager.unsavedDocuments
            .mapNotNull(fileDocumentManager::getFile)
            .filter { ProjectUtils.isProjectFile(project, it) }
            .map { getRelativePath(project, it) }
            .toSet()
    }

    private fun isPhpFile(psiFile: PsiFile): Boolean {
        return psiFile.language.id == "PHP" || psiFile.viewProvider.languages.any { it.id == "PHP" }
    }

    private fun findNamedPhpDeclarations(psiFile: PsiFile): List<PsiElement> {
        val phpClassClass = try {
            loadPhpClassClass()
        } catch (_: ClassNotFoundException) {
            return emptyList()
        }

        return PsiTreeUtil.findChildrenOfType(psiFile, phpClassClass)
            .filter { it.containingFile == psiFile }
            .filter { (it as? PsiNamedElement)?.name != null }
            .sortedBy { it.textOffset }
    }

    private fun cleanupMovedPhpFileImports(movedFile: PsiFile?) {
        if (movedFile == null || !movedFile.isValid || !isPhpFile(movedFile)) {
            return
        }

        runCatching {
            OptimizeImportsProcessor(movedFile.project, movedFile).runWithoutProgress()
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

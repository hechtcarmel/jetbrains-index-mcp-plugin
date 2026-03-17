package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.idea.actions.JavaToKotlinAction
import org.jetbrains.kotlin.psi.KtFile

/**
 * Tool for converting Java files to Kotlin using IntelliJ's built-in J2K (Java-to-Kotlin) converter.
 *
 * This tool uses reflection to access the Kotlin plugin's conversion handler (`JavaToKotlinAction.Handler`)
 * to avoid compile-time dependencies and UI dialogs. It follows a two-phase approach:
 *
 * 1. **Phase 1 (Background - Read Action)**: Resolve and validate Java files
 * 2. **Phase 2 (Headless Conversion)**: Invoke the handler's `convertFiles()` method via reflection
 *
 * The converter handles:
 * - Classes, interfaces, enums, annotations
 * - Methods, fields, constructors
 * - Generics and type parameters
 * - Java 8+ features (lambdas, streams, method references)
 * - Automatic import management and code formatting
 *
 * Note: Some advanced Java constructs may require manual adjustment after conversion.
 *
 * @see AbstractRefactoringTool
 */
class ConvertJavaToKotlinTool : AbstractRefactoringTool() {

    companion object {
        private val LOG = logger<ConvertJavaToKotlinTool>()
    }

    override val name = "ide_convert_java_to_kotlin"

    override val description = """
        Convert Java files to Kotlin using IntelliJ's built-in converter.

        The converter automatically handles:
        - Classes, interfaces, enums, annotations → Kotlin equivalents
        - Methods → functions with Kotlin syntax
        - Fields → properties with getters/setters
        - Java 8+ features (lambdas, streams) → Kotlin idioms
        - Imports and code formatting

        Some advanced constructs may need manual adjustment after conversion.

        Parameters:
        - file or files: Java file(s) to convert (required)

        Returns: List of created .kt files, conversion warnings, success status.

        Note: Requires both Java and Kotlin plugins. The converter automatically formats
        and optimizes imports. Java files are deleted after successful conversion.

        Example: {"file": "src/Main.java", "deleteOriginal": true}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Java file to convert (relative to project root).")
        .property("files", buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
            }
            put("description", "Multiple Java files to convert. Alternative to 'file' parameter.")
        })
        .build()

    /**
     * Data class holding validated Java files from Phase 1.
     */
    private data class ConversionPreparation(
        val javaFiles: List<PsiJavaFile>, // PsiJavaFile instances (accessed via reflection)
        val filePaths: List<String> // Relative paths for tracking
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        // Extract parameters
        val singleFile = arguments["file"]?.jsonPrimitive?.content
        val filesList = arguments["files"]?.jsonArray?.map { it.jsonPrimitive.content }

        if (singleFile == null && filesList == null) {
            return createErrorResult("Missing required parameter: 'file' or 'files'")
        }

        val filesToConvert = when {
            filesList != null -> filesList
            singleFile != null -> listOf(singleFile)
            else -> emptyList()
        }

        if (filesToConvert.isEmpty()) {
            return createErrorResult("No files specified for conversion")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Resolve and validate Java files
        // Note: File resolution happens outside read action to avoid VFS refresh under read lock
        // ═══════════════════════════════════════════════════════════════════════

        // Step 1a: Resolve virtual files (outside read action to allow VFS refresh)
        val virtualFiles = filesToConvert.mapNotNull { path ->
            PsiUtils.resolveVirtualFileAnywhere(project, path)?.let { path to it }
        }.toMap()

        if (virtualFiles.isEmpty()) {
            return createErrorResult("No valid files found at specified paths: ${filesToConvert.joinToString(", ")}")
        }

        // Step 1b: Validate Java files (inside read action)
        val preparation = suspendingReadAction {
            prepareJavaFiles(project, virtualFiles)
        }

        if (preparation.javaFiles.isEmpty()) {
            return createErrorResult("No valid Java files found. Ensure files have .java extension.")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: ASYNC - Invoke JavaToKotlinActionHandler (handles everything!)
        // The handler converts files, creates .kt files, and optionally deletes .java files
        // ═══════════════════════════════════════════════════════════════════════
        return try {
            performConversion(project, preparation)
        } catch (e: Exception) {
            LOG.error("Conversion failed", e)
            createErrorResult("Conversion failed: ${e.message}")
        }
    }

    /**
     * Phase 1: Validates Java files from pre-resolved virtual files.
     * Must run in read action.
     *
     * @param virtualFiles Map of original path -> VirtualFile (pre-resolved outside read action)
     */
    private fun prepareJavaFiles(
        project: Project,
        virtualFiles: Map<String, com.intellij.openapi.vfs.VirtualFile>,
    ): ConversionPreparation {
        val psiManager = PsiManager.getInstance(project)
        val javaFiles = mutableListOf<PsiJavaFile>()
        val validPaths = mutableListOf<String>()

        for ((filePath, virtualFile) in virtualFiles) {
            val psiFile = psiManager.findFile(virtualFile)
            if (psiFile == null) {
                LOG.warn("PSI file not found: $filePath")
                continue
            }

            // Check if it's a Java file using reflection (avoid PsiJavaFile compile dependency)
            if (psiFile !is PsiJavaFile) {
                LOG.warn("Not a Java file: $filePath")
                continue
            }

            javaFiles.add(psiFile)
            validPaths.add(filePath)
        }

        return ConversionPreparation(
            javaFiles = javaFiles,
            filePaths = validPaths
        )
    }

    /**
     * Phase 2: Invokes the J2K converter via reflection to call `JavaToKotlinAction.Handler.convertFiles()`
     * directly, bypassing the UI action system to avoid dialogs.
     *
     * Signature: fun convertFiles(files: List<PsiJavaFile>, project: Project, module: Module,
     *                              enableExternalCodeProcessing: Boolean = true,
     *                              askExternalCodeProcessing: Boolean = true,
     *                              forceUsingOldJ2k: Boolean = false,
     *                              settings: ConverterSettings = defaultSettings): List<KtFile>
     */
    private suspend fun performConversion(
        project: Project,
        preparation: ConversionPreparation
    ): ToolCallResult {
        try {
            // Get the module for the first file (required parameter)
            val module = getModuleForConversion(preparation.javaFiles)
                ?: return createErrorResult(
                    "Could not determine module for file. Ensure the file is part of a module in the project."
                )

            val ktFiles = edtAction {
                JavaToKotlinAction.Handler.convertFiles(
                    files = preparation.javaFiles,
                    project = project,
                    module = module,
                    enableExternalCodeProcessing = true,
                    askExternalCodeProcessing = false
                )
            }
            return processConversionResults(project, ktFiles, preparation)
        } catch (e: Exception) {
            LOG.error("Conversion failed", e)
            return createErrorResult("Conversion failed: ${e.message}")
        }
    }

    /**
     * Gets the module for the files to be converted.
     */
    private suspend fun getModuleForConversion(javaFiles: List<Any>): com.intellij.openapi.module.Module? {
        val firstFile = javaFiles.firstOrNull() ?: return null
        return suspendingReadAction {
            ModuleUtilCore.findModuleForPsiElement(firstFile as com.intellij.psi.PsiElement)
        }
    }

    /**
     * Processes the conversion results and creates the tool response.
     */
    private suspend fun processConversionResults(
        project: Project,
        ktFiles: List<KtFile>,
        preparation: ConversionPreparation
    ): ToolCallResult {
        val convertedInfoList = mutableListOf<ConvertedFileInfo>()

        for ((index, ktFile) in ktFiles.withIndex()) {
            val virtualFile = ktFile.virtualFile ?: continue
            val originalPath = preparation.filePaths.getOrNull(index) ?: "unknown"
            val relativePath = getRelativePath(project, virtualFile)

            val lineCount = suspendingReadAction {
                (ktFile as? com.intellij.psi.PsiFile)?.text?.lines()?.size ?: 0
            }

            val originalJavaPath = preparation.javaFiles.getOrNull(index)
                ?.let { it.virtualFile?.path }

            val javaStillExists = originalJavaPath?.let {
                VirtualFileManager.getInstance().findFileByUrl("file://$it") != null
            } ?: false

            convertedInfoList.add(
                ConvertedFileInfo(
                    originalJavaFile = originalPath,
                    newKotlinFile = relativePath,
                    linesConverted = lineCount,
                    deleted = !javaStillExists
                )
            )

            LOG.info("Successfully converted $originalPath to $relativePath")
        }

        if (convertedInfoList.isEmpty()) {
            return createErrorResult("Conversion completed but no Kotlin files were created")
        }

        return createJsonResult(
            JavaToKotlinConversionResult(
                success = true,
                convertedFiles = convertedInfoList,
                warnings = emptyList(),
                message = "Successfully converted ${convertedInfoList.size} Java file(s) to Kotlin (headless mode)"
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// RESULT DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════════

@Serializable
data class JavaToKotlinConversionResult(
    val success: Boolean,
    val convertedFiles: List<ConvertedFileInfo>,
    val warnings: List<String>,
    val message: String
)

@Serializable
data class ConvertedFileInfo(
    val originalJavaFile: String,
    val newKotlinFile: String,
    val linesConverted: Int,
    val deleted: Boolean
)
package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.idea.actions.JavaToKotlinAction
import org.jetbrains.kotlin.idea.configuration.hasKotlinPluginEnabled
import org.jetbrains.kotlin.psi.KtFile

/**
 * Tool for converting Java files to Kotlin using IntelliJ's built-in J2K (Java-to-Kotlin) converter.
 *
 * This tool uses he Kotlin plugin's conversion handler (`JavaToKotlinAction.Handler`)
 * to avoid compile-time dependencies and UI dialogs. It follows a two-phase approach:
 *
 * 1. **Phase 1 (Background - Read Action)**: Resolve and validate Java files
 * 2. **Phase 2 (Headless Conversion)**: Invoke the handler's `convertFiles()`
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
        and optimizes imports. Original Java files are deleted after successful conversion.

        Example: {"file": "src/Main.java"}
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
     * Mutable per-request state carried through the conversion pipeline.
     */
    private data class ConversionTarget(
        val requestedPath: String,
        var javaVirtualFilePath: String? = null,
        var psiJavaFile: PsiJavaFile? = null,
        var module: Module? = null,
        var result: FileConversionResult = FileConversionResult(
            requestedPath = requestedPath,
            status = ConversionStatus.SKIPPED,
            reason = "File not found"
        )
    ) {
        val expectedKotlinPath: String?
            get() = javaVirtualFilePath?.let { it.removeSuffix(".java") + ".kt" }
    }

    private data class ConversionPreparation(
        val targets: List<ConversionTarget>
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

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
        val virtualFiles = filesToConvert.mapNotNull { path ->
            PsiUtils.resolveVirtualFileAnywhere(project, path)?.let { path to it }
        }.toMap()

        val preparation = suspendingReadAction {
            prepareJavaFiles(project, virtualFiles, filesToConvert)
        }

        // If no files can be converted, return structured results immediately.
        if (preparation.targets.none { it.psiJavaFile != null }) {
            return createFinalResult(preparation.targets).result
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: ASYNC - Invoke JavaToKotlinActionHandler (handles everything!)
        // The handler converts files, creates .kt files, and optionally deletes .java files
        // ═══════════════════════════════════════════════════════════════════════
        return try {
            performConversion(project, preparation).also {
                if (it.summary.converted > 0) {
                    commitDocuments(project)
                    edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
                }
            }.result
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
     * @param allRequestedFiles List of all requested file paths for tracking
     */
    private fun prepareJavaFiles(
        project: Project,
        virtualFiles: Map<String, com.intellij.openapi.vfs.VirtualFile>,
        allRequestedFiles: List<String>
    ): ConversionPreparation {
        val psiManager = PsiManager.getInstance(project)
        val targets = allRequestedFiles.map { requestedPath -> ConversionTarget(requestedPath = requestedPath) }
        val targetsByPath = targets.associateBy { it.requestedPath }

        // Validate each found file
        for ((requestedPath, virtualFile) in virtualFiles) {
            val target = targetsByPath[requestedPath] ?: continue
            val psiFile = psiManager.findFile(virtualFile)
            if (psiFile == null) {
                LOG.warn("PSI file not found: $requestedPath")
                target.result = skippedResult(requestedPath, "PSI file not found")
                continue
            }

            if (psiFile !is PsiJavaFile) {
                LOG.warn("Not a Java file: $requestedPath")
                target.result = skippedResult(requestedPath, "Not a Java file (.java extension required)")
                continue
            }

            target.javaVirtualFilePath = virtualFile.path
            target.psiJavaFile = psiFile
            target.result = failedResult(requestedPath, "Conversion did not produce a Kotlin file")
        }

        return ConversionPreparation(targets = targets)
    }

    /**
     * Phase 2: Invokes the J2K converter `JavaToKotlinAction.Handler.convertFiles()`
     * directly, bypassing the UI action system to avoid dialogs.
     */
    private suspend fun performConversion(
        project: Project,
        preparation: ConversionPreparation
    ): ConversionExecutionResult {
        // Group files by module and track files without modules
        val targetsByModule = mutableMapOf<Module, MutableList<ConversionTarget>>()
        for (target in preparation.targets) {
            val javaFile = target.psiJavaFile ?: continue
            val module = getModuleForConversion(javaFile)
            target.module = module

            if (module == null) {
                target.result = skippedResult(target.requestedPath, "No module found for file")
                continue
            }

            if (!module.hasKotlinPluginEnabled()) {
                target.result = skippedResult(
                    target.requestedPath,
                    "Module '${module.name}' does not have Kotlin plugin enabled"
                )
                continue
            }

            targetsByModule.computeIfAbsent(module) { mutableListOf() }.add(target)
        }

        // Convert files module by module
        for ((module, targets) in targetsByModule) {
            try {
                val javaFiles = targets.mapNotNull { it.psiJavaFile }
                val converted = edtAction {
                    JavaToKotlinAction.Handler.convertFiles(
                        files = javaFiles, project = project, module = module,
                        enableExternalCodeProcessing = true, askExternalCodeProcessing = false
                    )
                }

                val targetsByExpectedKotlinPath = targets.mapNotNull { target ->
                    target.expectedKotlinPath?.let {
                        it to target
                    }
                }.toMap()

                val remainingConverted = converted.toMutableList()
                for ((expectedKotlinPath, target) in targetsByExpectedKotlinPath) {
                    val matchingKtFile = remainingConverted.firstOrNull { ktFile ->
                        ktFile.virtualFile?.path == expectedKotlinPath
                    } ?: continue

                    updateSuccessfulResult(project, target, matchingKtFile)
                    remainingConverted.remove(matchingKtFile)
                }

                for (target in targets) {
                    if (target.result.status != ConversionStatus.CONVERTED) {
                        target.result = failedResult(
                            target.requestedPath,
                            "Conversion did not produce a Kotlin file"
                        )
                    }
                }
            } catch (e: Exception) {
                LOG.error("Conversion failed for module ${module.name}", e)
                // Mark all files in this module as failed
                for (target in targets) {
                    target.result = failedResult(target.requestedPath, "Conversion error: ${e.message}")
                }
            }
        }

        return createFinalResult(preparation.targets)
    }

    /**
     * Gets the module for the files to be converted.
     */
    private suspend fun getModuleForConversion(javaFile: PsiJavaFile): Module? {
        return suspendingReadAction {
            ModuleUtilCore.findModuleForPsiElement(javaFile)
        }
    }

    private suspend fun updateSuccessfulResult(
        project: Project,
        target: ConversionTarget,
        ktFile: KtFile
    ) {
        val virtualFile = ktFile.virtualFile ?: return
        val kotlinRelativePath = getRelativePath(project, virtualFile)

        val lineCount = suspendingReadAction {
            ktFile.containingFile.text?.lines()?.size ?: 0
        }

        // Check if original Java file was deleted (use the requested path to resolve the original input).
        val javaVirtualFile = PsiUtils.resolveVirtualFileAnywhere(project, target.requestedPath)
        val javaStillExists = javaVirtualFile != null

        target.result = FileConversionResult(
            requestedPath = target.requestedPath,
            status = ConversionStatus.CONVERTED,
            kotlinFile = kotlinRelativePath,
            linesConverted = lineCount,
            javaFileDeleted = !javaStillExists
        )

        LOG.info("Successfully converted ${target.requestedPath} to $kotlinRelativePath")
    }

    /**
     * Creates the final result with summary statistics.
     */
    private fun createFinalResult(
        targets: List<ConversionTarget>
    ): ConversionExecutionResult {
        val fileResults = targets.map { it.result }
        val converted = fileResults.count { it.status == ConversionStatus.CONVERTED }
        val skipped = fileResults.count { it.status == ConversionStatus.SKIPPED }
        val failed = fileResults.count { it.status == ConversionStatus.FAILED }

        val result = JavaToKotlinConversionResult(
            files = fileResults,
            summary = ConversionSummary(
                totalRequested = targets.size,
                converted = converted,
                skipped = skipped,
                failed = failed
            )
        )

        return ConversionExecutionResult(
            result = createJsonResult(result),
            summary = result.summary
        )
    }

    private fun skippedResult(requestedPath: String, reason: String) = FileConversionResult(
        requestedPath = requestedPath,
        status = ConversionStatus.SKIPPED,
        reason = reason
    )

    private fun failedResult(requestedPath: String, reason: String) = FileConversionResult(
        requestedPath = requestedPath,
        status = ConversionStatus.FAILED,
        reason = reason
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// RESULT DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════════

private data class ConversionExecutionResult(
    val result: ToolCallResult,
    val summary: ConversionSummary
)

@Serializable
enum class ConversionStatus {
    CONVERTED,  // Successfully converted to Kotlin
    SKIPPED,    // Validation failed (not found, not Java, no module, etc.)
    FAILED      // Conversion attempted but threw exception
}

@Serializable
data class FileConversionResult(
    val requestedPath: String,              // Original path from request
    val status: ConversionStatus,

    // Success fields (only when status == CONVERTED)
    val kotlinFile: String? = null,         // Relative path to new .kt file
    val linesConverted: Int? = null,        // Line count
    val javaFileDeleted: Boolean? = null,   // Whether .java was deleted

    // Failure fields (only when status != CONVERTED)
    val reason: String? = null              // Why it failed/was skipped
)

@Serializable
data class JavaToKotlinConversionResult(
    val files: List<FileConversionResult>,
    val summary: ConversionSummary
)

@Serializable
data class ConversionSummary(
    val totalRequested: Int,
    val converted: Int,
    val skipped: Int,
    val failed: Int
)

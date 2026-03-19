package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.idea.configuration.hasKotlinPluginEnabled
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Tool for converting Java files to Kotlin using IntelliJ's built-in J2K (Java-to-Kotlin) converter.
 *
 * This tool uses reflection to invoke the Kotlin plugin's conversion handler, supporting both:
 * - **Old API (2025.x)**: `JavaToKotlinAction.Handler.convertFiles()` — synchronous, returns `List<KtFile>`
 * - **New API (2026.1+)**: `JavaToKotlinActionHandler.convertFiles()` — suspend, returns `Unit`
 *
 * It follows a two-phase approach:
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

        /**
         * Represents the J2K converter APIs across IntelliJ versions.
         * Uses reflection to avoid compile-time dependencies on classes that may not exist
         * in all supported IDE versions.
         */
        private sealed class ConverterApi(val handlerClass: Class<*>, val instance: Any) {
            /** Old API (2025.x): JavaToKotlinAction.Handler — synchronous, returns List<KtFile> */
            class Old(handlerClass: Class<*>, instance: Any) : ConverterApi(handlerClass, instance)
            /** New API (2026.1+): JavaToKotlinActionHandler — suspend, returns Unit */
            class New(handlerClass: Class<*>, instance: Any) : ConverterApi(handlerClass, instance)
        }

        private val converterApi: ConverterApi by lazy { detectConverterApi() }

        private fun detectConverterApi(): ConverterApi {
            // Try new API first (IntelliJ 2026.1+)
            try {
                val cls = Class.forName("org.jetbrains.kotlin.idea.actions.JavaToKotlinActionHandler")
                return ConverterApi.New(cls, cls.getField("INSTANCE").get(null))
            } catch (_: ClassNotFoundException) {}

            // Fall back to old API (IntelliJ 2025.x)
            try {
                val cls = Class.forName("org.jetbrains.kotlin.idea.actions.JavaToKotlinAction\$Handler")
                return ConverterApi.Old(cls, cls.getField("INSTANCE").get(null))
            } catch (_: ClassNotFoundException) {}

            throw IllegalStateException("No J2K converter API found - ensure the Kotlin plugin is installed")
        }
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
        - files: Java files to convert (required)

        Returns: List of created .kt files, conversion warnings, success status.

        Note: Requires both Java and Kotlin plugins. The converter automatically formats
        and optimizes imports. Original Java files are deleted after successful conversion.

        Example: {"files": ["src/Main.java"]}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .property("files", buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
            }
            put("description", "Java files to convert (relative to project root).")
        }, required = true)
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

    private data class ResolvedInput(
        val requestedPath: String,
        val virtualFile: VirtualFile?
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)

        val filesList = arguments["files"]?.jsonArray?.map { it.jsonPrimitive.content }

        if (filesList == null) {
            return createErrorResult("Missing required parameter: 'files'")
        }

        if (filesList.isEmpty()) {
            return createErrorResult("No files specified for conversion")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Resolve and validate Java files
        // Note: File resolution happens outside read action to avoid VFS refresh under read lock
        // ═══════════════════════════════════════════════════════════════════════
        val resolvedInputs = filesList.map { path ->
            ResolvedInput(
                requestedPath = path,
                virtualFile = PsiUtils.resolveVirtualFileAnywhere(project, path)
            )
        }

        val preparation = suspendingReadAction {
            prepareJavaFiles(project, resolvedInputs)
        }

        // If no files can be converted, return structured results immediately.
        if (preparation.targets.none { it.psiJavaFile != null }) {
            return createFinalResult(preparation.targets).result
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: ASYNC - Invoke J2K converter via reflection
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
     * @param resolvedInputs Request inputs paired with pre-resolved virtual files.
     */
    private fun prepareJavaFiles(
        project: Project,
        resolvedInputs: List<ResolvedInput>
    ): ConversionPreparation {
        val psiManager = PsiManager.getInstance(project)
        val targets = resolvedInputs.map { input -> ConversionTarget(requestedPath = input.requestedPath) }

        // Validate each found file
        for ((target, input) in targets.zip(resolvedInputs)) {
            val requestedPath = target.requestedPath
            val virtualFile = input.virtualFile ?: continue
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
     * Phase 2: Invokes the J2K converter via reflection, dispatching to the appropriate
     * API based on the detected IntelliJ version.
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
                when (val api = converterApi) {
                    is ConverterApi.Old -> convertWithOldApi(api, javaFiles, project, module, targets)
                    is ConverterApi.New -> convertWithNewApi(api, javaFiles, project, module, targets)
                }
            } catch (e: Exception) {
                LOG.error("Conversion failed for module ${module.name}", e)
                for (target in targets) {
                    target.result = failedResult(target.requestedPath, "Conversion error: ${e.message}")
                }
            }
        }

        return createFinalResult(preparation.targets)
    }

    /**
     * Converts files using the old API (2025.x): `JavaToKotlinAction.Handler.convertFiles()`
     *
     * This is a synchronous function that returns `List<KtFile>`. We match the returned files
     * to our targets by comparing expected Kotlin file paths.
     *
     * Uses the `$default` static method to handle default parameters without needing to resolve
     * `ConverterSettings.defaultSettings` via reflection.
     */
    private suspend fun convertWithOldApi(
        api: ConverterApi.Old,
        javaFiles: List<PsiJavaFile>,
        project: Project,
        module: Module,
        targets: List<ConversionTarget>
    ) {
        val defaultMethods = api.handlerClass.declaredMethods.filter {
            it.name == "convertFiles\$default"
        }

        if (defaultMethods.isEmpty()) {
            throw IllegalStateException("convertFiles\$default not found on ${api.handlerClass.name}")
        }

        // The Handler class has two convertFiles() overloads:
        //   - 7 real params (with forceUsingOldJ2k) → $default has 10 params
        //   - 6 real params (without forceUsingOldJ2k) → $default has 9 params
        // Prefer the 10-param variant; fall back to 9-param if absent.
        val tenParamMethod = defaultMethods.firstOrNull { it.parameterCount == 10 }
        val nineParamMethod = defaultMethods.firstOrNull { it.parameterCount == 9 }

        val converted = edtAction {
            try {
                if (tenParamMethod != null) {
                    // $default params: instance, files, project, module, enableExternal, askExternal,
                    //   forceUsingOldJ2k(defaulted), settings(defaulted), mask, marker
                    tenParamMethod.invoke(
                        null, api.instance, javaFiles, project, module,
                        true, false, false, null,
                        96, null // mask: bit 5 | bit 6 (forceUsingOldJ2k, settings)
                    ) as? List<*> ?: emptyList<Any>()
                } else {
                    // $default params: instance, files, project, module, enableExternal, askExternal,
                    //   settings(defaulted), mask, marker
                    nineParamMethod!!.invoke(
                        null, api.instance, javaFiles, project, module,
                        true, false, null,
                        32, null // mask: bit 5 (settings)
                    ) as? List<*> ?: emptyList<Any>()
                }
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }

        matchConvertedFilesToTargets(project, converted, targets)
    }

    /**
     * Matches returned KtFile objects (from old API) to conversion targets by expected path.
     * Uses reflection to access `getVirtualFile()` to avoid compile-time dependency on `KtFile`.
     */
    private suspend fun matchConvertedFilesToTargets(
        project: Project,
        converted: List<*>,
        targets: List<ConversionTarget>
    ) {
        val targetsByExpectedKotlinPath = targets.mapNotNull { target ->
            target.expectedKotlinPath?.let { it to target }
        }.toMap()

        val remainingConverted = converted.toMutableList()
        for ((expectedKotlinPath, target) in targetsByExpectedKotlinPath) {
            val matchingItem = remainingConverted.firstOrNull { item ->
                try {
                    val vf = item?.javaClass?.getMethod("getVirtualFile")?.invoke(item)
                    vf?.javaClass?.getMethod("getPath")?.invoke(vf) == expectedKotlinPath
                } catch (_: Exception) { false }
            } ?: continue

            val vf = try {
                matchingItem?.javaClass?.getMethod("getVirtualFile")?.invoke(matchingItem) as? VirtualFile
            } catch (_: Exception) { null }

            if (vf != null) {
                updateSuccessfulResult(project, target, vf)
            }
            remainingConverted.remove(matchingItem)
        }

        for (target in targets) {
            if (target.result.status != ConversionStatus.CONVERTED) {
                target.result = failedResult(
                    target.requestedPath,
                    "Conversion did not produce a Kotlin file"
                )
            }
        }
    }

    /**
     * Converts files using the new API (2026.1+): `JavaToKotlinActionHandler.convertFiles()`
     *
     * This is a suspend function that returns `Unit` — it handles file creation, formatting,
     * and optional Java file deletion internally. After the call completes, we check the
     * filesystem to determine which files were successfully converted.
     *
     * Uses `suspendCoroutineUninterceptedOrReturn` to invoke the suspend function via reflection.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun convertWithNewApi(
        api: ConverterApi.New,
        javaFiles: List<PsiJavaFile>,
        project: Project,
        module: Module,
        targets: List<ConversionTarget>
    ) {
        // 13 params: instance, files, project, module, enableExternal, askExternal,
        //   bodyFilter, settings, pre, post, continuation, mask, marker
        val defaultMethod = api.handlerClass.declaredMethods.firstOrNull {
            it.name == "convertFiles\$default" && it.parameterCount == 13
        } ?: throw IllegalStateException(
            "convertFiles\$default with 13 params not found on ${api.handlerClass.name}"
        )

        suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
            try {
                defaultMethod.invoke(
                    null, api.instance, javaFiles, project, module,
                    true, false, null, null, null, null,
                    uCont.intercepted(),
                    480, null // mask: bits 5-8 (bodyFilter, settings, pre, post)
                )
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }

        // New API handles file creation/deletion internally. Check filesystem for results.
        for (target in targets) {
            val expectedKtPath = target.expectedKotlinPath ?: continue
            val ktVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(expectedKtPath)
            if (ktVirtualFile != null) {
                updateSuccessfulResult(project, target, ktVirtualFile)
            } else {
                target.result = failedResult(
                    target.requestedPath,
                    "Conversion did not produce a Kotlin file"
                )
            }
        }
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
        ktVirtualFile: VirtualFile
    ) {
        val kotlinRelativePath = getRelativePath(project, ktVirtualFile)

        val lineCount = suspendingReadAction {
            PsiManager.getInstance(project).findFile(ktVirtualFile)?.text?.lines()?.size ?: 0
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

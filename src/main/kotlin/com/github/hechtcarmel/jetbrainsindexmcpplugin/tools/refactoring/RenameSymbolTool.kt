package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.UnifiedTargetArguments
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.intellij.util.containers.MultiMap
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.lang.reflect.InvocationTargetException

/**
 * Universal rename tool that works across all languages supported by JetBrains IDEs.
 *
 * This tool uses IntelliJ's `RenameProcessor` which is language-agnostic and delegates
 * to language-specific `RenamePsiElementProcessor` implementations. This enables:
 * - Java/Kotlin: getter/setter renaming, overriding methods, test classes
 * - Python: function/class/variable renaming
 * - JavaScript/TypeScript: symbol renaming across files
 * - Go: function/type/variable renaming
 * - And more languages via their respective plugins
 *
 * The tool uses a two-phase approach:
 * 1. **Background Phase**: Find element and validate (read action)
 * 2. **EDT Phase**: Execute rename via RenameProcessor (handles all references)
 */
class RenameSymbolTool : AbstractMcpTool() {

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(RenameSymbolTool::class.java)

        private val JS_TS_LANGUAGE_IDS = setOf(
            "JavaScript",
            "ECMAScript 6",
            "JSX Harmony",
            "TypeScript",
            "TypeScript JSX"
        )

        internal sealed class RenameModeDecision {
            data object FileRenameMode : RenameModeDecision()
            data class SymbolRenameMode(val line: Int, val column: Int) : RenameModeDecision()
            data class SymbolIdRenameMode(val symbolId: String) : RenameModeDecision()
            data object QualifiedSymbolRenameMode : RenameModeDecision()
            data class InvalidRenameMode(val error: String) : RenameModeDecision()
        }

        internal fun shouldBypassDialogSubstitutionForFileRename(
            languageId: String,
            overrideStrategy: String
        ): Boolean = shouldRetargetJsTsFileRenameSemantically(languageId, overrideStrategy)

        internal fun shouldRetargetJsTsFileRenameSemantically(
            languageId: String,
            overrideStrategy: String
        ): Boolean = overrideStrategy != "ask" && languageId in JS_TS_LANGUAGE_IDS

        internal fun resolveRenameMode(targetType: String?, line: Int?, column: Int?): RenameModeDecision {
            val legacySymbolPositionError = "Both 'line' and 'column' must be provided for symbol rename, or both omitted for file rename."
            val symbolModePositionError = "line and column are 1-based and must be positive for symbol rename. Omit them or set targetType=file for file rename."

            return when (targetType) {
                "file" -> RenameModeDecision.FileRenameMode
                "symbol" -> {
                    when {
                        line == null || column == null -> RenameModeDecision.InvalidRenameMode(symbolModePositionError)
                        line <= 0 || column <= 0 -> RenameModeDecision.InvalidRenameMode(symbolModePositionError)
                        else -> RenameModeDecision.SymbolRenameMode(line, column)
                    }
                }
                null -> {
                    when {
                        line == null && column == null -> RenameModeDecision.FileRenameMode
                        line == null || column == null -> RenameModeDecision.InvalidRenameMode(legacySymbolPositionError)
                        line <= 0 || column <= 0 -> RenameModeDecision.InvalidRenameMode(symbolModePositionError)
                        else -> RenameModeDecision.SymbolRenameMode(line, column)
                    }
                }
                else -> RenameModeDecision.InvalidRenameMode("Invalid targetType: '$targetType'. Must be 'symbol' or 'file'.")
            }
        }

        internal fun resolveRenameMode(arguments: JsonObject): RenameModeDecision {
            val targetType = arguments[ParamNames.TARGET_TYPE_CAMEL]?.jsonPrimitive?.content
            if (
                targetType == "file" &&
                arguments[UnifiedTargetArguments.NORMALIZED_VARIANT]?.jsonPrimitive?.content == UnifiedTargetArguments.POSITION
            ) {
                return RenameModeDecision.InvalidRenameMode(
                    "target.position selects a symbol and cannot be combined with targetType='file'"
                )
            }
            val symbolId = (arguments[ParamNames.SYMBOL_ID] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (symbolId != null) {
                val hasFile = (arguments[ParamNames.FILE] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.isNotBlank() == true
                val hasCoordinates = hasNonNullValue(arguments[ParamNames.LINE]) ||
                    hasNonNullValue(arguments[ParamNames.COLUMN])
                return if (targetType == "file" || hasFile || hasCoordinates) {
                    RenameModeDecision.InvalidRenameMode(ErrorMessages.SYMBOL_ID_AND_OTHER_TARGET_EXCLUSIVE)
                } else if (targetType == null || targetType == "symbol") {
                    RenameModeDecision.SymbolIdRenameMode(symbolId)
                } else {
                    RenameModeDecision.InvalidRenameMode("Invalid targetType: '$targetType'. Must be 'symbol' or 'file'.")
                }
            }
            val hasQualifiedSelector = hasNonBlankString(arguments[ParamNames.LANGUAGE]) ||
                hasNonBlankString(arguments[ParamNames.SYMBOL])
            if (hasQualifiedSelector) {
                val hasFile = (arguments[ParamNames.FILE] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.isNotBlank() == true
                val hasCoordinates = hasNonNullValue(arguments[ParamNames.LINE]) ||
                    hasNonNullValue(arguments[ParamNames.COLUMN])
                return if (targetType == "file" || hasFile || hasCoordinates) {
                    RenameModeDecision.InvalidRenameMode(ErrorMessages.LANGUAGE_SYMBOL_AND_OTHER_TARGET_EXCLUSIVE)
                } else if (targetType == null || targetType == "symbol") {
                    RenameModeDecision.QualifiedSymbolRenameMode
                } else {
                    RenameModeDecision.InvalidRenameMode("Invalid targetType: '$targetType'. Must be 'symbol' or 'file'.")
                }
            }
            return when (targetType) {
                "file" -> RenameModeDecision.FileRenameMode
                "symbol" -> resolveRenameMode(
                    targetType,
                    (readCoordinateValue(arguments[ParamNames.LINE]) as? CoordinateRead.Present)?.value,
                    (readCoordinateValue(arguments[ParamNames.COLUMN]) as? CoordinateRead.Present)?.value
                )
                null -> {
                    val line = readCoordinateValue(arguments[ParamNames.LINE])
                    val column = readCoordinateValue(arguments[ParamNames.COLUMN])
                    if (line is CoordinateRead.Invalid || column is CoordinateRead.Invalid) {
                        return RenameModeDecision.InvalidRenameMode(
                            "Both 'line' and 'column' must be provided for symbol rename, or both omitted for file rename."
                        )
                    }
                    resolveRenameMode(
                        targetType,
                        (line as? CoordinateRead.Present)?.value,
                        (column as? CoordinateRead.Present)?.value
                    )
                }
                else -> RenameModeDecision.InvalidRenameMode("Invalid targetType: '$targetType'. Must be 'symbol' or 'file'.")
            }
        }

        private sealed interface CoordinateRead {
            data object Missing : CoordinateRead
            data class Present(val value: Int) : CoordinateRead
            data object Invalid : CoordinateRead
        }

        private fun readCoordinateValue(value: JsonElement?): CoordinateRead {
            return try {
                when (value) {
                    null, JsonNull -> CoordinateRead.Missing
                    else -> CoordinateRead.Present(value.jsonPrimitive.int)
                }
            } catch (_: Exception) {
                CoordinateRead.Invalid
            }
        }

        private fun hasNonBlankString(value: JsonElement?): Boolean =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true

        private fun hasNonNullValue(value: JsonElement?): Boolean = value != null && value != JsonNull

        /** Exposed for testing — builds the error message returned when the target is a compiled element. */
        fun buildCompiledElementErrorMessage(elementName: String?, path: String): String =
            "Cannot rename: '$elementName' is defined in a compiled class file ($path), not in editable source. " +
            "Make sure the cursor is positioned on a symbol defined in source code within this project."
    }

    /**
     * Test hook replacing the `renameProcessor.run()` call, so tests can reproduce the
     * production abort paths where `BaseRefactoringProcessor.run()` returns normally without
     * applying anything (read-only files, conflict dialogs, dumb mode). In unit-test mode the
     * platform converts those aborts into exceptions before `run()` returns, so they cannot
     * be triggered for real.
     */
    @org.jetbrains.annotations.TestOnly
    internal var processorRunHook: (() -> Unit)? = null

    /** Lets behavior tests prove that an incomplete preview search fails closed. */
    @org.jetbrains.annotations.TestOnly
    internal var previewUsageSearchHook: (() -> Unit)? = null

    /** Lets behavior tests verify that cancellation from deep-super resolution is not swallowed. */
    @org.jetbrains.annotations.TestOnly
    internal var deepestSuperMethodResolutionHook: ((PsiNamedElement) -> PsiNamedElement?)? = null

    /** Kotlin auto-confirms the super-method chooser in tests; observe entry into that UI path. */
    @org.jetbrains.annotations.TestOnly
    internal var interactiveTargetSelectionHook: (() -> Unit)? = null

    override val name = "ide_refactor_rename"

    override val description = """
        Rename a symbol or file and update references semantically across the project. Supports undo.
        Supply newName and a symbol target (symbolId, position, or language+symbol), or file for file rename.
        targetType="file" ignores placeholder coordinates and supports binary/Android resource files, updating resource references. Include the extension in newName.
        Without targetType, omitted/null line+column selects file rename; both coordinates select a symbol.

        overrideStrategy: rename_base (default) includes base and overrides; rename_only_current limits to this declaration; ask opens an IDE chooser.
        relatedRenamingStrategy: all (default), none, accessors_and_tests, or ask. Related elements can include accessors, parameters/fields, tests, variables and inheritors.
        dryRun=true returns the planned changes, affected files, usages, conflicts and canApply without editing. Interactive ask strategies cannot produce an applicable headless preview.
        Apply returns affected files and change count.

        Example: {"file":"src/UserService.java","line":15,"column":18,"newName":"CustomerService"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .file(required = false, description = "Path to file relative to project root. Required for file rename or position-based symbol rename; omit when symbolId is used.")
        .enumProperty(
            ParamNames.TARGET_TYPE_CAMEL,
            "What to rename: 'symbol' (requires 1-based line+column) or 'file' (renames the file itself and ignores placeholder line/column values). If omitted, legacy behavior applies.",
            listOf("symbol", "file")
        )
        .intProperty("line", "1-based line number. Required for symbol rename; omit for file rename unless `targetType=file`.")
        .intProperty("column", "1-based column number. Required for symbol rename; omit for file rename unless `targetType=file`.")
        .stringProperty("newName", "The new name for the symbol or file. REQUIRED. For file renames, include the file extension (e.g., 'new_name.webp').", required = true)
        .enumProperty(
            "overrideStrategy",
            "Strategy when renaming a method that overrides a base method. " +
                "'rename_base' (default): rename the base method and all overrides automatically. " +
                "'rename_only_current': rename only the current method. " +
                "'ask': show the IDE dialog for interactive choice.",
            listOf("rename_base", "rename_only_current", "ask")
        )
        .enumProperty(
            "relatedRenamingStrategy",
            "Strategy for automatic renaming of related symbols (same-named properties, getters/setters, test classes, variables). " +
                "'all' (default): automatically rename all related symbols. " +
                "'none': rename only the targeted symbol, skip all automatic related renames. " +
                "'accessors_and_tests': only rename getters/setters and test classes/methods. " +
                "'ask': show the IDE dialog for each related rename for interactive choice.",
            listOf("all", "none", "accessors_and_tests", "ask")
        )
        .booleanProperty(
            ParamNames.DRY_RUN,
            "Resolve and validate the target, then discover usages/conflicts without modifying files. Default: false."
        )
        .build()

    /**
     * Data class holding validated rename parameters from Phase 1.
     */
    private data class RenameValidation(
        val element: PsiNamedElement,
        val oldName: String,
        val error: String? = null,
        val newNameOverride: String? = null,
        val previewDiscoveryWarnings: List<String> = emptyList()
    )

    private data class JsTsFileRenameRetargeting(
        val renamedFilePointer: SmartPsiElementPointer<PsiFile>,
        val references: List<JsTsFileRenameReference>
    )

    private data class JsTsFileRenameReference(
        val elementPointer: SmartPsiElementPointer<PsiElement>,
        val rangeInElement: TextRange,
        val importerFilePointer: SmartPsiElementPointer<PsiFile>?,
        val importerTextBeforeRename: String?,
        val referenceElementTextBeforeRename: String?
    )

    private data class RenameExecutionResult(
        val affectedFilesCount: Int,
        val relatedRenamesCount: Int,
        val warnings: List<String>?,
        val unretargetedImporters: List<String>?,
        val renamedElementPointer: SmartPsiElementPointer<PsiNamedElement>
    )

    private data class RenamePreviewSetup(
        val targetElement: PsiNamedElement,
        val effectiveNewName: String,
        val processor: HeadlessRenameProcessor
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val startedAtNanos = System.nanoTime()
        val dryRun = arguments[ParamNames.DRY_RUN]?.jsonPrimitive?.booleanOrNull == true
        val newName = arguments["newName"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: newName")

        val overrideStrategy = arguments["overrideStrategy"]?.jsonPrimitive?.content ?: "rename_base"
        if (overrideStrategy !in listOf("rename_base", "rename_only_current", "ask")) {
            return createErrorResult("Invalid overrideStrategy: '$overrideStrategy'. Must be 'rename_base', 'rename_only_current', or 'ask'.")
        }

        val relatedRenamingStrategy = arguments["relatedRenamingStrategy"]?.jsonPrimitive?.content ?: "all"
        if (relatedRenamingStrategy !in listOf("all", "none", "accessors_and_tests", "ask")) {
            return createErrorResult("Invalid relatedRenamingStrategy: '$relatedRenamingStrategy'. Must be 'all', 'none', 'accessors_and_tests', or 'ask'.")
        }

        if (newName.isBlank()) {
            return createErrorResult("newName cannot be blank")
        }

        val renameMode = resolveRenameMode(arguments)
        when (renameMode) {
            RenameModeDecision.FileRenameMode -> {
                // continue
            }
            is RenameModeDecision.SymbolRenameMode -> {
                // continue
            }
            is RenameModeDecision.SymbolIdRenameMode -> {
                // continue
            }
            RenameModeDecision.QualifiedSymbolRenameMode -> {
                // continue
            }
            is RenameModeDecision.InvalidRenameMode -> {
                return createErrorResult(renameMode.error)
            }
        }

        requireSmartMode(project)
        if (!dryRun) syncProjectForRefactoring(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Find element and validate (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val validation = suspendingReadAction {
            when (renameMode) {
                RenameModeDecision.FileRenameMode -> {
                    val file = optionalStringArg(arguments, ParamNames.FILE)
                        ?: return@suspendingReadAction RenameValidation(
                            DummyNamedElement,
                            "",
                            "Missing required parameter: ${ParamNames.FILE}"
                        )
                    validateAndPrepareFileRename(
                        project,
                        file,
                        newName,
                        requireWritable = !dryRun,
                        detectConflicts = !dryRun,
                        failClosedOnDiscoveryError = dryRun
                    )
                }
                is RenameModeDecision.SymbolRenameMode -> {
                    val file = optionalStringArg(arguments, ParamNames.FILE)
                        ?: return@suspendingReadAction RenameValidation(
                            DummyNamedElement,
                            "",
                            "Missing required parameter: ${ParamNames.FILE}"
                        )
                    validateAndPrepare(
                        project,
                        file,
                        renameMode.line,
                        renameMode.column,
                        newName,
                        requireWritable = !dryRun,
                        detectConflicts = !dryRun
                    )
                }
                is RenameModeDecision.SymbolIdRenameMode ->
                    validateAndPrepareBySemanticTarget(
                        project,
                        arguments,
                        newName,
                        requireWritable = !dryRun,
                        detectConflicts = !dryRun
                    )
                RenameModeDecision.QualifiedSymbolRenameMode ->
                    validateAndPrepareBySemanticTarget(
                        project,
                        arguments,
                        newName,
                        requireWritable = !dryRun,
                        detectConflicts = !dryRun
                    )
                is RenameModeDecision.InvalidRenameMode -> error("Invalid rename mode already returned")
            }
        }

        if (validation.error != null) {
            return createErrorResult(validation.error)
        }

        val element = validation.element
        val requestedPointer = suspendingReadAction {
            SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        }
        val oldName = validation.oldName
        val effectiveNewName = validation.newNameOverride ?: newName
        // File/language metadata belongs to PSI too. Keep the entire preview/apply
        // retargeting decision under the read action rather than reading it while this
        // coroutine is otherwise unlocked.
        val jsTsFileRetargeting = suspendingReadAction {
            if (
                renameMode is RenameModeDecision.FileRenameMode &&
                element is PsiFile &&
                shouldRetargetJsTsFileRenameSemantically(element.language.id, overrideStrategy)
            ) {
                collectJsTsFileRenameRetargeting(element)
            } else {
                null
            }
        }

        if (dryRun) {
            return previewRename(
                project = project,
                element = element,
                oldName = oldName,
                requestedNewName = effectiveNewName,
                requestedFileName = newName.takeIf { renameMode is RenameModeDecision.FileRenameMode },
                renameMode = renameMode,
                overrideStrategy = overrideStrategy,
                relatedRenamingStrategy = relatedRenamingStrategy,
                jsTsFileRetargeting = jsTsFileRetargeting,
                initialDiscoveryWarnings = validation.previewDiscoveryWarnings,
                startedAtNanos = startedAtNanos
            )
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Execute rename using RenameProcessor
        // ═══════════════════════════════════════════════════════════════════════
        val affectedFiles = mutableSetOf<String>()
        var renameExecutionResult: RenameExecutionResult? = null
        var errorMessage: String? = null
        var unsavedBefore: Set<Document> = emptySet()

        edtAction {
            unsavedBefore = FileDocumentManager.getInstance().unsavedDocuments.toSet()
            try {
                renameExecutionResult = executeRename(
                    project,
                    element,
                    oldName,
                    effectiveNewName,
                    overrideStrategy,
                    relatedRenamingStrategy,
                    affectedFiles,
                    jsTsFileRetargeting
                )
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error during rename"
            }
        }

        // Commit and save outside EDT block; commitDocuments switches to a
        // write-safe EDT modality.
        if (errorMessage == null) {
            commitDocuments(project)
            val notSaved = edtAction { saveChangedDocuments(project, unsavedBefore) }
            if (notSaved.isNotEmpty()) return createErrorResult(changesNotSavedMessage(notSaved))
        }

        return if (errorMessage != null) {
            createErrorResult("Rename failed: $errorMessage", ToolNames.DIAGNOSTICS)
        } else {
            val result = renameExecutionResult!!
            val updatedSymbol = suspendingReadAction {
                (requestedPointer.element ?: result.renamedElementPointer.element)?.let { renamedElement ->
                    resolvedSymbolInfo(
                        project,
                        renamedElement,
                        (renameMode as? RenameModeDecision.SymbolIdRenameMode)?.symbolId
                    )
                }
            }
            val relatedNote = if (result.relatedRenamesCount > 0) {
                " (also renamed ${result.relatedRenamesCount} related element(s))"
            } else ""

            val partialNote = if (!result.unretargetedImporters.isNullOrEmpty()) {
                "; ${result.unretargetedImporters.size} importers could not be auto-retargeted (see unretargetedImporters for details)"
            } else ""

            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = affectedFiles.toList(),
                    changesCount = result.affectedFilesCount,
                    message = "Successfully renamed '$oldName' to '$effectiveNewName'$relatedNote$partialNote",
                    warnings = result.warnings,
                    unretargetedImporters = result.unretargetedImporters,
                    updatedSymbol = updatedSymbol
                )
            )
        }
    }

    private suspend fun previewRename(
        project: Project,
        element: PsiNamedElement,
        oldName: String,
        requestedNewName: String,
        requestedFileName: String?,
        renameMode: RenameModeDecision,
        overrideStrategy: String,
        relatedRenamingStrategy: String,
        jsTsFileRetargeting: JsTsFileRenameRetargeting?,
        initialDiscoveryWarnings: List<String>,
        startedAtNanos: Long
    ): CallToolResult {
        val warnings = initialDiscoveryWarnings.toMutableList()
        val affectedFiles = linkedSetOf<String>()
        var usageCount = 0
        var conflicts = emptyList<String>()
        var discoveryComplete = initialDiscoveryWarnings.isEmpty()
        var resolvedPreviewTarget: PsiNamedElement? = null

        val (targetPath, targetWritable) = suspendingReadAction {
            val targetFile = element.containingFile?.virtualFile
            targetFile?.let { getRelativePath(project, it) } to (targetFile?.isWritable == true)
        }
        targetPath?.let(affectedFiles::add)
        if (!targetWritable) {
            warnings.add("Target file is read-only or unavailable; the rename cannot be applied.")
        }

        val exactInteractivePlan = overrideStrategy != "ask" && relatedRenamingStrategy != "ask"
        if (overrideStrategy == "ask") {
            warnings.add(
                "overrideStrategy='ask' requires interactive target selection; " +
                    "choose rename_base or rename_only_current for an applicable headless plan."
            )
        }
        if (relatedRenamingStrategy == "ask") {
            warnings.add(
                "relatedRenamingStrategy='ask' requires interactive choices; " +
                    "choose all, none, or accessors_and_tests for an applicable headless plan."
            )
        }

        var plannedNewName = requestedNewName
        try {
            val setup = suspendingReadAction {
                val targetElement = if (overrideStrategy == "ask") {
                    resolveNonDialogSubstitution(element, failClosedOnDiscoveryError = true)
                } else {
                    resolveRenameTarget(element, overrideStrategy, failClosedOnDiscoveryError = true)
                }
                val effectiveNewName = computeEffectiveNewName(
                    element,
                    targetElement,
                    requestedNewName,
                    failClosedOnDiscoveryError = true
                )
                val processor = createConfiguredRenameProcessor(
                    project = project,
                    targetElement = targetElement,
                    effectiveNewName = effectiveNewName,
                    relatedRenamingStrategy = relatedRenamingStrategy,
                    forceHeadless = true,
                    failClosedOnDiscoveryError = true
                ) as HeadlessRenameProcessor
                RenamePreviewSetup(targetElement, effectiveNewName, processor)
            }
            resolvedPreviewTarget = setup.targetElement
            plannedNewName = setup.effectiveNewName

            // Prepare the same headless plan as apply. Language preparation runs on EDT without
            // an outer read action; searches run off EDT. None of these steps mutates source.
            edtAction { setup.processor.preparePreviewRenaming() }

            previewUsageSearchHook?.invoke()
            val directUsages = RefactoringScopeGuard.computeUsagesOffEdtStrict(project) {
                setup.processor.findPreviewUsages()
            }
            edtAction { setup.processor.selectPreviewAutomaticRenames() }
            val automaticUsages = RefactoringScopeGuard.computeUsagesOffEdtStrict(project) {
                setup.processor.findPreviewAutomaticUsages()
            }
            val additionalRenames = edtAction { setup.processor.preparePreviewAutomaticRenames() }
            val preparedUsages = RefactoringScopeGuard.computeUsagesOffEdtStrict(project) {
                setup.processor.findPreviewPreparedUsages(additionalRenames)
            }
            val usages = (directUsages + automaticUsages + preparedUsages).distinct().toTypedArray()
            usageCount = usages.size

            val discovered = suspendingReadAction {
                val files = linkedSetOf<String>()
                val fileRenameConflicts = if (renameMode is RenameModeDecision.FileRenameMode) {
                    val file = element.containingFile
                    val sibling = file?.virtualFile?.parent?.findChild(requireNotNull(requestedFileName))
                    if (sibling != null && sibling != file.virtualFile) {
                        listOf("Cannot rename '${file.name}' to '$requestedFileName': a file or directory with that name already exists in its containing directory.")
                    } else emptyList()
                } else emptyList()
                val renamedVirtualFiles = buildList {
                    setup.targetElement.containingFile?.virtualFile?.let(::add)
                    setup.processor.elements.mapNotNullTo(this) { renamedElement ->
                        renamedElement.containingFile?.virtualFile
                    }
                }.distinct()
                renamedVirtualFiles.forEach { files.add(getRelativePath(project, it)) }
                usages.mapNotNullTo(files) { usage ->
                    usage.virtualFile?.let { getRelativePath(project, it) }
                }
                val readOnlyFiles = (
                    RefactoringScopeGuard.readOnlyFilesIn(project, usages) +
                        renamedVirtualFiles.filterNot { it.isWritable }
                            .map { getRelativePath(project, it) }
                    ).distinct().sorted()
                Triple(
                    files,
                    (fileRenameConflicts + setup.processor.collectPreviewConflicts(usages)).distinct(),
                    readOnlyFiles
                )
            }
            affectedFiles.addAll(discovered.first)
            conflicts = discovered.second
            warnings.addAll(conflicts)
            if (discovered.third.isNotEmpty()) {
                warnings.add(RefactoringScopeGuard.blockedMessage(discovered.third))
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            rethrowProcessCanceled(e)
            discoveryComplete = false
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            warnings.add(
                "Usage/conflict discovery failed: ${cause.message ?: cause.javaClass.simpleName}. " +
                    "The preview is not safe to apply."
            )
        }

        if (jsTsFileRetargeting != null) {
            val importerFiles = suspendingReadAction {
                jsTsFileRetargeting.references.mapNotNull { reference ->
                    reference.importerFilePointer?.element?.virtualFile?.let {
                        getRelativePath(project, it)
                    }
                }
            }
            affectedFiles.addAll(importerFiles)
        }

        val metadataTarget = resolvedPreviewTarget ?: element
        val target = suspendingReadAction {
            resolvedSymbolInfo(
                project,
                metadataTarget,
                (renameMode as? RenameModeDecision.SymbolIdRenameMode)
                    ?.takeIf { metadataTarget == element }
                    ?.symbolId
            )
        }
        val readOnlyTarget = !targetWritable
        val hasReadOnlyScope = warnings.any { it.startsWith("Blocked by read-only files") }
        val plannedChange = buildJsonObject {
            put("operation", "rename")
            put(
                "targetType",
                if (renameMode is RenameModeDecision.FileRenameMode) "file" else "symbol"
            )
            put("from", oldName)
            put("to", plannedNewName)
            put("overrideStrategy", overrideStrategy)
            put("relatedRenamingStrategy", relatedRenamingStrategy)
        }
        return createJsonResult(
            refactoringPreview(
                canApply = discoveryComplete && exactInteractivePlan && !readOnlyTarget &&
                    !hasReadOnlyScope && conflicts.isEmpty(),
                target = target,
                plannedChange = plannedChange,
                affectedFiles = affectedFiles,
                usageCount = usageCount,
                conflictCount = conflicts.size,
                warnings = warnings,
                startedAtNanos = startedAtNanos
            )
        )
    }

    /**
     * Validates rename parameters and prepares the element for renaming.
     * Runs in a read action (background thread).
     */
    private fun validateAndPrepare(
        project: Project,
        file: String,
        line: Int,
        column: Int,
        newName: String,
        requireWritable: Boolean = true,
        detectConflicts: Boolean = true
    ): RenameValidation {
        val psiFile = getPsiFile(project, file)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "File not found: $file"
            )
        if (requireWritable && psiFile.virtualFile?.isWritable == false) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "File is read-only and cannot be modified: ${psiFile.virtualFile.path}"
            )
        }

        val psiElement = findPsiElement(project, file, line, column)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "No element found at the specified position"
            )

        val namedElement = findNamedElement(psiElement)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "No renameable symbol found at the specified position"
            )

        // Reject compiled elements early — RenameProcessor constructor asserts on them.
        if (namedElement is PsiCompiledElement) {
            val loc = namedElement.containingFile?.virtualFile?.path ?: "unknown location"
            return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = buildCompiledElementErrorMessage(namedElement.name, loc)
            )
        }

        val oldName = namedElement.name
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "Element has no name"
            )

        if (oldName == newName) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = "New name is the same as the current name"
            )
        }

        // Validate the new name using language-specific rules
        val validationError = validateNewName(project, namedElement, newName)
        if (validationError != null) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = validationError
            )
        }

        // Check for naming conflicts (would show dialog otherwise)
        val conflictError = if (detectConflicts) checkForConflicts(namedElement, newName) else null
        if (conflictError != null) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = conflictError
            )
        }

        return RenameValidation(
            element = namedElement,
            oldName = oldName
        )
    }

    /** Resolves an exact semantic selector without any coordinate/nearest-element fallback. */
    private fun validateAndPrepareBySemanticTarget(
        project: Project,
        arguments: JsonObject,
        newName: String,
        requireWritable: Boolean = true,
        detectConflicts: Boolean = true
    ): RenameValidation {
        val symbolId = optionalStringArg(arguments, ParamNames.SYMBOL_ID)
        val element = resolveElementFromArguments(project, arguments, allowSymbolId = true).getOrElse {
            return RenameValidation(
                DummyNamedElement,
                "",
                it.message ?: symbolId?.let(ErrorMessages::symbolIdExpired) ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL
            )
        }
        val namedElement = element as? PsiNamedElement
            ?: return RenameValidation(
                DummyNamedElement,
                "",
                symbolId?.let(ErrorMessages::symbolIdExpired) ?: "Target does not identify a renameable named symbol"
            )
        val virtualFile = namedElement.containingFile?.virtualFile
        if (virtualFile == null || (requireWritable && !virtualFile.isWritable)) {
            return RenameValidation(
                DummyNamedElement,
                "",
                if (virtualFile == null) symbolId?.let(ErrorMessages::symbolIdExpired) ?: "Target has no editable source file"
                else "File is read-only and cannot be modified: ${virtualFile.path}"
            )
        }

        val oldName = namedElement.name
            ?: return RenameValidation(DummyNamedElement, "", "Element has no name")
        if (namedElement is PsiCompiledElement) {
            return RenameValidation(
                DummyNamedElement,
                "",
                buildCompiledElementErrorMessage(oldName, virtualFile.path)
            )
        }
        if (oldName == newName) {
            return RenameValidation(DummyNamedElement, oldName, "New name is the same as the current name")
        }
        validateNewName(project, namedElement, newName)?.let {
            return RenameValidation(DummyNamedElement, oldName, it)
        }
        if (detectConflicts) {
            checkForConflicts(namedElement, newName)?.let {
                return RenameValidation(DummyNamedElement, oldName, it)
            }
        }
        return RenameValidation(namedElement, oldName)
    }

    /**
     * Validates and prepares a file rename (no line/column — renames the file itself).
     *
     * Uses the PsiFile directly as the rename target, which works for all file types
     * including binary files (images, etc.). The RenameProcessor and its
     * RenamePsiElementProcessor handle language-specific behavior (e.g., Android
     * resource renaming updates all XML references).
     *
     * Skips language-specific identifier validation since file names follow different
     * rules than code identifiers.
     *
     * Java files whose top-level class matches the filename are retargeted onto that class
     * (see [retargetJavaFileRenameToClass]); every other case renames the file as-is.
     */
    private fun validateAndPrepareFileRename(
        project: Project,
        file: String,
        newName: String,
        requireWritable: Boolean = true,
        detectConflicts: Boolean = true,
        failClosedOnDiscoveryError: Boolean = false
    ): RenameValidation {
        val psiFile = getPsiFile(project, file)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "File not found: $file"
            )
        if (requireWritable && psiFile.virtualFile?.isWritable == false) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "File is read-only and cannot be modified: ${psiFile.virtualFile.path}"
            )
        }

        val oldName = psiFile.name

        if (oldName == newName) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = "New name is the same as the current name"
            )
        }

        retargetJavaFileRenameToClass(
            project,
            psiFile,
            oldName,
            newName,
            detectConflicts,
            failClosedOnDiscoveryError
        )?.let { return it }

        return RenameValidation(
            element = psiFile,
            oldName = oldName
        )
    }

    /**
     * Retargets a Java file rename onto its matching top-level class.
     *
     * A bare file rename leaves `public class Foo` inside `Bar.java` — a guaranteed compile
     * error — because the platform only couples class and file in the class->file direction
     * (`PsiClassImpl.setName` renames the containing file when the base names match). So when
     * a `.java` file contains a top-level class named after the file, the rename is redirected
     * to that class and `RenameProcessor` updates the class, all references, and the file.
     *
     * Java PSI is accessed reflectively because this tool is universal and loads in IDEs
     * without the Java plugin. Implicit classes (Java 21+ `PsiImplicitClass`) are excluded so
     * those files keep flowing through the platform's dedicated file-level processor.
     *
     * Returns null — falling through to the plain file rename — for every non-matching case:
     * non-Java files, extension changes, class/file name mismatches, implicit-class files,
     * invalid identifiers, and name conflicts.
     */
    private fun retargetJavaFileRenameToClass(
        project: Project,
        psiFile: PsiFile,
        oldName: String,
        newName: String,
        detectConflicts: Boolean = true,
        failClosedOnDiscoveryError: Boolean = false
    ): RenameValidation? {
        val keepsJavaExtension = newName.endsWith(".java") || !newName.contains('.')
        if (!keepsJavaExtension) return null

        val psiClass = try {
            val psiJavaFileClass = Class.forName("com.intellij.psi.PsiJavaFile")
            if (!psiJavaFileClass.isInstance(psiFile)) return null

            val implicitClassClass = try {
                Class.forName("com.intellij.psi.PsiImplicitClass")
            } catch (_: ClassNotFoundException) {
                null
            }

            val oldBase = oldName.substringBeforeLast('.')
            val classes = psiJavaFileClass.getMethod("getClasses").invoke(psiFile) as? Array<*>
                ?: return null
            classes.asSequence()
                .filterIsInstance<PsiNamedElement>()
                .filterNot { implicitClassClass?.isInstance(it) == true }
                .firstOrNull { it.name == oldBase }
        } catch (_: ClassNotFoundException) {
            // Java PSI is optional for this universal tool; no retargeting is available.
            null
        } catch (e: Exception) {
            rethrowProcessCanceled(e)
            if (failClosedOnDiscoveryError) {
                val cause = (e as? InvocationTargetException)?.cause ?: e
                return RenameValidation(
                    element = psiFile,
                    oldName = oldName,
                    previewDiscoveryWarnings = listOf(
                        "Java file rename retargeting discovery failed: " +
                            "${cause.message ?: cause.javaClass.simpleName}. The preview is not safe to apply."
                    )
                )
            }
            LOG.debug("Java file rename retargeting probe failed: ${e.message}")
            null
        } ?: return null

        val newBase = newName.substringBeforeLast('.')
        if (newBase.isEmpty() || newBase == psiClass.name) return null
        if (validateNewName(project, psiClass, newBase) != null) return null
        if (detectConflicts && checkForConflicts(psiClass, newBase) != null) return null
        val className = psiClass.name ?: return null

        return RenameValidation(
            element = psiClass,
            oldName = className,
            newNameOverride = newBase
        )
    }

    /**
     * Checks for naming conflicts that would prevent the rename.
     * Returns an error message if conflicts exist, null otherwise.
     */
    private fun checkForConflicts(element: PsiNamedElement, newName: String): String? {
        val processor = RenamePsiElementProcessor.forElement(element)
        val conflicts = MultiMap<PsiElement, String>()

        // Let the processor find existing name conflicts
        processor.findExistingNameConflicts(element, newName, conflicts)

        if (!conflicts.isEmpty) {
            val conflictMessages = conflicts.values().take(3).joinToString("; ") { ConflictMessages.sanitize(it) }
            val moreCount = conflicts.values().size - 3
            val suffix = if (moreCount > 0) " (and $moreCount more)" else ""
            return "Name conflict: $conflictMessages$suffix"
        }

        return null
    }

    /**
     * Validates the new name using language-specific identifier rules.
     */
    private fun validateNewName(
        project: Project,
        element: PsiElement,
        newName: String
    ): String? {
        val psiFile = element.containingFile ?: return null
        val language = psiFile.language

        val validator = LanguageNamesValidation.INSTANCE.forLanguage(language)

        if (!validator.isIdentifier(newName, project)) {
            return "'$newName' is not a valid identifier in ${language.displayName}"
        }

        if (validator.isKeyword(newName, project)) {
            return "'$newName' is a reserved keyword in ${language.displayName}"
        }

        return null
    }

    /**
     * Executes the rename using IntelliJ's RenameProcessor.
     * Must be called on EDT.
     *
     * HEADLESS OPERATION WITH AUTOMATIC RELATED RENAMES:
     * - Related elements (getters/setters, overriding methods, tests, etc.) are delegated to
     *   IntelliJ's automatic renamer infrastructure
     * - Dialog-producing renamers are force-applied through [HeadlessRenameProcessor]
     * - Constructor parameter -> field coupling is pre-added because the platform only provides
     *   the inverse relation (field -> constructor parameters)
     *
     * @return [RenameExecutionResult] with affected file count, related rename count, and any
     *   partial-success warnings from JS/TS import retargeting.
     */
    private fun executeRename(
        project: Project,
        element: PsiNamedElement,
        oldName: String,
        newName: String,
        overrideStrategy: String,
        relatedRenamingStrategy: String,
        affectedFiles: MutableSet<String>,
        jsTsFileRetargeting: JsTsFileRenameRetargeting?
    ): RenameExecutionResult {
        // Resolve the actual target element to rename based on override strategy.
        // For methods that override a base method, RenameJavaMethodProcessor's
        // substituteElementToRename() calls SuperMethodWarningUtil.checkSuperMethod()
        // which shows a modal dialog. We handle this ourselves based on the strategy:
        // - "rename_base": resolve to deepest super method (no dialog)
        // - "rename_only_current": use the element as-is (no dialog)
        // - "ask": delegate to substituteElementToRename (shows dialog)
        val targetElement = resolveRenameTarget(element, overrideStrategy)
        val modifiedFilesBeforeRename = collectUnsavedProjectFiles(project)

        // Compute the effective name for the rename target.
        //
        // When a PsiFile is substituted to a non-PsiFile (e.g., Android resource element),
        // the target's getName() returns the resource name WITHOUT file extension (e.g.,
        // "ic_launcher" not "ic_launcher.webp"). The RenameProcessor calls setName() with
        // the new name, and the Android plugin's prepareRenaming() appends extensions when
        // generating related file names. Passing a name WITH extension would cause double
        // extensions on related files (e.g., "app_icon.webp.webp").
        //
        // Conversely, when the target remains a PsiFile (no substitution), getName() returns
        // the full filename WITH extension, and setName() expects the same format.
        val effectiveNewName = computeEffectiveNewName(element, targetElement, newName)
        val jsTsFileElement = element as? PsiFile
        val shouldRetargetJsTsFileRename =
            jsTsFileElement != null && jsTsFileRetargeting != null

        val renameProcessor = createConfiguredRenameProcessor(
            project = project,
            targetElement = targetElement,
            effectiveNewName = effectiveNewName,
            relatedRenamingStrategy = relatedRenamingStrategy,
            forceHeadless = false
        )

        // Collected partial-success data from JS/TS import retargeting.
        val retargetWarnings = mutableListOf<String>()
        val unretargetedImporters = mutableListOf<String>()

        // Capture the target's identity before the run: BaseRefactoringProcessor.run()
        // returns normally on abort paths (conflicts dialog cancelled, read-only files),
        // so success must be verified against the PSI afterwards instead of assumed.
        val targetPointer = SmartPointerManager.getInstance(project)
            .createSmartPsiElementPointer(targetElement)
        val targetNameBeforeRename = targetElement.name

        // Pre-check the full refactoring scope for read-only files (issue #310):
        // run() would route them through ReadonlyStatusHandler's modal dialog,
        // blocking the EDT in headless MCP sessions. findUsages() is idempotent
        // (it clears the internal renamer list on entry), so run() repeating the
        // search is safe. The search must not run on this (EDT) thread: the Kotlin
        // K2 Analysis API forbids resolution on the EDT (issue #357), so it
        // executes on a pooled thread under a read action, like run()'s own search.
        val usagesInScope = RefactoringScopeGuard.computeUsagesOffEdt(project) { renameProcessor.findUsages() }
        val readOnlyInScope = usagesInScope?.let { RefactoringScopeGuard.readOnlyFilesIn(project, it) }.orEmpty()
        if (readOnlyInScope.isNotEmpty()) {
            throw Exception(RefactoringScopeGuard.blockedMessage(readOnlyInScope))
        }

        // RenameProcessor manages its own write actions and progress. Starting it inside
        // WriteCommandAction can deadlock in modern IDE builds.
        val hook = processorRunHook
        if (hook != null) hook() else renameProcessor.run()

        PsiDocumentManager.getInstance(project).commitAllDocuments()
        verifyRenameApplied(targetPointer, targetNameBeforeRename)

        if (shouldRetargetJsTsFileRename) {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val renamedFile = jsTsFileRetargeting!!.renamedFilePointer.element
                ?: error("JS/TS file rename retargeting failed: renamed file is no longer available")

            WriteCommandAction.runWriteCommandAction(project, "Retarget JS/TS imports for '$effectiveNewName'", null, {
                retargetJsTsFileRenameReferencesAfterRename(
                    project,
                    jsTsFileRetargeting,
                    renamedFile
                )
                finalizeJsTsFileRenameRetargeting(
                    project, jsTsFileRetargeting, renamedFile, affectedFiles,
                    retargetWarnings, unretargetedImporters
                )
            })
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments()
        affectedFiles.addAll(collectUnsavedProjectFiles(project) - modifiedFilesBeforeRename)

        val relatedRenamesCount = renameProcessor.elements.count { it != targetElement }
        for (renamedElement in renameProcessor.elements) {
            renamedElement.containingFile?.virtualFile?.let { vf ->
                affectedFiles.add(getRelativePath(project, vf))
            }
        }

        val conflictWarnings = (renameProcessor as? HeadlessRenameProcessor)
            ?.capturedConflicts.orEmpty()

        return RenameExecutionResult(
            affectedFilesCount = affectedFiles.size,
            relatedRenamesCount = relatedRenamesCount,
            warnings = (conflictWarnings + retargetWarnings).distinct().takeIf { it.isNotEmpty() },
            unretargetedImporters = unretargetedImporters.distinct().takeIf { it.isNotEmpty() },
            renamedElementPointer = targetPointer
        )
    }

    /** Builds the same processor configuration for apply and dry-run discovery. */
    private fun createConfiguredRenameProcessor(
        project: Project,
        targetElement: PsiNamedElement,
        effectiveNewName: String,
        relatedRenamingStrategy: String,
        forceHeadless: Boolean,
        failClosedOnDiscoveryError: Boolean = false
    ): RenameProcessor {
        // We intentionally do not search comments/text occurrences: those searches can add
        // non-code confirmation dialogs and make both apply and preview less deterministic.
        val renameProcessor = if (!forceHeadless && relatedRenamingStrategy == "ask") {
            RenameProcessor(project, targetElement, effectiveNewName, false, false)
        } else {
            HeadlessRenameProcessor(
                project,
                targetElement,
                effectiveNewName,
                false,
                false
            )
        }

        if (relatedRenamingStrategy != "none") {
            for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
                if (factory.optionName == null) continue
                if (relatedRenamingStrategy == "accessors_and_tests" && !isAccessorOrTestFactory(factory)) continue
                renameProcessor.addRenamerFactory(factory)
            }
        }
        addParameterFieldRelations(
            project,
            targetElement,
            effectiveNewName,
            renameProcessor,
            failClosedOnDiscoveryError
        )
        renameProcessor.setPreviewUsages(false)
        return renameProcessor
    }

    /**
     * Fails the rename when `RenameProcessor.run()` returned without touching the target.
     *
     * The check is conservative, mirroring [ChangeSignatureTool]'s post-run verification:
     * a pointer that no longer resolves means the refactoring restructured the PSI, and any
     * name change at all (even one differing from the requested name, e.g. extension handling
     * on file renames or Android resource substitution) counts as applied. Only a target that
     * provably still carries its pre-run name — the signature of a silent platform abort —
     * is treated as failed.
     */
    private fun verifyRenameApplied(
        targetPointer: SmartPsiElementPointer<PsiNamedElement>,
        targetNameBeforeRename: String?
    ) {
        if (targetNameBeforeRename == null) return
        val targetAfterRename = targetPointer.element ?: return
        if (targetAfterRename.isValid && targetAfterRename.name == targetNameBeforeRename) {
            throw Exception(
                "Rename was not applied — the platform aborted the refactoring " +
                    "(read-only files or unresolved conflicts)."
            )
        }
    }

    /**
     * Computes the effective name for the rename target, accounting for element substitution
     * during [RenamePsiElementProcessor.prepareRenaming].
     *
     * When a `PsiFile` is passed to `RenameProcessor`, some processors (e.g., Android's
     * `ResourceReferenceRenameProcessor`) swap the `PsiFile` for a higher-level element
     * (like `ResourceReferencePsiElement`) in `prepareRenaming()`. The substitute element
     * uses resource-style naming (without file extension), while `PsiFile` uses filename-style
     * naming (with extension).
     *
     * In the IDE's own rename dialog, this is handled naturally: the dialog shows the element's
     * `getName()` value, so after substitution the user sees the resource name (no extension).
     * For our headless flow, we must detect this substitution and adjust `newName` accordingly.
     *
     * We probe `prepareRenaming` with a temporary map to detect if substitution would occur.
     * Preview first rejects matching-declaration processors that may request confirmation,
     * because language preparation is not guaranteed to be non-interactive.
     *
     * Additionally, when no substitution occurs and the target remains a `PsiFile`, if the
     * user provided a name without extension, the original file's extension is preserved.
     */
    private fun computeEffectiveNewName(
        element: PsiNamedElement,
        targetElement: PsiNamedElement,
        newName: String,
        failClosedOnDiscoveryError: Boolean = false
    ): String {
        if (element !is PsiFile) return newName
        if (failClosedOnDiscoveryError) {
            HeadlessRenameProcessor.checkPreviewPreparationIsHeadless(targetElement)
        }

        // Probe: check if prepareRenaming would substitute this PsiFile for a different element.
        // Processors like Android's ResourceReferenceRenameProcessor remove the PsiFile from
        // allRenames and add a ResourceReferencePsiElement instead. That substitute uses
        // resource-style naming (no file extension).
        val processor = RenamePsiElementProcessor.forElement(targetElement)
        val probeRenames = linkedMapOf<PsiElement, String>(targetElement to newName)
        try {
            processor.prepareRenaming(targetElement, newName, probeRenames)
        } catch (e: Exception) {
            rethrowProcessCanceled(e)
            if (failClosedOnDiscoveryError) throw e
            // Apply compatibility: if probing fails, fall through to default behavior.
        }

        val wasSubstituted = targetElement !in probeRenames && probeRenames.isNotEmpty()

        if (wasSubstituted) {
            // Element will be substituted (e.g., Android resource) — strip file extension.
            // The substitute's handleElementRename() re-appends extensions per density variant.
            val nameWithoutExt = newName.substringBeforeLast('.')
            return if (nameWithoutExt.isNotEmpty() && nameWithoutExt != newName) nameWithoutExt else newName
        }

        // No substitution — target remains a PsiFile. PsiFile.setName() expects full filename.
        // If the user omitted the extension, preserve the original file's extension.
        val originalExt = element.name.substringAfterLast('.', "")
        if (originalExt.isNotEmpty() && !newName.contains('.')) {
            return "$newName.$originalExt"
        }

        return newName
    }

    private fun collectUnsavedProjectFiles(project: Project): Set<String> {
        val fileDocumentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        return fileDocumentManager.unsavedDocuments
            .mapNotNull(fileDocumentManager::getFile)
            .filter { ProjectUtils.isProjectFile(project, it) }
            .map { getRelativePath(project, it) }
            .toSet()
    }

    private fun collectJsTsFileRenameRetargeting(file: PsiFile): JsTsFileRenameRetargeting {
        val pointerManager = SmartPointerManager.getInstance(file.project)
        val references = mutableListOf<JsTsFileRenameReference>()
        val seenReferences = mutableSetOf<Pair<PsiElement, TextRange>>()

        ReferencesSearch.search(file, GlobalSearchScope.projectScope(file.project), false)
            .forEach(Processor { reference ->
                val collectedReference = findJsTsFileRenameReference(reference, file)
                    ?: return@Processor true
                val referenceElement = collectedReference.element
                val importerFile = referenceElement.containingFile
                val rangeInElement = collectedReference.rangeInElement
                if (!seenReferences.add(referenceElement to rangeInElement)) {
                    return@Processor true
                }

                references.add(
                    JsTsFileRenameReference(
                        elementPointer = pointerManager.createSmartPsiElementPointer(referenceElement),
                        rangeInElement = rangeInElement,
                        importerFilePointer = importerFile?.let {
                            pointerManager.createSmartPsiElementPointer(it)
                        },
                        importerTextBeforeRename = importerFile?.let { file ->
                            PsiDocumentManager.getInstance(file.project).getDocument(file)?.text ?: file.text
                        },
                        referenceElementTextBeforeRename = referenceElement.text
                    )
                )
                true
            })

        return JsTsFileRenameRetargeting(
            renamedFilePointer = pointerManager.createSmartPsiElementPointer(file),
            references = references
        )
    }

    private fun findJsTsFileRenameReference(reference: PsiReference, file: PsiFile): PsiReference? {
        val fileReference = FileReference.findFileReference(reference)
        if (fileReference != null) {
            val lastFileReference = fileReference.getFileReferenceSet().getLastReference()
                ?: return null
            if (lastFileReference.resolve()?.isEquivalentTo(file) == true) {
                return lastFileReference
            }
            return null
        }

        val referenceText = reference.rangeInElement.substring(reference.element.text)
        return if (isBareJsTsFileRenameReferenceToFile(reference, referenceText, file)) {
            reference
        } else {
            null
        }
    }

    private fun isBareJsTsFileRenameReferenceToFile(reference: PsiReference, referenceText: String, file: PsiFile): Boolean {
        val fileName = file.virtualFile?.name ?: file.name
        val nameWithoutExtension = file.virtualFile?.nameWithoutExtension
            ?: fileName.substringBeforeLast('.', fileName)

        val trimmedText = referenceText.trim('\'', '"', '`')
        if (trimmedText.contains('/') || trimmedText.contains('\\')) {
            return false
        }

        return (trimmedText == fileName || trimmedText == nameWithoutExtension) &&
            reference.resolve()?.isEquivalentTo(file) == true
    }

    private fun retargetJsTsFileRenameReferencesAfterRename(
        project: Project,
        retargeting: JsTsFileRenameRetargeting,
        renamedFile: PsiFile
    ) {
        for (referencePointer in retargeting.references) {
            val referenceElement = referencePointer.elementPointer.element
            if (isJsTsReferenceAlreadyRetargeted(referencePointer, referenceElement, renamedFile)) {
                continue
            }

            if (referenceElement == null) {
                continue
            }

            val reference = findCollectedReference(referenceElement, referencePointer)
            if (reference == null) {
                continue
            }

            try {
                bindJsTsFileRenameReferenceToFile(reference, renamedFile)
            } catch (e: Exception) {
                if (isJsTsReferenceAlreadyRetargeted(referencePointer, referenceElement, renamedFile)) {
                    continue
                }

                val importerPath = resolveImporterPath(project, referencePointer, referenceElement)
                val reason = e.message ?: e.javaClass.simpleName
                val warningMsg = "Semantic JS/TS file rename failed after rename" +
                    importerPath?.let { " for '$it'" }.orEmpty() +
                    ": $reason"
                LOG.debug(warningMsg)
            }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun bindJsTsFileRenameReferenceToFile(reference: PsiReference, renamedFile: PsiFile) {
        val fileReference = FileReference.findFileReference(reference)
        if (fileReference != null) {
            val lastFileReference = fileReference.getFileReferenceSet().getLastReference()
            if (lastFileReference != null && lastFileReference.rangeInElement == reference.rangeInElement) {
                lastFileReference.bindToElement(renamedFile)
                return
            }
        }

        reference.bindToElement(renamedFile)
    }

    private fun isJsTsReferenceAlreadyRetargeted(
        referencePointer: JsTsFileRenameReference,
        referenceElement: PsiElement?,
        renamedFile: PsiFile
    ): Boolean {
        if (referenceElement == null) {
            return false
        }

        val currentReference = findCollectedReference(referenceElement, referencePointer)
        if (currentReference != null) {
            return referenceResolvesToFile(currentReference, renamedFile)
        }

        return referencePointer.referenceElementTextBeforeRename != null &&
            referenceElement.text != referencePointer.referenceElementTextBeforeRename &&
            referenceElement.references.any { referenceResolvesToFile(it, renamedFile) }
    }

    private fun referenceResolvesToFile(reference: PsiReference, file: PsiFile): Boolean {
        return runCatching {
            val fileReference = FileReference.findFileReference(reference)
            val currentReference = fileReference?.getFileReferenceSet()?.getLastReference() ?: reference
            currentReference.resolve()?.isEquivalentTo(file) == true
        }.getOrDefault(false)
    }

    private fun finalizeJsTsFileRenameRetargeting(
        project: Project,
        retargeting: JsTsFileRenameRetargeting,
        renamedFile: PsiFile,
        affectedFiles: MutableSet<String>,
        warnings: MutableList<String>,
        unretargetedImporters: MutableList<String>
    ) {
        for (referencePointer in retargeting.references) {
            val referenceElement = referencePointer.elementPointer.element
            markJsTsRetargetingImporterAffectedIfChanged(
                project,
                referencePointer,
                referenceElement,
                referencePointer.importerTextBeforeRename,
                affectedFiles
            )
            if (isJsTsReferenceAlreadyRetargeted(referencePointer, referenceElement, renamedFile)) {
                continue
            }

            if (referenceElement == null) {
                val importerPath = resolveImporterPath(project, referencePointer, null)
                val warningMsg = "Semantic JS/TS file rename could not verify importer retargeting" +
                    importerPath?.let { " for '$it'" }.orEmpty() +
                    ": collected reference element is no longer available"
                warnings += warningMsg
                if (importerPath != null) {
                    unretargetedImporters += importerPath
                }
                continue
            }

            val importerPath = resolveImporterPath(project, referencePointer, referenceElement)
            val warningMsg = "Semantic JS/TS file rename left importer unchanged or unresolved" +
                importerPath?.let { " for '$it'" }.orEmpty()
            warnings += warningMsg
            if (importerPath != null) {
                unretargetedImporters += importerPath
            }
        }
    }

    private fun findCollectedReference(
        referenceElement: PsiElement,
        referencePointer: JsTsFileRenameReference
    ): PsiReference? {
        return referenceElement.references.asSequence()
            .mapNotNull { findJsTsFileRenameReferenceAtStoredRange(it, referencePointer.rangeInElement) }
            .firstOrNull { it.rangeInElement == referencePointer.rangeInElement }
    }

    private fun findJsTsFileRenameReferenceAtStoredRange(reference: PsiReference, rangeInElement: TextRange): PsiReference? {
        val fileReference = FileReference.findFileReference(reference)
        if (fileReference != null) {
            val lastFileReference = fileReference.getFileReferenceSet().getLastReference()
                ?: return null
            return lastFileReference.takeIf { it.rangeInElement == rangeInElement }
        }

        return reference.takeIf { it.rangeInElement == rangeInElement }
    }

    private fun markJsTsRetargetingImporterAffectedIfChanged(
        project: Project,
        referencePointer: JsTsFileRenameReference,
        referenceElement: PsiElement?,
        importerTextBefore: String?,
        affectedFiles: MutableSet<String>
    ): Boolean {
        val importerTextAfter = getImporterDocumentText(project, referencePointer, referenceElement)
        if (importerTextBefore != null && importerTextAfter == importerTextBefore) {
            return false
        }

        val importerFile = referencePointer.importerFilePointer?.element ?: referenceElement?.containingFile
        importerFile?.virtualFile?.let {
            affectedFiles.add(getRelativePath(project, it))
            return true
        }
        return false
    }

    private fun getImporterDocumentText(
        project: Project,
        referencePointer: JsTsFileRenameReference,
        referenceElement: PsiElement?
    ): String? {
        val importerFile = referencePointer.importerFilePointer?.element ?: referenceElement?.containingFile
        return importerFile?.let { file ->
            PsiDocumentManager.getInstance(project).getDocument(file)?.text ?: file.text
        }
    }

    private fun resolveImporterPath(
        project: Project,
        referencePointer: JsTsFileRenameReference,
        referenceElement: PsiElement?
    ): String? {
        return referencePointer.importerFilePointer?.element?.virtualFile?.let {
            getRelativePath(project, it)
        } ?: referenceElement?.containingFile?.virtualFile?.let { getRelativePath(project, it) }
    }

    /**
     * Checks if an [AutomaticRenamerFactory] is an accessor (getter/setter) or test renamer.
     *
     * Used by the "accessors_and_tests" related renaming strategy to filter factories.
     * Matches by class name suffix to remain language-agnostic (works for Java, Kotlin, etc.).
     */
    private fun isAccessorOrTestFactory(factory: AutomaticRenamerFactory): Boolean {
        val className = factory.javaClass.simpleName
        return className.contains("GetterSetter") ||
            className.contains("Accessor") ||
            className.contains("Test")
    }

    /**
     * Resolves the actual PsiNamedElement to rename based on the override strategy.
     *
     * For methods that override a base method, IntelliJ's substituteElementToRename()
     * calls SuperMethodWarningUtil.checkSuperMethod() which shows a modal dialog.
     *
     * @param overrideStrategy Controls behavior for override methods:
     *   - "rename_base": resolve to deepest super method automatically (no dialog)
     *   - "rename_only_current": use the element as-is, skip substitution (no dialog)
     *   - "ask": delegate to substituteElementToRename (shows IDE dialog)
     */
    private fun resolveRenameTarget(
        element: PsiNamedElement,
        overrideStrategy: String,
        failClosedOnDiscoveryError: Boolean = false
    ): PsiNamedElement {
        if (element is PsiFile && shouldBypassDialogSubstitutionForFileRename(element.language.id, overrideStrategy)) {
            return element
        }

        when (overrideStrategy) {
            "rename_base" -> {
                // A failed lookup cannot fall back to an interactive choice or a narrower rename.
                val deepestSuper = resolveDeepestSuperMethod(element, failClosedOnDiscoveryError = true)
                if (deepestSuper != null) {
                    // Rename the Kotlin source declaration, not its generated JVM light view.
                    // This matches the Kotlin processor's substitution for a source function.
                    val source = deepestSuper.navigationElement
                    return if (source is PsiNamedElement && isKotlinFunction(source)) source else deepestSuper
                }
                if (failClosedOnDiscoveryError || isKotlinFunction(element)) {
                    // Kotlin functions without a JVM super method still use the headless path.
                    return resolveNonDialogSubstitution(element, failClosedOnDiscoveryError = true)
                }
            }
            "rename_only_current" -> {
                // Use the element directly — skip substituteElementToRename entirely
                // to avoid the dialog. Only apply non-dialog substitutions.
                return resolveNonDialogSubstitution(element, failClosedOnDiscoveryError)
            }
            "ask" -> {
                // Fall through to substituteElementToRename (will show dialog)
            }
        }

        // For non-override elements or "ask" strategy, use standard substitution
        interactiveTargetSelectionHook?.invoke()
        val elementProcessor = RenamePsiElementProcessor.forElement(element)
        val substituted = elementProcessor.substituteElementToRename(element, null)
        return (substituted as? PsiNamedElement) ?: element
    }

    private fun isKotlinFunction(element: PsiNamedElement): Boolean = try {
        Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction").isInstance(element.navigationElement)
    } catch (_: ClassNotFoundException) {
        false
    }

    /**
     * Applies non-dialog substitutions (e.g., record component for accessor).
     * Skips substituteElementToRename() which would trigger the super method dialog.
     */
    private fun resolveNonDialogSubstitution(
        element: PsiNamedElement,
        failClosedOnDiscoveryError: Boolean = false
    ): PsiNamedElement {
        try {
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (!psiMethodClass.isInstance(element)) return element
            if (psiMethodClass.getMethod("isConstructor").invoke(element) == true) {
                return psiMethodClass.getMethod("getContainingClass").invoke(element) as? PsiNamedElement ?: element
            }
            // Check for record component accessor (Java 16+)
            val recordUtilClass = Class.forName("com.intellij.psi.util.JavaPsiRecordUtil")
            val result = recordUtilClass.getMethod("getRecordComponentForAccessor", psiMethodClass)
                .invoke(null, element)
            if (result is PsiNamedElement) return result
        } catch (_: ClassNotFoundException) {
            // Record PSI is optional; retain the original target when Java is unavailable.
        } catch (e: Exception) {
            rethrowProcessCanceled(e)
            if (failClosedOnDiscoveryError) throw e
            LOG.warn("Failed to resolve record component for accessor: ${e.message}", e)
        }
        return element
    }

    /**
     * If the element is a method that overrides a base method, returns the deepest
     * super method. Returns null if the element is not a method or has no super methods.
     *
     * Handles both:
     * - Java/Kotlin PsiMethod (including KtLightMethod) via PsiMethod.findDeepestSuperMethods()
     * - Kotlin KtNamedFunction via toLightMethods(PsiElement) (reflection)
     *
     * Uses reflection to access language-specific APIs to keep the tool language-agnostic.
     */
    private fun resolveDeepestSuperMethod(
        element: PsiNamedElement,
        failClosedOnDiscoveryError: Boolean = false
    ): PsiNamedElement? {
        if (ApplicationManager.getApplication().isDispatchThread) {
            // Like usage search, Kotlin K2 super-method resolution must run off the EDT.
            return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<PsiNamedElement?, RuntimeException> {
                    ReadAction.compute<PsiNamedElement?, RuntimeException> {
                        resolveDeepestSuperMethod(element, failClosedOnDiscoveryError)
                    }
                },
                "Resolving base method",
                true,
                element.project
            )
        }
        // Try Java/Kotlin PsiMethod path (covers KtLightMethod too)
        try {
            deepestSuperMethodResolutionHook?.let { return it(element) }
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (psiMethodClass.isInstance(element)) {
                val deepestSuperMethods = psiMethodClass.getMethod("findDeepestSuperMethods")
                    .invoke(element) as? Array<*> ?: return null
                if (deepestSuperMethods.isNotEmpty()) {
                    return deepestSuperMethods[0] as? PsiNamedElement
                }
                return null
            }
        } catch (_: ClassNotFoundException) {
            // Java PSI is optional for this universal tool.
        } catch (e: Exception) {
            rethrowProcessCanceled(e)
            if (failClosedOnDiscoveryError) throw e
            LOG.warn("Failed to resolve deepest super method via PsiMethod API: ${e.message}", e)
        }

        // Try Kotlin KtNamedFunction path — unwrap to light method and use PsiMethod API
        try {
            val ktNamedFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            if (!ktNamedFunctionClass.isInstance(element)) return null

            val lightMethod = PsiUtils.toLightMethodsStrict(element).firstOrNull() ?: return null

            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (!psiMethodClass.isInstance(lightMethod)) return null

            val deepestSuperMethods = psiMethodClass.getMethod("findDeepestSuperMethods")
                .invoke(lightMethod) as? Array<*> ?: return null

            if (deepestSuperMethods.isNotEmpty()) {
                return deepestSuperMethods[0] as? PsiNamedElement
            }
        } catch (_: ClassNotFoundException) {
            // Kotlin PSI is optional for this universal tool.
        } catch (e: Exception) {
            rethrowProcessCanceled(e)
            if (failClosedOnDiscoveryError) throw e
            LOG.warn("Failed to resolve deepest super method via Kotlin KtNamedFunction API: ${e.message}", e)
        }

        return null
    }

    private fun rethrowProcessCanceled(throwable: Throwable) {
        generateSequence(throwable) { it.cause }
            .filterIsInstance<ProcessCanceledException>()
            .firstOrNull()
            ?.let { throw it }
    }

    /**
     * Detects and adds constructor parameter -> field relationships that IntelliJ does not
     * model automatically.
     *
     * The platform has a built-in automatic renamer for the inverse direction
     * (field -> constructor parameters), but not for parameter -> field. We mirror the
     * Java naming logic so constructor parameters like `ready` can rename related fields
     * such as `isReady` or code-style-prefixed variants.
     *
     * Uses reflection to access Java PSI classes to keep the tool language-agnostic.
     *
     * @return Number of related elements added
     */
    private fun addParameterFieldRelations(
        project: Project,
        element: PsiNamedElement,
        newName: String,
        renameProcessor: RenameProcessor,
        failClosedOnDiscoveryError: Boolean = false
    ): Int {
        var count = 0

        try {
            // Check if this is a Java/Kotlin parameter declared on a constructor
            val psiParameterClass = try {
                Class.forName("com.intellij.psi.PsiParameter")
            } catch (_: ClassNotFoundException) {
                return 0 // Java plugin not available
            }

            if (!psiParameterClass.isInstance(element)) {
                return 0
            }

            val declarationScope = element.javaClass.getMethod("getDeclarationScope").invoke(element)
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (!psiMethodClass.isInstance(declarationScope)) {
                return 0
            }

            val isConstructor = psiMethodClass.getMethod("isConstructor").invoke(declarationScope) as Boolean
            if (!isConstructor) {
                return 0
            }

            val parameterName = element.name ?: return 0
            val containingClass = psiMethodClass.getMethod("getContainingClass").invoke(declarationScope) ?: return 0
            val psiClassClass = Class.forName("com.intellij.psi.PsiClass")
            val javaCodeStyleManagerClass = Class.forName("com.intellij.psi.codeStyle.JavaCodeStyleManager")
            val variableKindClass = Class.forName("com.intellij.psi.codeStyle.VariableKind")

            @Suppress("UNCHECKED_CAST")
            val enumClass = variableKindClass as Class<out Enum<*>>
            val parameterKind = java.lang.Enum.valueOf(enumClass, "PARAMETER")
            val fieldKind = java.lang.Enum.valueOf(enumClass, "FIELD")

            val styleManager = javaCodeStyleManagerClass.getMethod("getInstance", Project::class.java)
                .invoke(null, project)
            val variableNameToPropertyName = javaCodeStyleManagerClass.getMethod(
                "variableNameToPropertyName",
                String::class.java,
                variableKindClass
            )
            val propertyNameToVariableName = javaCodeStyleManagerClass.getMethod(
                "propertyNameToVariableName",
                String::class.java,
                variableKindClass
            )

            val parameterPropertyName = variableNameToPropertyName.invoke(
                styleManager,
                parameterName,
                parameterKind
            ) as? String ?: return 0
            val newPropertyName = variableNameToPropertyName.invoke(
                styleManager,
                newName,
                parameterKind
            ) as? String ?: return 0
            val expectedFieldName = propertyNameToVariableName.invoke(
                styleManager,
                newPropertyName,
                fieldKind
            ) as? String ?: return 0

            val fields = psiClassClass.getMethod("getAllFields").invoke(containingClass) as Array<*>
            for (field in fields) {
                if (field !is PsiNamedElement) continue

                val fieldName = field.name ?: continue
                val fieldPropertyName = variableNameToPropertyName.invoke(
                    styleManager,
                    fieldName,
                    fieldKind
                ) as? String ?: continue

                if (fieldPropertyName != parameterPropertyName) continue
                if (fieldName == expectedFieldName) continue

                renameProcessor.addElement(field, expectedFieldName)
                count++
            }
        } catch (e: Exception) {
            rethrowProcessCanceled(e)
            if (failClosedOnDiscoveryError) throw e
            // Reflection failed - likely not a Java/Kotlin project or different PSI structure
            // This is expected for other languages, silently continue
        }

        return count
    }

    /**
     * Finds the named element from a PSI element.
     *
     * First checks if the element itself is a named element (direct declaration hit).
     * Then checks if the element or its close ancestors have PSI references that resolve
     * to a named declaration — this handles cases like Android XML resource references
     * (`@+id/Foo`) where the cursor is inside a reference, not on a declaration.
     * Falls back to walking up the tree for the nearest [PsiNamedElement].
     */
    private fun findNamedElement(element: PsiElement): PsiNamedElement? {
        if (element is PsiNamedElement && element.name != null) {
            return element
        }

        var current: PsiElement? = element
        while (current != null) {
            for (reference in current.references) {
                val resolved = reference.resolve()
                if (resolved is PsiNamedElement && resolved.name != null) {
                    return resolved
                }
            }
            if (current is PsiNamedElement && current.name != null) {
                return current
            }
            current = current.parent
        }

        return null
    }

    /**
     * Dummy placeholder for error cases to satisfy non-null return type.
     */
    @Suppress("DEPRECATION")
    private object DummyNamedElement : PsiNamedElement {
        override fun setName(name: String): PsiElement = this
        override fun getName(): String? = null
        override fun getProject() = throw UnsupportedOperationException()
        override fun getLanguage() = throw UnsupportedOperationException()
        override fun getManager() = throw UnsupportedOperationException()
        override fun getChildren() = throw UnsupportedOperationException()
        override fun getParent() = throw UnsupportedOperationException()
        override fun getFirstChild() = throw UnsupportedOperationException()
        override fun getLastChild() = throw UnsupportedOperationException()
        override fun getNextSibling() = throw UnsupportedOperationException()
        override fun getPrevSibling() = throw UnsupportedOperationException()
        override fun getContainingFile() = throw UnsupportedOperationException()
        override fun getTextRange() = throw UnsupportedOperationException()
        override fun getStartOffsetInParent() = throw UnsupportedOperationException()
        override fun getTextLength() = throw UnsupportedOperationException()
        override fun findElementAt(offset: Int) = throw UnsupportedOperationException()
        override fun findReferenceAt(offset: Int) = throw UnsupportedOperationException()
        override fun getTextOffset() = throw UnsupportedOperationException()
        override fun getText() = throw UnsupportedOperationException()
        override fun textToCharArray() = throw UnsupportedOperationException()
        override fun getNavigationElement() = throw UnsupportedOperationException()
        override fun getOriginalElement() = throw UnsupportedOperationException()
        override fun textMatches(text: CharSequence) = throw UnsupportedOperationException()
        override fun textMatches(element: PsiElement) = throw UnsupportedOperationException()
        override fun textContains(c: Char) = throw UnsupportedOperationException()
        override fun accept(visitor: com.intellij.psi.PsiElementVisitor) = throw UnsupportedOperationException()
        override fun acceptChildren(visitor: com.intellij.psi.PsiElementVisitor) = throw UnsupportedOperationException()
        override fun copy() = throw UnsupportedOperationException()
        override fun add(element: PsiElement) = throw UnsupportedOperationException()
        override fun addBefore(element: PsiElement, anchor: PsiElement?) = throw UnsupportedOperationException()
        override fun addAfter(element: PsiElement, anchor: PsiElement?) = throw UnsupportedOperationException()
        override fun checkAdd(element: PsiElement) = throw UnsupportedOperationException()
        override fun addRange(first: PsiElement, last: PsiElement) = throw UnsupportedOperationException()
        override fun addRangeBefore(first: PsiElement, last: PsiElement, anchor: PsiElement) = throw UnsupportedOperationException()
        override fun addRangeAfter(first: PsiElement, last: PsiElement, anchor: PsiElement) = throw UnsupportedOperationException()
        override fun delete() = throw UnsupportedOperationException()
        override fun checkDelete() = throw UnsupportedOperationException()
        override fun deleteChildRange(first: PsiElement, last: PsiElement) = throw UnsupportedOperationException()
        override fun replace(newElement: PsiElement) = throw UnsupportedOperationException()
        override fun isValid() = false
        override fun isWritable() = false
        override fun getReference() = throw UnsupportedOperationException()
        override fun getReferences() = throw UnsupportedOperationException()
        override fun <T> getCopyableUserData(key: com.intellij.openapi.util.Key<T>) = throw UnsupportedOperationException()
        override fun <T> putCopyableUserData(key: com.intellij.openapi.util.Key<T>, value: T?) = throw UnsupportedOperationException()
        override fun processDeclarations(processor: com.intellij.psi.scope.PsiScopeProcessor, state: com.intellij.psi.ResolveState, lastParent: PsiElement?, place: PsiElement) = throw UnsupportedOperationException()
        override fun getContext() = throw UnsupportedOperationException()
        override fun isPhysical() = false
        override fun getResolveScope() = throw UnsupportedOperationException()
        override fun getUseScope() = throw UnsupportedOperationException()
        override fun getNode() = throw UnsupportedOperationException()
        override fun isEquivalentTo(another: PsiElement?) = false
        override fun getIcon(flags: Int) = throw UnsupportedOperationException()
        override fun <T> getUserData(key: com.intellij.openapi.util.Key<T>): T? = null
        override fun <T> putUserData(key: com.intellij.openapi.util.Key<T>, value: T?) {}
    }
}

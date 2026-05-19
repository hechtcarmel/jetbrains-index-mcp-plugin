package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.containers.MultiMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

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
    }

    override val name = "ide_refactor_rename"

    override val description = """
        Rename a symbol or file and update all references across the project. Use instead of find-and-replace for safe, semantic renaming that handles all usages correctly. Supports undo (Ctrl+Z).

        Two modes:
        - **Symbol rename** (`targetType="symbol"`, file + line + column + newName): Rename a symbol at a specific position. `line` and `column` are 1-based and must be provided.
        - **File rename** (`targetType="file"`, file + newName): Rename the file itself. Any placeholder `line`/`column` values are ignored for mode selection. Works for all file types including binary files (images, etc.). Especially useful for Android resource files (.webp, .png, .xml in res/) where it updates all resource references across the project.

        Backward compatibility: if `targetType` is omitted, null/null line+column still means file rename; provided line+column still means symbol rename.

        Automatically renames related elements: getters/setters, overriding methods, constructor parameters ↔ fields, test classes.

        When renaming a method that overrides a base method, the `overrideStrategy` parameter controls behavior:
        - "rename_base" (default): Automatically renames the base method and all overrides. No dialog shown.
        - "rename_only_current": Renames only the current method, leaving the base and other overrides unchanged.
        - "ask": Shows the IDE's built-in dialog to let the user choose interactively.

        The `relatedRenamingStrategy` parameter controls automatic renaming of related symbols (e.g., same-named properties on unrelated classes, getters/setters, test classes, variables):
        - "all" (default): Automatically rename all related symbols. Current behavior.
        - "none": Rename only the targeted symbol. Skip all automatic related renames.
        - "accessors_and_tests": Only rename getters/setters and test classes/methods. Skip variables, inheritors, overloads, and parameters on unrelated classes.
        - "ask": Show the IDE dialog for each related rename for interactive choice.

        Returns: affected files list and change count. Modifies source files.

        Parameters: file + newName (required). targetType is optional; line + column are only needed for symbol rename. overrideStrategy + relatedRenamingStrategy (optional).

        Examples:
        - Symbol rename: {"file": "src/UserService.java", "targetType": "symbol", "line": 15, "column": 18, "newName": "CustomerService"}
        - File rename: {"file": "res/mipmap-hdpi/ic_launcher.webp", "targetType": "file", "newName": "ic_app_icon.webp"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root. REQUIRED.")
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
        .build()

    /**
     * Data class holding validated rename parameters from Phase 1.
     */
    private data class RenameValidation(
        val element: PsiNamedElement,
        val oldName: String,
        val error: String? = null
    )

    private data class JsTsFileRenameRetargeting(
        val renamedFilePointer: SmartPsiElementPointer<PsiFile>,
        val references: List<JsTsFileRenameReference>
    )

    private data class JsTsFileRenameReference(
        val elementPointer: SmartPsiElementPointer<PsiElement>,
        val rangeInElement: TextRange,
        val importerFilePointer: SmartPsiElementPointer<PsiFile>?
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, "file").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }
        val line = arguments["line"]?.jsonPrimitive?.int
        val column = arguments["column"]?.jsonPrimitive?.int
        val targetType = arguments[ParamNames.TARGET_TYPE_CAMEL]?.jsonPrimitive?.content
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

        val renameMode = resolveRenameMode(targetType, line, column)
        when (renameMode) {
            RenameModeDecision.FileRenameMode -> {
                // continue
            }
            is RenameModeDecision.SymbolRenameMode -> {
                // continue
            }
            is RenameModeDecision.InvalidRenameMode -> {
                return createErrorResult(renameMode.error)
            }
        }

        requireSmartMode(project)

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Find element and validate (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val validation = suspendingReadAction {
            if (renameMode is RenameModeDecision.FileRenameMode) {
                validateAndPrepareFileRename(project, file, newName)
            } else {
                val symbolMode = renameMode as RenameModeDecision.SymbolRenameMode
                validateAndPrepare(project, file, symbolMode.line, symbolMode.column, newName)
            }
        }

        if (validation.error != null) {
            return createErrorResult(validation.error)
        }

        val element = validation.element
        val oldName = validation.oldName
        val jsTsFileRetargeting = if (
            renameMode is RenameModeDecision.FileRenameMode &&
            element is PsiFile &&
            shouldRetargetJsTsFileRenameSemantically(element.language.id, overrideStrategy)
        ) {
            suspendingReadAction { collectJsTsFileRenameRetargeting(element) }
        } else {
            null
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Execute rename using RenameProcessor
        // ═══════════════════════════════════════════════════════════════════════
        var changesCount = 0
        val affectedFiles = mutableSetOf<String>()
        var relatedRenamesCount = 0
        var errorMessage: String? = null

        edtAction {
            try {
                val result = executeRename(
                    project,
                    element,
                    newName,
                    overrideStrategy,
                    relatedRenamingStrategy,
                    affectedFiles,
                    jsTsFileRetargeting
                )
                changesCount = result.first
                relatedRenamesCount = result.second
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown error during rename"
            }
        }

        // Commit and save outside EDT block; commitDocuments switches to a
        // write-safe EDT modality.
        if (errorMessage == null) {
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
        }

        return if (errorMessage != null) {
            createErrorResult("Rename failed: $errorMessage")
        } else {
            val relatedNote = if (relatedRenamesCount > 0) {
                " (also renamed $relatedRenamesCount related element(s))"
            } else ""

            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = affectedFiles.toList(),
                    changesCount = changesCount,
                    message = "Successfully renamed '$oldName' to '$newName'$relatedNote"
                )
            )
        }
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
        newName: String
    ): RenameValidation {
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
        val conflictError = checkForConflicts(namedElement, newName)
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
     */
    private fun validateAndPrepareFileRename(
        project: Project,
        file: String,
        newName: String
    ): RenameValidation {
        val psiFile = getPsiFile(project, file)
            ?: return RenameValidation(
                element = DummyNamedElement,
                oldName = "",
                error = "File not found: $file"
            )

        val oldName = psiFile.name

        if (oldName == newName) {
            return RenameValidation(
                element = DummyNamedElement,
                oldName = oldName,
                error = "New name is the same as the current name"
            )
        }

        return RenameValidation(
            element = psiFile,
            oldName = oldName
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
            val conflictMessages = conflicts.values().take(3).joinToString("; ")
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
     * @return Pair of (affected files count, related elements renamed count)
     */
    private fun executeRename(
        project: Project,
        element: PsiNamedElement,
        newName: String,
        overrideStrategy: String,
        relatedRenamingStrategy: String,
        affectedFiles: MutableSet<String>,
        jsTsFileRetargeting: JsTsFileRenameRetargeting?
    ): Pair<Int, Int> {
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
        // Create the RenameProcessor with language-appropriate settings.
        // NOTE: We intentionally DON'T search in comments/text occurrences to avoid
        // non-code usage dialogs. The basic rename is more predictable for agents.
        // When relatedRenamingStrategy is "ask", use a standard RenameProcessor so the
        // IDE shows its built-in dialog for each automatic renamer.
        val renameProcessor = if (relatedRenamingStrategy == "ask") {
            RenameProcessor(project, targetElement, effectiveNewName, false, false)
        } else {
            HeadlessRenameProcessor(project, targetElement, effectiveNewName, false, false)
        }

        // Register automatic renamers based on the relatedRenamingStrategy.
        // Factories with null option names are already handled automatically by RenameProcessor.
        if (relatedRenamingStrategy != "none") {
            for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
                if (factory.optionName == null) continue
                if (relatedRenamingStrategy == "accessors_and_tests" && !isAccessorOrTestFactory(factory)) continue
                renameProcessor.addRenamerFactory(factory)
            }
        }

        // Add constructor parameter -> field relation up front.
        addParameterFieldRelations(project, targetElement, effectiveNewName, renameProcessor)

        // Disable preview dialog for headless operation
        renameProcessor.setPreviewUsages(false)

        // Execute the rename - this modifies files in place (primary + all related elements)
        renameProcessor.run()

        if (shouldRetargetJsTsFileRename) {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val renamedFile = jsTsFileRetargeting!!.renamedFilePointer.element
                ?: error("JS/TS file rename retargeting failed: renamed file is no longer available")
            writeAction(project, "Retarget JS/TS File Rename References") {
                finalizeJsTsFileRenameRetargeting(project, jsTsFileRetargeting, renamedFile, affectedFiles)
            }
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments()
        affectedFiles.addAll(collectUnsavedProjectFiles(project) - modifiedFilesBeforeRename)

        val relatedRenamesCount = renameProcessor.elements.count { it != targetElement }
        for (renamedElement in renameProcessor.elements) {
            renamedElement.containingFile?.virtualFile?.let { vf ->
                affectedFiles.add(getRelativePath(project, vf))
            }
        }

        return Pair(affectedFiles.size, relatedRenamesCount)
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
     * This is safe because `prepareRenaming` only creates lightweight wrapper objects.
     *
     * Additionally, when no substitution occurs and the target remains a `PsiFile`, if the
     * user provided a name without extension, the original file's extension is preserved.
     */
    private fun computeEffectiveNewName(
        element: PsiNamedElement,
        targetElement: PsiNamedElement,
        newName: String
    ): String {
        if (element !is PsiFile) return newName

        // Probe: check if prepareRenaming would substitute this PsiFile for a different element.
        // Processors like Android's ResourceReferenceRenameProcessor remove the PsiFile from
        // allRenames and add a ResourceReferencePsiElement instead. That substitute uses
        // resource-style naming (no file extension).
        val processor = RenamePsiElementProcessor.forElement(targetElement)
        val probeRenames = linkedMapOf<PsiElement, String>(targetElement to newName)
        try {
            processor.prepareRenaming(targetElement, newName, probeRenames)
        } catch (_: Exception) {
            // If probing fails, fall through to default behavior
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

        ReferencesSearch.search(file, GlobalSearchScope.projectScope(file.project), false)
            .forEach(Processor { reference ->
                val referenceElement = reference.element
                references.add(
                    JsTsFileRenameReference(
                        elementPointer = pointerManager.createSmartPsiElementPointer(referenceElement),
                        rangeInElement = reference.rangeInElement,
                        importerFilePointer = referenceElement.containingFile?.let {
                            pointerManager.createSmartPsiElementPointer(it)
                        }
                    )
                )
                true
            })

        return JsTsFileRenameRetargeting(
            renamedFilePointer = pointerManager.createSmartPsiElementPointer(file),
            references = references
        )
    }

    private fun finalizeJsTsFileRenameRetargeting(
        project: Project,
        retargeting: JsTsFileRenameRetargeting,
        renamedFile: PsiFile,
        affectedFiles: MutableSet<String>
    ) {
        for (referencePointer in retargeting.references) {
            val referenceElement = referencePointer.elementPointer.element
                ?: failJsTsFileRenameRetargeting(
                    project,
                    referencePointer,
                    null,
                    "collected reference element is no longer available"
                )
            val reference = referenceElement.references.firstOrNull {
                it.rangeInElement == referencePointer.rangeInElement
            } ?: referenceElement.findReferenceAt(referencePointer.rangeInElement.startOffset)

            if (reference == null) {
                if (referenceElement.references.any { it.resolve()?.isEquivalentTo(renamedFile) == true }) {
                    markJsTsRetargetingImporterAffected(project, referencePointer, referenceElement, affectedFiles)
                    continue
                }
                failJsTsFileRenameRetargeting(
                    project,
                    referencePointer,
                    referenceElement,
                    "collected reference could not be found at stored range ${referencePointer.rangeInElement}"
                )
            }

            try {
                reference.bindToElement(renamedFile)
            } catch (e: Exception) {
                failJsTsFileRenameRetargeting(
                    project,
                    referencePointer,
                    referenceElement,
                    e.message ?: e.javaClass.simpleName
                )
            }

            markJsTsRetargetingImporterAffected(project, referencePointer, referenceElement, affectedFiles)
        }
    }

    private fun markJsTsRetargetingImporterAffected(
        project: Project,
        referencePointer: JsTsFileRenameReference,
        referenceElement: PsiElement,
        affectedFiles: MutableSet<String>
    ) {
        val importerFile = referencePointer.importerFilePointer?.element ?: referenceElement.containingFile
        importerFile?.virtualFile?.let { affectedFiles.add(getRelativePath(project, it)) }
    }

    private fun failJsTsFileRenameRetargeting(
        project: Project,
        referencePointer: JsTsFileRenameReference,
        referenceElement: PsiElement?,
        reason: String
    ): Nothing {
        val importerPath = referencePointer.importerFilePointer?.element?.virtualFile?.let {
            getRelativePath(project, it)
        } ?: referenceElement?.containingFile?.virtualFile?.let { getRelativePath(project, it) }
        val location = importerPath?.let { " in '$it'" } ?: ""
        error("JS/TS file rename retargeting failed$location: $reason")
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
    private fun resolveRenameTarget(element: PsiNamedElement, overrideStrategy: String): PsiNamedElement {
        if (element is PsiFile && shouldBypassDialogSubstitutionForFileRename(element.language.id, overrideStrategy)) {
            return element
        }

        when (overrideStrategy) {
            "rename_base" -> {
                // Resolve to the deepest super method to avoid the dialog
                val deepestSuper = resolveDeepestSuperMethod(element)
                if (deepestSuper != null) return deepestSuper
            }
            "rename_only_current" -> {
                // Use the element directly — skip substituteElementToRename entirely
                // to avoid the dialog. Only apply non-dialog substitutions.
                return resolveNonDialogSubstitution(element)
            }
            "ask" -> {
                // Fall through to substituteElementToRename (will show dialog)
            }
        }

        // For non-override elements or "ask" strategy, use standard substitution
        val elementProcessor = RenamePsiElementProcessor.forElement(element)
        val substituted = elementProcessor.substituteElementToRename(element, null)
        return (substituted as? PsiNamedElement) ?: element
    }

    /**
     * Applies non-dialog substitutions (e.g., record component for accessor).
     * Skips substituteElementToRename() which would trigger the super method dialog.
     */
    private fun resolveNonDialogSubstitution(element: PsiNamedElement): PsiNamedElement {
        try {
            // Check for record component accessor (Java 16+)
            val recordUtilClass = Class.forName("com.intellij.psi.util.JavaPsiRecordUtil")
            val result = recordUtilClass.getMethod("getRecordComponentForAccessor", Class.forName("com.intellij.psi.PsiMethod"))
                .invoke(null, element)
            if (result is PsiNamedElement) return result
        } catch (e: Exception) {
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
     * - Kotlin KtNamedFunction via KtNamedFunction.getOverriddenDescriptors() (reflection)
     *
     * Uses reflection to access language-specific APIs to keep the tool language-agnostic.
     */
    private fun resolveDeepestSuperMethod(element: PsiNamedElement): PsiNamedElement? {
        // Try Java/Kotlin PsiMethod path (covers KtLightMethod too)
        try {
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (psiMethodClass.isInstance(element)) {
                val deepestSuperMethods = psiMethodClass.getMethod("findDeepestSuperMethods")
                    .invoke(element) as? Array<*> ?: return null
                if (deepestSuperMethods.isNotEmpty()) {
                    return deepestSuperMethods[0] as? PsiNamedElement
                }
                return null
            }
        } catch (e: Exception) {
            LOG.warn("Failed to resolve deepest super method via PsiMethod API: ${e.message}", e)
        }

        // Try Kotlin KtNamedFunction path — unwrap to light method and use PsiMethod API
        try {
            val ktNamedFunctionClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            if (!ktNamedFunctionClass.isInstance(element)) return null

            // Use LightClassUtils to get the light method wrapper
            val lightClassUtilsClass = Class.forName("org.jetbrains.kotlin.asJava.LightClassUtilsKt")
            val lightElements = lightClassUtilsClass.getMethod("toLightMethods", Class.forName("org.jetbrains.kotlin.psi.KtDeclaration"))
                .invoke(null, element) as? List<*> ?: return null

            val lightMethod = lightElements.firstOrNull() ?: return null

            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (!psiMethodClass.isInstance(lightMethod)) return null

            val deepestSuperMethods = psiMethodClass.getMethod("findDeepestSuperMethods")
                .invoke(lightMethod) as? Array<*> ?: return null

            if (deepestSuperMethods.isNotEmpty()) {
                return deepestSuperMethods[0] as? PsiNamedElement
            }
        } catch (e: Exception) {
            LOG.warn("Failed to resolve deepest super method via Kotlin KtNamedFunction API: ${e.message}", e)
        }

        return null
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
        renameProcessor: RenameProcessor
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

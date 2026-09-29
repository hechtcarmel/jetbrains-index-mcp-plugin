package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiWhiteSpace
import com.intellij.refactoring.RefactoringFactory
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.annotations.TestOnly

/**
 * Safe delete tool that checks for usages before deletion.
 *
 * This implementation uses a two-phase approach to avoid UI freezes:
 * 1. **Background Phase**: Find element and check for usages (in read action)
 * 2. **EDT Phase**: Apply deletion quickly (in write action)
 */
class SafeDeleteTool : AbstractRefactoringTool() {

    private companion object {
        /**
         * How far below the file [collectDeclarationsInto] may descend through *unnamed* wrapper
         * nodes before giving up. Three is what the deepest known wrapper chain needs
         * (`ES6ExportDeclaration` > `JSVarStatement` > `JSVariable`), and keeping it small is
         * what stops the walk from wandering into statement bodies in script-style files.
         */
        const val MAX_WRAPPER_DEPTH = 3
    }

    /**
     * Test hook invoked between the usage check (phase 1) and the deletion write action
     * (phase 2), i.e. inside the window where a concurrent external edit can invalidate
     * the prepared PSI element.
     */
    @TestOnly
    internal var beforeDeletionHook: (() -> Unit)? = null

    /**
     * Test hook invoked at the start of every usage search, so tests can simulate a
     * search failure deterministically.
     */
    @TestOnly
    internal var usageSearchHook: (() -> Unit)? = null

    override val name = "ide_refactor_safe_delete"

    override val description = """
        Delete a symbol or file safely by first checking for usages. Use when removing code to avoid breaking references.

        Modes:
        - target_type='symbol' (default): Delete the exact symbol selected by symbolId,
          language + symbol, or file + line/column. The equivalent nested target is also supported.
          If position is whitespace/comment, returns nearby symbol suggestions.
        - target_type='file': Delete the entire file (REQUIRED: file only).
          Only succeeds if no symbols have external usages. Internal call chains don't block deletion.

        Behavior: If usages exist and force=false, returns the usage list instead of deleting.
        Use force=true to delete anyway (may break compilation).

        With dryRun=true, returns the shared non-mutating refactoring preview.
        Otherwise returns success status and affected files, a blocking usages list, or nearby symbol suggestions.

        Examples:
        - Symbol ID: {"symbolId": "<opaque-id>"}
        - Symbol: {"file": "src/OldClass.java", "line": 10, "column": 14}
        - Symbol with force: {"file": "src/OldClass.java", "line": 10, "column": 14, "force": true}
        - File: {"file": "src/UnusedUtils.java", "target_type": "file"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .file(required = false, description = "Path to file relative to project root. Required for file deletion or position-based symbol deletion; omit when symbolId is used.")
        .intProperty("line", "1-based line number for position-based symbol deletion. Required with file and column.")
        .intProperty("column", "1-based column number for position-based symbol deletion. Required with file and line.")
        .property("target_type", buildJsonObject {
            put("type", "string")
            putJsonArray("enum") {
                add(JsonPrimitive("symbol"))
                add(JsonPrimitive("file"))
            }
            put("default", "symbol")
            put("description", "What to delete: 'symbol' (default, requires one symbol selector) or 'file' (requires only file and deletes it if no external usages block the operation).")
        })
        .property("force", buildJsonObject {
            put("type", "boolean")
            put("default", false)
            put("description", "Force deletion even if usages exist. Default: false. Use with caution!")
        })
        .booleanProperty(
            ParamNames.DRY_RUN,
            "Resolve the target and check usages without deleting anything. Default: false."
        )
        .build()

    /**
     * Data class to hold all information collected in background for symbol delete operation.
     */
    private data class SymbolDeletePreparation(
        val element: PsiNamedElement,
        val elementName: String,
        val elementType: String,
        val usages: List<UsageInfo>,
        val affectedFile: String,
        val symbolId: String? = null,
        val previewSymbolId: String? = symbolId
    )

    /**
     * Data class to hold all information collected in background for file delete operation.
     */
    private data class FileDeletePreparation(
        val psiFile: PsiFile,
        val fileName: String,
        val filePath: String,
        val symbols: List<SymbolInfo>,
        val externalUsages: List<UsageInfo>,
        val incompleteDiscoveryReason: String? = null
    )

    /**
     * Result of attempting to prepare a symbol for deletion.
     */
    private sealed class SymbolPreparationResult {
        data class Success(val data: SymbolDeletePreparation) : SymbolPreparationResult()
        data class NoSymbolFound(
            val elementType: String,
            val nearbySuggestions: List<SymbolSuggestion>
        ) : SymbolPreparationResult()
        data class FileNotFound(val file: String) : SymbolPreparationResult()
        data class ReadOnly(val file: String) : SymbolPreparationResult()
        data class PositionOutOfBounds(val line: Int, val column: Int) : SymbolPreparationResult()
        data class UsageSearchFailed(
            val reason: String,
            val partial: SymbolDeletePreparation? = null
        ) : SymbolPreparationResult()
        data class InvalidSymbolId(val message: String) : SymbolPreparationResult()
    }

    /**
     * Result of attempting to prepare a file for deletion.
     */
    private sealed class FilePreparationResult {
        data class Success(val data: FileDeletePreparation) : FilePreparationResult()
        data object FileNotFound : FilePreparationResult()
        data class ReadOnly(val file: String) : FilePreparationResult()
        data class NonPhysicalFile(val fileName: String) : FilePreparationResult()
        data class UsageSearchFailed(
            val reason: String,
            val partial: FileDeletePreparation? = null
        ) : FilePreparationResult()
    }

    /**
     * Wraps an unexpected failure of the usage search so callers can refuse the deletion
     * instead of treating a partial (possibly empty) result as "no usages".
     */
    private class UsageSearchException(cause: Exception) : RuntimeException(cause)

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val startedAtNanos = System.nanoTime()
        val dryRun = arguments[ParamNames.DRY_RUN]?.jsonPrimitive?.booleanOrNull == true
        val symbolId = optionalStringArg(arguments, ParamNames.SYMBOL_ID)
        val targetType = arguments["target_type"]?.jsonPrimitive?.content ?: "symbol"
        val force = arguments["force"]?.jsonPrimitive?.content?.toBoolean() ?: false

        requireSmartMode(project)
        if (!dryRun) syncProjectForRefactoring(project)

        return when (targetType) {
            "file" -> {
                if (symbolId != null ||
                    optionalStringArg(arguments, ParamNames.LANGUAGE) != null ||
                    optionalStringArg(arguments, ParamNames.SYMBOL) != null ||
                    arguments[ParamNames.LINE]?.let { it != JsonNull } == true ||
                    arguments[ParamNames.COLUMN]?.let { it != JsonNull } == true
                ) {
                    return createErrorResult("target_type='file' accepts only the file path; omit symbol selectors and coordinates")
                }
                val file = optionalStringArg(arguments, ParamNames.FILE)
                    ?: return createErrorResult("Missing required parameter: ${ParamNames.FILE}")
                executeFileDelete(project, file, force, dryRun, startedAtNanos)
            }
            "symbol" -> {
                when (resolveLookupMode(arguments, allowSymbolId = true)) {
                    LookupModeState.SYMBOL_ID,
                    LookupModeState.SYMBOL -> executeSymbolDeleteBySemanticTarget(
                        project,
                        arguments,
                        symbolId,
                        force,
                        dryRun,
                        startedAtNanos
                    )
                    LookupModeState.POSITION -> {
                        val file = optionalStringArg(arguments, ParamNames.FILE)
                            ?: return createErrorResult("Missing required parameter: ${ParamNames.FILE}")
                        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
                            ?: return createErrorResult("Missing required parameter '${ParamNames.LINE}' for target_type='symbol'")
                        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
                            ?: return createErrorResult("Missing required parameter '${ParamNames.COLUMN}' for target_type='symbol'")
                        executeSymbolDelete(project, file, line, column, force, dryRun, startedAtNanos)
                    }
                    LookupModeState.CONFLICT -> createErrorResult(
                        if (symbolId != null) {
                            ErrorMessages.SYMBOL_ID_AND_OTHER_TARGET_EXCLUSIVE
                        } else {
                            ErrorMessages.LANGUAGE_SYMBOL_AND_OTHER_TARGET_EXCLUSIVE
                        }
                    )
                    LookupModeState.MISSING ->
                        createErrorResult("Missing required parameter: ${ParamNames.FILE}")
                }
            }
            else -> createErrorResult("Invalid target_type: '$targetType'. Must be 'symbol' or 'file'.")
        }
    }

    /**
     * Executes symbol deletion at a specific position.
     * If position is whitespace/comment, returns nearby symbol suggestions.
     */
    private suspend fun executeSymbolDelete(
        project: Project,
        file: String,
        line: Int,
        column: Int,
        force: Boolean,
        dryRun: Boolean,
        startedAtNanos: Long
    ): CallToolResult {
        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Find element and check usages (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val preparationResult = suspendingReadAction {
            prepareSymbolDelete(
                project,
                file,
                line,
                column,
                force = if (dryRun) false else force,
                allowReadOnly = dryRun
            )
        }

        return when (preparationResult) {
            is SymbolPreparationResult.Success -> {
                val preparation = preparationResult.data
                if (dryRun) {
                    return createSymbolDeletePreview(project, preparation, force, startedAtNanos)
                }
                // If there are usages and force is false, return them without deleting
                if (preparation.usages.isNotEmpty() && !force) {
                    return createJsonResult(
                        SafeDeleteBlockedResult(
                            canDelete = false,
                            elementName = preparation.elementName,
                            elementType = preparation.elementType,
                            usageCount = preparation.usages.size,
                            blockingUsages = preparation.usages.take(20),
                            message = "Cannot delete '${preparation.elementName}': found ${preparation.usages.size} usage(s). Use force=true to delete anyway."
                        )
                    )
                }

                beforeDeletionHook?.invoke()

                // ═══════════════════════════════════════════════════════════════════════
                // PHASE 2: EDT - Apply deletion quickly (write action)
                // ═══════════════════════════════════════════════════════════════════════
                applySymbolDeletion(project, preparation, force)
            }
            is SymbolPreparationResult.NoSymbolFound -> {
                createJsonResult(
                    NoSymbolFoundResult(
                        error = "No symbol found at line $line, column $column (found ${preparationResult.elementType})",
                        position = PositionInfo(line, column, preparationResult.elementType),
                        suggestions = preparationResult.nearbySuggestions,
                        hint = if (preparationResult.nearbySuggestions.isNotEmpty()) {
                            "Try one of the suggested symbols, or use target_type=\"file\" to delete the entire file"
                        } else {
                            "Use target_type=\"file\" to delete the entire file"
                        }
                    )
                )
            }
            is SymbolPreparationResult.FileNotFound -> {
                createErrorResult("File not found: ${preparationResult.file}")
            }
            is SymbolPreparationResult.ReadOnly -> {
                createErrorResult("File is read-only and cannot be modified: ${preparationResult.file}")
            }
            is SymbolPreparationResult.PositionOutOfBounds -> {
                createErrorResult("Position out of bounds: line ${preparationResult.line}, column ${preparationResult.column}")
            }
            is SymbolPreparationResult.UsageSearchFailed -> {
                if (dryRun && preparationResult.partial != null) {
                    createSymbolDeletePreview(
                        project,
                        preparationResult.partial,
                        force,
                        startedAtNanos,
                        discoveryError = preparationResult.reason
                    )
                } else {
                    createErrorResult(usageSearchFailedMessage(preparationResult.reason))
                }
            }
            is SymbolPreparationResult.InvalidSymbolId -> {
                // This variant is only produced by the symbolId path, but keeping the branch
                // explicit makes this exhaustive if preparation logic is shared in the future.
                createErrorResult(preparationResult.message)
            }
        }
    }

    /** Exact semantic target path, with no nearby-symbol suggestions or coordinate fallback. */
    private suspend fun executeSymbolDeleteBySemanticTarget(
        project: Project,
        arguments: JsonObject,
        symbolId: String?,
        force: Boolean,
        dryRun: Boolean,
        startedAtNanos: Long
    ): CallToolResult {
        val preparationResult = suspendingReadAction {
            prepareSymbolDeleteBySemanticTarget(
                project,
                arguments,
                symbolId,
                force = if (dryRun) false else force,
                allowReadOnly = dryRun
            )
        }

        return when (preparationResult) {
            is SymbolPreparationResult.Success -> {
                val preparation = preparationResult.data
                if (dryRun) {
                    return createSymbolDeletePreview(project, preparation, force, startedAtNanos)
                }
                if (preparation.usages.isNotEmpty() && !force) {
                    createJsonResult(
                        SafeDeleteBlockedResult(
                            canDelete = false,
                            elementName = preparation.elementName,
                            elementType = preparation.elementType,
                            usageCount = preparation.usages.size,
                            blockingUsages = preparation.usages.take(20),
                            message = "Cannot delete '${preparation.elementName}': found ${preparation.usages.size} usage(s). Use force=true to delete anyway."
                        )
                    )
                } else {
                    beforeDeletionHook?.invoke()
                    applySymbolDeletion(project, preparation, force)
                }
            }
            is SymbolPreparationResult.InvalidSymbolId -> createErrorResult(preparationResult.message)
            is SymbolPreparationResult.ReadOnly ->
                createErrorResult("File is read-only and cannot be modified: ${preparationResult.file}")
            is SymbolPreparationResult.UsageSearchFailed -> {
                if (dryRun && preparationResult.partial != null) {
                    createSymbolDeletePreview(
                        project,
                        preparationResult.partial,
                        force,
                        startedAtNanos,
                        discoveryError = preparationResult.reason
                    )
                } else {
                    createErrorResult(usageSearchFailedMessage(preparationResult.reason))
                }
            }
            else -> createErrorResult(
                symbolId?.let(ErrorMessages::symbolIdExpired) ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL
            )
        }
    }

    /**
     * Executes file deletion, checking for external usages of all symbols in the file.
     */
    private suspend fun executeFileDelete(
        project: Project,
        file: String,
        force: Boolean,
        dryRun: Boolean,
        startedAtNanos: Long
    ): CallToolResult {
        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Collect symbols and find external usages
        // ═══════════════════════════════════════════════════════════════════════
        val preparationResult = suspendingReadAction {
            prepareFileDelete(
                project,
                file,
                force = if (dryRun) false else force,
                allowReadOnly = dryRun
            )
        }

        return when (preparationResult) {
            is FilePreparationResult.Success -> {
                val preparation = preparationResult.data
                if (dryRun) {
                    return createFileDeletePreview(project, preparation, force, startedAtNanos)
                }
                // If there are external usages and force is false, return them
                if (preparation.externalUsages.isNotEmpty() && !force) {
                    return createJsonResult(
                        SafeDeleteFileBlockedResult(
                            canDelete = false,
                            fileName = preparation.fileName,
                            symbolCount = preparation.symbols.size,
                            externalUsageCount = preparation.externalUsages.size,
                            blockingUsages = preparation.externalUsages.take(20),
                            message = "Cannot delete file '${preparation.fileName}': found ${preparation.externalUsages.size} external usage(s) of symbols in this file. Use force=true to delete anyway."
                        )
                    )
                }

                beforeDeletionHook?.invoke()

                // ═══════════════════════════════════════════════════════════════════════
                // PHASE 2: EDT - Delete the file
                // ═══════════════════════════════════════════════════════════════════════
                applyFileDeletion(project, preparation, force)
            }
            is FilePreparationResult.FileNotFound -> {
                createErrorResult("File not found: $file")
            }
            is FilePreparationResult.ReadOnly -> {
                createErrorResult("File is read-only and cannot be modified: ${preparationResult.file}")
            }
            is FilePreparationResult.NonPhysicalFile -> {
                createErrorResult("Cannot delete non-physical file '${preparationResult.fileName}' (e.g., in-memory or generated file)")
            }
            is FilePreparationResult.UsageSearchFailed -> {
                if (dryRun && preparationResult.partial != null) {
                    createFileDeletePreview(
                        project,
                        preparationResult.partial,
                        force,
                        startedAtNanos,
                        discoveryError = preparationResult.reason
                    )
                } else {
                    createErrorResult(usageSearchFailedMessage(preparationResult.reason))
                }
            }
        }
    }

    private fun usageSearchFailedMessage(reason: String): String =
        "Usage search failed ($reason) — refusing to delete without a complete usage check. " +
            "Retry, or use force=true to delete anyway."

    private suspend fun createSymbolDeletePreview(
        project: Project,
        preparation: SymbolDeletePreparation,
        force: Boolean,
        startedAtNanos: Long,
        discoveryError: String? = null
    ): CallToolResult {
        val (writable, target) = suspendingReadAction {
            val targetFile = preparation.element.containingFile?.virtualFile
            (targetFile?.isWritable == true) to
                resolvedSymbolInfo(project, preparation.element, preparation.previewSymbolId)
        }
        val warnings = mutableListOf<String>()
        if (!writable) {
            warnings.add("Target file is read-only or unavailable; the deletion cannot be applied.")
        }
        if (discoveryError != null) {
            warnings.add(
                "Usage discovery failed: $discoveryError. " +
                    if (force) "Force allows deletion without a complete usage check."
                    else "Refusing to mark this preview as applicable."
            )
        } else if (preparation.usages.isNotEmpty()) {
            warnings.add(
                if (force) {
                    "Force deletion would leave ${preparation.usages.size} usage(s) unresolved."
                } else {
                    "Deletion is blocked by ${preparation.usages.size} usage(s); set force=true to override."
                }
            )
        }

        return createJsonResult(
            refactoringPreview(
                canApply = writable && (force || (discoveryError == null && preparation.usages.isEmpty())),
                target = target,
                plannedChange = buildJsonObject {
                    put("operation", "safeDelete")
                    put("targetType", "symbol")
                    put("name", preparation.elementName)
                    put("force", force)
                },
                affectedFiles = listOf(preparation.affectedFile),
                usageCount = preparation.usages.size,
                conflictCount = preparation.usages.size,
                warnings = warnings,
                startedAtNanos = startedAtNanos
            )
        )
    }

    private suspend fun createFileDeletePreview(
        project: Project,
        preparation: FileDeletePreparation,
        force: Boolean,
        startedAtNanos: Long,
        discoveryError: String? = null
    ): CallToolResult {
        val (writable, target) = suspendingReadAction {
            val targetFile = preparation.psiFile.virtualFile
            (targetFile?.isWritable == true) to resolvedSymbolInfo(project, preparation.psiFile)
        }
        val warnings = mutableListOf<String>()
        if (!writable) {
            warnings.add("Target file is read-only or unavailable; the deletion cannot be applied.")
        }
        if (discoveryError != null) {
            warnings.add(
                "Usage discovery failed: $discoveryError. " +
                    if (force) "Force allows deletion without a complete usage check."
                    else "Refusing to mark this preview as applicable."
            )
        } else if (preparation.externalUsages.isNotEmpty()) {
            warnings.add(
                if (force) {
                    "Force deletion would leave ${preparation.externalUsages.size} external usage(s) unresolved."
                } else {
                    "Deletion is blocked by ${preparation.externalUsages.size} external usage(s); " +
                        "set force=true to override."
                }
            )
        }
        if (preparation.symbols.isEmpty()) {
            warnings.add(
                preparation.incompleteDiscoveryReason
                    ?: "No top-level declarations were found; complete usage discovery cannot be proven."
            )
        }

        return createJsonResult(
            refactoringPreview(
                canApply = writable && (force || (discoveryError == null && preparation.externalUsages.isEmpty())),
                target = target,
                plannedChange = buildJsonObject {
                    put("operation", "safeDelete")
                    put("targetType", "file")
                    put("name", preparation.fileName)
                    put("force", force)
                },
                affectedFiles = listOf(preparation.filePath),
                usageCount = preparation.externalUsages.size,
                conflictCount = preparation.externalUsages.size,
                warnings = warnings,
                startedAtNanos = startedAtNanos
            )
        )
    }

    private suspend fun applySymbolDeletion(
        project: Project,
        preparation: SymbolDeletePreparation,
        force: Boolean
    ): CallToolResult {
        var success = false
        var errorMessage: String? = null
        var notSaved: List<String> = emptyList()

        edtAction {
            val unsavedBefore = FileDocumentManager.getInstance().unsavedDocuments.toSet()
            WriteCommandAction.writeCommandAction(project)
                .withName("Safe Delete: ${preparation.elementName}")
                .withGroupId("MCP Refactoring")
                .run<Throwable> {
                    try {
                        if (!preparation.element.isValid) {
                            errorMessage = preparation.symbolId?.let(ErrorMessages::symbolIdExpired)
                                ?: "Element '${preparation.elementName}' is no longer valid — " +
                                    "the file changed since usages were checked. Retry the operation."
                            return@run
                        }
                        preparation.element.delete()

                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        success = true
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                }
            // Saved outside the command: a declined save reloads the Document from disk.
            if (success) notSaved = saveRefactoredDocuments(project, unsavedBefore)
        }
        if (notSaved.isNotEmpty()) return createErrorResult(refactoringNotSavedMessage(notSaved))

        return if (success) {
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = listOf(preparation.affectedFile),
                    changesCount = 1,
                    message = if (force && preparation.usages.isNotEmpty()) {
                        "Force-deleted '${preparation.elementName}' (had ${preparation.usages.size} usage(s) that may now be broken)"
                    } else {
                        "Successfully deleted '${preparation.elementName}'"
                    },
                    invalidatedSymbolId = preparation.symbolId
                )
            )
        } else {
            if (errorMessage?.startsWith("SYMBOL_ID_EXPIRED:") == true) {
                createErrorResult(errorMessage!!)
            } else {
                createErrorResult("Safe delete failed: ${errorMessage ?: "Unknown error"}", ToolNames.DIAGNOSTICS)
            }
        }
    }

    private suspend fun applyFileDeletion(
        project: Project,
        preparation: FileDeletePreparation,
        force: Boolean
    ): CallToolResult {
        var success = false
        var errorMessage: String? = null
        var notSaved: List<String> = emptyList()

        edtAction {
            val unsavedBefore = FileDocumentManager.getInstance().unsavedDocuments.toSet()
            WriteCommandAction.writeCommandAction(project)
                .withName("Safe Delete File: ${preparation.fileName}")
                .withGroupId("MCP Refactoring")
                .run<Throwable> {
                    try {
                        if (!preparation.psiFile.isValid) {
                            errorMessage = "File '${preparation.fileName}' is no longer valid — " +
                                "the file changed since usages were checked. Retry the operation."
                            return@run
                        }
                        preparation.psiFile.delete()

                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        success = true
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                }
            // Saved outside the command: a declined save reloads the Document from disk.
            if (success) notSaved = saveRefactoredDocuments(project, unsavedBefore)
        }
        if (notSaved.isNotEmpty()) return createErrorResult(refactoringNotSavedMessage(notSaved))

        return if (success) {
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = listOf(preparation.filePath),
                    changesCount = 1,
                    message = when {
                        force && preparation.externalUsages.isNotEmpty() ->
                            "Force-deleted file '${preparation.fileName}' (had ${preparation.externalUsages.size} external usage(s) that may now be broken)"
                        // No declarations to scan means layer 3 never ran. Saying "0 symbol(s)
                        // with no external usages" reads like a completed check; it is not one.
                        preparation.symbols.isEmpty() ->
                            "Successfully deleted file '${preparation.fileName}' (no top-level declarations found in it — only direct references to the file itself were checked)"
                        else ->
                            "Successfully deleted file '${preparation.fileName}' (contained ${preparation.symbols.size} symbol(s) with no external usages)"
                    }
                )
            )
        } else {
            createErrorResult("File deletion failed: ${errorMessage ?: "Unknown error"}", ToolNames.DIAGNOSTICS)
        }
    }

    /**
     * Prepares all data needed for symbol delete in a read action.
     * If no symbol found at position, returns nearby suggestions.
     */
    private fun prepareSymbolDelete(
        project: Project,
        file: String,
        line: Int,
        column: Int,
        force: Boolean,
        allowReadOnly: Boolean = false
    ): SymbolPreparationResult {
        val psiFile = PsiUtils.getPsiFile(project, file)
            ?: return SymbolPreparationResult.FileNotFound(file)
        if (!allowReadOnly && psiFile.virtualFile?.isWritable == false) {
            return SymbolPreparationResult.ReadOnly(psiFile.virtualFile.path)
        }

        val leafElement = PsiUtils.findElementAtPosition(project, file, line, column)
            ?: return SymbolPreparationResult.PositionOutOfBounds(line, column)

        // Check if we're on whitespace or comment
        if (leafElement is PsiWhiteSpace || leafElement is PsiComment) {
            // For doc comments, check if the next sibling is the documented symbol
            val docAdjacentSymbol = findDocAdjacentSymbol(leafElement)
            if (docAdjacentSymbol != null) {
                val suggestions = listOf(createSymbolSuggestion(project, psiFile, docAdjacentSymbol, line))
                return SymbolPreparationResult.NoSymbolFound("doc comment", suggestions)
            }

            val suggestions = findNearbySymbols(psiFile, line, maxDistance = 10)
            val elementType = if (leafElement is PsiWhiteSpace) "whitespace" else "comment"
            return SymbolPreparationResult.NoSymbolFound(elementType, suggestions)
        }

        // Try to find a named element (excludes PsiFile)
        val element = findNamedElement(leafElement)
        if (element == null) {
            val suggestions = findNearbySymbols(psiFile, line, maxDistance = 10)
            return SymbolPreparationResult.NoSymbolFound(
                leafElement.javaClass.simpleName.removePrefix("Psi").lowercase(),
                suggestions
            )
        }

        val elementName = element.name ?: "unnamed"
        val elementType = getElementType(element)
        val affectedFile = element.containingFile?.virtualFile?.let {
            getRelativePath(project, it)
        } ?: file

        // Find usages (POTENTIALLY SLOW - but in background!)
        // References inside the deleted element itself (recursive calls, factory methods
        // returning the class) vanish with the deletion, so they must not block it —
        // matching IntelliJ's own SafeDeleteProcessor and this tool's file-delete mode.
        val usages = try {
            findUsages(project, element, excludeWithin = element)
        } catch (e: UsageSearchException) {
            if (!force) {
                return SymbolPreparationResult.UsageSearchFailed(
                    searchFailureReason(e),
                    SymbolDeletePreparation(
                        element = element,
                        elementName = elementName,
                        elementType = elementType,
                        usages = emptyList(),
                        affectedFile = affectedFile
                    )
                )
            }
            // force=true means "delete regardless of usages", so a failed search cannot block it.
            emptyList()
        }

        return SymbolPreparationResult.Success(
            SymbolDeletePreparation(
                element = element,
                elementName = elementName,
                elementType = elementType,
                usages = usages,
                affectedFile = affectedFile
            )
        )
    }

    private fun prepareSymbolDeleteBySemanticTarget(
        project: Project,
        arguments: JsonObject,
        symbolId: String?,
        force: Boolean,
        allowReadOnly: Boolean = false
    ): SymbolPreparationResult {
        val resolved = resolveElementFromArguments(project, arguments, allowSymbolId = true).getOrElse {
            return SymbolPreparationResult.InvalidSymbolId(
                it.message ?: symbolId?.let(ErrorMessages::symbolIdExpired) ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL
            )
        }
        val element = PsiUtils.resolveNavigationTarget(resolved) as? PsiNamedElement
            ?: return SymbolPreparationResult.InvalidSymbolId(
                if (symbolId != null) "symbolId '$symbolId' does not identify a deletable named symbol"
                else "Target does not identify a deletable named symbol"
            )
        if (element.language.id == "kotlin" && resolved is PsiMethod && resolved !== element) {
            // Generated JVM members may navigate to an enclosing class, property, or constructor.
            // Only a matching source function/constructor is an exact deletion target.
            val isSourceFunction = Class.forName("org.jetbrains.kotlin.psi.KtFunction").isInstance(element)
            val isSourceConstructor = Class.forName("org.jetbrains.kotlin.psi.KtConstructor").isInstance(element)
            val matchesSourceMethod = isSourceFunction && resolved.isConstructor == isSourceConstructor &&
                PsiUtils.toLightMethods(element).any { method ->
                    method.name == resolved.name &&
                        method.parameterList.parameters.map { it.type.canonicalText } ==
                        resolved.parameterList.parameters.map { it.type.canonicalText }
                }
            if (!matchesSourceMethod) {
                return SymbolPreparationResult.InvalidSymbolId(
                    "The selected JVM method has no standalone Kotlin declaration to delete. " +
                        "Select the intended source declaration explicitly."
                )
            }
        }
        // A Java-signature lookup or exact handle may resolve to a Kotlin light method while the
        // actual deletion target is its source navigation element. Preview metadata describes the
        // source target, but must not rebind the caller's exact light-element handle to a different
        // PSI identity. This mirrors rename preview's identity guard.
        val previewSymbolId = symbolId?.takeIf { resolved === element }
        if (element is PsiFile) {
            return SymbolPreparationResult.InvalidSymbolId(
                if (symbolId != null) "symbolId '$symbolId' identifies a file; use target_type='file' with its file path"
                else "Target identifies a file; use target_type='file' with its file path"
            )
        }
        val virtualFile = element.containingFile?.virtualFile
            ?: return SymbolPreparationResult.InvalidSymbolId(
                symbolId?.let(ErrorMessages::symbolIdExpired) ?: "Target has no editable source file"
            )
        if (!allowReadOnly && !virtualFile.isWritable) {
            return SymbolPreparationResult.ReadOnly(virtualFile.path)
        }

        val elementName = element.name ?: "unnamed"
        val elementType = getElementType(element)
        val affectedFile = getRelativePath(project, virtualFile)
        val usages = try {
            findUsages(project, element, excludeWithin = element)
        } catch (e: UsageSearchException) {
            if (!force) {
                return SymbolPreparationResult.UsageSearchFailed(
                    searchFailureReason(e),
                    SymbolDeletePreparation(
                        element = element,
                        elementName = elementName,
                        elementType = elementType,
                        usages = emptyList(),
                        affectedFile = affectedFile,
                        symbolId = symbolId,
                        previewSymbolId = previewSymbolId
                    )
                )
            }
            emptyList()
        }
        return SymbolPreparationResult.Success(
            SymbolDeletePreparation(
                element = element,
                elementName = elementName,
                elementType = elementType,
                usages = usages,
                affectedFile = affectedFile,
                symbolId = symbolId,
                previewSymbolId = previewSymbolId
            )
        )
    }

    /**
     * For doc comments, finds the immediately following declaration.
     *
     * This is a best-effort heuristic that detects common doc comment patterns:
     * - JavaDoc: `/** ... */`
     * - KDoc: `/** ... */`
     * - Rust/C#/Swift doc comments: `///`
     *
     * Note: This does NOT detect all documentation styles across all languages:
     * - Python docstrings (`"""`) are string literals, not PsiComment nodes
     * - Rust `#[doc = "..."]` attributes are not detected
     * - Other language-specific patterns may not be covered
     *
     * When a doc comment is detected and followed by a declaration, that declaration
     * is suggested as the likely intended target.
     *
     * @param commentElement The PsiComment element to check
     * @return The documented symbol if found, null otherwise
     */
    private fun findDocAdjacentSymbol(commentElement: PsiElement): PsiNamedElement? {
        if (commentElement !is PsiComment) return null

        // Detect common doc comment patterns (best-effort heuristic)
        val text = commentElement.text
        val isDocComment = text.startsWith("/**") || text.startsWith("///")
        if (!isDocComment) return null

        // Find the next non-whitespace sibling
        var sibling = commentElement.nextSibling
        while (sibling != null && sibling is PsiWhiteSpace) {
            sibling = sibling.nextSibling
        }

        // If the next meaningful element is a named element, return it
        return if (sibling is PsiNamedElement && sibling !is PsiFile && sibling.name != null) {
            sibling
        } else {
            null
        }
    }

    /**
     * Creates a SymbolSuggestion for a given element.
     */
    private fun createSymbolSuggestion(
        project: Project,
        psiFile: PsiFile,
        element: PsiNamedElement,
        fromLine: Int
    ): SymbolSuggestion {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        val elementLine = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
        val elementColumn = if (document != null && elementLine > 0) {
            val lineStart = document.getLineStartOffset(elementLine - 1)
            element.textOffset - lineStart + 1
        } else {
            1
        }
        return SymbolSuggestion(
            name = element.name!!,
            type = getElementType(element),
            line = elementLine,
            column = elementColumn,
            distance = kotlin.math.abs(elementLine - fromLine)
        )
    }

    /**
     * Prepares all data needed for file delete in a read action.
     * Finds external usages through three layers:
     *
     * 1. **Direct file references** — `ReferencesSearch.search(psiFile)` catches any PSI
     *    references that resolve directly to the file.
     * 2. **Resource element references** — For Android resource files, the file maps to a
     *    resource element (e.g., `ResourceReferencePsiElement`). We probe
     *    `RenamePsiElementProcessor.prepareRenaming()` to detect this substitution, then
     *    search for usages of the resource element. This catches `@xml/`, `@drawable/`, etc.
     *    references in XML files.
     * 3. **Top-level symbol references** — Checks the file's top-level declarations (see
     *    [collectTopLevelDeclarations]) for external usages. Internal call chains don't block
     *    deletion. This is the only layer that fires for an ordinary source file, so an
     *    enumeration that comes back empty leaves the file effectively unchecked.
     */
    private fun prepareFileDelete(
        project: Project,
        file: String,
        force: Boolean,
        allowReadOnly: Boolean = false
    ): FilePreparationResult {
        val psiFile = PsiUtils.getPsiFile(project, file)
            ?: return FilePreparationResult.FileNotFound
        if (!allowReadOnly && psiFile.virtualFile?.isWritable == false) {
            return FilePreparationResult.ReadOnly(psiFile.virtualFile.path)
        }

        // Check if file is physical (can be deleted from disk)
        if (!psiFile.isPhysical) {
            return FilePreparationResult.NonPhysicalFile(psiFile.name)
        }

        val fileName = psiFile.name
        val filePath = psiFile.virtualFile?.let { getRelativePath(project, it) } ?: file

        val topLevelElements = collectTopLevelDeclarations(project, psiFile)

        val symbols = topLevelElements.map { (element, line, column) ->
            SymbolInfo(
                name = element.name!!,
                type = getElementType(element),
                line = line,
                column = column
            )
        }

        val externalUsages = mutableListOf<UsageInfo>()

        try {
            // Layer 1: Search for direct references to the PsiFile itself.
            // PsiFile implements PsiNamedElement, so findUsages works directly.
            ProgressManager.checkCanceled()
            for (usage in findUsages(project, psiFile)) {
                if (usage.file != filePath) {
                    externalUsages.add(usage)
                }
            }

            // Layer 2: Check if the file maps to a resource element (e.g., Android resource).
            // Uses the same prepareRenaming probe as RenameSymbolTool.computeEffectiveNewName.
            // Apply may stop after a blocking usage, but a preview must account for every
            // discovery layer: with force=true its count is otherwise deceptively partial.
            if (externalUsages.isEmpty() || allowReadOnly) {
                ProgressManager.checkCanceled()
                externalUsages.addAll(
                    findResourceElementUsages(
                        project,
                        psiFile,
                        filePath
                    )
                )
            }

            // Layer 3: Search top-level declarations for external usages.
            if (externalUsages.isEmpty() || allowReadOnly) {
                for ((element, _, _) in topLevelElements) {
                    ProgressManager.checkCanceled()
                    for (usage in findUsages(project, element)) {
                        if (usage.file != filePath) {
                            externalUsages.add(usage)
                        }
                    }
                }
            }
        } catch (e: UsageSearchException) {
            if (!force) {
                return FilePreparationResult.UsageSearchFailed(
                    searchFailureReason(e),
                    FileDeletePreparation(
                        psiFile = psiFile,
                        fileName = fileName,
                        filePath = filePath,
                        symbols = symbols,
                        externalUsages = previewUsageList(externalUsages, allowReadOnly),
                        incompleteDiscoveryReason = incompleteFileDiscoveryReason(topLevelElements)
                    )
                )
            }
            // force=true means "delete regardless of usages", so a failed search cannot block it.
        }

        return FilePreparationResult.Success(
            FileDeletePreparation(
                psiFile = psiFile,
                fileName = fileName,
                filePath = filePath,
                symbols = symbols,
                externalUsages = previewUsageList(externalUsages, allowReadOnly),
                incompleteDiscoveryReason = incompleteFileDiscoveryReason(topLevelElements)
            )
        )
    }

    /**
     * Checks if the file maps to a resource element (e.g., Android resource) and searches
     * for usages of that element.
     *
     * Uses [RenamePsiElementProcessor.prepareRenaming] to probe for element substitution.
     * When Android's `ResourceReferenceRenameProcessor` is present, it replaces the `PsiFile`
     * with a `ResourceReferencePsiElement` in the allRenames map. We then search for usages
     * of that resource element, which catches `@xml/`, `@drawable/`, etc. references in XML.
     *
     * Returns only external usages (from files other than the given file).
     */
    private fun findResourceElementUsages(
        project: Project,
        psiFile: PsiFile,
        filePath: String
    ): List<UsageInfo> {
        val processor = RenamePsiElementProcessor.forElement(psiFile)
        val probeRenames = linkedMapOf<PsiElement, String>(psiFile to psiFile.name)
        try {
            processor.prepareRenaming(psiFile, psiFile.name, probeRenames)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IndexNotReadyException) {
            throw e
        } catch (e: Exception) {
            throw if (e is UsageSearchException) e else UsageSearchException(e)
        }

        // If the PsiFile was substituted, search for usages of the substitute element
        if (psiFile !in probeRenames && probeRenames.isNotEmpty()) {
            val resourceElement = probeRenames.keys.firstOrNull()
            if (resourceElement is PsiNamedElement) {
                return findUsages(project, resourceElement).filter { it.file != filePath }
            }
        }

        return emptyList()
    }

    /**
     * File deletion has no generic semantic target when a language exposes no top-level
     * declarations. Direct file/resource references are useful evidence, but not proof that a
     * cross-language reference search is complete. Keep that limitation visible in previews even
     * though it does not change the existing file-delete eligibility by itself.
     */
    private fun incompleteFileDiscoveryReason(
        topLevelElements: List<Triple<PsiNamedElement, Int, Int>>
    ): String? = if (topLevelElements.isEmpty()) {
        "No top-level declarations were found; direct file/resource references were checked, " +
            "but complete usage discovery cannot be proven."
    } else {
        null
    }

    /** Avoid double-counting the same location when preview runs every discovery layer. */
    private fun previewUsageList(
        usages: List<UsageInfo>,
        preview: Boolean
    ): List<UsageInfo> = if (preview) usages.distinct() else usages

    /**
     * Collects the file's top-level declarations — the symbols whose *external* references are
     * what make a file unsafe to delete.
     *
     * Two properties this deliberately has, both of them bug fixes (issue #336):
     *
     * 1. **The document does not gate the collection.** Line and column are display-only, but
     *    reading the document *first* and bailing out on `null` made the entire layer-3 usage
     *    scan collapse to a no-op — `prepareFileDelete` then reported a complete-looking empty
     *    result and deleted the file. That is precisely the failure [UsageSearchException]
     *    exists to prevent: an incomplete check must never be reported as "no usages". A missing
     *    document now degrades the *positions* to 0, not the symbol list to empty.
     *
     * 2. **The walk does not stop at the file's direct children.** Several languages nest their
     *    top-level declarations one or two nodes down, and a direct-children scan silently finds
     *    nothing at all in those files:
     *    - Scala wraps every definition in an `ScPackaging` node as soon as the file has a
     *      `package` clause, so its classes are grandchildren of the file.
     *    - `export const API_URL = "…"` in JS/TS names the `JSVariable` *inside* a
     *      `JSVarStatement`.
     *    - Go's `type Foo struct{}` names the `GoTypeSpec` inside a `GoTypeDeclaration`.
     *    - Python module-level constants name the `PyTargetExpression` inside a
     *      `PyAssignmentStatement`.
     *
     * The walk therefore descends through *unnamed* wrapper nodes, and stops at the first named
     * element on each path so it never walks into a class or function body — the members inside
     * a top-level declaration cannot outlive it, and searching each of them would turn one file
     * delete into hundreds of index queries.
     *
     * [PsiClassOwner.getClasses] is unioned on top of the walk. For the class-owning languages
     * (Java, Kotlin, Scala, Groovy) it is the language's own authoritative answer to "what does
     * this file declare", and it unwraps package nesting whatever shape that language chose.
     */
    private fun collectTopLevelDeclarations(
        project: Project,
        psiFile: PsiFile
    ): List<Triple<PsiNamedElement, Int, Int>> {
        val declarations = mutableListOf<PsiNamedElement>()
        val seenTargets = HashSet<PsiElement>()

        val record: (PsiNamedElement) -> Unit = { element ->
            if (element !is PsiFile && element.name != null) {
                // A light class (Kotlin) and the source declaration it wraps share a navigation
                // element, so this keeps the structural walk and getClasses() from both landing
                // the same declaration and doubling the reported usage count.
                if (seenTargets.add(element.navigationElement)) {
                    declarations.add(element)
                }
            }
        }

        collectDeclarationsInto(psiFile, MAX_WRAPPER_DEPTH, record)
        (psiFile as? PsiClassOwner)?.classes?.forEach(record)

        // Display-only, and legitimately absent for binary files — never a reason to drop symbols.
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        return declarations.map { element ->
            val (line, column) = positionOf(document, element.textOffset)
            Triple(element, line, column)
        }
    }

    /**
     * Records every named child of [parent], descending through unnamed wrapper nodes until
     * [depthBudget] is spent. Named elements terminate their branch: their members are deleted
     * with them and cannot be broken independently.
     */
    private fun collectDeclarationsInto(
        parent: PsiElement,
        depthBudget: Int,
        record: (PsiNamedElement) -> Unit
    ) {
        for (child in parent.children) {
            ProgressManager.checkCanceled()
            if (child is PsiWhiteSpace || child is PsiComment) continue
            if (child is PsiNamedElement && child !is PsiFile && child.name != null) {
                record(child)
                continue
            }
            if (depthBudget > 0) {
                collectDeclarationsInto(child, depthBudget - 1, record)
            }
        }
    }

    /**
     * 1-based (line, column) for [offset], or `(0, 0)` when it cannot be resolved — no document
     * for the file, or an offset no longer inside it. `(0, 0)` is the "position unknown"
     * encoding this tool has always reported for usages in documentless files.
     */
    private fun positionOf(document: Document?, offset: Int): Pair<Int, Int> {
        if (document == null || offset < 0 || offset > document.textLength) return 0 to 0
        val line = document.getLineNumber(offset) + 1
        return line to (offset - document.getLineStartOffset(line - 1) + 1)
    }

    /**
     * Finds named symbols within a certain line distance from the given line.
     *
     * Optimized to only traverse lines within the specified range rather than the entire file.
     * Includes symbols on the same line (distance 0) for cases where cursor is at end of line.
     *
     * @param psiFile The file to search in
     * @param currentLine The line number to search around (1-based)
     * @param maxDistance Maximum line distance to include (inclusive)
     * @return Up to 5 nearest symbol suggestions, sorted by distance
     */
    private fun findNearbySymbols(psiFile: PsiFile, currentLine: Int, maxDistance: Int): List<SymbolSuggestion> {
        val project = psiFile.project
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return emptyList()

        // Calculate the line range to search (clamped to document bounds)
        val startLine = maxOf(1, currentLine - maxDistance)
        val endLine = minOf(document.lineCount, currentLine + maxDistance)

        // Convert to offsets for efficient range-based search
        val startOffset = document.getLineStartOffset(startLine - 1)
        val endOffset = if (endLine <= document.lineCount) {
            document.getLineEndOffset(endLine - 1)
        } else {
            document.textLength
        }

        val suggestions = mutableListOf<SymbolSuggestion>()

        // Only traverse elements within the line range
        PsiTreeUtil.processElements(psiFile) { element ->
            // Skip elements outside our offset range
            if (element.textOffset < startOffset || element.textOffset > endOffset) {
                return@processElements true // continue but skip this element
            }

            if (element is PsiNamedElement && element !is PsiFile && element.name != null) {
                val elementLine = document.getLineNumber(element.textOffset) + 1
                val distance = kotlin.math.abs(elementLine - currentLine)

                // Include distance 0 (same line) for cases where cursor is at end of declaration line
                if (distance <= maxDistance) {
                    val lineStart = document.getLineStartOffset(elementLine - 1)
                    val column = element.textOffset - lineStart + 1
                    suggestions.add(
                        SymbolSuggestion(
                            name = element.name!!,
                            type = getElementType(element),
                            line = elementLine,
                            column = column,
                            distance = distance
                        )
                    )
                }
            }
            true // continue processing
        }

        return suggestions.sortedBy { it.distance }.take(5)
    }

    /**
     * Finds all references to [element].
     *
     * Cancellation ([ProcessCanceledException]) and dumb mode ([IndexNotReadyException])
     * are rethrown — the latter is translated into the standard dumb-mode retry error by
     * [AbstractMcpTool.execute][com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool.execute].
     * Any other failure is wrapped in [UsageSearchException]: an incomplete search must
     * never be reported as "no usages" by a tool whose whole point is the safety check.
     *
     * @param excludeWithin when non-null, references located inside this element are skipped —
     *   they are deleted together with it and cannot be broken by the deletion.
     */
    private fun findUsages(
        project: Project,
        element: PsiNamedElement,
        excludeWithin: PsiElement? = null
    ): List<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()
        val seenLocations = mutableSetOf<Pair<String, Int>>()

        fun recordUsage(usageElement: PsiElement) {
            if (excludeWithin != null && PsiTreeUtil.isAncestor(excludeWithin, usageElement, false)) {
                return
            }
            val usageFile = usageElement.containingFile
            val virtualFile = usageFile?.virtualFile ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(usageFile)
            val (lineNumber, columnNumber) = positionOf(document, usageElement.textOffset)
            val filePath = getRelativePath(project, virtualFile)
            // Display coordinates degrade to 0:0 when a document is unavailable. The VFS URL and
            // PSI offset still distinguish separate usages in such files.
            if (!seenLocations.add(virtualFile.url to usageElement.textOffset)) return

            usages.add(
                UsageInfo(
                    file = filePath,
                    line = lineNumber,
                    column = columnNumber,
                    context = getContextLine(document, lineNumber)
                )
            )
        }

        try {
            usageSearchHook?.invoke()
            ReferencesSearch.search(element).forEach { reference ->
                ProgressManager.checkCanceled() // Allow cancellation
                recordUsage(reference.element)
            }

            // A Java override/implementation is a semantic dependency, not a PsiReference to
            // the base declaration, so ReferencesSearch alone can report a dangerously clean
            // result. The apply path performs a literal element.delete(); it cannot repair or
            // remove overriding declarations. Treat every descendant override as blocking unless
            // the caller explicitly opts into force=true.
            val hierarchyMethods = if (element is PsiMethod) {
                listOf(element)
            } else {
                // Exact Java-signature targets are normalized to their Kotlin source declaration
                // before this search. Convert them back to light methods solely for hierarchy
                // discovery so an override cannot disappear from the safety check.
                PsiUtils.toLightMethods(element)
            }
            hierarchyMethods.distinct().forEach { method ->
                OverridingMethodsSearch.search(method, true).forEach { overridingMethod ->
                    ProgressManager.checkCanceled()
                    recordUsage(overridingMethod.nameIdentifier ?: overridingMethod)
                }
            }

            // Call-site arguments do not reference their declaration's PsiParameter, so a plain
            // ReferencesSearch(parameter) is always incomplete. Ask the platform safe-delete
            // delegates for parameter-specific usages (Java call arguments, override chains, and
            // the equivalent contributed by an installed Kotlin plugin), but keep the apply path
            // conservative: every discovered external element blocks our literal delete unless
            // force=true.
            val isKotlinParameter = element.javaClass.name == "org.jetbrains.kotlin.psi.KtParameter"
            // Java's delegate casts a parameter's declaration scope to PsiMethod. Lambda,
            // catch and foreach parameters are local bindings and need only ReferencesSearch.
            val isJavaMethodParameter = element is PsiParameter && element.declarationScope is PsiMethod
            if (isJavaMethodParameter) {
                val method = (element as PsiParameter).declarationScope as PsiMethod
                val parameterIndex = method.parameterList.getParameterIndex(element)
                fun recordHierarchyParameter(related: PsiMethod) {
                    ProgressManager.checkCanceled()
                    related.parameterList.parameters.getOrNull(parameterIndex)?.let(::recordUsage)
                }
                // The public safe-delete usage query does not run getElementsToSearch(), which
                // is where Java's processor collects related parameters. Our literal deletion
                // cannot change the hierarchy, so both super and overriding parameters block it.
                method.findSuperMethods().forEach(::recordHierarchyParameter)
                OverridingMethodsSearch.search(method, true).forEach { overriding ->
                    recordHierarchyParameter(overriding)
                }
            }
            if (isJavaMethodParameter || isKotlinParameter) {
                val refactoring = RefactoringFactory.getInstance(project).createSafeDelete(arrayOf(element))
                refactoring.setSearchInComments(false)
                refactoring.setSearchInNonJavaFiles(false)
                refactoring.findUsages().forEach { usage ->
                    ProgressManager.checkCanceled()
                    val usageElement = usage.element ?: return@forEach
                    if (usageElement !== element) recordUsage(usageElement)
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IndexNotReadyException) {
            throw e
        } catch (e: Exception) {
            throw UsageSearchException(e)
        }

        return usages
    }

    private fun searchFailureReason(e: UsageSearchException): String {
        val cause = e.cause
        return cause?.message ?: cause?.javaClass?.simpleName ?: "unknown error"
    }

    private fun getContextLine(document: Document?, line: Int): String {
        if (document == null || line < 1 || line > document.lineCount) return ""
        val lineIndex = line - 1
        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        return document.getText(TextRange(startOffset, endOffset)).trim()
    }

    private fun getElementType(element: PsiElement): String {
        return when {
            element is com.intellij.psi.PsiMethod -> "method"
            element is com.intellij.psi.PsiClass -> "class"
            element is com.intellij.psi.PsiField -> "field"
            element is com.intellij.psi.PsiLocalVariable -> "variable"
            element is com.intellij.psi.PsiParameter -> "parameter"
            else -> element.javaClass.simpleName.removePrefix("Psi").lowercase()
        }
    }
}

@Serializable
data class SafeDeleteBlockedResult(
    val canDelete: Boolean,
    val elementName: String,
    val elementType: String,
    val usageCount: Int,
    val blockingUsages: List<UsageInfo>,
    val message: String
)

@Serializable
data class UsageInfo(
    val file: String,
    val line: Int,
    val column: Int,
    val context: String
)

@Serializable
data class NoSymbolFoundResult(
    val error: String,
    val position: PositionInfo,
    val suggestions: List<SymbolSuggestion>,
    val hint: String
)

@Serializable
data class PositionInfo(
    val line: Int,
    val column: Int,
    val elementType: String
)

@Serializable
data class SymbolSuggestion(
    val name: String,
    val type: String,
    val line: Int,
    val column: Int,
    val distance: Int
)

@Serializable
data class SymbolInfo(
    val name: String,
    val type: String,
    val line: Int,
    val column: Int
)

@Serializable
data class SafeDeleteFileBlockedResult(
    val canDelete: Boolean,
    val fileName: String,
    val symbolCount: Int,
    val externalUsageCount: Int,
    val blockingUsages: List<UsageInfo>,
    val message: String
)

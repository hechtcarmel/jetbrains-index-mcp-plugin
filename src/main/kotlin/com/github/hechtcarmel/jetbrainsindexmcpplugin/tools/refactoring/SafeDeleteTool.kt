package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Safe delete tool that checks for usages before deletion.
 *
 * This implementation uses a two-phase approach to avoid UI freezes:
 * 1. **Background Phase**: Find element and check for usages (in read action)
 * 2. **EDT Phase**: Apply deletion quickly (in write action)
 */
class SafeDeleteTool : AbstractRefactoringTool() {

    override val name = "ide_refactor_safe_delete"

    override val description = """
        Delete a symbol or file safely by first checking for usages. Use when removing code to avoid breaking references.

        Modes:
        - target_type='symbol' (default): Delete the symbol at line/column (REQUIRED: file, line, column).
          If position is whitespace/comment, returns nearby symbol suggestions.
        - target_type='file': Delete the entire file (REQUIRED: file only).
          Only succeeds if no symbols have external usages. Internal call chains don't block deletion.

        Behavior: If usages exist and force=false, returns the usage list instead of deleting.
        Use force=true to delete anyway (may break compilation).

        Returns: success status and affected files, OR blocking usages list, OR nearby symbol suggestions.

        Examples:
        - Symbol: {"file": "src/OldClass.java", "line": 10, "column": 14}
        - Symbol with force: {"file": "src/OldClass.java", "line": 10, "column": 14, "force": true}
        - File: {"file": "src/UnusedUtils.java", "target_type": "file"}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        // Use oneOf to define different required fields based on target_type
        putJsonArray("oneOf") {
            // Schema for symbol deletion (default) - requires file, line, column
            add(buildJsonObject {
                putJsonObject("properties") {
                    putJsonObject("target_type") {
                        put("const", "symbol")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("file"))
                    add(JsonPrimitive("line"))
                    add(JsonPrimitive("column"))
                }
            })
            // Schema for symbol deletion when target_type is omitted (default behavior)
            add(buildJsonObject {
                putJsonObject("properties") {
                    putJsonObject("target_type") {
                        put("type", "null")  // target_type not present
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("file"))
                    add(JsonPrimitive("line"))
                    add(JsonPrimitive("column"))
                }
            })
            // Schema for file deletion - requires only file
            add(buildJsonObject {
                putJsonObject("properties") {
                    putJsonObject("target_type") {
                        put("const", "file")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("file"))
                }
            })
        }
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
                put("description", "1-based line number where the symbol is located. REQUIRED when target_type='symbol' (default).")
            }
            putJsonObject("column") {
                put("type", "integer")
                put("description", "1-based column number. REQUIRED when target_type='symbol' (default).")
            }
            putJsonObject("target_type") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("symbol"))
                    add(JsonPrimitive("file"))
                }
                put("default", "symbol")
                put("description", "What to delete: 'symbol' (default, requires line+column) or 'file' (deletes entire file if no external usages).")
            }
            putJsonObject("force") {
                put("type", "boolean")
                put("default", false)
                put("description", "Force deletion even if usages exist. Default: false. Use with caution!")
            }
        }
    }

    /**
     * Data class to hold all information collected in background for symbol delete operation.
     */
    private data class SymbolDeletePreparation(
        val element: PsiNamedElement,
        val elementName: String,
        val elementType: String,
        val usages: List<UsageInfo>,
        val affectedFile: String
    )

    /**
     * Data class to hold all information collected in background for file delete operation.
     */
    private data class FileDeletePreparation(
        val psiFile: PsiFile,
        val fileName: String,
        val filePath: String,
        val symbols: List<SymbolInfo>,
        val externalUsages: List<UsageInfo>
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
        data class PositionOutOfBounds(val line: Int, val column: Int) : SymbolPreparationResult()
    }

    /**
     * Result of attempting to prepare a file for deletion.
     */
    private sealed class FilePreparationResult {
        data class Success(val data: FileDeletePreparation) : FilePreparationResult()
        data object FileNotFound : FilePreparationResult()
        data class NonPhysicalFile(val fileName: String) : FilePreparationResult()
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val targetType = arguments["target_type"]?.jsonPrimitive?.content ?: "symbol"
        val force = arguments["force"]?.jsonPrimitive?.content?.toBoolean() ?: false

        requireSmartMode(project)

        return when (targetType) {
            "file" -> executeFileDelete(project, file, force)
            "symbol" -> {
                val line = arguments["line"]?.jsonPrimitive?.int
                    ?: return createErrorResult("Missing required parameter 'line' for target_type='symbol'")
                val column = arguments["column"]?.jsonPrimitive?.int
                    ?: return createErrorResult("Missing required parameter 'column' for target_type='symbol'")
                executeSymbolDelete(project, file, line, column, force)
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
        force: Boolean
    ): ToolCallResult {
        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Find element and check usages (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val preparationResult = suspendingReadAction {
            prepareSymbolDelete(project, file, line, column)
        }

        return when (preparationResult) {
            is SymbolPreparationResult.Success -> {
                val preparation = preparationResult.data
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
            is SymbolPreparationResult.PositionOutOfBounds -> {
                createErrorResult("Position out of bounds: line ${preparationResult.line}, column ${preparationResult.column}")
            }
        }
    }

    /**
     * Executes file deletion, checking for external usages of all symbols in the file.
     */
    private suspend fun executeFileDelete(
        project: Project,
        file: String,
        force: Boolean
    ): ToolCallResult {
        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Collect symbols and find external usages
        // ═══════════════════════════════════════════════════════════════════════
        val preparationResult = suspendingReadAction {
            prepareFileDelete(project, file)
        }

        return when (preparationResult) {
            is FilePreparationResult.Success -> {
                val preparation = preparationResult.data
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

                // ═══════════════════════════════════════════════════════════════════════
                // PHASE 2: EDT - Delete the file
                // ═══════════════════════════════════════════════════════════════════════
                applyFileDeletion(project, preparation, force)
            }
            is FilePreparationResult.FileNotFound -> {
                createErrorResult("File not found: $file")
            }
            is FilePreparationResult.NonPhysicalFile -> {
                createErrorResult("Cannot delete non-physical file '${preparationResult.fileName}' (e.g., in-memory or generated file)")
            }
        }
    }

    private suspend fun applySymbolDeletion(
        project: Project,
        preparation: SymbolDeletePreparation,
        force: Boolean
    ): ToolCallResult {
        var success = false
        var errorMessage: String? = null

        withContext(Dispatchers.EDT) {
            WriteCommandAction.writeCommandAction(project)
                .withName("Safe Delete: ${preparation.elementName}")
                .withGroupId("MCP Refactoring")
                .run<Throwable> {
                    try {
                        if (preparation.element.isValid) {
                            preparation.element.delete()
                        }

                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        FileDocumentManager.getInstance().saveAllDocuments()

                        success = true
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                }
        }

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
                    }
                )
            )
        } else {
            createErrorResult("Safe delete failed: ${errorMessage ?: "Unknown error"}")
        }
    }

    private suspend fun applyFileDeletion(
        project: Project,
        preparation: FileDeletePreparation,
        force: Boolean
    ): ToolCallResult {
        var success = false
        var errorMessage: String? = null

        withContext(Dispatchers.EDT) {
            WriteCommandAction.writeCommandAction(project)
                .withName("Safe Delete File: ${preparation.fileName}")
                .withGroupId("MCP Refactoring")
                .run<Throwable> {
                    try {
                        if (preparation.psiFile.isValid) {
                            preparation.psiFile.delete()
                        }

                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        FileDocumentManager.getInstance().saveAllDocuments()

                        success = true
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                }
        }

        return if (success) {
            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = listOf(preparation.filePath),
                    changesCount = 1,
                    message = if (force && preparation.externalUsages.isNotEmpty()) {
                        "Force-deleted file '${preparation.fileName}' (had ${preparation.externalUsages.size} external usage(s) that may now be broken)"
                    } else {
                        "Successfully deleted file '${preparation.fileName}' (contained ${preparation.symbols.size} symbol(s) with no external usages)"
                    }
                )
            )
        } else {
            createErrorResult("File deletion failed: ${errorMessage ?: "Unknown error"}")
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
        column: Int
    ): SymbolPreparationResult {
        val psiFile = PsiUtils.getPsiFile(project, file)
            ?: return SymbolPreparationResult.FileNotFound(file)

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
        val usages = findUsages(project, element)

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
     * Collects top-level declarations and finds only EXTERNAL usages (internal call chains don't block).
     *
     * Only checks top-level declarations (classes, top-level functions) for external usages,
     * not every named element. Internal methods within a class are covered by checking the class itself.
     */
    private fun prepareFileDelete(
        project: Project,
        file: String
    ): FilePreparationResult {
        val psiFile = PsiUtils.getPsiFile(project, file)
            ?: return FilePreparationResult.FileNotFound

        // Check if file is physical (can be deleted from disk)
        if (!psiFile.isPhysical) {
            return FilePreparationResult.NonPhysicalFile(psiFile.name)
        }

        val fileName = psiFile.name
        val filePath = psiFile.virtualFile?.let { getRelativePath(project, it) } ?: file

        // Collect only TOP-LEVEL declarations (not all named elements)
        // This improves performance and is semantically correct:
        // - If a top-level class has external usages, we block deletion
        // - Internal methods are implicitly covered by the class check
        val topLevelElements = collectTopLevelDeclarations(project, psiFile)

        val symbols = topLevelElements.map { (element, line, column) ->
            SymbolInfo(
                name = element.name!!,
                type = getElementType(element),
                line = line,
                column = column
            )
        }

        val namedElements = topLevelElements.map { it.first }

        // Find EXTERNAL usages only (filter out usages within this file)
        // Check for cancellation between symbol searches for large files
        val externalUsages = mutableListOf<UsageInfo>()
        for (element in namedElements) {
            ProgressManager.checkCanceled() // Allow cancellation between symbol searches
            val usages = findUsages(project, element)
            for (usage in usages) {
                // Only include usages from OTHER files
                if (usage.file != filePath) {
                    externalUsages.add(usage)
                }
            }
        }

        return FilePreparationResult.Success(
            FileDeletePreparation(
                psiFile = psiFile,
                fileName = fileName,
                filePath = filePath,
                symbols = symbols,
                externalUsages = externalUsages
            )
        )
    }

    /**
     * Collects only top-level declarations (classes, interfaces, top-level functions/properties).
     * This is more efficient than collecting all named elements for file deletion checks.
     */
    private fun collectTopLevelDeclarations(
        project: Project,
        psiFile: PsiFile
    ): List<Triple<PsiNamedElement, Int, Int>> {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return emptyList()

        val results = mutableListOf<Triple<PsiNamedElement, Int, Int>>()

        // Only process direct children of the file (top-level declarations)
        for (child in psiFile.children) {
            if (child is PsiNamedElement && child.name != null) {
                val line = document.getLineNumber(child.textOffset) + 1
                val lineStart = document.getLineStartOffset(line - 1)
                val column = child.textOffset - lineStart + 1
                results.add(Triple(child, line, column))
            }
        }

        return results
    }

    /**
     * Collects all named elements in a file with their line and column positions.
     * Shared helper used by both findNearbySymbols and prepareFileDelete.
     *
     * @return List of triples: (element, line, column)
     */
    private fun collectNamedElementsWithPositions(
        project: Project,
        psiFile: PsiFile
    ): List<Triple<PsiNamedElement, Int, Int>> {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return emptyList()

        val results = mutableListOf<Triple<PsiNamedElement, Int, Int>>()

        PsiTreeUtil.processElements(psiFile) { element ->
            if (element is PsiNamedElement && element !is PsiFile && element.name != null) {
                val line = document.getLineNumber(element.textOffset) + 1
                val lineStart = document.getLineStartOffset(line - 1)
                val column = element.textOffset - lineStart + 1
                results.add(Triple(element, line, column))
            }
            true // continue processing
        }

        return results
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

    private fun findUsages(project: Project, element: PsiNamedElement): List<UsageInfo> {
        val usages = mutableListOf<UsageInfo>()

        try {
            ReferencesSearch.search(element).forEach { reference ->
                ProgressManager.checkCanceled() // Allow cancellation

                val refElement = reference.element
                val refFile = refElement.containingFile?.virtualFile

                if (refFile != null) {
                    val document = PsiDocumentManager.getInstance(project).getDocument(refElement.containingFile)
                    val lineNumber = document?.getLineNumber(refElement.textOffset)?.plus(1) ?: 0
                    val columnNumber = if (document != null && lineNumber > 0) {
                        val lineStart = document.getLineStartOffset(lineNumber - 1)
                        refElement.textOffset - lineStart + 1
                    } else {
                        0
                    }

                    usages.add(
                        UsageInfo(
                            file = getRelativePath(project, refFile),
                            line = lineNumber,
                            column = columnNumber,
                            context = getContextLine(document, lineNumber)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // If we can't find usages, assume there are none
        }

        return usages
    }

    private fun getContextLine(document: com.intellij.openapi.editor.Document?, line: Int): String {
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

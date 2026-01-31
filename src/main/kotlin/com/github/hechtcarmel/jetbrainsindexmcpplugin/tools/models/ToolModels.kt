package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models

import kotlinx.serialization.Serializable

@Serializable
data class PositionInput(
    val file: String,
    val line: Int,
    val column: Int
)

// find_usages output
@Serializable
data class UsageLocation(
    val file: String,
    val line: Int,
    val column: Int,
    val context: String,
    val type: String
)

@Serializable
data class FindUsagesResult(
    val usages: List<UsageLocation>,
    val totalCount: Int
)

// find_definition output
@Serializable
data class DefinitionResult(
    val file: String,
    val line: Int,
    val column: Int,
    val preview: String,
    val symbolName: String
)


// type_hierarchy output
@Serializable
data class TypeHierarchyResult(
    val element: TypeElement,
    val supertypes: List<TypeElement>,
    val subtypes: List<TypeElement>
)

@Serializable
data class TypeElement(
    val name: String,
    val file: String?,
    val kind: String,
    val language: String? = null,
    val supertypes: List<TypeElement>? = null
)

// call_hierarchy output
@Serializable
data class CallHierarchyResult(
    val element: CallElement,
    val calls: List<CallElement>
)

@Serializable
data class CallElement(
    val name: String,
    val file: String,
    val line: Int,
    val language: String? = null,
    val children: List<CallElement>? = null
)

// find_implementations output
@Serializable
data class ImplementationResult(
    val implementations: List<ImplementationLocation>,
    val totalCount: Int
)

@Serializable
data class ImplementationLocation(
    val name: String,
    val file: String,
    val line: Int,
    val kind: String,
    val language: String? = null
)


// ide_diagnostics output
@Serializable
data class DiagnosticsResult(
    val problems: List<ProblemInfo>,
    val intentions: List<IntentionInfo>,
    val problemCount: Int,
    val intentionCount: Int
)

@Serializable
data class ProblemInfo(
    val message: String,
    val severity: String,
    val file: String,
    val line: Int,
    val column: Int,
    val endLine: Int?,
    val endColumn: Int?
)

@Serializable
data class IntentionInfo(
    val name: String,
    val description: String?
)

// Refactoring result
@Serializable
data class RefactoringResult(
    val success: Boolean,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val message: String
)


// get_index_status output
@Serializable
data class IndexStatusResult(
    val isDumbMode: Boolean,
    val isIndexing: Boolean,
    val indexingProgress: Double?
)

// ide_find_symbol output
@Serializable
data class FindSymbolResult(
    val symbols: List<SymbolMatch>,
    val totalCount: Int,
    val query: String
)

@Serializable
data class SymbolMatch(
    val name: String,
    val qualifiedName: String?,
    val kind: String,
    val file: String,
    val line: Int,
    val containerName: String?,
    val language: String? = null
)

// ide_find_super_methods output
@Serializable
data class SuperMethodsResult(
    val method: MethodInfo,
    val hierarchy: List<SuperMethodInfo>,
    val totalCount: Int
)

@Serializable
data class MethodInfo(
    val name: String,
    val signature: String,
    val containingClass: String,
    val file: String,
    val line: Int,
    val language: String? = null
)

@Serializable
data class SuperMethodInfo(
    val name: String,
    val signature: String,
    val containingClass: String,
    val containingClassKind: String,
    val file: String?,
    val line: Int?,
    val isInterface: Boolean,
    val depth: Int,
    val language: String? = null
)

// ide_find_class output (reuses SymbolMatch)
@Serializable
data class FindClassResult(
    val classes: List<SymbolMatch>,
    val totalCount: Int,
    val query: String
)

// ide_find_file output
@Serializable
data class FindFileResult(
    val files: List<FileMatch>,
    val totalCount: Int,
    val query: String
)

@Serializable
data class FileMatch(
    val name: String,
    val path: String,
    val directory: String
)

// ide_search_text output
@Serializable
data class SearchTextResult(
    val matches: List<TextMatch>,
    val totalCount: Int,
    val query: String
)

@Serializable
data class TextMatch(
    val file: String,
    val line: Int,
    val column: Int,
    val context: String,       // line content
    val contextType: String    // "CODE", "COMMENT", "STRING_LITERAL"
)

// ide_get_current_file output
@Serializable
data class CurrentFileResult(
    val file: String?,              // absolute path to current file
    val relativePath: String?,      // path relative to project root
    val language: String?,          // language ID (e.g., "JAVA", "PHP", "Kotlin")
    val isModified: Boolean         // true if file has unsaved changes
)

// ide_get_selection output
@Serializable
data class SelectionResult(
    val hasSelection: Boolean,      // true if text is selected
    val text: String?,              // the selected text (null if no selection)
    val startLine: Int?,            // 1-based start line
    val startColumn: Int?,          // 1-based start column
    val endLine: Int?,              // 1-based end line
    val endColumn: Int?,            // 1-based end column
    val file: String?               // file path where selection is
)

// ide_get_cursor_position output
@Serializable
data class CursorPositionResult(
    val line: Int,                  // 1-based line number
    val column: Int,                // 1-based column number
    val offset: Int,                // character offset from start of file
    val file: String?               // file path where cursor is
)

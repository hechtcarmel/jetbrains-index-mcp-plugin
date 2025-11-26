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

// get_symbol_info output
@Serializable
data class SymbolInfoResult(
    val name: String,
    val kind: String,
    val type: String?,
    val documentation: String?,
    val modifiers: List<String>,
    val file: String?,
    val line: Int?,
    val containingClass: String?
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
    val kind: String
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
    val kind: String
)

// get_completions output
@Serializable
data class CompletionsResult(
    val completions: List<CompletionItem>,
    val totalCount: Int
)

@Serializable
data class CompletionItem(
    val text: String,
    val type: String?,
    val detail: String?,
    val documentation: String?
)

// get_inspections output
@Serializable
data class InspectionsResult(
    val problems: List<ProblemInfo>,
    val totalCount: Int
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

// get_quick_fixes output
@Serializable
data class QuickFixesResult(
    val fixes: List<QuickFixInfo>
)

@Serializable
data class QuickFixInfo(
    val id: String,
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

// get_project_structure output
@Serializable
data class ProjectStructureResult(
    val name: String,
    val basePath: String?,
    val modules: List<ModuleInfo>
)

@Serializable
data class ModuleInfo(
    val name: String,
    val sourceRoots: List<String>,
    val testRoots: List<String>,
    val resourceRoots: List<String>
)

// get_file_structure output
@Serializable
data class FileStructureResult(
    val file: String,
    val elements: List<FileElement>
)

@Serializable
data class FileElement(
    val name: String,
    val kind: String,
    val line: Int,
    val modifiers: List<String>,
    val type: String?,
    val children: List<FileElement>?
)

// get_dependencies output
@Serializable
data class DependenciesResult(
    val dependencies: List<DependencyInfo>
)

@Serializable
data class DependencyInfo(
    val name: String,
    val version: String?,
    val scope: String?
)

// get_index_status output
@Serializable
data class IndexStatusResult(
    val isDumbMode: Boolean,
    val isIndexing: Boolean,
    val indexingProgress: Double?
)

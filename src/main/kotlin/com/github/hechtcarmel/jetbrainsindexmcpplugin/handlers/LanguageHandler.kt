package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Base interface for language-specific handlers.
 *
 * Language handlers provide language-specific implementations for code intelligence
 * operations like type hierarchy, call hierarchy, finding implementations, etc.
 *
 * Each handler is associated with a specific language (e.g., "Java", "Python", "JavaScript")
 * and is only loaded when the corresponding language plugin is available in the IDE.
 *
 * ## Usage
 *
 * Handlers are registered with the [LanguageHandlerRegistry] and are discovered
 * at runtime based on available plugins. Tools delegate to handlers via the registry:
 *
 * ```kotlin
 * val handler = LanguageHandlerRegistry.getHandler<TypeHierarchyHandler>(element)
 * val result = handler?.getTypeHierarchy(element, project)
 * ```
 *
 * @param T The result type returned by this handler
 * @see LanguageHandlerRegistry
 */
interface LanguageHandler<T> {
    /**
     * The language ID this handler supports.
     *
     * Common values: "JAVA", "kotlin", "Python", "JavaScript", "TypeScript", "ECMAScript 6"
     *
     * This should match the [com.intellij.lang.Language.id] of the supported language.
     */
    val languageId: String

    /**
     * Check if this handler can process the given element.
     *
     * Implementations should check:
     * 1. That the element's language matches this handler
     * 2. That the element type is appropriate for this handler
     *
     * @param element The PSI element to check
     * @return true if this handler can process the element
     */
    fun canHandle(element: PsiElement): Boolean

    /**
     * Check if the required plugin is available in the current IDE.
     *
     * This allows handlers to be registered at startup but only become active
     * when their required plugin is installed and enabled.
     *
     * @return true if the required plugin is available
     */
    fun isAvailable(): Boolean
}

/**
 * Handler for type hierarchy operations.
 *
 * Provides the inheritance hierarchy (supertypes and subtypes) for a class,
 * interface, or similar type declaration.
 */
interface TypeHierarchyHandler : LanguageHandler<TypeHierarchyData> {
    /**
     * Gets the complete type hierarchy for an element.
     *
     * @param element The PSI element (should be a class/interface/type declaration)
     * @param project The project context
     * @return The type hierarchy data, or null if the element is not a type
     */
    fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData?
}

/**
 * Handler for finding implementations.
 *
 * Finds all implementations of an interface, abstract class, or abstract/interface method.
 */
interface ImplementationsHandler : LanguageHandler<List<ImplementationData>> {
    /**
     * Finds all implementations of the given element.
     *
     * @param element The PSI element (interface, abstract class, or method)
     * @param project The project context
     * @return List of implementations, or null if the element doesn't support implementations
     */
    fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>?
}

/**
 * Handler for call hierarchy operations.
 *
 * Provides the callers or callees of a method/function.
 */
interface CallHierarchyHandler : LanguageHandler<CallHierarchyData> {
    /**
     * Gets the call hierarchy for a method/function.
     *
     * @param element The PSI element (should be a method/function)
     * @param project The project context
     * @param direction "callers" or "callees"
     * @param depth How many levels to traverse
     * @return The call hierarchy data, or null if the element is not a callable
     */
    fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int
    ): CallHierarchyData?
}

/**
 * Handler for symbol search.
 *
 * Searches for classes, methods, functions, fields, etc. by name.
 */
interface SymbolSearchHandler : LanguageHandler<List<SymbolData>> {
    /**
     * Searches for symbols matching the given pattern.
     *
     * @param project The project context
     * @param pattern The search pattern (supports substring and camelCase matching)
     * @param includeLibraries Whether to search in library dependencies
     * @param limit Maximum number of results
     * @return List of matching symbols
     */
    fun searchSymbols(
        project: Project,
        pattern: String,
        includeLibraries: Boolean,
        limit: Int
    ): List<SymbolData>
}

/**
 * Handler for finding super methods.
 *
 * Finds all parent methods that a method overrides or implements.
 */
interface SuperMethodsHandler : LanguageHandler<SuperMethodsData> {
    /**
     * Finds all super methods for the given method.
     *
     * @param element The PSI element (should be a method)
     * @param project The project context
     * @return The super methods data, or null if the element is not a method
     */
    fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData?
}

// Data classes for handler results

/**
 * Result data for type hierarchy operations.
 */
data class TypeHierarchyData(
    val element: TypeElementData,
    val supertypes: List<TypeElementData>,
    val subtypes: List<TypeElementData>
)

/**
 * Represents a type element in a hierarchy.
 */
data class TypeElementData(
    val name: String,
    val qualifiedName: String?,
    val file: String?,
    val line: Int?,
    val kind: String,
    val language: String,
    val supertypes: List<TypeElementData>? = null
)

/**
 * Result data for implementation search.
 */
data class ImplementationData(
    val name: String,
    val file: String,
    val line: Int,
    val kind: String,
    val language: String
)

/**
 * Result data for call hierarchy operations.
 */
data class CallHierarchyData(
    val element: CallElementData,
    val calls: List<CallElementData>
)

/**
 * Represents a method/function in a call hierarchy.
 */
data class CallElementData(
    val name: String,
    val file: String,
    val line: Int,
    val language: String,
    val children: List<CallElementData>? = null
)

/**
 * Result data for symbol search.
 */
data class SymbolData(
    val name: String,
    val qualifiedName: String?,
    val kind: String,
    val file: String,
    val line: Int,
    val containerName: String?,
    val language: String
)

/**
 * Result data for super methods search.
 */
data class SuperMethodsData(
    val method: MethodData,
    val hierarchy: List<SuperMethodData>
)

/**
 * Represents a method in super methods results.
 */
data class MethodData(
    val name: String,
    val signature: String,
    val containingClass: String,
    val file: String,
    val line: Int,
    val language: String
)

/**
 * Represents a super method in the inheritance chain.
 */
data class SuperMethodData(
    val name: String,
    val signature: String,
    val containingClass: String,
    val containingClassKind: String,
    val file: String?,
    val line: Int?,
    val isInterface: Boolean,
    val depth: Int,
    val language: String
)

/**
 * Handler for file structure operations.
 *
 * Extracts the hierarchical structure of a source file including
 * classes, methods, fields, and their nesting relationships.
 */
interface StructureHandler : LanguageHandler<List<StructureNode>> {
    /**
     * Extracts the structure from a PSI file.
     *
     * @param file The PSI file to analyze
     * @param project The project context
     * @return List of top-level structure nodes (classes, functions, etc.)
     */
    fun getFileStructure(file: PsiFile, project: Project): List<StructureNode>
}

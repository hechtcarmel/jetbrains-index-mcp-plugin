package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.rust

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.RustPluginDetector
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Registration entry point for Rust language handlers.
 *
 * This class is loaded via reflection when a Rust plugin is available.
 * It registers all Rust-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## Rust PSI Classes Used (via reflection)
 *
 * - `org.rust.lang.core.psi.RsFile` - Rust source files
 * - `org.rust.lang.core.psi.RsStructItem` - Struct declarations
 * - `org.rust.lang.core.psi.RsTraitItem` - Trait declarations
 * - `org.rust.lang.core.psi.RsImplItem` - Impl blocks
 * - `org.rust.lang.core.psi.RsEnumItem` - Enum declarations
 * - `org.rust.lang.core.psi.RsFunction` - Function/method declarations
 * - `org.rust.lang.core.psi.RsModItem` - Module declarations
 * - `org.rust.lang.core.psi.RsCallExpr` - Function call expressions
 * - `org.rust.lang.core.psi.RsMethodCall` - Method call expressions
 *
 * ## Rust-Specific Concepts
 *
 * - **No Inheritance**: Rust uses composition and traits instead of class inheritance
 * - **Traits**: Similar to interfaces but can have default implementations
 * - **Impl Blocks**: Separate blocks for implementing traits on types
 * - **Supertraits**: Traits can require other traits as bounds
 *
 * ## Supported Plugin IDs
 *
 * - `org.jetbrains.rust` - New official JetBrains plugin (RustRover, IDEA Ultimate, CLion)
 * - `org.rust.lang` - Deprecated community intellij-rust plugin
 */
object RustHandlers {

    private val LOG = logger<RustHandlers>()

    /**
     * Registers all Rust handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!RustPluginDetector.isRustPluginAvailable) {
            LOG.info("Rust plugin not available, skipping Rust handler registration")
            return
        }

        try {
            // Verify Rust classes are accessible before registering
            Class.forName("org.rust.lang.core.psi.RsFile")
            Class.forName("org.rust.lang.core.psi.RsFunction")

            registry.registerTypeHierarchyHandler(RustTypeHierarchyHandler())
            registry.registerImplementationsHandler(RustImplementationsHandler())
            registry.registerCallHierarchyHandler(RustCallHierarchyHandler())
            registry.registerSymbolSearchHandler(RustSymbolSearchHandler())
            // Note: SuperMethodsHandler is NOT registered for Rust because Rust uses trait
            // implementations rather than classical inheritance. There are no "super methods"
            // in the OOP sense. Users should use ide_find_definition or ide_type_hierarchy instead.

            LOG.info("Registered Rust handlers (4 handlers - SuperMethods not applicable for Rust)")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Rust PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register Rust handlers: ${e.message}")
        }
    }
}

/**
 * Base class for Rust handlers with common utilities.
 *
 * Uses reflection to access Rust PSI classes to avoid compile-time dependencies.
 */
abstract class BaseRustHandler<T> : LanguageHandler<T> {

    protected val LOG = logger<BaseRustHandler<*>>()

    /**
     * Checks if the element is from Rust language.
     */
    protected fun isRustLanguage(element: PsiElement): Boolean {
        return element.language.id == "Rust"
    }

    // Lazy-loaded Rust PSI classes via reflection

    protected val rsFileClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsFile")
    }

    protected val rsStructItemClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsStructItem")
    }

    protected val rsTraitItemClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsTraitItem")
    }

    protected val rsImplItemClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsImplItem")
    }

    protected val rsEnumItemClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsEnumItem")
    }

    protected val rsFunctionClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsFunction")
    }

    protected val rsModItemClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsModItem")
    }

    protected val rsCallExprClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsCallExpr")
    }

    protected val rsMethodCallClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsMethodCall")
    }

    protected val rsNamedElementClass: Class<*>? by lazy {
        loadClass("org.rust.lang.core.psi.RsNamedElement")
    }

    private fun loadClass(className: String): Class<*>? {
        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            LOG.debug("$className not found")
            null
        }
    }

    // Type checking helpers

    protected fun isRsTrait(element: PsiElement): Boolean {
        return rsTraitItemClass?.isInstance(element) == true
    }

    protected fun isRsStruct(element: PsiElement): Boolean {
        return rsStructItemClass?.isInstance(element) == true
    }

    protected fun isRsEnum(element: PsiElement): Boolean {
        return rsEnumItemClass?.isInstance(element) == true
    }

    protected fun isRsImpl(element: PsiElement): Boolean {
        return rsImplItemClass?.isInstance(element) == true
    }

    protected fun isRsFunction(element: PsiElement): Boolean {
        return rsFunctionClass?.isInstance(element) == true
    }

    protected fun isRsMod(element: PsiElement): Boolean {
        return rsModItemClass?.isInstance(element) == true
    }

    protected fun isRsType(element: PsiElement): Boolean {
        return isRsStruct(element) || isRsEnum(element) || isRsTrait(element)
    }

    // Navigation helpers

    protected fun findContainingRsTrait(element: PsiElement): PsiElement? {
        if (isRsTrait(element)) return element
        val cls = rsTraitItemClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }

    protected fun findContainingRsImpl(element: PsiElement): PsiElement? {
        if (isRsImpl(element)) return element
        val cls = rsImplItemClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }

    protected fun findContainingRsFunction(element: PsiElement): PsiElement? {
        if (isRsFunction(element)) return element
        val cls = rsFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }

    protected fun findContainingRsStruct(element: PsiElement): PsiElement? {
        if (isRsStruct(element)) return element
        val cls = rsStructItemClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }

    protected fun findContainingRsEnum(element: PsiElement): PsiElement? {
        if (isRsEnum(element)) return element
        val cls = rsEnumItemClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }

    // Reflection-based API calls

    /**
     * Gets the name of a Rust element via reflection.
     */
    protected fun getName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the trait reference from an impl block.
     * Returns null for inherent impls (impl Type { ... } without a trait).
     */
    protected fun getTraitRef(implItem: PsiElement): PsiElement? {
        return try {
            val method = implItem.javaClass.getMethod("getTraitRef")
            method.invoke(implItem) as? PsiElement
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the type reference from an impl block.
     * This is the type being implemented for (e.g., MyStruct in "impl Trait for MyStruct").
     */
    protected fun getTypeReference(implItem: PsiElement): PsiElement? {
        return try {
            val method = implItem.javaClass.getMethod("getTypeReference")
            method.invoke(implItem) as? PsiElement
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the supertraits of a trait.
     */
    protected fun getSuperTraits(traitItem: PsiElement): List<PsiElement>? {
        return try {
            val method = traitItem.javaClass.getMethod("getSuperTraits")
            @Suppress("UNCHECKED_CAST")
            method.invoke(traitItem) as? List<PsiElement>
        } catch (e: Exception) {
            // Try alternative method names
            try {
                val method = traitItem.javaClass.getMethod("getTypeParamBounds")
                @Suppress("UNCHECKED_CAST")
                method.invoke(traitItem) as? List<PsiElement>
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Resolves a reference to its target element.
     */
    protected fun resolveReference(element: PsiElement): PsiElement? {
        return try {
            val referenceMethod = element.javaClass.getMethod("getReference")
            val reference = referenceMethod.invoke(element) as? com.intellij.psi.PsiReference
            reference?.resolve()
        } catch (e: Exception) {
            // Try resolve() directly if available
            try {
                val resolveMethod = element.javaClass.getMethod("resolve")
                resolveMethod.invoke(element) as? PsiElement
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Gets the functions/methods within a trait or impl.
     */
    protected fun getFunctions(container: PsiElement): List<PsiElement>? {
        return try {
            // Try different method names used in Rust PSI
            val methodNames = listOf("getFunctions", "getMembers", "getExpandedMembers")
            for (methodName in methodNames) {
                try {
                    val method = container.javaClass.getMethod(methodName)
                    val result = method.invoke(container)
                    @Suppress("UNCHECKED_CAST")
                    val list = result as? List<*>
                    if (list != null) {
                        return list.filterIsInstance<PsiElement>().filter { isRsFunction(it) }
                    }
                } catch (e: NoSuchMethodException) {
                    continue
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // Utility methods

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return file.path.removePrefix(basePath).removePrefix("/")
    }

    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun determineElementKind(element: PsiElement): String {
        return when {
            isRsTrait(element) -> "TRAIT"
            isRsStruct(element) -> "STRUCT"
            isRsEnum(element) -> "ENUM"
            isRsImpl(element) -> "IMPL"
            isRsFunction(element) -> "FUNCTION"
            isRsMod(element) -> "MODULE"
            else -> "SYMBOL"
        }
    }

    /**
     * Gets a qualified name for a Rust element if available.
     */
    protected fun getQualifiedName(element: PsiElement): String? {
        return try {
            // Try different methods for getting qualified names
            val methodNames = listOf("getQualifiedName", "getName")
            for (methodName in methodNames) {
                try {
                    val method = element.javaClass.getMethod(methodName)
                    val result = method.invoke(element) as? String
                    if (result != null) return result
                } catch (e: NoSuchMethodException) {
                    continue
                }
            }
            null
        } catch (e: Exception) {
            getName(element)
        }
    }
}

/**
 * Rust implementation of [TypeHierarchyHandler].
 *
 * Handles Rust-specific type relationships:
 * - **For Traits**: Shows supertraits and implementing types
 * - **For Structs/Enums**: Shows implemented traits (via impl blocks)
 * - **For Impl Blocks**: Shows the trait/type relationship
 *
 * Note: Rust does NOT have class inheritance. Composition and trait
 * implementations are the primary mechanisms for code reuse.
 */
class RustTypeHierarchyHandler : BaseRustHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 50
    }

    override val languageId = "Rust"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRustLanguage(element)
    }

    override fun isAvailable(): Boolean = RustPluginDetector.isRustPluginAvailable && rsTraitItemClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        LOG.debug("Getting type hierarchy for Rust element at ${element.containingFile?.name}")

        // Handle traits
        val trait = findContainingRsTrait(element)
        if (trait != null) {
            LOG.debug("Getting hierarchy for trait: ${getName(trait)}")
            return getTraitHierarchy(project, trait)
        }

        // Handle structs
        val struct = findContainingRsStruct(element)
        if (struct != null) {
            LOG.debug("Getting hierarchy for struct: ${getName(struct)}")
            return getTypeImplHierarchy(project, struct)
        }

        // Handle enums
        val enum = findContainingRsEnum(element)
        if (enum != null) {
            LOG.debug("Getting hierarchy for enum: ${getName(enum)}")
            return getTypeImplHierarchy(project, enum)
        }

        // Handle impl blocks
        val impl = findContainingRsImpl(element)
        if (impl != null) {
            LOG.debug("Getting hierarchy for impl block")
            return getImplHierarchy(project, impl)
        }

        return null
    }

    private fun getTraitHierarchy(project: Project, trait: PsiElement): TypeHierarchyData {
        val supertypes = getSupertraitHierarchy(project, trait, mutableSetOf())
        val subtypes = getImplementingTypes(project, trait)

        LOG.debug("Found ${supertypes.size} supertraits and ${subtypes.size} implementing types")

        return TypeHierarchyData(
            element = TypeElementData(
                name = getName(trait) ?: "unknown",
                qualifiedName = getQualifiedName(trait),
                file = trait.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, trait),
                kind = "TRAIT",
                language = "Rust"
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun getSupertraitHierarchy(
        project: Project,
        trait: PsiElement,
        visited: MutableSet<String>,
        depth: Int = 0
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val traitName = getName(trait) ?: return emptyList()
        if (traitName in visited) return emptyList()
        visited.add(traitName)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            val superTraits = getSuperTraits(trait) ?: emptyList()
            for (superTraitRef in superTraits) {
                val resolved = resolveReference(superTraitRef)
                if (resolved != null && isRsTrait(resolved)) {
                    val resolvedName = getName(resolved) ?: continue
                    if (resolvedName !in visited) {
                        val nestedSupertypes = getSupertraitHierarchy(project, resolved, visited, depth + 1)
                        supertypes.add(TypeElementData(
                            name = resolvedName,
                            qualifiedName = getQualifiedName(resolved),
                            file = resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                            line = getLineNumber(project, resolved),
                            kind = "TRAIT",
                            language = "Rust",
                            supertypes = nestedSupertypes.takeIf { it.isNotEmpty() }
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error getting supertraits: ${e.message}")
        }

        return supertypes
    }

    private fun getImplementingTypes(project: Project, trait: PsiElement): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()

        try {
            val scope = GlobalSearchScope.projectScope(project)

            DefinitionsScopedSearch.search(trait, scope).forEach(Processor { definition ->
                if (isRsImpl(definition)) {
                    val typeRef = getTypeReference(definition)
                    if (typeRef != null) {
                        val resolvedType = resolveReference(typeRef)
                        val typeName = if (resolvedType != null) {
                            getName(resolvedType) ?: typeRef.text?.trim()
                        } else {
                            typeRef.text?.trim()
                        }

                        if (typeName != null) {
                            val targetElement = resolvedType ?: definition
                            results.add(TypeElementData(
                                name = typeName,
                                qualifiedName = resolvedType?.let { getQualifiedName(it) },
                                file = targetElement.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                                line = getLineNumber(project, targetElement),
                                kind = if (resolvedType != null) determineElementKind(resolvedType) else "IMPL",
                                language = "Rust"
                            ))
                        }
                    }
                }
                results.size < 100
            })

            LOG.debug("Found ${results.size} implementing types via DefinitionsScopedSearch")
        } catch (e: Exception) {
            LOG.debug("Error getting implementing types: ${e.message}")
        }

        return results
    }

    private fun getTypeImplHierarchy(project: Project, type: PsiElement): TypeHierarchyData {
        val implementedTraits = findImplementedTraits(project, type)

        return TypeHierarchyData(
            element = TypeElementData(
                name = getName(type) ?: "unknown",
                qualifiedName = getQualifiedName(type),
                file = type.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, type),
                kind = determineElementKind(type),
                language = "Rust"
            ),
            supertypes = implementedTraits,  // Implemented traits shown as "supertypes"
            subtypes = emptyList()           // Rust has no type inheritance
        )
    }

    private fun findImplementedTraits(project: Project, type: PsiElement): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()

        try {
            val scope = GlobalSearchScope.projectScope(project)

            // Search for references to this type to find impl blocks
            ReferencesSearch.search(type, scope).forEach(Processor { reference ->
                val impl = findContainingRsImpl(reference.element)
                if (impl != null) {
                    val traitRef = getTraitRef(impl)
                    if (traitRef != null) {
                        val resolvedTrait = resolveReference(traitRef)
                        val traitName = if (resolvedTrait != null) {
                            getName(resolvedTrait)
                        } else {
                            traitRef.text?.trim()
                        }

                        if (traitName != null && results.none { it.name == traitName }) {
                            val targetElement = resolvedTrait ?: impl
                            results.add(TypeElementData(
                                name = traitName,
                                qualifiedName = resolvedTrait?.let { getQualifiedName(it) },
                                file = targetElement.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                                line = getLineNumber(project, targetElement),
                                kind = "TRAIT",
                                language = "Rust"
                            ))
                        }
                    }
                }
                results.size < 100
            })
        } catch (e: Exception) {
            LOG.debug("Error finding implemented traits: ${e.message}")
        }

        return results
    }

    private fun getImplHierarchy(project: Project, impl: PsiElement): TypeHierarchyData {
        val traitRef = getTraitRef(impl)
        val typeRef = getTypeReference(impl)

        val supertypes = mutableListOf<TypeElementData>()
        val subtypes = mutableListOf<TypeElementData>()

        // If implementing a trait, show the trait as a supertype
        if (traitRef != null) {
            val resolvedTrait = resolveReference(traitRef)
            val traitName = if (resolvedTrait != null) getName(resolvedTrait) else traitRef.text?.trim()
            if (traitName != null) {
                val targetElement = resolvedTrait ?: impl
                supertypes.add(TypeElementData(
                    name = traitName,
                    qualifiedName = resolvedTrait?.let { getQualifiedName(it) },
                    file = targetElement.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, targetElement),
                    kind = "TRAIT",
                    language = "Rust"
                ))
            }
        }

        // Show the implementing type as a subtype
        if (typeRef != null) {
            val resolvedType = resolveReference(typeRef)
            val typeName = if (resolvedType != null) getName(resolvedType) else typeRef.text?.trim()
            if (typeName != null) {
                val targetElement = resolvedType ?: impl
                subtypes.add(TypeElementData(
                    name = typeName,
                    qualifiedName = resolvedType?.let { getQualifiedName(it) },
                    file = targetElement.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, targetElement),
                    kind = if (resolvedType != null) determineElementKind(resolvedType) else "TYPE",
                    language = "Rust"
                ))
            }
        }

        val implName = buildImplName(traitRef, typeRef)

        return TypeHierarchyData(
            element = TypeElementData(
                name = implName,
                qualifiedName = null,
                file = impl.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, impl),
                kind = "IMPL",
                language = "Rust"
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun buildImplName(traitRef: PsiElement?, typeRef: PsiElement?): String {
        val traitName = traitRef?.text?.trim()
        val typeName = typeRef?.text?.trim()

        return when {
            traitName != null && typeName != null -> "impl $traitName for $typeName"
            typeName != null -> "impl $typeName"
            else -> "impl"
        }
    }
}

/**
 * Rust implementation of [ImplementationsHandler].
 *
 * Finds implementations of traits:
 * - For traits: Finds all `impl TraitName for Type` blocks
 * - For trait methods: Finds all implementations of that method
 * - For struct/enum types: Not applicable (Rust has no type inheritance)
 */
class RustImplementationsHandler : BaseRustHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "Rust"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRustLanguage(element)
    }

    override fun isAvailable(): Boolean = RustPluginDetector.isRustPluginAvailable && rsTraitItemClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        LOG.debug("Finding implementations for element at ${element.containingFile?.name}")

        // Check if it's a trait
        val trait = findContainingRsTrait(element)
        if (trait != null) {
            // If element is a method within the trait, find method implementations
            val function = findContainingRsFunction(element)
            if (function != null && isWithinTrait(function)) {
                LOG.debug("Finding method implementations for ${getName(function)}")
                return findMethodImplementations(project, function, trait)
            }
            // Otherwise, find all trait implementations
            LOG.debug("Finding trait implementations for ${getName(trait)}")
            return findTraitImplementations(project, trait)
        }

        // For methods in impl blocks, find the trait method and its implementations
        val function = findContainingRsFunction(element)
        if (function != null) {
            val impl = findContainingRsImpl(function)
            if (impl != null) {
                val traitRef = getTraitRef(impl)
                if (traitRef != null) {
                    val resolvedTrait = resolveReference(traitRef)
                    if (resolvedTrait != null && isRsTrait(resolvedTrait)) {
                        LOG.debug("Finding implementations of trait method ${getName(function)}")
                        return findMethodImplementations(project, function, resolvedTrait)
                    }
                }
            }
        }

        return null
    }

    private fun isWithinTrait(function: PsiElement): Boolean {
        return findContainingRsTrait(function) != null
    }

    private fun findTraitImplementations(project: Project, trait: PsiElement): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()

        try {
            val scope = GlobalSearchScope.projectScope(project)
            val traitName = getName(trait) ?: "unknown"

            DefinitionsScopedSearch.search(trait, scope).forEach(Processor { definition ->
                if (isRsImpl(definition)) {
                    val typeRef = getTypeReference(definition)
                    val file = definition.containingFile?.virtualFile

                    if (file != null) {
                        val typeName = typeRef?.text?.trim() ?: "unknown"
                        results.add(ImplementationData(
                            name = "impl $traitName for $typeName",
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, definition) ?: 0,
                            kind = "IMPL",
                            language = "Rust"
                        ))
                    }
                }
                results.size < 100
            })

            LOG.debug("Found ${results.size} trait implementations")
        } catch (e: Exception) {
            LOG.warn("Error finding trait implementations: ${e.message}")
        }

        return results
    }

    private fun findMethodImplementations(
        project: Project,
        method: PsiElement,
        trait: PsiElement
    ): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()

        try {
            val methodName = getName(method) ?: return emptyList()
            val scope = GlobalSearchScope.projectScope(project)

            // Use DefinitionsScopedSearch to find implementations of this method
            DefinitionsScopedSearch.search(method, scope).forEach(Processor { definition ->
                if (isRsFunction(definition) && definition != method) {
                    val file = definition.containingFile?.virtualFile
                    if (file != null) {
                        val implItem = findContainingRsImpl(definition)
                        val typeName = implItem?.let { getTypeReference(it)?.text?.trim() } ?: ""

                        val displayName = if (typeName.isNotEmpty()) {
                            "$typeName::$methodName"
                        } else {
                            methodName
                        }

                        results.add(ImplementationData(
                            name = displayName,
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, definition) ?: 0,
                            kind = "METHOD",
                            language = "Rust"
                        ))
                    }
                }
                results.size < 100
            })

            LOG.debug("Found ${results.size} method implementations for $methodName")
        } catch (e: Exception) {
            LOG.warn("Error finding method implementations: ${e.message}")
        }

        return results
    }
}

/**
 * Rust implementation of [CallHierarchyHandler].
 *
 * Works similarly to other languages - tracks function/method calls.
 */
class RustCallHierarchyHandler : BaseRustHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
    }

    override val languageId = "Rust"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRustLanguage(element)
    }

    override fun isAvailable(): Boolean = RustPluginDetector.isRustPluginAvailable && rsFunctionClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int
    ): CallHierarchyData? {
        val function = findContainingRsFunction(element) ?: return null
        LOG.debug("Getting call hierarchy for ${getName(function)}, direction=$direction, depth=$depth")

        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") {
            findCallersRecursive(project, function, depth, visited)
        } else {
            findCalleesRecursive(project, function, depth, visited)
        }

        LOG.debug("Found ${calls.size} $direction")

        return CallHierarchyData(
            element = createCallElement(project, function),
            calls = calls
        )
    }

    private fun findCallersRecursive(
        project: Project,
        function: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val key = getFunctionKey(function)
        if (key in visited) return emptyList()
        visited.add(key)

        return try {
            val scope = GlobalSearchScope.projectScope(project)
            val references = mutableListOf<com.intellij.psi.PsiReference>()

            ReferencesSearch.search(function, scope).forEach(Processor { reference ->
                references.add(reference)
                references.size < MAX_RESULTS_PER_LEVEL * 2
            })

            LOG.debug("Found ${references.size} references for ${getName(function)}")

            references.take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val containingFunction = findContainingRsFunction(refElement)
                    if (containingFunction != null && containingFunction != function) {
                        val children = if (depth > 1) {
                            findCallersRecursive(project, containingFunction, depth - 1, visited, stackDepth + 1)
                        } else null
                        createCallElement(project, containingFunction, children)
                    } else null
                }
                .distinctBy { it.name + it.file + it.line }
        } catch (e: Exception) {
            LOG.warn("Error finding callers: ${e.message}")
            emptyList()
        }
    }

    private fun findCalleesRecursive(
        project: Project,
        function: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val key = getFunctionKey(function)
        if (key in visited) return emptyList()
        visited.add(key)

        val callees = mutableListOf<CallElementData>()

        try {
            // Find RsCallExpr and RsMethodCall within the function
            listOfNotNull(rsCallExprClass, rsMethodCallClass).forEach { callClass ->
                @Suppress("UNCHECKED_CAST")
                val calls = PsiTreeUtil.findChildrenOfType(function, callClass as Class<out PsiElement>)

                calls.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                    val resolved = resolveCallExpression(callExpr)
                    if (resolved != null && isRsFunction(resolved)) {
                        val children = if (depth > 1) {
                            findCalleesRecursive(project, resolved, depth - 1, visited, stackDepth + 1)
                        } else null
                        val element = createCallElement(project, resolved, children)
                        if (callees.none { it.name == element.name && it.file == element.file }) {
                            callees.add(element)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error finding callees: ${e.message}")
        }

        return callees
    }

    private fun resolveCallExpression(callExpr: PsiElement): PsiElement? {
        return try {
            // Try different methods to resolve the call
            val methodNames = listOf("getPath", "getExpr", "getMethodName")
            for (methodName in methodNames) {
                try {
                    val method = callExpr.javaClass.getMethod(methodName)
                    val result = method.invoke(callExpr) as? PsiElement
                    if (result != null) {
                        val resolved = resolveReference(result)
                        if (resolved != null && isRsFunction(resolved)) {
                            return resolved
                        }
                    }
                } catch (e: NoSuchMethodException) {
                    continue
                }
            }
            // Fallback to reference resolution
            resolveReference(callExpr)
        } catch (e: Exception) {
            null
        }
    }

    private fun getFunctionKey(function: PsiElement): String {
        val name = getName(function) ?: ""
        val file = function.containingFile?.virtualFile?.path ?: ""
        val line = getLineNumber(function.project, function) ?: 0
        return "$file:$line:$name"
    }

    private fun createCallElement(
        project: Project,
        function: PsiElement,
        children: List<CallElementData>? = null
    ): CallElementData {
        val file = function.containingFile?.virtualFile
        val name = buildFunctionName(function)

        return CallElementData(
            name = name,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, function) ?: 0,
            language = "Rust",
            children = children?.takeIf { it.isNotEmpty() }
        )
    }

    private fun buildFunctionName(function: PsiElement): String {
        val functionName = getName(function) ?: "unknown"

        // Check if it's a method in an impl block
        val impl = findContainingRsImpl(function)
        if (impl != null) {
            val typeRef = getTypeReference(impl)
            val typeName = typeRef?.text?.trim()?.substringBefore('<')  // Remove generics for display
            if (typeName != null) {
                return "$typeName::$functionName"
            }
        }

        return functionName
    }
}

/**
 * Rust implementation of [SymbolSearchHandler].
 *
 * Uses the optimized [OptimizedSymbolSearch] infrastructure which leverages IntelliJ's
 * built-in "Go to Symbol" APIs with caching, word index, and prefix matching.
 */
class RustSymbolSearchHandler : BaseRustHandler<List<SymbolData>>(), SymbolSearchHandler {

    override val languageId = "Rust"

    override fun canHandle(element: PsiElement): Boolean = isAvailable()

    override fun isAvailable(): Boolean = RustPluginDetector.isRustPluginAvailable && rsFileClass != null

    override fun searchSymbols(
        project: Project,
        pattern: String,
        includeLibraries: Boolean,
        limit: Int
    ): List<SymbolData> {
        val scope = if (includeLibraries) {
            GlobalSearchScope.allScope(project)
        } else {
            GlobalSearchScope.projectScope(project)
        }

        // Use the optimized platform-based search with language filter for Rust
        return OptimizedSymbolSearch.search(
            project = project,
            pattern = pattern,
            scope = scope,
            limit = limit,
            languageFilter = setOf("Rust")
        )
    }
}

/**
 * Rust implementation of [SuperMethodsHandler].
 *
 * Finds the trait method that a given implementation overrides.
 *
 * **Rust-Specific Semantics:**
 * - Methods in `impl Trait for Type` blocks implement trait methods
 * - The "super method" is the method declaration in the trait
 * - Supertraits create a chain of method declarations
 *
 * **Limitations:**
 * - Only applicable for methods in impl blocks that implement a trait
 * - Standalone functions and inherent impl methods have no "super methods"
 */
class RustSuperMethodsHandler : BaseRustHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "Rust"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRustLanguage(element)
    }

    override fun isAvailable(): Boolean = RustPluginDetector.isRustPluginAvailable && rsFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val function = findContainingRsFunction(element) ?: return null
        LOG.debug("Finding super methods for ${getName(function)}")

        // Check if it's directly in a trait (then it's the declaration, not implementation)
        val containingTrait = findContainingRsTrait(function)
        if (containingTrait != null) {
            // This is a trait method declaration - check supertraits
            return findSuperMethodsFromTrait(project, function, containingTrait)
        }

        // Check if it's in an impl block
        val implItem = findContainingRsImpl(function)
        if (implItem == null) {
            // Standalone function - no super methods
            return null
        }

        val traitRef = getTraitRef(implItem)
        if (traitRef == null) {
            // Inherent impl (no trait) - no super methods
            return null
        }

        val trait = resolveReference(traitRef)
        if (trait == null || !isRsTrait(trait)) {
            return null
        }

        val methodName = getName(function) ?: return null
        val file = function.containingFile?.virtualFile

        val typeName = getTypeReference(implItem)?.text?.trim() ?: "unknown"

        val methodData = MethodData(
            name = methodName,
            signature = buildMethodSignature(function),
            containingClass = typeName,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, function) ?: 0,
            language = "Rust"
        )

        val hierarchy = buildHierarchy(project, trait, methodName, mutableSetOf())
        LOG.debug("Found ${hierarchy.size} super methods")

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun findSuperMethodsFromTrait(
        project: Project,
        function: PsiElement,
        trait: PsiElement
    ): SuperMethodsData? {
        val methodName = getName(function) ?: return null
        val file = function.containingFile?.virtualFile
        val traitName = getName(trait) ?: "unknown"

        val methodData = MethodData(
            name = methodName,
            signature = buildMethodSignature(function),
            containingClass = traitName,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, function) ?: 0,
            language = "Rust"
        )

        // Find in supertraits
        val hierarchy = mutableListOf<SuperMethodData>()
        val superTraits = getSuperTraits(trait) ?: emptyList()

        for (superTraitRef in superTraits) {
            val superTrait = resolveReference(superTraitRef)
            if (superTrait != null && isRsTrait(superTrait)) {
                hierarchy.addAll(buildHierarchy(project, superTrait, methodName, mutableSetOf()))
            }
        }

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        trait: PsiElement,
        methodName: String,
        visited: MutableSet<String>,
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        val traitName = getName(trait) ?: return emptyList()
        if (traitName in visited) return emptyList()
        visited.add(traitName)

        // Find the method in this trait
        val traitMethod = findMethodInTrait(trait, methodName)
        if (traitMethod != null) {
            val file = traitMethod.containingFile?.virtualFile
            hierarchy.add(SuperMethodData(
                name = methodName,
                signature = buildMethodSignature(traitMethod),
                containingClass = traitName,
                containingClassKind = "TRAIT",
                file = file?.let { getRelativePath(project, it) },
                line = getLineNumber(project, traitMethod),
                isInterface = true,  // Traits are like interfaces
                depth = depth,
                language = "Rust"
            ))
        }

        // Check supertraits
        val superTraits = getSuperTraits(trait) ?: emptyList()
        for (superTraitRef in superTraits) {
            val superTrait = resolveReference(superTraitRef)
            if (superTrait != null && isRsTrait(superTrait)) {
                hierarchy.addAll(buildHierarchy(project, superTrait, methodName, visited, depth + 1))
            }
        }

        return hierarchy
    }

    private fun findMethodInTrait(trait: PsiElement, methodName: String): PsiElement? {
        val functions = getFunctions(trait) ?: return null
        return functions.find { getName(it) == methodName }
    }

    private fun buildMethodSignature(function: PsiElement): String {
        return try {
            // Try to get the value parameter list
            val methodNames = listOf("getValueParameterList", "getParameterList")
            for (methodName in methodNames) {
                try {
                    val method = function.javaClass.getMethod(methodName)
                    val paramList = method.invoke(function) as? PsiElement
                    if (paramList != null) {
                        val params = paramList.text ?: "()"
                        val name = getName(function) ?: "unknown"
                        return "fn $name$params"
                    }
                } catch (e: NoSuchMethodException) {
                    continue
                }
            }
            "fn ${getName(function) ?: "unknown"}()"
        } catch (e: Exception) {
            "fn ${getName(function) ?: "unknown"}()"
        }
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.php

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PhpPluginDetector
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Registration entry point for PHP language handlers.
 *
 * This class is loaded via reflection when the PHP plugin is available.
 * It registers all PHP-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## PHP PSI Classes Used (via reflection)
 *
 * - `com.jetbrains.php.lang.psi.elements.PhpClass` - PHP class declarations
 * - `com.jetbrains.php.lang.psi.elements.Method` - PHP method declarations
 * - `com.jetbrains.php.lang.psi.elements.Function` - PHP function declarations
 * - `com.jetbrains.php.lang.psi.elements.Field` - PHP class field/property
 * - `com.jetbrains.php.lang.psi.elements.PhpNamedElement` - Any named PHP element
 * - `com.jetbrains.php.lang.psi.elements.MethodReference` - Method call reference
 * - `com.jetbrains.php.lang.psi.elements.FunctionReference` - Function call reference
 *
 * ## PHP-Specific Concepts
 *
 * - **Single Inheritance**: PHP uses single class inheritance with interfaces
 * - **Traits**: PHP traits are similar to mixins, shown in hierarchy
 * - **Interfaces**: Full interface/implementation support
 * - **Late Static Binding**: Properly handled by PhpStorm's type inference
 */
object PhpHandlers {

    private val LOG = logger<PhpHandlers>()

    /**
     * Registers all PHP handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!PhpPluginDetector.isPhpPluginAvailable) {
            LOG.info("PHP plugin not available, skipping PHP handler registration")
            return
        }

        try {
            // Verify PHP classes are accessible before registering
            Class.forName("com.jetbrains.php.lang.psi.elements.PhpClass")
            Class.forName("com.jetbrains.php.lang.psi.elements.Method")

            registry.registerTypeHierarchyHandler(PhpTypeHierarchyHandler())
            registry.registerImplementationsHandler(PhpImplementationsHandler())
            registry.registerCallHierarchyHandler(PhpCallHierarchyHandler())
            registry.registerSymbolSearchHandler(PhpSymbolSearchHandler())
            registry.registerSuperMethodsHandler(PhpSuperMethodsHandler())

            LOG.info("Registered PHP handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("PHP PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register PHP handlers: ${e.message}")
        }
    }
}

/**
 * Base class for PHP handlers with common utilities.
 *
 * Uses reflection to access PHP PSI classes to avoid compile-time dependencies.
 */
abstract class BasePhpHandler<T> : LanguageHandler<T> {

    protected val LOG = logger<BasePhpHandler<*>>()

    /**
     * Checks if the element is from PHP language.
     */
    protected fun isPhpLanguage(element: PsiElement): Boolean {
        return element.language.id == "PHP"
    }

    // Lazy-loaded PHP PSI classes via reflection

    protected val phpClassClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.php.lang.psi.elements.PhpClass")
        } catch (e: ClassNotFoundException) {
            LOG.debug("PhpClass not found")
            null
        }
    }

    protected val methodClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.php.lang.psi.elements.Method")
        } catch (e: ClassNotFoundException) {
            LOG.debug("Method not found")
            null
        }
    }

    protected val functionClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.php.lang.psi.elements.Function")
        } catch (e: ClassNotFoundException) {
            LOG.debug("Function not found")
            null
        }
    }

    protected val fieldClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.php.lang.psi.elements.Field")
        } catch (e: ClassNotFoundException) {
            LOG.debug("Field not found")
            null
        }
    }

    protected val phpNamedElementClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.php.lang.psi.elements.PhpNamedElement")
        } catch (e: ClassNotFoundException) {
            LOG.debug("PhpNamedElement not found")
            null
        }
    }

    protected val methodReferenceClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.php.lang.psi.elements.MethodReference")
        } catch (e: ClassNotFoundException) {
            LOG.debug("MethodReference not found")
            null
        }
    }

    protected val functionReferenceClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.php.lang.psi.elements.FunctionReference")
        } catch (e: ClassNotFoundException) {
            LOG.debug("FunctionReference not found")
            null
        }
    }

    /**
     * PhpIndex class for accessing PHP symbols and their relationships.
     * This is the central API for finding subclasses/implementations in PHP.
     */
    protected val phpIndexClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.php.PhpIndex")
        } catch (e: ClassNotFoundException) {
            LOG.debug("PhpIndex not found")
            null
        }
    }

    // Helper methods

    /**
     * Gets the PhpIndex instance for the given project.
     * PhpIndex is the central API for accessing PHP symbols.
     */
    protected fun getPhpIndex(project: Project): Any? {
        return try {
            val phpIndexCls = phpIndexClass ?: return null
            val getInstanceMethod = phpIndexCls.getMethod("getInstance", Project::class.java)
            getInstanceMethod.invoke(null, project)
        } catch (e: Exception) {
            LOG.debug("Error getting PhpIndex instance: ${e.message}")
            null
        }
    }

    /**
     * Gets all subclasses/implementations of a PHP class or interface using PhpIndex.
     *
     * @param project The current project
     * @param fqn The fully qualified name of the class/interface (e.g., "\App\Contracts\Describable")
     * @return Collection of PhpClass elements that extend/implement the given class/interface
     */
    protected fun getAllSubclasses(project: Project, fqn: String): Collection<PsiElement> {
        return try {
            val phpIndex = getPhpIndex(project) ?: return emptyList()

            val getAllSubclassesMethod = phpIndex.javaClass.getMethod("getAllSubclasses", String::class.java)
            val result = getAllSubclassesMethod.invoke(phpIndex, fqn)

            @Suppress("UNCHECKED_CAST")
            (result as? Collection<*>)?.filterIsInstance<PsiElement>() ?: emptyList()
        } catch (e: Exception) {
            LOG.debug("Error getting subclasses for $fqn: ${e.message}")
            emptyList()
        }
    }

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return file.path.removePrefix(basePath).removePrefix("/")
    }

    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    /**
     * Checks if element is a PhpClass using reflection.
     */
    protected fun isPhpClass(element: PsiElement): Boolean {
        return phpClassClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a Method using reflection.
     */
    protected fun isMethod(element: PsiElement): Boolean {
        return methodClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a Function using reflection.
     */
    protected fun isFunction(element: PsiElement): Boolean {
        return functionClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a Field using reflection.
     */
    protected fun isField(element: PsiElement): Boolean {
        return fieldClass?.isInstance(element) == true
    }

    /**
     * Finds containing PhpClass using reflection.
     */
    protected fun findContainingPhpClass(element: PsiElement): PsiElement? {
        if (isPhpClass(element)) return element
        val phpClass = phpClassClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, phpClass as Class<out PsiElement>)
    }

    /**
     * Finds containing Method using reflection.
     */
    protected fun findContainingMethod(element: PsiElement): PsiElement? {
        if (isMethod(element)) return element
        val method = methodClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, method as Class<out PsiElement>)
    }

    /**
     * Finds containing Function using reflection.
     */
    protected fun findContainingFunction(element: PsiElement): PsiElement? {
        if (isFunction(element)) return element
        val function = functionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, function as Class<out PsiElement>)
    }

    /**
     * Finds containing method or function.
     */
    protected fun findContainingCallable(element: PsiElement): PsiElement? {
        return findContainingMethod(element) ?: findContainingFunction(element)
    }

    /**
     * Gets the name of a PHP element via reflection.
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
     * Gets the FQN (Fully Qualified Name) of a PhpClass via reflection.
     */
    protected fun getFQN(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getFQN")
            method.invoke(element) as? String
        } catch (e: Exception) {
            // Fallback to just the name
            getName(element)
        }
    }

    /**
     * Gets the superclass of a PhpClass via reflection.
     */
    protected fun getSuperClass(phpClass: PsiElement): PsiElement? {
        return try {
            val method = phpClass.javaClass.getMethod("getSuperClass")
            method.invoke(phpClass) as? PsiElement
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets implemented interfaces of a PhpClass via reflection.
     */
    protected fun getImplementedInterfaces(phpClass: PsiElement): Array<*>? {
        return try {
            val method = phpClass.javaClass.getMethod("getImplementedInterfaces")
            method.invoke(phpClass) as? Array<*>
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets traits used by a PhpClass via reflection.
     */
    protected fun getTraits(phpClass: PsiElement): Array<*>? {
        return try {
            val method = phpClass.javaClass.getMethod("getTraits")
            method.invoke(phpClass) as? Array<*>
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if a PhpClass is an interface via reflection.
     */
    protected fun isInterface(phpClass: PsiElement): Boolean {
        return try {
            val method = phpClass.javaClass.getMethod("isInterface")
            method.invoke(phpClass) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if a PhpClass is a trait via reflection.
     */
    protected fun isTrait(phpClass: PsiElement): Boolean {
        return try {
            val method = phpClass.javaClass.getMethod("isTrait")
            method.invoke(phpClass) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if a PhpClass is abstract via reflection.
     */
    protected fun isAbstract(phpClass: PsiElement): Boolean {
        return try {
            val method = phpClass.javaClass.getMethod("isAbstract")
            method.invoke(phpClass) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets the containing class of a method via reflection.
     */
    protected fun getContainingClass(method: PsiElement): PsiElement? {
        return try {
            val getContainingClassMethod = method.javaClass.getMethod("getContainingClass")
            getContainingClassMethod.invoke(method) as? PsiElement
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Determines the kind of a PHP class element.
     */
    protected fun determineClassKind(element: PsiElement): String {
        return when {
            isInterface(element) -> "INTERFACE"
            isTrait(element) -> "TRAIT"
            isAbstract(element) -> "ABSTRACT_CLASS"
            else -> "CLASS"
        }
    }

    /**
     * Determines the kind of a PHP element.
     */
    protected fun determineElementKind(element: PsiElement): String {
        return when {
            isPhpClass(element) -> determineClassKind(element)
            isMethod(element) -> "METHOD"
            isFunction(element) -> "FUNCTION"
            isField(element) -> "FIELD"
            else -> "SYMBOL"
        }
    }

    /**
     * Finds a method by name in a PhpClass using reflection.
     * Uses `findMethodByName()` API, falling back to searching `getOwnMethods()`.
     */
    protected fun findMethodInClass(phpClass: PsiElement, methodName: String): PsiElement? {
        return try {
            val findMethodByNameMethod = phpClass.javaClass.getMethod("findMethodByName", String::class.java)
            findMethodByNameMethod.invoke(phpClass, methodName) as? PsiElement
        } catch (e: Exception) {
            // Fallback to getOwnMethods and search manually
            try {
                val getMethodsMethod = phpClass.javaClass.getMethod("getOwnMethods")
                val methods = getMethodsMethod.invoke(phpClass) as? Array<*> ?: return null
                methods.filterIsInstance<PsiElement>().find { getName(it) == methodName }
            } catch (e2: Exception) {
                null
            }
        }
    }
}

/**
 * PHP implementation of [TypeHierarchyHandler].
 *
 * Handles PHP-specific type relationships:
 * - Single class inheritance (extends)
 * - Interface implementation (implements)
 * - Trait usage (use)
 */
class PhpTypeHierarchyHandler : BasePhpHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 50
    }

    override val languageId = "PHP"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPhpLanguage(element)
    }

    override fun isAvailable(): Boolean = PhpPluginDetector.isPhpPluginAvailable && phpClassClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val phpClass = findContainingPhpClass(element) ?: return null
        LOG.debug("Getting type hierarchy for PHP class: ${getName(phpClass)}")

        val supertypes = getSupertypes(project, phpClass)
        val subtypes = getSubtypes(project, phpClass)

        LOG.debug("Found ${supertypes.size} supertypes and ${subtypes.size} subtypes")

        return TypeHierarchyData(
            element = TypeElementData(
                name = getFQN(phpClass) ?: getName(phpClass) ?: "unknown",
                qualifiedName = getFQN(phpClass),
                file = phpClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, phpClass),
                kind = determineClassKind(phpClass),
                language = "PHP"
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun getSupertypes(
        project: Project,
        phpClass: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val className = getFQN(phpClass) ?: getName(phpClass) ?: return emptyList()
        if (className in visited) return emptyList()
        visited.add(className)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            // Get superclass (extends)
            val superClass = getSuperClass(phpClass)
            if (superClass != null) {
                val superName = getFQN(superClass) ?: getName(superClass)
                if (superName != null && superName !in visited) {
                    val superSupertypes = getSupertypes(project, superClass, visited, depth + 1)
                    supertypes.add(TypeElementData(
                        name = superName,
                        qualifiedName = getFQN(superClass),
                        file = superClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superClass),
                        kind = determineClassKind(superClass),
                        language = "PHP",
                        supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                    ))
                }
            }

            // Get implemented interfaces
            val interfaces = getImplementedInterfaces(phpClass)
            interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getFQN(iface) ?: getName(iface)
                if (ifaceName != null && ifaceName !in visited) {
                    val ifaceSupertypes = getSupertypes(project, iface, visited, depth + 1)
                    supertypes.add(TypeElementData(
                        name = ifaceName,
                        qualifiedName = getFQN(iface),
                        file = iface.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, iface),
                        kind = "INTERFACE",
                        language = "PHP",
                        supertypes = ifaceSupertypes.takeIf { it.isNotEmpty() }
                    ))
                }
            }

            // Get used traits
            val traits = getTraits(phpClass)
            traits?.filterIsInstance<PsiElement>()?.forEach { trait ->
                val traitName = getFQN(trait) ?: getName(trait)
                if (traitName != null && traitName !in visited) {
                    supertypes.add(TypeElementData(
                        name = traitName,
                        qualifiedName = getFQN(trait),
                        file = trait.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, trait),
                        kind = "TRAIT",
                        language = "PHP"
                    ))
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error getting supertypes: ${e.message}")
        }

        return supertypes
    }

    private fun getSubtypes(project: Project, phpClass: PsiElement): List<TypeElementData> {
        return try {
            val fqn = getFQN(phpClass) ?: return emptyList()
            val results = mutableListOf<TypeElementData>()

            // Use PhpIndex.getAllSubclasses() - the correct API for finding PHP subclasses
            val subclasses = getAllSubclasses(project, fqn)

            subclasses.take(100).forEach { subclass ->
                results.add(TypeElementData(
                    name = getFQN(subclass) ?: getName(subclass) ?: "unknown",
                    qualifiedName = getFQN(subclass),
                    file = subclass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, subclass),
                    kind = determineClassKind(subclass),
                    language = "PHP"
                ))
            }

            LOG.debug("Found ${results.size} subtypes for $fqn using PhpIndex")
            results
        } catch (e: Exception) {
            LOG.warn("Error getting subtypes: ${e.message}")
            emptyList()
        }
    }
}

/**
 * PHP implementation of [ImplementationsHandler].
 *
 * Finds implementations of:
 * - Interfaces (classes that implement the interface)
 * - Abstract classes (classes that extend the abstract class)
 * - Abstract/interface methods (concrete method implementations)
 */
class PhpImplementationsHandler : BasePhpHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "PHP"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPhpLanguage(element)
    }

    override fun isAvailable(): Boolean = PhpPluginDetector.isPhpPluginAvailable && phpClassClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        LOG.debug("Finding implementations for element at ${element.containingFile?.name}")

        // Check if it's a method
        val method = findContainingMethod(element)
        if (method != null) {
            LOG.debug("Finding method implementations for ${getName(method)}")
            return findMethodImplementations(project, method)
        }

        // Check if it's a class/interface
        val phpClass = findContainingPhpClass(element)
        if (phpClass != null) {
            LOG.debug("Finding class implementations for ${getName(phpClass)}")
            return findClassImplementations(project, phpClass)
        }

        return null
    }

    private fun findMethodImplementations(project: Project, method: PsiElement): List<ImplementationData> {
        return try {
            val methodName = getName(method) ?: return emptyList()
            val containingClass = getContainingClass(method) ?: return emptyList()
            val classFqn = getFQN(containingClass) ?: return emptyList()

            val results = mutableListOf<ImplementationData>()

            // Use PhpIndex to get all subclasses, then find methods with the same name
            val subclasses = getAllSubclasses(project, classFqn)

            subclasses.take(100).forEach { subclass ->
                // Find method with same name in this subclass
                val overridingMethod = findMethodInClass(subclass, methodName)
                if (overridingMethod != null) {
                    val file = overridingMethod.containingFile?.virtualFile
                    if (file != null) {
                        val className = getName(subclass) ?: ""
                        results.add(ImplementationData(
                            name = if (className.isNotEmpty()) "$className::$methodName" else methodName,
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, overridingMethod) ?: 0,
                            kind = "METHOD",
                            language = "PHP"
                        ))
                    }
                }
            }

            LOG.debug("Found ${results.size} method implementations for $methodName in $classFqn using PhpIndex")
            results
        } catch (e: Exception) {
            LOG.warn("Error finding method implementations: ${e.message}")
            emptyList()
        }
    }

    private fun findClassImplementations(project: Project, phpClass: PsiElement): List<ImplementationData> {
        return try {
            val fqn = getFQN(phpClass) ?: return emptyList()
            val results = mutableListOf<ImplementationData>()

            // Use PhpIndex.getAllSubclasses() - the correct API for finding PHP implementations
            val subclasses = getAllSubclasses(project, fqn)

            subclasses.take(100).forEach { subclass ->
                val file = subclass.containingFile?.virtualFile
                if (file != null) {
                    results.add(ImplementationData(
                        name = getFQN(subclass) ?: getName(subclass) ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, subclass) ?: 0,
                        kind = determineClassKind(subclass),
                        language = "PHP"
                    ))
                }
            }

            LOG.debug("Found ${results.size} implementations for $fqn using PhpIndex")
            results
        } catch (e: Exception) {
            LOG.warn("Error finding class implementations: ${e.message}")
            emptyList()
        }
    }
}

/**
 * PHP implementation of [CallHierarchyHandler].
 */
class PhpCallHierarchyHandler : BasePhpHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
        private const val MAX_SUPER_METHODS = 10
    }

    override val languageId = "PHP"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPhpLanguage(element)
    }

    override fun isAvailable(): Boolean = PhpPluginDetector.isPhpPluginAvailable && methodClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int
    ): CallHierarchyData? {
        val callable = findContainingCallable(element) ?: return null
        LOG.debug("Getting call hierarchy for ${getName(callable)}, direction=$direction, depth=$depth")

        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") {
            findCallersRecursive(project, callable, depth, visited)
        } else {
            findCalleesRecursive(project, callable, depth, visited)
        }

        LOG.debug("Found ${calls.size} $direction")

        return CallHierarchyData(
            element = createCallElement(project, callable),
            calls = calls
        )
    }

    /**
     * Finds all super methods that the given method overrides.
     * This handles polymorphism - callers of base methods could dispatch to this method.
     */
    private fun findAllSuperMethods(project: Project, method: PsiElement): Set<PsiElement> {
        if (!isMethod(method)) return emptySet()

        val superMethods = mutableSetOf<PsiElement>()
        val visited = mutableSetOf<String>()
        findSuperMethodsRecursive(project, method, superMethods, visited)
        return superMethods.take(MAX_SUPER_METHODS).toSet()
    }

    private fun findSuperMethodsRecursive(
        project: Project,
        method: PsiElement,
        result: MutableSet<PsiElement>,
        visited: MutableSet<String>
    ) {
        val containingClass = getContainingClass(method) ?: return
        val methodName = getName(method) ?: return

        // Check superclass
        val superClass = getSuperClass(containingClass)
        if (superClass != null) {
            val superClassName = getFQN(superClass) ?: getName(superClass)
            val key = "$superClassName::$methodName"
            if (key !in visited) {
                visited.add(key)
                val superMethod = findMethodInClass(superClass, methodName)
                if (superMethod != null) {
                    result.add(superMethod)
                    findSuperMethodsRecursive(project, superMethod, result, visited)
                }
            }
        }

        // Check interfaces
        val interfaces = getImplementedInterfaces(containingClass)
        interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
            val ifaceName = getFQN(iface) ?: getName(iface)
            val key = "$ifaceName::$methodName"
            if (key !in visited) {
                visited.add(key)
                val ifaceMethod = findMethodInClass(iface, methodName)
                if (ifaceMethod != null) {
                    result.add(ifaceMethod)
                }
            }
        }
    }

    private fun findCallersRecursive(
        project: Project,
        callable: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val callableKey = getCallableKey(callable)
        if (callableKey in visited) return emptyList()
        visited.add(callableKey)

        return try {
            // Collect all methods to search: current method + all super methods
            val methodsToSearch = mutableSetOf(callable)
            if (isMethod(callable)) {
                methodsToSearch.addAll(findAllSuperMethods(project, callable))
            }

            val scope = GlobalSearchScope.projectScope(project)
            val allReferences = mutableListOf<com.intellij.psi.PsiReference>()

            for (methodToSearch in methodsToSearch) {
                ReferencesSearch.search(methodToSearch, scope).forEach(Processor { reference ->
                    allReferences.add(reference)
                    allReferences.size < MAX_RESULTS_PER_LEVEL * 2
                })
            }

            LOG.debug("Found ${allReferences.size} references for ${getName(callable)}")

            allReferences.take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val containingCallable = findContainingCallable(refElement)
                    if (containingCallable != null && containingCallable != callable && containingCallable !in methodsToSearch) {
                        val children = if (depth > 1) {
                            findCallersRecursive(project, containingCallable, depth - 1, visited, stackDepth + 1)
                        } else null
                        createCallElement(project, containingCallable, children)
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
        callable: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val callableKey = getCallableKey(callable)
        if (callableKey in visited) return emptyList()
        visited.add(callableKey)

        val callees = mutableListOf<CallElementData>()
        try {
            // Find MethodReference and FunctionReference within the callable
            val methodRef = methodReferenceClass
            val functionRef = functionReferenceClass

            if (methodRef != null) {
                @Suppress("UNCHECKED_CAST")
                val methodCalls = PsiTreeUtil.findChildrenOfType(callable, methodRef as Class<out PsiElement>)
                methodCalls.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                    val calledMethod = resolveReference(callExpr)
                    if (calledMethod != null && (isMethod(calledMethod) || isFunction(calledMethod))) {
                        val children = if (depth > 1) {
                            findCalleesRecursive(project, calledMethod, depth - 1, visited, stackDepth + 1)
                        } else null
                        val element = createCallElement(project, calledMethod, children)
                        if (callees.none { it.name == element.name && it.file == element.file }) {
                            callees.add(element)
                        }
                    }
                }
            }

            if (functionRef != null) {
                @Suppress("UNCHECKED_CAST")
                val functionCalls = PsiTreeUtil.findChildrenOfType(callable, functionRef as Class<out PsiElement>)
                functionCalls.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                    val calledFunction = resolveReference(callExpr)
                    if (calledFunction != null && isFunction(calledFunction)) {
                        val children = if (depth > 1) {
                            findCalleesRecursive(project, calledFunction, depth - 1, visited, stackDepth + 1)
                        } else null
                        val element = createCallElement(project, calledFunction, children)
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

    private fun resolveReference(reference: PsiElement): PsiElement? {
        return try {
            val resolveMethod = reference.javaClass.getMethod("resolve")
            resolveMethod.invoke(reference) as? PsiElement
        } catch (e: Exception) {
            null
        }
    }

    private fun getCallableKey(callable: PsiElement): String {
        val containingClass = if (isMethod(callable)) getContainingClass(callable) else null
        val className = containingClass?.let { getFQN(it) ?: getName(it) } ?: ""
        val callableName = getName(callable) ?: ""
        return "$className::$callableName"
    }

    private fun createCallElement(project: Project, callable: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val file = callable.containingFile?.virtualFile
        val callableName = getName(callable) ?: "unknown"

        val name = if (isMethod(callable)) {
            val containingClass = getContainingClass(callable)
            val className = containingClass?.let { getName(it) }
            if (className != null) "$className::$callableName" else callableName
        } else {
            callableName
        }

        return CallElementData(
            name = name,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, callable) ?: 0,
            language = "PHP",
            children = children?.takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * PHP implementation of [SymbolSearchHandler].
 *
 * Uses the optimized [OptimizedSymbolSearch] infrastructure which leverages IntelliJ's
 * built-in "Go to Symbol" APIs with caching, word index, and prefix matching.
 */
class PhpSymbolSearchHandler : BasePhpHandler<List<SymbolData>>(), SymbolSearchHandler {

    override val languageId = "PHP"

    override fun canHandle(element: PsiElement): Boolean = isAvailable()

    override fun isAvailable(): Boolean = PhpPluginDetector.isPhpPluginAvailable && phpClassClass != null

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

        // Use the optimized platform-based search with language filter for PHP
        return OptimizedSymbolSearch.search(
            project = project,
            pattern = pattern,
            scope = scope,
            limit = limit,
            languageFilter = setOf("PHP")
        )
    }
}

/**
 * PHP implementation of [SuperMethodsHandler].
 *
 * Finds all parent methods that a method overrides or implements:
 * - Methods from parent classes (extends)
 * - Methods from implemented interfaces (implements)
 */
class PhpSuperMethodsHandler : BasePhpHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "PHP"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPhpLanguage(element)
    }

    override fun isAvailable(): Boolean = PhpPluginDetector.isPhpPluginAvailable && methodClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val method = findContainingMethod(element) ?: return null
        val containingClass = getContainingClass(method) ?: return null

        LOG.debug("Finding super methods for ${getName(method)}")

        val file = method.containingFile?.virtualFile
        val methodData = MethodData(
            name = getName(method) ?: "unknown",
            signature = buildMethodSignature(method),
            containingClass = getFQN(containingClass) ?: getName(containingClass) ?: "unknown",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, method) ?: 0,
            language = "PHP"
        )

        val hierarchy = buildHierarchy(project, method)
        LOG.debug("Found ${hierarchy.size} super methods")

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        method: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        try {
            val containingClass = getContainingClass(method) ?: return emptyList()
            val methodName = getName(method) ?: return emptyList()

            // Check superclass
            val superClass = getSuperClass(containingClass)
            if (superClass != null) {
                val superClassName = getFQN(superClass) ?: getName(superClass)
                val key = "$superClassName::$methodName"
                if (key !in visited) {
                    visited.add(key)

                    val superMethod = findMethodInClass(superClass, methodName)
                    if (superMethod != null) {
                        val file = superMethod.containingFile?.virtualFile

                        hierarchy.add(SuperMethodData(
                            name = methodName,
                            signature = buildMethodSignature(superMethod),
                            containingClass = superClassName ?: "unknown",
                            containingClassKind = determineClassKind(superClass),
                            file = file?.let { getRelativePath(project, it) },
                            line = getLineNumber(project, superMethod),
                            isInterface = isInterface(superClass),
                            depth = depth,
                            language = "PHP"
                        ))

                        hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
                    }
                }
            }

            // Check interfaces
            val interfaces = getImplementedInterfaces(containingClass)
            interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getFQN(iface) ?: getName(iface)
                val key = "$ifaceName::$methodName"
                if (key !in visited) {
                    visited.add(key)

                    val ifaceMethod = findMethodInClass(iface, methodName)
                    if (ifaceMethod != null) {
                        val file = ifaceMethod.containingFile?.virtualFile

                        hierarchy.add(SuperMethodData(
                            name = methodName,
                            signature = buildMethodSignature(ifaceMethod),
                            containingClass = ifaceName ?: "unknown",
                            containingClassKind = "INTERFACE",
                            file = file?.let { getRelativePath(project, it) },
                            line = getLineNumber(project, ifaceMethod),
                            isInterface = true,
                            depth = depth,
                            language = "PHP"
                        ))

                        // Interfaces can extend other interfaces
                        hierarchy.addAll(buildHierarchy(project, ifaceMethod, visited, depth + 1))
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error building hierarchy: ${e.message}")
        }

        return hierarchy
    }

    private fun buildMethodSignature(method: PsiElement): String {
        return try {
            // Try to get parameters
            val getParametersMethod = method.javaClass.getMethod("getParameters")
            val parameters = getParametersMethod.invoke(method) as? Array<*> ?: emptyArray<Any>()

            val params = parameters.filterIsInstance<PsiElement>().mapNotNull { param ->
                try {
                    val getName = param.javaClass.getMethod("getName")
                    val name = getName.invoke(param) as? String ?: return@mapNotNull null

                    // Try to get type
                    val type = try {
                        val getType = param.javaClass.getMethod("getDeclaredType")
                        val typeElement = getType.invoke(param)
                        if (typeElement != null) {
                            val toStringMethod = typeElement.javaClass.getMethod("toString")
                            toStringMethod.invoke(typeElement) as? String
                        } else null
                    } catch (e: Exception) { null }

                    if (type != null) "$type \$$name" else "\$$name"
                } catch (e: Exception) {
                    null
                }
            }.joinToString(", ")

            val methodName = getName(method) ?: "unknown"
            "$methodName($params)"
        } catch (e: Exception) {
            getName(method) ?: "unknown"
        }
    }
}

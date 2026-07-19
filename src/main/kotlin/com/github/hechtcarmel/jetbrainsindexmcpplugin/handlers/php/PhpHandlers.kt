package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.php

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.toArgumentFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.IdeStructureViewExtractor
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiFile
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
        if (!PluginDetectors.php.isAvailable) {
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
            registry.registerSuperMethodsHandler(PhpSuperMethodsHandler())
            registry.registerStructureHandler(PhpStructureHandler())
            registry.registerSymbolReferenceHandler(PhpSymbolReferenceHandler())

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
        return ProjectUtils.getToolFilePath(project, file)
    }

    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun getColumnNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val lineNumber = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(lineNumber) + 1
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
     * Checks if a PhpClass element is an enum using reflection.
     */
    protected fun isEnum(phpClass: PsiElement): Boolean {
        return try {
            phpClass.javaClass.getMethod("isEnum").invoke(phpClass) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
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

    protected data class DeclaredPhpMethod(
        val method: PsiElement,
        val declaringClass: PsiElement
    )

    /**
     * Finds a method by name in a PhpClass using reflection.
     * Uses PhpStorm's `findMethodByName(CharSequence)` API so inherited methods
     * and PHP's case-insensitive method names are handled by the PHP plugin.
     *
     * This is appropriate for direct symbol resolution (`Child::inheritedMethod()`),
     * but implementation/super-method traversal should use declared-only helpers.
     */
    protected fun findMethodInClass(phpClass: PsiElement, methodName: String): PsiElement? {
        return findMethodByName(phpClass, methodName)
            ?: findMethodInClassCollections(phpClass, methodName, includeInherited = true)
    }

    /**
     * Finds a method declared directly on the given PhpClass/interface.
     * Does not use PhpClass.findMethodByName() and does not inspect getMethods(),
     * because both can return inherited methods.
     */
    protected fun findOwnMethodInClass(phpClass: PsiElement, methodName: String): PsiElement? {
        return findMethodInClassCollections(phpClass, methodName, includeInherited = false)
    }

    /**
     * Finds the nearest ancestor class that actually declares the requested method.
     */
    protected fun findOwnMethodInClassHierarchy(
        phpClass: PsiElement,
        methodName: String
    ): DeclaredPhpMethod? {
        val visited = mutableSetOf<String>()
        var currentClass: PsiElement? = phpClass

        while (currentClass != null) {
            val classKey = getFQN(currentClass) ?: getName(currentClass) ?: currentClass.textOffset.toString()
            if (!visited.add(classKey)) return null

            val ownMethod = findOwnMethodInClass(currentClass, methodName)
            if (ownMethod != null) {
                return DeclaredPhpMethod(ownMethod, currentClass)
            }

            currentClass = getSuperClass(currentClass)
        }

        return null
    }

    /**
     * Finds the nearest interface in an extends chain that actually declares the method.
     */
    protected fun findOwnMethodInInterfaceHierarchy(
        iface: PsiElement,
        methodName: String,
        visited: MutableSet<String> = mutableSetOf()
    ): DeclaredPhpMethod? {
        val ifaceKey = getFQN(iface) ?: getName(iface) ?: iface.textOffset.toString()
        if (!visited.add(ifaceKey)) return null

        val ownMethod = findOwnMethodInClass(iface, methodName)
        if (ownMethod != null) {
            return DeclaredPhpMethod(ownMethod, iface)
        }

        return getImplementedInterfaces(iface)
            ?.filterIsInstance<PsiElement>()
            ?.firstNotNullOfOrNull { parentInterface ->
                findOwnMethodInInterfaceHierarchy(parentInterface, methodName, visited)
            }
    }

    private fun findMethodByName(phpClass: PsiElement, methodName: String): PsiElement? {
        val parameterTypes = listOf(CharSequence::class.java, String::class.java)

        for (parameterType in parameterTypes) {
            val method = try {
                val findMethodByNameMethod = phpClass.javaClass.getMethod("findMethodByName", parameterType)
                findMethodByNameMethod.invoke(phpClass, methodName) as? PsiElement
            } catch (_: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                LOG.debug("Error resolving PHP method $methodName using findMethodByName: ${e.message}")
                null
            }

            if (method != null) {
                return method
            }
        }

        return null
    }

    private fun findMethodInClassCollections(
        phpClass: PsiElement,
        methodName: String,
        includeInherited: Boolean
    ): PsiElement? {
        val collectionMethodNames = if (includeInherited) {
            listOf("getOwnMethods", "getMethods")
        } else {
            listOf("getOwnMethods")
        }

        for (collectionMethodName in collectionMethodNames) {
            val method = try {
                val getMethodsMethod = phpClass.javaClass.getMethod(collectionMethodName)
                toPsiElements(getMethodsMethod.invoke(phpClass)).find {
                    getName(it)?.equals(methodName, ignoreCase = true) == true
                }
            } catch (_: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                LOG.debug("Error resolving PHP method $methodName using $collectionMethodName: ${e.message}")
                null
            }

            if (method != null) {
                return method
            }
        }

        return null
    }

    /**
     * Finds a PHP class, interface, or trait by its fully qualified name using PhpIndex.
     */
    protected fun getClassByFQN(project: Project, fqn: String): PsiElement? {
        val phpIndex = getPhpIndex(project) ?: return null
        val lookupMethods = listOf(
            "getClassesByFQN",
            "getInterfacesByFQN",
            "getTraitsByFQN"
        )

        for (methodName in lookupMethods) {
            val element = try {
                val method = phpIndex.javaClass.getMethod(methodName, String::class.java)
                toPsiElements(method.invoke(phpIndex, fqn)).firstOrNull()
            } catch (e: NoSuchMethodException) {
                LOG.debug("PhpIndex.$methodName(String) is not available")
                null
            } catch (e: Exception) {
                LOG.debug("Error resolving PHP type by FQN $fqn using $methodName: ${e.message}")
                null
            }

            if (element != null) {
                return element
            }
        }

        return null
    }

    /**
     * Finds a non-constant field/property by name in a PhpClass using reflection.
     */
    protected fun findFieldInClass(phpClass: PsiElement, fieldName: String): PsiElement? {
        return findFieldByName(phpClass, fieldName, findConstant = false)
            ?: findFieldInClassCollections(phpClass, fieldName, expectedConstant = false)
    }

    /**
     * Finds a class constant by name in a PhpClass using reflection.
     */
    protected fun findConstantInClass(phpClass: PsiElement, constantName: String): PsiElement? {
        return findFieldByName(phpClass, constantName, findConstant = true)
            ?: findFieldInClassCollections(phpClass, constantName, expectedConstant = true)
    }

    /**
     * Finds an enum case by name in a PhpClass enum using reflection.
     * Uses PhpStorm's `getEnumCases()` API to retrieve enum cases.
     */
    protected fun findEnumCaseInClass(phpClass: PsiElement, caseName: String): PsiElement? {
        val collectionMethodNames = listOf("getEnumCases")

        for (methodName in collectionMethodNames) {
            val result = try {
                val method = phpClass.javaClass.getMethod(methodName)
                toPsiElements(method.invoke(phpClass)).firstOrNull {
                    getName(it) == caseName
                }
            } catch (_: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                LOG.debug("Error resolving PHP enum case $caseName using $methodName: ${e.message}")
                null
            }

            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun findFieldByName(
        phpClass: PsiElement,
        fieldName: String,
        findConstant: Boolean
    ): PsiElement? {
        val booleanPrimitiveType = Boolean::class.javaPrimitiveType ?: Boolean::class.javaObjectType
        val signatureCandidates = listOf(
            arrayOf(CharSequence::class.java, booleanPrimitiveType),
            arrayOf(CharSequence::class.java, Boolean::class.javaObjectType),
            arrayOf<Class<*>>(String::class.java, booleanPrimitiveType),
            arrayOf<Class<*>>(String::class.java, Boolean::class.javaObjectType)
        )

        for (signature in signatureCandidates) {
            val field = try {
                val method = phpClass.javaClass.getMethod("findFieldByName", *signature)
                method.invoke(phpClass, fieldName, findConstant) as? PsiElement
            } catch (_: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                LOG.debug("Error resolving PHP field $fieldName using findFieldByName: ${e.message}")
                null
            }

            if (field != null && isField(field) && isConstantField(field) == findConstant) {
                return field
            }
        }

        return null
    }

    private fun findFieldInClassCollections(
        phpClass: PsiElement,
        fieldName: String,
        expectedConstant: Boolean
    ): PsiElement? {
        for (methodName in listOf("getOwnFields", "getFields")) {
            val field = try {
                val method = phpClass.javaClass.getMethod(methodName)
                toPsiElements(method.invoke(phpClass)).find {
                    isField(it) && getName(it) == fieldName && isConstantField(it) == expectedConstant
                }
            } catch (_: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                LOG.debug("Error resolving PHP field $fieldName using $methodName: ${e.message}")
                null
            }

            if (field != null) {
                return field
            }
        }

        return null
    }

    private fun isConstantField(field: PsiElement): Boolean {
        return try {
            val method = field.javaClass.getMethod("isConstant")
            method.invoke(field) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun toPsiElements(value: Any?): Sequence<PsiElement> {
        return when (value) {
            is Array<*> -> value.asSequence().filterIsInstance<PsiElement>()
            is Iterable<*> -> value.asSequence().filterIsInstance<PsiElement>()
            else -> emptySequence()
        }
    }
}

/**
 * PHP implementation of [StructureHandler].
 *
 * Uses IntelliJ's public Structure View API so the PHP plugin owns PHP tree
 * construction, ordering, and nesting. This handler only normalizes the
 * resulting tree elements into the MCP [StructureNode] model.
 */
class PhpStructureHandler : BasePhpHandler<List<StructureNode>>(), StructureHandler {

    override val languageId = "PHP"

    private fun getEndLineNumber(project: Project, element: PsiElement): Int? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: return null
        val endOffset = element.textRange?.endOffset ?: return null
        if (endOffset <= 0 || endOffset > document.textLength) return null
        return document.getLineNumber(endOffset - 1) + 1
    }

    private val phpNamespaceClass by lazy { loadOptionalClass("com.jetbrains.php.lang.psi.elements.PhpNamespace") }
    private val phpConstantClass by lazy { loadOptionalClass("com.jetbrains.php.lang.psi.elements.Constant") }
    private val phpEnumCaseClass by lazy { loadOptionalClass("com.jetbrains.php.lang.psi.elements.PhpEnumCase") }
    private val phpIncludeClass by lazy { loadOptionalClass("com.jetbrains.php.lang.psi.elements.PhpInclude") }

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPhpLanguage(element)
    }

    override fun isAvailable(): Boolean {
        return PluginDetectors.php.isAvailable && phpClassClass != null && methodClass != null
    }

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        if (!canHandle(file)) return emptyList()

        val nodes = IdeStructureViewExtractor.extract(
            file = file,
            project = project,
            classifier = PhpStructureClassifier()
        )
        return addNamespaceContainers(file, project, nodes)
    }

    private data class NamespaceRegion(
        val name: String,
        val line: Int,
        val endLine: Int? = null
    )

    private inner class PhpStructureClassifier : IdeStructureViewExtractor.Classifier {
        override fun describe(
            value: Any?,
            presentation: ItemPresentation
        ): IdeStructureViewExtractor.StructureElementInfo? {
            val element = value as? PsiElement ?: return null

            return when {
                isPhpClass(element) -> describeClass(element, presentation)
                isMethod(element) -> describeMethod(element, presentation)
                isFunction(element) -> describeFunction(element, presentation)
                isField(element) -> describeField(element, presentation)
                isPhpNamespace(element) -> describeNamespace(element, presentation)
                isPhpConstant(element) -> describeConstant(element, presentation)
                isPhpEnumCase(element) -> describeEnumCase(element, presentation)
                isPhpInclude(element) -> describeInclude(element, presentation)
                else -> null
            }
        }
    }

    private fun addNamespaceContainers(
        file: PsiFile,
        project: Project,
        nodes: List<StructureNode>
    ): List<StructureNode> {
        if (nodes.any { it.kind == StructureKind.NAMESPACE }) return nodes

        val namespaces = findNamespaceRegions(file, project)
        if (namespaces.isEmpty()) return nodes

        if (nodes.isEmpty()) {
            return namespaces.map { namespace ->
                StructureNode(
                    name = namespace.name,
                    kind = StructureKind.NAMESPACE,
                    modifiers = emptyList(),
                    signature = null,
                    line = namespace.line,
                    endLine = namespace.endLine
                )
            }
        }

        val result = nodes.filter { it.line < namespaces.first().line }.toMutableList()

        namespaces.forEachIndexed { index, namespace ->
            val nextNamespaceLine = namespaces.getOrNull(index + 1)?.line ?: Int.MAX_VALUE
            val namespaceChildren = nodes.filter { node ->
                node.line >= namespace.line && node.line < nextNamespaceLine
            }

            result += StructureNode(
                name = namespace.name,
                kind = StructureKind.NAMESPACE,
                modifiers = emptyList(),
                signature = null,
                line = namespace.line,
                endLine = namespace.endLine,
                children = namespaceChildren
            )
        }

        return result
    }

    private fun findNamespaceRegions(file: PsiFile, project: Project): List<NamespaceRegion> {
        val namespaceClass = phpNamespaceClass ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val namespaces = PsiTreeUtil.findChildrenOfType(file, namespaceClass as Class<out PsiElement>)

        return namespaces
            .mapNotNull { namespace ->
                val name = namespaceName(namespace) ?: return@mapNotNull null
                val line = getLineNumber(project, namespace) ?: return@mapNotNull null
                val endLine = getEndLineNumber(project, namespace)
                NamespaceRegion(name = name, line = line, endLine = endLine)
            }
            .distinct()
            .sortedBy { it.line }
    }

    private fun namespaceName(element: PsiElement): String? {
        return sequenceOf(
            invokeString(element, "getFQN"),
            invokeString(element, "getNamespaceName"),
            getName(element)
        )
            .mapNotNull { it?.trim()?.trim('\\') }
            .firstOrNull()
            ?.ifBlank { "<global>" }
    }

    private fun describeClass(
        element: PsiElement,
        presentation: ItemPresentation
    ): IdeStructureViewExtractor.StructureElementInfo? {
        val name = getName(element) ?: presentation.presentableText?.trim() ?: return null
        val kind = when {
            invokeBoolean(element, "isInterface") -> StructureKind.INTERFACE
            invokeBoolean(element, "isTrait") -> StructureKind.TRAIT
            invokeBoolean(element, "isEnum") -> StructureKind.ENUM
            else -> StructureKind.CLASS
        }

        val signatureParts = mutableListOf<String>()
        getSuperClass(element)?.let { superClass ->
            val superName = getName(superClass) ?: getFQN(superClass)
            if (!superName.isNullOrBlank()) {
                signatureParts += "extends $superName"
            }
        }

        val interfaces = getImplementedInterfaces(element)
            ?.filterIsInstance<PsiElement>()
            ?.mapNotNull { getName(it) ?: getFQN(it) }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.filterNot { kind == StructureKind.ENUM && isImplicitEnumInterface(it) }
            ?.distinct()
            .orEmpty()
        if (interfaces.isNotEmpty()) {
            val relation = if (kind == StructureKind.INTERFACE) "extends" else "implements"
            signatureParts += "$relation ${interfaces.joinToString(", ")}"
        }

        val traits = getTraits(element)
            ?.filterIsInstance<PsiElement>()
            ?.mapNotNull { getName(it) ?: getFQN(it) }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        if (traits.isNotEmpty()) {
            signatureParts += "uses ${traits.joinToString(", ")}"
        }

        return IdeStructureViewExtractor.StructureElementInfo(
            name = name,
            kind = kind,
            modifiers = classModifiersFor(element, kind),
            signature = signatureParts.joinToString(" ").ifBlank { null }
        )
    }

    private fun classModifiersFor(element: PsiElement, kind: StructureKind): List<String> {
        return modifiersFor(element, includeVisibility = false).filterNot { modifier ->
            modifier == "abstract" && (kind == StructureKind.INTERFACE || kind == StructureKind.TRAIT) ||
                modifier == "final" && kind == StructureKind.ENUM
        }
    }

    private fun isImplicitEnumInterface(name: String): Boolean {
        return name.trim('\\') == "UnitEnum" || name.trim('\\') == "BackedEnum"
    }

    private fun describeNamespace(
        element: PsiElement,
        presentation: ItemPresentation
    ): IdeStructureViewExtractor.StructureElementInfo? {
        val name = invokeString(element, "getFQN")
            ?: invokeString(element, "getNamespaceName")
            ?: getName(element)
            ?: presentation.presentableText?.trim()
            ?: return null

        return IdeStructureViewExtractor.StructureElementInfo(
            name = name.trim('\\').ifBlank { "<global>" },
            kind = StructureKind.NAMESPACE
        )
    }

    private fun describeMethod(
        element: PsiElement,
        presentation: ItemPresentation
    ): IdeStructureViewExtractor.StructureElementInfo? {
        val name = displayName(element, presentation) ?: return null
        val kind = if (name.equals("__construct", ignoreCase = true) || invokeBoolean(element, "isConstructor")) {
            StructureKind.CONSTRUCTOR
        } else {
            StructureKind.METHOD
        }

        return IdeStructureViewExtractor.StructureElementInfo(
            name = name,
            kind = kind,
            modifiers = modifiersFor(element, includeVisibility = true),
            signature = signatureFromPresentation(name, presentation)
        )
    }

    private fun describeFunction(
        element: PsiElement,
        presentation: ItemPresentation
    ): IdeStructureViewExtractor.StructureElementInfo? {
        val name = displayName(element, presentation) ?: return null
        return IdeStructureViewExtractor.StructureElementInfo(
            name = name,
            kind = StructureKind.FUNCTION,
            modifiers = modifiersFor(element, includeVisibility = false),
            signature = signatureFromPresentation(name, presentation)
        )
    }

    private fun describeField(
        element: PsiElement,
        presentation: ItemPresentation
    ): IdeStructureViewExtractor.StructureElementInfo? {
        val name = displayName(element, presentation) ?: return null
        val kind = if (invokeBoolean(element, "isConstant")) {
            StructureKind.CONSTANT
        } else {
            StructureKind.PROPERTY
        }

        return IdeStructureViewExtractor.StructureElementInfo(
            name = name,
            kind = kind,
            modifiers = memberModifiersFor(element, kind),
            signature = signatureFromPresentation(name, presentation)
        )
    }

    private fun describeConstant(
        element: PsiElement,
        presentation: ItemPresentation
    ): IdeStructureViewExtractor.StructureElementInfo? {
        val name = displayName(element, presentation) ?: return null
        return IdeStructureViewExtractor.StructureElementInfo(
            name = name,
            kind = StructureKind.CONSTANT,
            modifiers = memberModifiersFor(element, StructureKind.CONSTANT),
            signature = signatureFromPresentation(name, presentation)
        )
    }

    private fun memberModifiersFor(element: PsiElement, kind: StructureKind): List<String> {
        return modifiersFor(element, includeVisibility = true).filterNot { modifier ->
            kind == StructureKind.CONSTANT && modifier == "static" ||
                modifier == "final" &&
                (kind == StructureKind.PROPERTY || kind == StructureKind.CONSTANT) &&
                !hasExplicitModifierKeyword(element, "final")
        }
    }

    private fun describeEnumCase(
        element: PsiElement,
        presentation: ItemPresentation
    ): IdeStructureViewExtractor.StructureElementInfo? {
        val name = displayName(element, presentation) ?: return null
        return IdeStructureViewExtractor.StructureElementInfo(
            name = name,
            kind = StructureKind.ENUM_CASE,
            signature = signatureFromPresentation(name, presentation)
        )
    }

    private fun describeInclude(
        element: PsiElement,
        presentation: ItemPresentation
    ): IdeStructureViewExtractor.StructureElementInfo? {
        val name = presentation.presentableText?.trim()
            ?: element.text?.trim()?.lineSequence()?.firstOrNull()
            ?: return null

        return IdeStructureViewExtractor.StructureElementInfo(
            name = name,
            kind = StructureKind.INCLUDE
        )
    }

    private fun isPhpNamespace(element: PsiElement): Boolean {
        return phpNamespaceClass?.isInstance(element) == true
    }

    private fun isPhpConstant(element: PsiElement): Boolean {
        return phpConstantClass?.isInstance(element) == true
    }

    private fun isPhpEnumCase(element: PsiElement): Boolean {
        return phpEnumCaseClass?.isInstance(element) == true
    }

    private fun isPhpInclude(element: PsiElement): Boolean {
        return phpIncludeClass?.isInstance(element) == true ||
            element.javaClass.name.contains("Include", ignoreCase = true)
    }

    private fun modifiersFor(element: PsiElement, includeVisibility: Boolean): List<String> {
        val modifiers = mutableListOf<String>()

        if (includeVisibility) {
            accessModifier(element)?.let { modifiers += it }
        }
        if (hasModifierFlag(element, "isAbstract")) modifiers += "abstract"
        if (hasModifierFlag(element, "isFinal")) modifiers += "final"
        if (hasModifierFlag(element, "isStatic")) modifiers += "static"
        if (hasModifierFlag(element, "isReadonly")) modifiers += "readonly"

        return modifiers.distinct()
    }

    private fun accessModifier(element: PsiElement): String? {
        val access = invokeAccessName(element, "getAccess")
            ?: invokeModifier(element)?.let { invokeAccessName(it, "getAccess") }
            ?: return null

        return when (access) {
            "private" -> "private"
            "protected" -> "protected"
            "public" -> "public"
            else -> null
        }
    }

    private fun hasModifierFlag(element: PsiElement, methodName: String): Boolean {
        return invokeBoolean(element, methodName) || invokeModifier(element)?.let { invokeBoolean(it, methodName) } == true
    }

    private fun hasExplicitModifierKeyword(element: PsiElement, keyword: String): Boolean {
        val modifierText = (invokeModifier(element) as? PsiElement)?.text
        if (modifierText != null && containsWord(modifierText, keyword)) {
            return true
        }

        val lineText = elementLineText(element)
        if (lineText != null && containsWord(lineText, keyword)) {
            return true
        }

        return element.text.lineSequence().firstOrNull()?.let { containsWord(it, keyword) } == true
    }

    private fun elementLineText(element: PsiElement): String? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(element.project).getDocument(psiFile) ?: return null
        val line = document.getLineNumber(element.textOffset)
        return document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(line),
                document.getLineEndOffset(line)
            )
        )
    }

    private fun containsWord(text: String, word: String): Boolean {
        return Regex("""(?i)(^|\W)${Regex.escape(word)}($|\W)""").containsMatchIn(text)
    }

    private fun invokeModifier(element: PsiElement): Any? {
        return invokeObject(element, "getModifier")
    }

    private fun invokeAccessName(target: Any, methodName: String): String? {
        val access = invokeObject(target, methodName)?.toString()?.lowercase() ?: return null
        return when {
            access == "private" || access.endsWith(".private") -> "private"
            access == "protected" || access.endsWith(".protected") -> "protected"
            access == "public" || access.endsWith(".public") -> "public"
            else -> null
        }
    }

    private fun displayName(element: PsiElement, presentation: ItemPresentation): String? {
        val psiName = getName(element)?.takeIf { it.isNotBlank() }
        val text = presentation.presentableText?.trim()?.takeIf { it.isNotBlank() }
        if (psiName == null) return text
        val presentationToken = text
            ?.substringBefore(' ')
            ?.substringBefore(':')
            ?.substringBefore('(')

        return if (presentationToken == "\$$psiName") presentationToken else psiName
    }

    private fun signatureFromPresentation(name: String, presentation: ItemPresentation): String? {
        val text = presentation.presentableText?.trim() ?: return null
        val normalizedName = name.removePrefix("\$")
        val prefixes = listOf(name, "\$$normalizedName").distinct()

        for (prefix in prefixes) {
            if (text == prefix) return null
            if (text.startsWith(prefix)) {
                return text.removePrefix(prefix).trim().ifBlank { null }
            }
        }

        return null
    }

    private fun invokeBoolean(target: Any, methodName: String): Boolean {
        return try {
            target.javaClass.getMethod(methodName).invoke(target) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun invokeString(element: PsiElement, methodName: String): String? {
        return invokeObject(element, methodName)?.toString()
    }

    private fun invokeObject(target: Any, methodName: String): Any? {
        return try {
            target.javaClass.getMethod(methodName).invoke(target)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadOptionalClass(className: String): Class<*>? {
        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            LOG.debug("$className not found")
            null
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

    override fun isAvailable(): Boolean = PluginDetectors.php.isAvailable && phpClassClass != null

    override fun getTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): TypeHierarchyData? {
        val phpClass = findContainingPhpClass(element) ?: return null
        LOG.debug("Getting type hierarchy for PHP class: ${getName(phpClass)}")
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)

        val supertypes = getSupertypes(project, phpClass, searchScope = searchScope)
        val subtypes = getSubtypes(project, phpClass, searchScope)

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
        depth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val className = getFQN(phpClass) ?: getName(phpClass) ?: return emptyList()
        if (className in visited) return emptyList()
        visited.add(className)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            // Get superclass (extends)
            val superClass = getSuperClass(phpClass)
            if (superClass != null && shouldIncludeNavigationElement(searchScope, superClass)) {
                val superName = getFQN(superClass) ?: getName(superClass)
                if (superName != null && superName !in visited) {
                    val superSupertypes = getSupertypes(project, superClass, visited, depth + 1, searchScope)
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
                if (
                    ifaceName != null &&
                    ifaceName !in visited &&
                    shouldIncludeNavigationElement(searchScope, iface)
                ) {
                    val ifaceSupertypes = getSupertypes(project, iface, visited, depth + 1, searchScope)
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
                if (
                    traitName != null &&
                    traitName !in visited &&
                    shouldIncludeNavigationElement(searchScope, trait)
                ) {
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

    private fun getSubtypes(
        project: Project,
        phpClass: PsiElement,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        return try {
            val fqn = getFQN(phpClass) ?: return emptyList()
            val results = mutableListOf<TypeElementData>()

            // Use PhpIndex.getAllSubclasses() - the correct API for finding PHP subclasses
            val subclasses = getAllSubclasses(project, fqn)

            subclasses
                .filter { shouldIncludeNavigationElement(searchScope, it) }
                .take(100)
                .forEach { subclass ->
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

    override fun isAvailable(): Boolean = PluginDetectors.php.isAvailable && phpClassClass != null

    override fun findImplementations(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): List<ImplementationData>? {
        LOG.debug("Finding implementations for element at ${element.containingFile?.name}")
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)

        // Check if it's a method
        val method = findContainingMethod(element)
        if (method != null) {
            LOG.debug("Finding method implementations for ${getName(method)}")
            return findMethodImplementations(project, method, searchScope)
        }

        // Check if it's a class/interface
        val phpClass = findContainingPhpClass(element)
        if (phpClass != null) {
            LOG.debug("Finding class implementations for ${getName(phpClass)}")
            return findClassImplementations(project, phpClass, searchScope)
        }

        return null
    }

    private fun findMethodImplementations(
        project: Project,
        method: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        return try {
            val methodName = getName(method) ?: return emptyList()
            val containingClass = getContainingClass(method) ?: return emptyList()
            val classFqn = getFQN(containingClass) ?: return emptyList()

            val results = mutableListOf<ImplementationData>()

            // Use PhpIndex to get all subclasses, then find methods with the same name
            val subclasses = getAllSubclasses(project, classFqn)

            subclasses
                .filter { shouldIncludeNavigationElement(searchScope, it) }
                .take(100)
                .forEach { subclass ->
                // Find only methods declared directly in this subclass. Inherited methods
                // are not implementations/overrides of the queried method.
                val overridingMethod = findOwnMethodInClass(subclass, methodName)
                if (overridingMethod != null) {
                    val file = overridingMethod.containingFile?.virtualFile
                    if (file != null) {
                        val className = getName(subclass) ?: ""
                        results.add(ImplementationData(
                            name = if (className.isNotEmpty()) "$className::$methodName" else methodName,
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, overridingMethod) ?: 0,
                            column = getColumnNumber(project, overridingMethod) ?: 0,
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

    private fun findClassImplementations(
        project: Project,
        phpClass: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        return try {
            val fqn = getFQN(phpClass) ?: return emptyList()
            val results = mutableListOf<ImplementationData>()

            // Use PhpIndex.getAllSubclasses() - the correct API for finding PHP implementations
            val subclasses = getAllSubclasses(project, fqn)

            subclasses
                .filter { shouldIncludeNavigationElement(searchScope, it) }
                .take(100)
                .forEach { subclass ->
                val file = subclass.containingFile?.virtualFile
                if (file != null) {
                    results.add(ImplementationData(
                        name = getFQN(subclass) ?: getName(subclass) ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, subclass) ?: 0,
                        column = getColumnNumber(project, subclass) ?: 0,
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

    override fun isAvailable(): Boolean = PluginDetectors.php.isAvailable && methodClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): CallHierarchyData? {
        val callable = findContainingCallable(element) ?: return null
        LOG.debug("Getting call hierarchy for ${getName(callable)}, direction=$direction, depth=$depth")
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)

        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") {
            findCallersRecursive(project, callable, depth, visited, searchScope = searchScope)
        } else {
            findCalleesRecursive(project, callable, depth, visited, searchScope = searchScope)
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

        // Check superclass. Search the class chain for the nearest class that
        // actually declares the method instead of collecting an inherited PSI
        // element under an intermediate class key.
        val superClass = getSuperClass(containingClass)
        if (superClass != null) {
            val declaredSuperMethod = findOwnMethodInClassHierarchy(superClass, methodName)
            if (declaredSuperMethod != null) {
                val declaringClassName = getFQN(declaredSuperMethod.declaringClass)
                    ?: getName(declaredSuperMethod.declaringClass)
                val key = "$declaringClassName::$methodName"
                if (key !in visited) {
                    visited.add(key)
                    result.add(declaredSuperMethod.method)
                    findSuperMethodsRecursive(project, declaredSuperMethod.method, result, visited)
                }
            }
        }

        // Check interfaces. Use the declaring interface as the visited key.
        val interfaces = getImplementedInterfaces(containingClass)
        interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
            val declaredIfaceMethod = findOwnMethodInInterfaceHierarchy(iface, methodName)
            if (declaredIfaceMethod != null) {
                val ifaceName = getFQN(declaredIfaceMethod.declaringClass)
                    ?: getName(declaredIfaceMethod.declaringClass)
                val key = "$ifaceName::$methodName"
                if (key !in visited) {
                    visited.add(key)
                    result.add(declaredIfaceMethod.method)
                }
            }
        }
    }

    private fun findCallersRecursive(
        project: Project,
        callable: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope
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

            val allReferences = mutableListOf<com.intellij.psi.PsiReference>()

            for (methodToSearch in methodsToSearch) {
                ReferencesSearch.search(methodToSearch, searchScope).forEach(Processor { reference ->
                    allReferences.add(reference)
                    allReferences.size < MAX_RESULTS_PER_LEVEL * 2
                })
            }

            LOG.debug("Found ${allReferences.size} references for ${getName(callable)}")

            val results = mutableListOf<CallElementData>()
            for (reference in allReferences) {
                if (results.size >= MAX_RESULTS_PER_LEVEL) break
                val refElement = reference.element
                val containingCallable = findContainingCallable(refElement)
                if (containingCallable != null && containingCallable != callable && containingCallable !in methodsToSearch) {
                    val children = if (depth > 1) {
                        findCallersRecursive(project, containingCallable, depth - 1, visited, stackDepth + 1, searchScope)
                    } else null
                    if (shouldIncludeNavigationElement(searchScope, containingCallable)) {
                        results.add(createCallElement(project, containingCallable, children))
                    } else if (children != null) {
                        results.addAll(children)
                    }
                }
            }
            results.distinctBy { it.name + it.file + it.line }.take(MAX_RESULTS_PER_LEVEL)
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
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope
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
                            findCalleesRecursive(project, calledMethod, depth - 1, visited, stackDepth + 1, searchScope)
                        } else null
                        if (shouldIncludeNavigationElement(searchScope, calledMethod)) {
                            val element = createCallElement(project, calledMethod, children)
                            if (callees.none { it.name == element.name && it.file == element.file }) {
                                callees.add(element)
                            }
                        } else if (children != null) {
                            children.forEach { child ->
                                if (callees.none { it.name == child.name && it.file == child.file }) {
                                    callees.add(child)
                                }
                            }
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
                            findCalleesRecursive(project, calledFunction, depth - 1, visited, stackDepth + 1, searchScope)
                        } else null
                        if (shouldIncludeNavigationElement(searchScope, calledFunction)) {
                            val element = createCallElement(project, calledFunction, children)
                            if (callees.none { it.name == element.name && it.file == element.file }) {
                                callees.add(element)
                            }
                        } else if (children != null) {
                            children.forEach { child ->
                                if (callees.none { it.name == child.name && it.file == child.file }) {
                                    callees.add(child)
                                }
                            }
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
            column = getColumnNumber(project, callable) ?: 0,
            language = "PHP",
            children = children?.takeIf { it.isNotEmpty() }
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

    override fun isAvailable(): Boolean = PluginDetectors.php.isAvailable && methodClass != null

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
            column = getColumnNumber(project, method) ?: 0,
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

            // Check superclass. Skip intermediate classes that only inherit the method,
            // and report the actual class that declares the super method.
            val superClass = getSuperClass(containingClass)
            if (superClass != null) {
                val declaredSuperMethod = findOwnMethodInClassHierarchy(superClass, methodName)
                if (declaredSuperMethod != null) {
                    val superMethod = declaredSuperMethod.method
                    val declaringClass = declaredSuperMethod.declaringClass
                    val declaringClassName = getFQN(declaringClass) ?: getName(declaringClass)
                    val key = "$declaringClassName::$methodName"
                    if (key !in visited) {
                        visited.add(key)

                        val file = superMethod.containingFile?.virtualFile

                        hierarchy.add(SuperMethodData(
                            name = methodName,
                            signature = buildMethodSignature(superMethod),
                            containingClass = declaringClassName ?: "unknown",
                            containingClassKind = determineClassKind(declaringClass),
                            file = file?.let { getRelativePath(project, it) },
                            line = getLineNumber(project, superMethod),
                            column = getColumnNumber(project, superMethod),
                            isInterface = isInterface(declaringClass),
                            depth = depth,
                            language = "PHP"
                        ))

                        hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
                    }
                }
            }

            // Check interfaces. As with classes, report the interface that actually
            // declares the method, not an intermediate interface that only inherits it.
            val interfaces = getImplementedInterfaces(containingClass)
            interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val declaredIfaceMethod = findOwnMethodInInterfaceHierarchy(iface, methodName)
                if (declaredIfaceMethod != null) {
                    val ifaceMethod = declaredIfaceMethod.method
                    val declaringInterface = declaredIfaceMethod.declaringClass
                    val ifaceName = getFQN(declaringInterface) ?: getName(declaringInterface)
                    val key = "$ifaceName::$methodName"
                    if (key !in visited) {
                        visited.add(key)

                        val file = ifaceMethod.containingFile?.virtualFile

                        hierarchy.add(SuperMethodData(
                            name = methodName,
                            signature = buildMethodSignature(ifaceMethod),
                            containingClass = ifaceName ?: "unknown",
                            containingClassKind = "INTERFACE",
                            file = file?.let { getRelativePath(project, it) },
                            line = getLineNumber(project, ifaceMethod),
                            column = getColumnNumber(project, ifaceMethod),
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

/**
 * PHP implementation of [SymbolReferenceHandler].
 *
 * Resolves PHP symbol reference strings (e.g., `\\App\\Service\\UserService::find()`) to PSI elements.
 *
 * Supported symbol formats:
 * - `\\App\\Service\\UserService` - Class/interface/trait by fully qualified name
 * - `App\\Service\\UserService` - Same, without leading backslash (auto-normalized)
 * - `\\App\\Service\\UserService::find` - Method by name
 * - `\\App\\Service\\UserService::find()` - Method with parameters (ignored; PHP has no overloading by type)
 * - `\\App\\Service\\UserService::\$name` - Field/property
 * - `\\App\\Service\\UserService::ROLE_ADMIN` - Class constant
 * - `\\App\\Service\\StatusEnum::ACTIVE` - Enum case
 */
class PhpSymbolReferenceHandler : BasePhpHandler<PsiNamedElement>(), SymbolReferenceHandler {

    companion object {
        // A valid PHP identifier (simplified: covers the vast majority of code)
        private const val IDENTIFIER = """[a-zA-Z_\x7f-\xff][a-zA-Z0-9_\x7f-\xff]*"""

        // A PHP namespace segment (same as identifier)
        private const val NAMESPACE_SEGMENT = IDENTIFIER

        // Fully qualified name with optional leading backslash and backslash-separated segments
        // In the triple-quoted string, \\ = regex \\ (matches one literal \\)
        private const val FQN = """\\?(?:$NAMESPACE_SEGMENT\\)*$NAMESPACE_SEGMENT"""

        // Property prefixed with $, regex: \$ matches literal $
        private const val PROPERTY = "\\$" + IDENTIFIER

        // Method call with optional parameters, e.g. methodName(...)
        private const val METHOD_CALL = """$IDENTIFIER\([^)]*\)"""

        // Member: method call, property, or plain identifier (constant/method name)
        private const val MEMBER = """(?:$METHOD_CALL|$PROPERTY|$IDENTIFIER)"""

        // Full pattern: FQN with optional ::member
        internal val PHP_SYMBOL_PATTERN = """^$FQN(::$MEMBER)?$""".toRegex()

        private val SYMBOL_EXAMPLES = listOf(
            "'\\App\\Service\\UserService'",
            "'\\App\\Service\\UserService::find'",
            "'\\App\\Service\\UserService::find()'",
            "'\\App\\Service\\UserService::\$property'",
            "'\\App\\Service\\UserService::ROLE_ADMIN'",
            "'\\App\\Service\\StatusEnum::ACTIVE'"
        )
    }

    override val languageId = "PHP"
    override val languageName = "PHP"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isPhpLanguage(element)

    override fun isAvailable(): Boolean =
        PluginDetectors.php.isAvailable && phpClassClass != null && phpNamedElementClass != null

    override fun resolveSymbol(project: Project, symbol: String): Result<PsiNamedElement> {
        val trimmed = symbol.trim()

        if (!PHP_SYMBOL_PATTERN.matches(trimmed)) {
            return ErrorMessages.invalidSymbolFormat(trimmed, SYMBOL_EXAMPLES).toArgumentFailure()
        }

        val fullyQualifiedName: String
        val memberPart: String?

        val memberSeparatorIndex = trimmed.indexOf("::")
        if (memberSeparatorIndex >= 0) {
            fullyQualifiedName = trimmed.substring(0, memberSeparatorIndex)
            memberPart = trimmed.substring(memberSeparatorIndex + 2)
        } else {
            fullyQualifiedName = trimmed
            memberPart = null
        }

        // Normalize to leading backslash (PhpIndex resolves both forms but leading \ is canonical)
        val classFqn = if (fullyQualifiedName.startsWith("\\")) fullyQualifiedName else "\\$fullyQualifiedName"

        val phpClass = getClassByFQN(project, classFqn)
            ?: return ErrorMessages.typeNotFound(classFqn, project.name).toArgumentFailure()

        if (memberPart == null) {
            return phpClass.toPsiNamedElementResult()
        }

        return resolvePhpMember(phpClass, classFqn, memberPart)
    }

    private fun resolvePhpMember(
        phpClass: PsiElement,
        classFqn: String,
        memberPart: String
    ): Result<PsiNamedElement> {
        // Strip parameter parentheses for method lookup (PHP has no overloading by type)
        val hasParens = memberPart.endsWith(")") && memberPart.contains("(")
        val memberName = if (hasParens) {
            memberPart.substring(0, memberPart.indexOf('('))
        } else {
            memberPart
        }

        val isProperty = memberName.startsWith("$")

        if (isProperty) {
            // Field/property: strip the leading $ for the getName comparison
            val fieldName = memberName.substring(1)
            val field = findFieldInClass(phpClass, fieldName)
                ?: return ErrorMessages.memberNotFoundInType(memberPart, classFqn).toArgumentFailure()
            return field.toPsiNamedElementResult()
        }

        if (hasParens) {
            // Explicit method call syntax with (): look up method by name (ignore params)
            val method = findMethodInClass(phpClass, memberName)
                ?: return ErrorMessages.memberNotFoundInType(memberPart, classFqn).toArgumentFailure()
            return method.toPsiNamedElementResult()
        }

        // No parentheses and no $ prefix: could be an enum case, constant, or method.
        // Properties require the documented `$property` syntax so a plain name
        // does not silently navigate to a same-named property.

        // Try enum case first (only if the type is an enum)
        if (isEnum(phpClass)) {
            val enumCase = findEnumCaseInClass(phpClass, memberName)
            if (enumCase != null) {
                return enumCase.toPsiNamedElementResult()
            }
        }

        // Try constant
        val constant = findConstantInClass(phpClass, memberName)
        if (constant != null) {
            return constant.toPsiNamedElementResult()
        }

        // Try method
        val method = findMethodInClass(phpClass, memberName)
        if (method != null) {
            return method.toPsiNamedElementResult()
        }

        return ErrorMessages.memberNotFoundInType(memberPart, classFqn).toArgumentFailure()
    }

    private fun PsiElement.toPsiNamedElementResult(): Result<PsiNamedElement> {
        return if (this is PsiNamedElement) {
            Result.success(this)
        } else {
            ErrorMessages.SYMBOL_NOT_RESOLVED.toArgumentFailure()
        }
    }
}

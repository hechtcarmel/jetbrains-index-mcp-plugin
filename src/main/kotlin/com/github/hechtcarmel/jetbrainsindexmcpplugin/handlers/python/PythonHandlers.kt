package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PythonPluginDetector
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * Registration entry point for Python language handlers.
 *
 * This class is loaded via reflection when the Python plugin is available.
 * It registers all Python-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## Python PSI Classes Used (via reflection)
 *
 * - `com.jetbrains.python.psi.PyClass` - Python class declarations
 * - `com.jetbrains.python.psi.PyFunction` - Python function/method declarations
 * - `com.jetbrains.python.psi.PyCallExpression` - Function/method calls
 * - `com.jetbrains.python.psi.stubs.PyClassNameIndex` - Index for finding classes by name
 * - `com.jetbrains.python.psi.stubs.PyFunctionNameIndex` - Index for finding functions by name
 * - `com.jetbrains.python.psi.search.PyClassInheritorsSearch` - Search for subclasses
 * - `com.jetbrains.python.psi.search.PyOverridingMethodsSearch` - Search for overriding methods
 */
object PythonHandlers {

    private val LOG = logger<PythonHandlers>()

    /**
     * Registers all Python handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!PythonPluginDetector.isPythonPluginAvailable) {
            LOG.info("Python plugin not available, skipping Python handler registration")
            return
        }

        try {
            // Verify Python classes are accessible before registering
            Class.forName("com.jetbrains.python.psi.PyClass")
            Class.forName("com.jetbrains.python.psi.PyFunction")

            registry.registerTypeHierarchyHandler(PythonTypeHierarchyHandler())
            registry.registerImplementationsHandler(PythonImplementationsHandler())
            registry.registerCallHierarchyHandler(PythonCallHierarchyHandler())
            registry.registerSymbolSearchHandler(PythonSymbolSearchHandler())
            registry.registerSuperMethodsHandler(PythonSuperMethodsHandler())
            registry.registerStructureHandler(PythonStructureHandler())

            LOG.info("Registered Python handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Python PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register Python handlers: ${e.message}")
        }
    }
}

/**
 * Base class for Python handlers with common utilities.
 *
 * Uses reflection to access Python PSI classes to avoid compile-time dependencies.
 */
abstract class BasePythonHandler<T> : LanguageHandler<T> {

    /**
     * Checks if the element is from a Python language.
     */
    protected fun isPythonLanguage(element: PsiElement): Boolean {
        return element.language.id == "Python"
    }

    protected val pyClassClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.python.psi.PyClass")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val pyFunctionClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.python.psi.PyFunction")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val pyCallExpressionClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.python.psi.PyCallExpression")
        } catch (e: ClassNotFoundException) {
            null
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
     * Checks if element is a PyClass using reflection.
     */
    protected fun isPyClass(element: PsiElement): Boolean {
        return pyClassClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a PyFunction using reflection.
     */
    protected fun isPyFunction(element: PsiElement): Boolean {
        return pyFunctionClass?.isInstance(element) == true
    }

    /**
     * Finds containing PyClass using reflection.
     */
    protected fun findContainingPyClass(element: PsiElement): PsiElement? {
        if (isPyClass(element)) return element
        val pyClass = pyClassClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, pyClass as Class<out PsiElement>)
    }

    /**
     * Finds containing PyFunction using reflection.
     */
    protected fun findContainingPyFunction(element: PsiElement): PsiElement? {
        if (isPyFunction(element)) return element
        val pyFunction = pyFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, pyFunction as Class<out PsiElement>)
    }

    /**
     * Gets the name of a PyClass or PyFunction via reflection.
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
     * Gets the qualified name of a PyClass via reflection.
     */
    protected fun getQualifiedName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getQualifiedName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets superclasses of a PyClass via reflection.
     */
    protected fun getSuperClasses(pyClass: PsiElement): Array<*>? {
        return try {
            val method = pyClass.javaClass.getMethod("getSuperClasses", com.intellij.psi.search.GlobalSearchScope::class.java)
            val scope = GlobalSearchScope.allScope(pyClass.project)
            method.invoke(pyClass, scope) as? Array<*>
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Python implementation of [TypeHierarchyHandler].
 */
class PythonTypeHierarchyHandler : BasePythonHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 50
    }

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PythonPluginDetector.isPythonPluginAvailable && pyClassClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val pyClass = findContainingPyClass(element) ?: return null

        val supertypes = getSupertypes(project, pyClass)
        val subtypes = getSubtypes(project, pyClass)

        return TypeHierarchyData(
            element = TypeElementData(
                name = getQualifiedName(pyClass) ?: getName(pyClass) ?: "unknown",
                qualifiedName = getQualifiedName(pyClass),
                file = pyClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, pyClass),
                kind = "CLASS",
                language = "Python"
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun getSupertypes(
        project: Project,
        pyClass: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val className = getQualifiedName(pyClass) ?: getName(pyClass) ?: return emptyList()
        if (className in visited || className == "object") return emptyList()
        visited.add(className)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            val superClasses = getSuperClasses(pyClass)
            superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
                val superName = getQualifiedName(superClass) ?: getName(superClass)
                if (superName != null && superName != "object") {
                    val superSupertypes = getSupertypes(project, superClass, visited, depth + 1)
                    supertypes.add(TypeElementData(
                        name = superName,
                        qualifiedName = getQualifiedName(superClass),
                        file = superClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superClass),
                        kind = "CLASS",
                        language = "Python",
                        supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                    ))
                }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }

        return supertypes
    }

    private fun getSubtypes(project: Project, pyClass: PsiElement): List<TypeElementData> {
        return try {
            val searchClass = Class.forName("com.jetbrains.python.psi.search.PyClassInheritorsSearch")
            val searchMethod = searchClass.getMethod("search", pyClassClass, java.lang.Boolean.TYPE)
            val query = searchMethod.invoke(null, pyClass, true)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val inheritors = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            inheritors.filterIsInstance<PsiElement>()
                .take(100)
                .map { inheritor ->
                    TypeElementData(
                        name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                        qualifiedName = getQualifiedName(inheritor),
                        file = inheritor.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, inheritor),
                        kind = "CLASS",
                        language = "Python"
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Python implementation of [ImplementationsHandler].
 */
class PythonImplementationsHandler : BasePythonHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PythonPluginDetector.isPythonPluginAvailable && pyClassClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        val pyFunction = findContainingPyFunction(element)
        if (pyFunction != null) {
            return findMethodImplementations(project, pyFunction)
        }

        val pyClass = findContainingPyClass(element)
        if (pyClass != null) {
            return findClassImplementations(project, pyClass)
        }

        return null
    }

    private fun findMethodImplementations(project: Project, pyFunction: PsiElement): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.jetbrains.python.psi.search.PyOverridingMethodsSearch")
            val searchMethod = searchClass.getMethod("search", pyFunctionClass, java.lang.Boolean.TYPE)
            val query = searchMethod.invoke(null, pyFunction, true)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val overridingMethods = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            overridingMethods.filterIsInstance<PsiElement>()
                .take(100)
                .mapNotNull { overridingMethod ->
                    val file = overridingMethod.containingFile?.virtualFile ?: return@mapNotNull null
                    val containingClass = findContainingPyClass(overridingMethod)
                    val className = containingClass?.let { getName(it) } ?: ""
                    val methodName = getName(overridingMethod) ?: "unknown"
                    ImplementationData(
                        name = if (className.isNotEmpty()) "$className.$methodName" else methodName,
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, overridingMethod) ?: 0,
                        kind = "METHOD",
                        language = "Python"
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun findClassImplementations(project: Project, pyClass: PsiElement): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.jetbrains.python.psi.search.PyClassInheritorsSearch")
            val searchMethod = searchClass.getMethod("search", pyClassClass, java.lang.Boolean.TYPE)
            val query = searchMethod.invoke(null, pyClass, true)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val inheritors = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            inheritors.filterIsInstance<PsiElement>()
                .take(100)
                .mapNotNull { inheritor ->
                    val file = inheritor.containingFile?.virtualFile ?: return@mapNotNull null
                    ImplementationData(
                        name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, inheritor) ?: 0,
                        kind = "CLASS",
                        language = "Python"
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Python implementation of [CallHierarchyHandler].
 */
class PythonCallHierarchyHandler : BasePythonHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
        private const val MAX_SUPER_METHODS = 10
    }

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PythonPluginDetector.isPythonPluginAvailable && pyFunctionClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int
    ): CallHierarchyData? {
        val pyFunction = findContainingPyFunction(element) ?: return null
        val visited = mutableSetOf<String>()

        val calls = if (direction == "callers") {
            findCallersRecursive(project, pyFunction, depth, visited)
        } else {
            findCalleesRecursive(project, pyFunction, depth, visited)
        }

        return CallHierarchyData(
            element = createCallElement(project, pyFunction),
            calls = calls
        )
    }

    /**
     * Finds all super methods that the given method overrides.
     * This is used to also search for callers of base methods, since those
     * calls could be dispatched to this method at runtime (polymorphism).
     */
    private fun findAllSuperMethods(project: Project, pyFunction: PsiElement): Set<PsiElement> {
        val superMethods = mutableSetOf<PsiElement>()
        val visited = mutableSetOf<String>()
        findSuperMethodsRecursive(project, pyFunction, superMethods, visited)
        return superMethods.take(MAX_SUPER_METHODS).toSet()
    }

    private fun findSuperMethodsRecursive(
        project: Project,
        pyFunction: PsiElement,
        result: MutableSet<PsiElement>,
        visited: MutableSet<String>
    ) {
        val containingClass = findContainingPyClass(pyFunction) ?: return
        val methodName = getName(pyFunction) ?: return

        val superClasses = getSuperClasses(containingClass)
        superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
            val superClassName = getQualifiedName(superClass) ?: getName(superClass)
            val key = "$superClassName.$methodName"
            if (key in visited) return@forEach
            visited.add(key)

            val superMethod = findMethodInClass(superClass, methodName)
            if (superMethod != null) {
                result.add(superMethod)
                findSuperMethodsRecursive(project, superMethod, result, visited)
            }
        }
    }

    private fun findMethodInClass(pyClass: PsiElement, methodName: String): PsiElement? {
        return try {
            val getMethodsMethod = pyClass.javaClass.getMethod("getMethods")
            val methods = getMethodsMethod.invoke(pyClass) as? Array<*> ?: return null
            methods.filterIsInstance<PsiElement>().find { getName(it) == methodName }
        } catch (e: Exception) {
            null
        }
    }

    private fun findCallersRecursive(
        project: Project,
        pyFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(pyFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        return try {
            // Collect all methods to search: current method + all super methods it overrides
            // This handles polymorphism - callers of base methods could dispatch to this method
            val methodsToSearch = mutableSetOf(pyFunction)
            methodsToSearch.addAll(findAllSuperMethods(project, pyFunction))

            // Search for references to all methods in the hierarchy
            val referencesSearchClass = Class.forName("com.intellij.psi.search.searches.ReferencesSearch")
            val searchMethod = referencesSearchClass.getMethod("search", PsiElement::class.java, GlobalSearchScope::class.java)
            val scope = GlobalSearchScope.projectScope(project)

            val allReferences = mutableListOf<com.intellij.psi.PsiReference>()
            for (methodToSearch in methodsToSearch) {
                val query = searchMethod.invoke(null, methodToSearch, scope)
                val findAllMethod = query.javaClass.getMethod("findAll")
                val references = findAllMethod.invoke(query) as? Collection<*> ?: continue
                references.filterIsInstance<com.intellij.psi.PsiReference>().forEach { allReferences.add(it) }
            }

            allReferences.take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val containingFunction = findContainingPyFunction(refElement)
                    if (containingFunction != null && containingFunction != pyFunction && !methodsToSearch.contains(containingFunction)) {
                        val children = if (depth > 1) {
                            findCallersRecursive(project, containingFunction, depth - 1, visited, stackDepth + 1)
                        } else null
                        createCallElement(project, containingFunction, children)
                    } else null
                }
                .distinctBy { it.name + it.file + it.line }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun findCalleesRecursive(
        project: Project,
        pyFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(pyFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        val callees = mutableListOf<CallElementData>()
        try {
            val pyCallExpr = pyCallExpressionClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val callExpressions = PsiTreeUtil.findChildrenOfType(pyFunction, pyCallExpr as Class<out PsiElement>)

            callExpressions.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                val calledFunction = resolveCallExpression(callExpr)
                if (calledFunction != null && isPyFunction(calledFunction)) {
                    val children = if (depth > 1) {
                        findCalleesRecursive(project, calledFunction, depth - 1, visited, stackDepth + 1)
                    } else null
                    val element = createCallElement(project, calledFunction, children)
                    if (callees.none { it.name == element.name && it.file == element.file }) {
                        callees.add(element)
                    }
                }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }
        return callees
    }

    private fun resolveCallExpression(callExpr: PsiElement): PsiElement? {
        return try {
            // Get the callee and resolve it
            val calleeMethod = callExpr.javaClass.getMethod("getCallee")
            val callee = calleeMethod.invoke(callExpr) as? PsiElement ?: return null

            val referenceMethod = callee.javaClass.getMethod("getReference")
            val reference = referenceMethod.invoke(callee) as? com.intellij.psi.PsiReference
            reference?.resolve()
        } catch (e: Exception) {
            null
        }
    }

    private fun getFunctionKey(pyFunction: PsiElement): String {
        val containingClass = findContainingPyClass(pyFunction)
        val className = containingClass?.let { getQualifiedName(it) ?: getName(it) } ?: ""
        val functionName = getName(pyFunction) ?: ""
        return "$className.$functionName"
    }

    private fun createCallElement(project: Project, pyFunction: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val file = pyFunction.containingFile?.virtualFile
        val containingClass = findContainingPyClass(pyFunction)
        val className = containingClass?.let { getName(it) }
        val functionName = getName(pyFunction) ?: "unknown"

        val name = if (className != null) "$className.$functionName" else functionName

        return CallElementData(
            name = name,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, pyFunction) ?: 0,
            language = "Python",
            children = children?.takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * Python implementation of [SymbolSearchHandler].
 *
 * Uses the optimized [OptimizedSymbolSearch] infrastructure which leverages IntelliJ's
 * built-in "Go to Symbol" APIs with caching, word index, and prefix matching.
 */
class PythonSymbolSearchHandler : BasePythonHandler<List<SymbolData>>(), SymbolSearchHandler {

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean = isAvailable()

    override fun isAvailable(): Boolean = PythonPluginDetector.isPythonPluginAvailable && pyClassClass != null

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

        // Use the optimized platform-based search with language filter for Python
        return OptimizedSymbolSearch.search(
            project = project,
            pattern = pattern,
            scope = scope,
            limit = limit,
            languageFilter = setOf("Python")
        )
    }
}

/**
 * Python implementation of [SuperMethodsHandler].
 */
class PythonSuperMethodsHandler : BasePythonHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PythonPluginDetector.isPythonPluginAvailable && pyFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val pyFunction = findContainingPyFunction(element) ?: return null
        val containingClass = findContainingPyClass(pyFunction) ?: return null

        val file = pyFunction.containingFile?.virtualFile
        val methodData = MethodData(
            name = getName(pyFunction) ?: "unknown",
            signature = buildMethodSignature(pyFunction),
            containingClass = getQualifiedName(containingClass) ?: getName(containingClass) ?: "unknown",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, pyFunction) ?: 0,
            language = "Python"
        )

        val hierarchy = buildHierarchy(project, pyFunction)

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        pyFunction: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        try {
            // Find super methods by looking at parent classes
            val containingClass = findContainingPyClass(pyFunction) ?: return emptyList()
            val methodName = getName(pyFunction) ?: return emptyList()

            val superClasses = getSuperClasses(containingClass)
            superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
                val superClassName = getQualifiedName(superClass) ?: getName(superClass)
                val key = "$superClassName.$methodName"
                if (key in visited) return@forEach
                visited.add(key)

                // Find method with same name in superclass
                val superMethod = findMethodInClass(superClass, methodName)
                if (superMethod != null) {
                    val file = superMethod.containingFile?.virtualFile

                    hierarchy.add(SuperMethodData(
                        name = methodName,
                        signature = buildMethodSignature(superMethod),
                        containingClass = superClassName ?: "unknown",
                        containingClassKind = "CLASS",
                        file = file?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superMethod),
                        isInterface = false,
                        depth = depth,
                        language = "Python"
                    ))

                    hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
                }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }

        return hierarchy
    }

    private fun findMethodInClass(pyClass: PsiElement, methodName: String): PsiElement? {
        return try {
            // Try to get methods and find by name - more reliable than findMethodByName
            val getMethodsMethod = pyClass.javaClass.getMethod("getMethods")
            val methods = getMethodsMethod.invoke(pyClass) as? Array<*> ?: return null
            methods.filterIsInstance<PsiElement>().find { getName(it) == methodName }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildMethodSignature(pyFunction: PsiElement): String {
        return try {
            val getParameterListMethod = pyFunction.javaClass.getMethod("getParameterList")
            val parameterList = getParameterListMethod.invoke(pyFunction)
            val getParametersMethod = parameterList.javaClass.getMethod("getParameters")
            val parameters = getParametersMethod.invoke(parameterList) as? Array<*> ?: emptyArray<Any>()

            val params = parameters.filterIsInstance<PsiElement>().mapNotNull { param ->
                try {
                    val getNameMethod = param.javaClass.getMethod("getName")
                    getNameMethod.invoke(param) as? String
                } catch (e: Exception) {
                    null
                }
            }.joinToString(", ")

            val functionName = getName(pyFunction) ?: "unknown"
            "$functionName($params)"
        } catch (e: Exception) {
            getName(pyFunction) ?: "unknown"
        }
    }
}

/**
 * Python implementation of [StructureHandler].
 *
 * Extracts the hierarchical structure of Python source files including
 * classes, functions, and their nesting relationships.
 *
 * Uses reflection to access Python PSI classes to avoid compile-time dependencies.
 */
class PythonStructureHandler : BasePythonHandler<List<StructureNode>>(), StructureHandler {

    companion object {
        private val LOG = logger<PythonStructureHandler>()
    }

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PythonPluginDetector.isPythonPluginAvailable && pyClassClass != null

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val structure = mutableListOf<StructureNode>()

        try {
            val pyFileClass = Class.forName("com.jetbrains.python.psi.PyFile")
            if (!pyFileClass.isInstance(file)) {
                LOG.debug("File is not a PyFile: ${file.javaClass.name}, language: ${file.language.id}")
                return emptyList()
            }

            // Use PsiTreeUtil to find all top-level classes and functions
            // This is more reliable than calling getClasses()/getFunctions() which may not exist

            @Suppress("UNCHECKED_CAST")
            val classes = PsiTreeUtil.findChildrenOfType(file, pyClassClass as Class<PsiElement>)
            LOG.debug("Found ${classes?.size ?: 0} classes in Python file")

            classes?.forEach { pyClass ->
                // Only include top-level classes (not nested ones initially)
                if (isTopLevel(pyClass, file)) {
                    structure.add(extractClassStructure(pyClass, project))
                }
            }

            @Suppress("UNCHECKED_CAST")
            val functions = PsiTreeUtil.findChildrenOfType(file, pyFunctionClass as Class<PsiElement>)
            LOG.debug("Found ${functions?.size ?: 0} functions in Python file")

            functions?.forEach { pyFunction ->
                // Only include top-level functions (not class methods)
                if (isTopLevel(pyFunction, file)) {
                    structure.add(extractFunctionStructure(pyFunction, project))
                }
            }

        } catch (e: ClassNotFoundException) {
            LOG.warn("Python PSI class not found: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to extract Python file structure: ${e.message}, ${e.javaClass.simpleName}")
        }

        return structure.sortedBy { it.line }
    }

    /**
     * Check if an element is a top-level element (not nested inside a class).
     */
    private fun isTopLevel(element: PsiElement, file: PsiFile): Boolean {
        // Walk up the tree from element to file, checking if we pass through a PyClass
        var current: PsiElement? = element.parent
        while (current != null && current != file) {
            if (isPyClass(current)) {
                return false // Nested inside a class
            }
            current = current.parent
        }
        return true
    }

    private fun extractClassStructure(pyClass: PsiElement, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()

        try {
            // Get class methods
            val getMethodsMethod = pyClass.javaClass.getMethod("getMethods")
            val methods = getMethodsMethod.invoke(pyClass) as? Array<*> ?: emptyArray<Any?>()

            for (method in methods) {
                if (method is PsiElement) {
                    children.add(extractFunctionStructure(method as PsiElement, project))
                }
            }

            // Get nested classes
            val getInnerClassesMethod = pyClass.javaClass.getMethod("getInnerClasses")
            val innerClasses = getInnerClassesMethod.invoke(pyClass) as? List<*> ?: emptyList<Any?>()

            for (innerClass in innerClasses) {
                if (innerClass is PsiElement) {
                    children.add(extractClassStructure(innerClass as PsiElement, project))
                }
            }

        } catch (e: Exception) {
            LOG.warn("Failed to extract Python class structure: ${e.message}")
        }

        val name = getName(pyClass) ?: "unknown"

        return StructureNode(
            name = name,
            kind = StructureKind.CLASS,
            modifiers = getPythonModifiers(pyClass),
            signature = buildClassSignature(pyClass),
            line = getLineNumber(project, pyClass) ?: 0,
            children = children.sortedBy { it.line }
        )
    }

    private fun extractFunctionStructure(pyFunction: PsiElement, project: Project): StructureNode {
        val name = getName(pyFunction) ?: "unknown"

        return StructureNode(
            name = name,
            kind = StructureKind.FUNCTION,
            modifiers = getPythonModifiers(pyFunction),
            signature = buildFunctionSignature(pyFunction),
            line = getLineNumber(project, pyFunction) ?: 0
        )
    }

    private fun getPythonModifiers(element: PsiElement): List<String> {
        val modifiers = mutableListOf<String>()

        try {
            // Check for decorators using reflection
            val hasDecoratorMethod = element.javaClass.getMethod("hasDecorator", String::class.java)

            if (hasDecoratorMethod.invoke(element, "property") as? Boolean == true) {
                modifiers.add("@property")
            }
            if (hasDecoratorMethod.invoke(element, "staticmethod") as? Boolean == true) {
                modifiers.add("@staticmethod")
            }
            if (hasDecoratorMethod.invoke(element, "classmethod") as? Boolean == true) {
                modifiers.add("@classmethod")
            }
        } catch (e: Exception) {
            // Ignore
        }

        return modifiers
    }

    private fun buildClassSignature(pyClass: PsiElement): String {
        return try {
            val getSuperClassesMethod = pyClass.javaClass.getMethod(
                "getSuperClasses",
                GlobalSearchScope::class.java
            )
            val scope = GlobalSearchScope.allScope(pyClass.project)
            val superClasses = getSuperClassesMethod.invoke(pyClass, scope) as? Array<*> ?: emptyArray<Any?>()

            if (superClasses.isNotEmpty()) {
                val names = superClasses.mapNotNull {
                    val element = it as? PsiElement
                    if (element != null) {
                        getQualifiedName(element) ?: getName(element)
                    } else null
                }
                return "(${names.joinToString(", ")})"
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun buildFunctionSignature(pyFunction: PsiElement): String {
        return try {
            val getParameterListMethod = pyFunction.javaClass.getMethod("getParameterList")
            val parameterList = getParameterListMethod.invoke(pyFunction)
            val getParametersMethod = parameterList.javaClass.getMethod("getParameters")
            val parameters = getParametersMethod.invoke(parameterList) as? Array<*> ?: emptyArray<Any?>()

            val params = parameters.filterIsInstance<PsiElement>().mapNotNull { param ->
                try {
                    val getNameMethod = param.javaClass.getMethod("getName")
                    getNameMethod.invoke(param) as? String
                } catch (e: Exception) {
                    null
                }
            }.joinToString(", ")

            "($params)"
        } catch (e: Exception) {
            "()"
        }
    }
}


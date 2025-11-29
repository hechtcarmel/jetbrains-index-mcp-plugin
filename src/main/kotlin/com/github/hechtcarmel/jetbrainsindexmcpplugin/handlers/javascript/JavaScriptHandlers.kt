package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.JavaScriptPluginDetector
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * Registration entry point for JavaScript/TypeScript language handlers.
 *
 * This class is loaded via reflection when the JavaScript plugin is available.
 * It registers all JavaScript/TypeScript-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## JavaScript PSI Classes Used (via reflection)
 *
 * - `com.intellij.lang.javascript.psi.JSClass` - JS/TS class declarations (ES6+)
 * - `com.intellij.lang.javascript.psi.JSFunction` - Function/method declarations
 * - `com.intellij.lang.javascript.psi.ecmal4.JSClass` - TypeScript class declarations
 * - `com.intellij.lang.javascript.psi.JSCallExpression` - Function/method calls
 * - `com.intellij.lang.javascript.index.JSSymbolIndex` - Symbol index
 * - `com.intellij.lang.javascript.psi.resolve.JSClassResolver` - Class resolution
 *
 * ## Supported Languages
 *
 * - JavaScript (ES5, ES6+)
 * - TypeScript
 * - JSX / TSX
 * - Flow
 */
object JavaScriptHandlers {

    private val LOG = logger<JavaScriptHandlers>()

    /**
     * Registers all JavaScript/TypeScript handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!JavaScriptPluginDetector.isJavaScriptPluginAvailable) {
            LOG.info("JavaScript plugin not available, skipping JavaScript handler registration")
            return
        }

        try {
            // Verify JavaScript classes are accessible before registering
            Class.forName("com.intellij.lang.javascript.psi.JSFunction")

            registry.registerTypeHierarchyHandler(JavaScriptTypeHierarchyHandler())
            registry.registerImplementationsHandler(JavaScriptImplementationsHandler())
            registry.registerCallHierarchyHandler(JavaScriptCallHierarchyHandler())
            registry.registerSymbolSearchHandler(JavaScriptSymbolSearchHandler())
            registry.registerSuperMethodsHandler(JavaScriptSuperMethodsHandler())

            // Also register for TypeScript (uses same handlers)
            registry.registerTypeHierarchyHandler(TypeScriptTypeHierarchyHandler())
            registry.registerImplementationsHandler(TypeScriptImplementationsHandler())
            registry.registerCallHierarchyHandler(TypeScriptCallHierarchyHandler())
            registry.registerSymbolSearchHandler(TypeScriptSymbolSearchHandler())
            registry.registerSuperMethodsHandler(TypeScriptSuperMethodsHandler())

            LOG.info("Registered JavaScript and TypeScript handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("JavaScript PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register JavaScript handlers: ${e.message}")
        }
    }
}

/**
 * Base class for JavaScript/TypeScript handlers with common utilities.
 *
 * Uses reflection to access JavaScript PSI classes to avoid compile-time dependencies.
 */
abstract class BaseJavaScriptHandler<T> : LanguageHandler<T> {

    protected val jsClassClass: Class<*>? by lazy {
        try {
            // Try ES6 class first
            Class.forName("com.intellij.lang.javascript.psi.ecmal4.JSClass")
        } catch (e: ClassNotFoundException) {
            try {
                Class.forName("com.intellij.lang.javascript.psi.JSClass")
            } catch (e2: ClassNotFoundException) {
                null
            }
        }
    }

    protected val jsFunctionClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSFunction")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val jsCallExpressionClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSCallExpression")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val jsReferenceExpressionClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSReferenceExpression")
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
     * Detects the language name from element.
     */
    protected fun getLanguageName(element: PsiElement): String {
        return when (element.language.id) {
            "TypeScript" -> "TypeScript"
            "TypeScript JSX" -> "TypeScript"
            "JavaScript" -> "JavaScript"
            "ECMAScript 6" -> "JavaScript"
            "JSX Harmony" -> "JavaScript"
            else -> "JavaScript"
        }
    }

    /**
     * Checks if element is a JSClass using reflection.
     */
    protected fun isJSClass(element: PsiElement): Boolean {
        return jsClassClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a JSFunction using reflection.
     */
    protected fun isJSFunction(element: PsiElement): Boolean {
        return jsFunctionClass?.isInstance(element) == true
    }

    /**
     * Finds containing JSClass using reflection.
     */
    protected fun findContainingJSClass(element: PsiElement): PsiElement? {
        if (isJSClass(element)) return element
        val jsClass = jsClassClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, jsClass as Class<out PsiElement>)
    }

    /**
     * Finds containing JSFunction using reflection.
     */
    protected fun findContainingJSFunction(element: PsiElement): PsiElement? {
        if (isJSFunction(element)) return element
        val jsFunction = jsFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, jsFunction as Class<out PsiElement>)
    }

    /**
     * Gets the name of a JS element via reflection.
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
     * Gets the qualified name of a JSClass via reflection.
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
     * Gets the kind of a JS class (class, interface, etc.)
     */
    protected fun getClassKind(jsClass: PsiElement): String {
        return try {
            val isInterfaceMethod = jsClass.javaClass.getMethod("isInterface")
            val isInterface = isInterfaceMethod.invoke(jsClass) as? Boolean ?: false
            if (isInterface) "INTERFACE" else "CLASS"
        } catch (e: Exception) {
            "CLASS"
        }
    }

    /**
     * Gets superclasses/interfaces of a JSClass via reflection.
     */
    protected fun getSuperClasses(jsClass: PsiElement): Array<*>? {
        return try {
            val method = jsClass.javaClass.getMethod("getSuperClasses")
            method.invoke(jsClass) as? Array<*>
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets implemented interfaces of a JSClass via reflection.
     */
    protected fun getImplementedInterfaces(jsClass: PsiElement): Array<*>? {
        return try {
            val method = jsClass.javaClass.getMethod("getImplementedInterfaces")
            method.invoke(jsClass) as? Array<*>
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * JavaScript implementation of [TypeHierarchyHandler].
 */
class JavaScriptTypeHierarchyHandler : BaseJavaScriptHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 50
    }

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && (isJSClass(element) || findContainingJSClass(element) != null)
    }

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val jsClass = findContainingJSClass(element) ?: return null

        val supertypes = getSupertypes(project, jsClass)
        val subtypes = getSubtypes(project, jsClass)

        return TypeHierarchyData(
            element = TypeElementData(
                name = getQualifiedName(jsClass) ?: getName(jsClass) ?: "unknown",
                qualifiedName = getQualifiedName(jsClass),
                file = jsClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, jsClass),
                kind = getClassKind(jsClass),
                language = getLanguageName(jsClass)
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun getSupertypes(
        project: Project,
        jsClass: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val className = getQualifiedName(jsClass) ?: getName(jsClass) ?: return emptyList()
        if (className in visited) return emptyList()
        visited.add(className)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            // Get superclasses
            val superClasses = getSuperClasses(jsClass)
            superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
                val superName = getQualifiedName(superClass) ?: getName(superClass)
                if (superName != null) {
                    val superSupertypes = getSupertypes(project, superClass, visited, depth + 1)
                    supertypes.add(TypeElementData(
                        name = superName,
                        qualifiedName = getQualifiedName(superClass),
                        file = superClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superClass),
                        kind = getClassKind(superClass),
                        language = getLanguageName(superClass),
                        supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                    ))
                }
            }

            // Get implemented interfaces
            val interfaces = getImplementedInterfaces(jsClass)
            interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getQualifiedName(iface) ?: getName(iface)
                if (ifaceName != null && ifaceName !in visited) {
                    val ifaceSupertypes = getSupertypes(project, iface, visited, depth + 1)
                    supertypes.add(TypeElementData(
                        name = ifaceName,
                        qualifiedName = getQualifiedName(iface),
                        file = iface.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, iface),
                        kind = "INTERFACE",
                        language = getLanguageName(iface),
                        supertypes = ifaceSupertypes.takeIf { it.isNotEmpty() }
                    ))
                }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }

        return supertypes
    }

    private fun getSubtypes(project: Project, jsClass: PsiElement): List<TypeElementData> {
        return try {
            // Use DefinitionsScopedSearch or similar
            val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSInheritorsSearch")
            val searchMethod = searchClass.getMethod("search", jsClassClass)
            val query = searchMethod.invoke(null, jsClass)

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
                        kind = getClassKind(inheritor),
                        language = getLanguageName(inheritor)
                    )
                }
        } catch (e: Exception) {
            // Fallback: Use DefinitionsScopedSearch
            try {
                findInheritorsViaDefinitions(project, jsClass)
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    private fun findInheritorsViaDefinitions(project: Project, jsClass: PsiElement): List<TypeElementData> {
        return try {
            val searchClass = Class.forName("com.intellij.psi.search.searches.DefinitionsScopedSearch")
            val searchMethod = searchClass.getMethod("search", PsiElement::class.java, GlobalSearchScope::class.java)
            val scope = GlobalSearchScope.projectScope(project)
            val query = searchMethod.invoke(null, jsClass, scope)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val definitions = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            definitions.filterIsInstance<PsiElement>()
                .filter { isJSClass(it) }
                .take(100)
                .map { inheritor ->
                    TypeElementData(
                        name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                        qualifiedName = getQualifiedName(inheritor),
                        file = inheritor.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, inheritor),
                        kind = getClassKind(inheritor),
                        language = getLanguageName(inheritor)
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * JavaScript implementation of [ImplementationsHandler].
 */
class JavaScriptImplementationsHandler : BaseJavaScriptHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && (isJSClass(element) || isJSFunction(element) ||
            findContainingJSClass(element) != null || findContainingJSFunction(element) != null)
    }

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        val jsFunction = findContainingJSFunction(element)
        if (jsFunction != null) {
            // Check if it's a method in a class
            val containingClass = findContainingJSClass(jsFunction)
            if (containingClass != null) {
                return findMethodImplementations(project, jsFunction)
            }
        }

        val jsClass = findContainingJSClass(element)
        if (jsClass != null) {
            return findClassImplementations(project, jsClass)
        }

        return null
    }

    private fun findMethodImplementations(project: Project, jsFunction: PsiElement): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSFunctionOverridingSearch")
            val searchMethod = searchClass.getMethod("search", jsFunctionClass)
            val query = searchMethod.invoke(null, jsFunction)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val overridingMethods = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            overridingMethods.filterIsInstance<PsiElement>()
                .take(100)
                .mapNotNull { overridingMethod ->
                    val file = overridingMethod.containingFile?.virtualFile ?: return@mapNotNull null
                    val containingClass = findContainingJSClass(overridingMethod)
                    val className = containingClass?.let { getName(it) } ?: ""
                    val methodName = getName(overridingMethod) ?: "unknown"
                    ImplementationData(
                        name = if (className.isNotEmpty()) "$className.$methodName" else methodName,
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, overridingMethod) ?: 0,
                        kind = "METHOD",
                        language = getLanguageName(overridingMethod)
                    )
                }
        } catch (e: Exception) {
            // Fallback using DefinitionsScopedSearch
            findImplementationsViaDefinitions(project, jsFunction)
        }
    }

    private fun findClassImplementations(project: Project, jsClass: PsiElement): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSInheritorsSearch")
            val searchMethod = searchClass.getMethod("search", jsClassClass)
            val query = searchMethod.invoke(null, jsClass)

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
                        kind = getClassKind(inheritor),
                        language = getLanguageName(inheritor)
                    )
                }
        } catch (e: Exception) {
            findImplementationsViaDefinitions(project, jsClass)
        }
    }

    private fun findImplementationsViaDefinitions(project: Project, element: PsiElement): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.intellij.psi.search.searches.DefinitionsScopedSearch")
            val searchMethod = searchClass.getMethod("search", PsiElement::class.java, GlobalSearchScope::class.java)
            val scope = GlobalSearchScope.projectScope(project)
            val query = searchMethod.invoke(null, element, scope)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val definitions = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            definitions.filterIsInstance<PsiElement>()
                .take(100)
                .mapNotNull { definition ->
                    val file = definition.containingFile?.virtualFile ?: return@mapNotNull null
                    val kind = when {
                        isJSClass(definition) -> getClassKind(definition)
                        isJSFunction(definition) -> "METHOD"
                        else -> "UNKNOWN"
                    }
                    ImplementationData(
                        name = getQualifiedName(definition) ?: getName(definition) ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, definition) ?: 0,
                        kind = kind,
                        language = getLanguageName(definition)
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * JavaScript implementation of [CallHierarchyHandler].
 */
class JavaScriptCallHierarchyHandler : BaseJavaScriptHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
    }

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && (isJSFunction(element) || findContainingJSFunction(element) != null)
    }

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int
    ): CallHierarchyData? {
        val jsFunction = findContainingJSFunction(element) ?: return null
        val visited = mutableSetOf<String>()

        val calls = if (direction == "callers") {
            findCallersRecursive(project, jsFunction, depth, visited)
        } else {
            findCalleesRecursive(project, jsFunction, depth, visited)
        }

        return CallHierarchyData(
            element = createCallElement(project, jsFunction),
            calls = calls
        )
    }

    private fun findCallersRecursive(
        project: Project,
        jsFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(jsFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        return try {
            val referencesSearchClass = Class.forName("com.intellij.psi.search.searches.ReferencesSearch")
            val searchMethod = referencesSearchClass.getMethod("search", PsiElement::class.java, GlobalSearchScope::class.java)
            val scope = GlobalSearchScope.projectScope(project)
            val query = searchMethod.invoke(null, jsFunction, scope)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val references = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            references.take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { ref ->
                    val reference = ref as? com.intellij.psi.PsiReference ?: return@mapNotNull null
                    val refElement = reference.element
                    val containingFunction = findContainingJSFunction(refElement)
                    if (containingFunction != null && containingFunction != jsFunction) {
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
        jsFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(jsFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        val callees = mutableListOf<CallElementData>()
        try {
            val jsCallExpr = jsCallExpressionClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val callExpressions = PsiTreeUtil.findChildrenOfType(jsFunction, jsCallExpr as Class<out PsiElement>)

            callExpressions.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                val calledFunction = resolveCallExpression(callExpr)
                if (calledFunction != null && isJSFunction(calledFunction)) {
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
            // Get the method expression and resolve it
            val methodExprMethod = callExpr.javaClass.getMethod("getMethodExpression")
            val methodExpr = methodExprMethod.invoke(callExpr) as? PsiElement ?: return null

            val referenceMethod = methodExpr.javaClass.getMethod("getReference")
            val reference = referenceMethod.invoke(methodExpr) as? com.intellij.psi.PsiReference
            reference?.resolve()
        } catch (e: Exception) {
            null
        }
    }

    private fun getFunctionKey(jsFunction: PsiElement): String {
        val containingClass = findContainingJSClass(jsFunction)
        val className = containingClass?.let { getQualifiedName(it) ?: getName(it) } ?: ""
        val functionName = getName(jsFunction) ?: ""
        val file = jsFunction.containingFile?.virtualFile?.path ?: ""
        return "$file:$className.$functionName"
    }

    private fun createCallElement(project: Project, jsFunction: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val file = jsFunction.containingFile?.virtualFile
        val containingClass = findContainingJSClass(jsFunction)
        val className = containingClass?.let { getName(it) }
        val functionName = getName(jsFunction) ?: "unknown"

        val name = if (className != null) "$className.$functionName" else functionName

        return CallElementData(
            name = name,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, jsFunction) ?: 0,
            language = getLanguageName(jsFunction),
            children = children?.takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * JavaScript implementation of [SymbolSearchHandler].
 */
class JavaScriptSymbolSearchHandler : BaseJavaScriptHandler<List<SymbolData>>(), SymbolSearchHandler {

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean = isAvailable()

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

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

        val matches = mutableListOf<SymbolData>()

        // Use JSSymbolIndex2 or JSNameIndex to search for symbols
        try {
            val indexClass = Class.forName("com.intellij.lang.javascript.index.JSSymbolIndex2")
            val keyField = indexClass.getDeclaredField("KEY")
            keyField.isAccessible = true
            val key = keyField.get(null)

            val stubIndexClass = Class.forName("com.intellij.psi.stubs.StubIndex")
            val getInstanceMethod = stubIndexClass.getMethod("getInstance")
            val stubIndex = getInstanceMethod.invoke(null)

            val getAllKeysMethod = stubIndex.javaClass.getMethod("getAllKeys", Class.forName("com.intellij.psi.stubs.StubIndexKey"), Project::class.java)
            val allNames = getAllKeysMethod.invoke(stubIndex, key, project) as? Collection<*> ?: emptyList<Any>()

            val matchingNames = allNames.filterIsInstance<String>().filter { matchesQuery(it, pattern) }

            for (symbolName in matchingNames) {
                if (matches.size >= limit) break

                val getElementsMethod = stubIndex.javaClass.getMethod(
                    "getElements",
                    Class.forName("com.intellij.psi.stubs.StubIndexKey"),
                    java.lang.Object::class.java,
                    Project::class.java,
                    GlobalSearchScope::class.java,
                    java.lang.Class::class.java
                )
                val elements = getElementsMethod.invoke(stubIndex, key, symbolName, project, scope, PsiElement::class.java) as? Collection<*>

                elements?.filterIsInstance<PsiElement>()?.forEach { element ->
                    if (matches.size >= limit) return@forEach
                    val file = element.containingFile?.virtualFile ?: return@forEach

                    val kind = when {
                        isJSClass(element) -> getClassKind(element)
                        isJSFunction(element) -> {
                            val containingClass = findContainingJSClass(element)
                            if (containingClass != null) "METHOD" else "FUNCTION"
                        }
                        else -> "SYMBOL"
                    }

                    val containingClass = findContainingJSClass(element)
                    matches.add(SymbolData(
                        name = getName(element) ?: symbolName,
                        qualifiedName = getQualifiedName(element),
                        kind = kind,
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, element) ?: 1,
                        containerName = containingClass?.let { getName(it) },
                        language = getLanguageName(element)
                    ))
                }
            }
        } catch (e: Exception) {
            // Try fallback approach using PsiShortNamesCache for JS
            searchSymbolsFallback(project, pattern, scope, matches, limit)
        }

        return matches.sortedWith(compareBy(
            { !it.name.equals(pattern, ignoreCase = true) },
            { levenshteinDistance(it.name.lowercase(), pattern.lowercase()) }
        ))
    }

    private fun searchSymbolsFallback(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        matches: MutableList<SymbolData>,
        limit: Int
    ) {
        try {
            // Use GotoSymbolContributor
            val contributorClass = Class.forName("com.intellij.lang.javascript.navigation.JSGotoSymbolContributor")
            val contributor = contributorClass.getDeclaredConstructor().newInstance()

            val getItemsByNameMethod = contributorClass.getMethod(
                "getItemsByName",
                String::class.java,
                String::class.java,
                Project::class.java,
                java.lang.Boolean.TYPE
            )

            // This is a simplified search - real implementation would need to handle navigation items
            val items = getItemsByNameMethod.invoke(contributor, pattern, pattern, project, false) as? Array<*>
            items?.filterIsInstance<com.intellij.navigation.NavigationItem>()?.take(limit)?.forEach { item ->
                val psiElement = (item as? com.intellij.pom.Navigatable) ?: return@forEach
                // Would need to extract PSI element from navigation item
            }
        } catch (e: Exception) {
            // Fallback failed, return empty results
        }
    }

    private fun matchesQuery(name: String, query: String): Boolean {
        if (name.contains(query, ignoreCase = true)) return true
        return matchesCamelCase(name, query)
    }

    private fun matchesCamelCase(name: String, query: String): Boolean {
        var queryIndex = 0
        for (char in name) {
            if (queryIndex >= query.length) return true
            if (char.equals(query[queryIndex], ignoreCase = true)) queryIndex++
        }
        return queryIndex >= query.length
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (s1[i - 1] == s2[j - 1]) 0 else 1
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}

/**
 * JavaScript implementation of [SuperMethodsHandler].
 */
class JavaScriptSuperMethodsHandler : BaseJavaScriptHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && (isJSFunction(element) || findContainingJSFunction(element) != null)
    }

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val jsFunction = findContainingJSFunction(element) ?: return null
        val containingClass = findContainingJSClass(jsFunction) ?: return null

        val file = jsFunction.containingFile?.virtualFile
        val methodData = MethodData(
            name = getName(jsFunction) ?: "unknown",
            signature = buildMethodSignature(jsFunction),
            containingClass = getQualifiedName(containingClass) ?: getName(containingClass) ?: "unknown",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, jsFunction) ?: 0,
            language = getLanguageName(jsFunction)
        )

        val hierarchy = buildHierarchy(project, jsFunction)

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        jsFunction: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        try {
            val containingClass = findContainingJSClass(jsFunction) ?: return emptyList()
            val methodName = getName(jsFunction) ?: return emptyList()

            // Get superclasses and look for methods with the same name
            val superClasses = getSuperClasses(containingClass)
            superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
                val superClassName = getQualifiedName(superClass) ?: getName(superClass)
                val key = "$superClassName.$methodName"
                if (key in visited) return@forEach
                visited.add(key)

                val superMethod = findMethodInClass(superClass, methodName)
                if (superMethod != null) {
                    val file = superMethod.containingFile?.virtualFile

                    hierarchy.add(SuperMethodData(
                        name = methodName,
                        signature = buildMethodSignature(superMethod),
                        containingClass = superClassName ?: "unknown",
                        containingClassKind = getClassKind(superClass),
                        file = file?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superMethod),
                        isInterface = getClassKind(superClass) == "INTERFACE",
                        depth = depth,
                        language = getLanguageName(superMethod)
                    ))

                    hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
                }
            }

            // Also check implemented interfaces
            val interfaces = getImplementedInterfaces(containingClass)
            interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getQualifiedName(iface) ?: getName(iface)
                val key = "$ifaceName.$methodName"
                if (key in visited) return@forEach
                visited.add(key)

                val superMethod = findMethodInClass(iface, methodName)
                if (superMethod != null) {
                    val file = superMethod.containingFile?.virtualFile

                    hierarchy.add(SuperMethodData(
                        name = methodName,
                        signature = buildMethodSignature(superMethod),
                        containingClass = ifaceName ?: "unknown",
                        containingClassKind = "INTERFACE",
                        file = file?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superMethod),
                        isInterface = true,
                        depth = depth,
                        language = getLanguageName(superMethod)
                    ))
                }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }

        return hierarchy
    }

    private fun findMethodInClass(jsClass: PsiElement, methodName: String): PsiElement? {
        return try {
            val findFunctionMethod = jsClass.javaClass.getMethod("findFunctionByName", String::class.java)
            findFunctionMethod.invoke(jsClass, methodName) as? PsiElement
        } catch (e: Exception) {
            // Try alternative: iterate through functions
            try {
                val getFunctionsMethod = jsClass.javaClass.getMethod("getFunctions")
                val functions = getFunctionsMethod.invoke(jsClass) as? Array<*> ?: return null
                functions.filterIsInstance<PsiElement>().find { getName(it) == methodName }
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun buildMethodSignature(jsFunction: PsiElement): String {
        return try {
            val getParameterListMethod = jsFunction.javaClass.getMethod("getParameterList")
            val parameterList = getParameterListMethod.invoke(jsFunction)
            val getParametersMethod = parameterList.javaClass.getMethod("getParameters")
            val parameters = getParametersMethod.invoke(parameterList) as? Array<*> ?: emptyArray<Any>()

            val params = parameters.filterIsInstance<PsiElement>().mapNotNull { param ->
                try {
                    val getNameMethod = param.javaClass.getMethod("getName")
                    val name = getNameMethod.invoke(param) as? String

                    // Try to get type
                    val type = try {
                        val getTypeMethod = param.javaClass.getMethod("getType")
                        val typeElement = getTypeMethod.invoke(param)
                        typeElement?.toString()
                    } catch (e: Exception) {
                        null
                    }

                    if (type != null) "$name: $type" else name
                } catch (e: Exception) {
                    null
                }
            }.joinToString(", ")

            val functionName = getName(jsFunction) ?: "unknown"

            // Try to get return type
            val returnType = try {
                val getReturnTypeMethod = jsFunction.javaClass.getMethod("getReturnType")
                val returnTypeElement = getReturnTypeMethod.invoke(jsFunction)
                returnTypeElement?.toString()
            } catch (e: Exception) {
                null
            }

            if (returnType != null) {
                "$functionName($params): $returnType"
            } else {
                "$functionName($params)"
            }
        } catch (e: Exception) {
            getName(jsFunction) ?: "unknown"
        }
    }
}

// TypeScript handlers delegate to JavaScript handlers

class TypeScriptTypeHierarchyHandler : TypeHierarchyHandler by JavaScriptTypeHierarchyHandler() {
    override val languageId = "TypeScript"
}

class TypeScriptImplementationsHandler : ImplementationsHandler by JavaScriptImplementationsHandler() {
    override val languageId = "TypeScript"
}

class TypeScriptCallHierarchyHandler : CallHierarchyHandler by JavaScriptCallHierarchyHandler() {
    override val languageId = "TypeScript"
}

class TypeScriptSymbolSearchHandler : SymbolSearchHandler by JavaScriptSymbolSearchHandler() {
    override val languageId = "TypeScript"
}

class TypeScriptSuperMethodsHandler : SuperMethodsHandler by JavaScriptSuperMethodsHandler() {
    override val languageId = "TypeScript"
}

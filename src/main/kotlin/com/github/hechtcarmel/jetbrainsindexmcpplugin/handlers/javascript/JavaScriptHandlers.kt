package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.JavaScriptPluginDetector
import com.intellij.ide.util.gotoByName.ChooseByNameModelEx
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

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

    protected val LOG = logger<BaseJavaScriptHandler<*>>()

    /**
     * Checks if the element is from a JavaScript/TypeScript language.
     */
    protected fun isJavaScriptLanguage(element: PsiElement): Boolean {
        val langId = element.language.id
        return langId == "JavaScript" || langId == "TypeScript" ||
            langId == "ECMAScript 6" || langId == "JSX Harmony" ||
            langId == "TypeScript JSX"
    }

    /**
     * Checks if a file is a JavaScript/TypeScript file by extension.
     */
    protected fun isJavaScriptFile(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext in listOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
    }

    protected val jsClassClass: Class<*>? by lazy {
        try {
            // Try ES6 class first (com.intellij.lang.javascript.psi.ecmal4.JSClass)
            Class.forName("com.intellij.lang.javascript.psi.ecmal4.JSClass")
        } catch (e: ClassNotFoundException) {
            try {
                Class.forName("com.intellij.lang.javascript.psi.JSClass")
            } catch (e2: ClassNotFoundException) {
                LOG.debug("JSClass not found")
                null
            }
        }
    }

    protected val jsFunctionClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSFunction")
        } catch (e: ClassNotFoundException) {
            LOG.debug("JSFunction not found")
            null
        }
    }

    protected val jsCallExpressionClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSCallExpression")
        } catch (e: ClassNotFoundException) {
            LOG.debug("JSCallExpression not found")
            null
        }
    }

    protected val jsNamedElementClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSNamedElement")
        } catch (e: ClassNotFoundException) {
            try {
                // Fallback to base PsiNamedElement
                PsiNamedElement::class.java
            } catch (e2: Exception) {
                LOG.debug("JSNamedElement not found")
                null
            }
        }
    }

    protected val jsVariableClass: Class<*>? by lazy {
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSVariable")
        } catch (e: ClassNotFoundException) {
            LOG.debug("JSVariable not found")
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
     * Checks if element is a JSVariable using reflection.
     */
    protected fun isJSVariable(element: PsiElement): Boolean {
        return jsVariableClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a JSNamedElement using reflection.
     */
    protected fun isJSNamedElement(element: PsiElement): Boolean {
        return jsNamedElementClass?.isInstance(element) == true
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

    /**
     * Determines the kind of a JavaScript element.
     */
    protected fun determineElementKind(element: PsiElement): String {
        return when {
            isJSClass(element) -> getClassKind(element)
            isJSFunction(element) -> {
                val containingClass = findContainingJSClass(element)
                if (containingClass != null && containingClass != element) "METHOD" else "FUNCTION"
            }
            isJSVariable(element) -> "VARIABLE"
            else -> "SYMBOL"
        }
    }

    /**
     * Finds a method in a JS class by name.
     */
    protected fun findMethodInClass(jsClass: PsiElement, methodName: String): PsiElement? {
        return try {
            val findFunctionMethod = jsClass.javaClass.getMethod("findFunctionByName", String::class.java)
            findFunctionMethod.invoke(jsClass, methodName) as? PsiElement
        } catch (e: Exception) {
            try {
                val getFunctionsMethod = jsClass.javaClass.getMethod("getFunctions")
                val functions = getFunctionsMethod.invoke(jsClass) as? Array<*> ?: return null
                functions.filterIsInstance<PsiElement>().find { getName(it) == methodName }
            } catch (e2: Exception) {
                null
            }
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
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val jsClass = findContainingJSClass(element) ?: return null
        LOG.debug("Getting type hierarchy for JS class: ${getName(jsClass)}")

        val supertypes = getSupertypes(project, jsClass)
        val subtypes = getSubtypes(project, jsClass)

        LOG.debug("Found ${supertypes.size} supertypes and ${subtypes.size} subtypes")

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
            LOG.debug("Error getting supertypes: ${e.message}")
        }

        return supertypes
    }

    private fun getSubtypes(project: Project, jsClass: PsiElement): List<TypeElementData> {
        // Strategy 1: Try JSInheritorsSearch (JavaScript plugin API)
        try {
            val result = searchUsingJSInheritorsSearch(project, jsClass)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} subtypes via JSInheritorsSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("JSInheritorsSearch failed: ${e.message}")
        }

        // Strategy 2: Try DefinitionsScopedSearch (Platform API)
        try {
            val result = searchUsingDefinitionsScopedSearch(project, jsClass)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} subtypes via DefinitionsScopedSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("DefinitionsScopedSearch failed: ${e.message}")
        }

        LOG.debug("No subtypes found")
        return emptyList()
    }

    private fun searchUsingJSInheritorsSearch(project: Project, jsClass: PsiElement): List<TypeElementData> {
        val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSInheritorsSearch")
        val searchMethod = searchClass.getMethod("search", jsClassClass)
        val query = searchMethod.invoke(null, jsClass)

        val findAllMethod = query.javaClass.getMethod("findAll")
        val inheritors = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

        return inheritors.filterIsInstance<PsiElement>()
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
    }

    private fun searchUsingDefinitionsScopedSearch(project: Project, jsClass: PsiElement): List<TypeElementData> {
        val scope = GlobalSearchScope.projectScope(project)
        val definitions = DefinitionsScopedSearch.search(jsClass, scope).findAll()

        return definitions
            .filter { it != jsClass && isJSClass(it) }
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
    }
}

/**
 * JavaScript implementation of [ImplementationsHandler].
 */
class JavaScriptImplementationsHandler : BaseJavaScriptHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        LOG.debug("Finding implementations for element at ${element.containingFile?.name}")

        val jsFunction = findContainingJSFunction(element)
        if (jsFunction != null) {
            val containingClass = findContainingJSClass(jsFunction)
            if (containingClass != null) {
                LOG.debug("Finding method implementations for ${getName(jsFunction)}")
                return findMethodImplementations(project, jsFunction)
            }
        }

        val jsClass = findContainingJSClass(element)
        if (jsClass != null) {
            LOG.debug("Finding class implementations for ${getName(jsClass)}")
            return findClassImplementations(project, jsClass)
        }

        return null
    }

    private fun findMethodImplementations(project: Project, jsFunction: PsiElement): List<ImplementationData> {
        // Strategy 1: Try JSFunctionOverridingSearch
        try {
            val result = searchUsingJSFunctionOverridingSearch(project, jsFunction)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} implementations via JSFunctionOverridingSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("JSFunctionOverridingSearch failed: ${e.message}")
        }

        // Strategy 2: Try DefinitionsScopedSearch (Platform API)
        try {
            val result = searchUsingDefinitionsScopedSearch(project, jsFunction)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} implementations via DefinitionsScopedSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("DefinitionsScopedSearch failed: ${e.message}")
        }

        LOG.debug("No implementations found")
        return emptyList()
    }

    private fun searchUsingJSFunctionOverridingSearch(project: Project, jsFunction: PsiElement): List<ImplementationData> {
        val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSFunctionOverridingSearch")
        val searchMethod = searchClass.getMethod("search", jsFunctionClass)
        val query = searchMethod.invoke(null, jsFunction)

        val findAllMethod = query.javaClass.getMethod("findAll")
        val overridingMethods = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

        return overridingMethods.filterIsInstance<PsiElement>()
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
    }

    private fun findClassImplementations(project: Project, jsClass: PsiElement): List<ImplementationData> {
        // Strategy 1: Try JSInheritorsSearch
        try {
            val result = searchClassUsingJSInheritorsSearch(project, jsClass)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} class implementations via JSInheritorsSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("JSInheritorsSearch failed: ${e.message}")
        }

        // Strategy 2: Try DefinitionsScopedSearch (Platform API)
        try {
            val result = searchUsingDefinitionsScopedSearch(project, jsClass)
            if (result.isNotEmpty()) {
                LOG.debug("Found ${result.size} class implementations via DefinitionsScopedSearch")
                return result
            }
        } catch (e: Exception) {
            LOG.debug("DefinitionsScopedSearch failed: ${e.message}")
        }

        LOG.debug("No class implementations found")
        return emptyList()
    }

    private fun searchClassUsingJSInheritorsSearch(project: Project, jsClass: PsiElement): List<ImplementationData> {
        val searchClass = Class.forName("com.intellij.lang.javascript.psi.resolve.JSInheritorsSearch")
        val searchMethod = searchClass.getMethod("search", jsClassClass)
        val query = searchMethod.invoke(null, jsClass)

        val findAllMethod = query.javaClass.getMethod("findAll")
        val inheritors = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

        return inheritors.filterIsInstance<PsiElement>()
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
    }

    private fun searchUsingDefinitionsScopedSearch(project: Project, element: PsiElement): List<ImplementationData> {
        val scope = GlobalSearchScope.projectScope(project)
        val definitions = DefinitionsScopedSearch.search(element, scope).findAll()

        return definitions
            .filter { it != element }
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
    }
}

/**
 * JavaScript implementation of [CallHierarchyHandler].
 */
class JavaScriptCallHierarchyHandler : BaseJavaScriptHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
        private const val MAX_SUPER_METHODS = 10
    }

    override val languageId = "JavaScript"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int
    ): CallHierarchyData? {
        val jsFunction = findContainingJSFunction(element) ?: return null
        LOG.debug("Getting call hierarchy for ${getName(jsFunction)}, direction=$direction, depth=$depth")

        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") {
            findCallersRecursive(project, jsFunction, depth, visited)
        } else {
            findCalleesRecursive(project, jsFunction, depth, visited)
        }

        LOG.debug("Found ${calls.size} ${direction}")

        return CallHierarchyData(
            element = createCallElement(project, jsFunction),
            calls = calls
        )
    }

    private fun findAllSuperMethods(project: Project, jsFunction: PsiElement): Set<PsiElement> {
        val superMethods = mutableSetOf<PsiElement>()
        val visited = mutableSetOf<String>()
        findSuperMethodsRecursive(project, jsFunction, superMethods, visited)
        return superMethods.take(MAX_SUPER_METHODS).toSet()
    }

    private fun findSuperMethodsRecursive(
        project: Project,
        jsFunction: PsiElement,
        result: MutableSet<PsiElement>,
        visited: MutableSet<String>
    ) {
        val containingClass = findContainingJSClass(jsFunction) ?: return
        val methodName = getName(jsFunction) ?: return

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

        val interfaces = getImplementedInterfaces(containingClass)
        interfaces?.filterIsInstance<PsiElement>()?.forEach { iface ->
            val ifaceName = getQualifiedName(iface) ?: getName(iface)
            val key = "$ifaceName.$methodName"
            if (key in visited) return@forEach
            visited.add(key)

            val superMethod = findMethodInClass(iface, methodName)
            if (superMethod != null) {
                result.add(superMethod)
            }
        }
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
            // Collect all methods to search: current method + all super methods it overrides
            val methodsToSearch = mutableSetOf(jsFunction)
            methodsToSearch.addAll(findAllSuperMethods(project, jsFunction))

            // Use platform ReferencesSearch API
            val scope = GlobalSearchScope.projectScope(project)
            val allReferences = mutableListOf<com.intellij.psi.PsiReference>()

            for (methodToSearch in methodsToSearch) {
                val references = ReferencesSearch.search(methodToSearch, scope).findAll()
                allReferences.addAll(references)
            }

            LOG.debug("Found ${allReferences.size} references for ${getName(jsFunction)}")

            allReferences.take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val containingFunction = findContainingJSFunction(refElement)
                    if (containingFunction != null && containingFunction != jsFunction && !methodsToSearch.contains(containingFunction)) {
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
            LOG.debug("Error finding callees: ${e.message}")
        }
        return callees
    }

    private fun resolveCallExpression(callExpr: PsiElement): PsiElement? {
        return try {
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
 *
 * Uses multiple strategies to find symbols:
 * 1. Platform ChooseByNameContributor extension points (most reliable)
 * 2. JavaScript-specific stub indexes
 * 3. File-based PSI tree scan (fallback)
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
        LOG.info("Searching for JS/TS symbols matching '$pattern' (includeLibraries=$includeLibraries, limit=$limit)")

        val scope = if (includeLibraries) {
            GlobalSearchScope.allScope(project)
        } else {
            GlobalSearchScope.projectScope(project)
        }

        val matches = mutableListOf<SymbolData>()

        // Strategy 1: Use platform ChooseByNameContributor extension points
        try {
            val contributorResults = searchUsingChooseByNameContributors(project, pattern, scope, limit)
            if (contributorResults.isNotEmpty()) {
                LOG.info("Found ${contributorResults.size} symbols via ChooseByNameContributors")
                matches.addAll(contributorResults)
            }
        } catch (e: Exception) {
            LOG.debug("ChooseByNameContributor search failed: ${e.message}")
        }

        // Strategy 2: If not enough results, try JSGotoSymbolContributor directly
        if (matches.size < limit) {
            try {
                val jsContributorResults = searchUsingJSGotoSymbolContributor(project, pattern, scope, limit - matches.size)
                if (jsContributorResults.isNotEmpty()) {
                    LOG.info("Found ${jsContributorResults.size} additional symbols via JSGotoSymbolContributor")
                    // Add only unique results
                    jsContributorResults.forEach { result ->
                        if (matches.none { it.file == result.file && it.line == result.line && it.name == result.name }) {
                            matches.add(result)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("JSGotoSymbolContributor search failed: ${e.message}")
            }
        }

        // Strategy 3: If still not enough results, scan JS/TS files directly
        if (matches.size < limit && !includeLibraries) {
            try {
                val fileBasedResults = searchUsingFileScan(project, pattern, scope, limit - matches.size)
                if (fileBasedResults.isNotEmpty()) {
                    LOG.info("Found ${fileBasedResults.size} additional symbols via file scan")
                    fileBasedResults.forEach { result ->
                        if (matches.none { it.file == result.file && it.line == result.line && it.name == result.name }) {
                            matches.add(result)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("File-based search failed: ${e.message}")
            }
        }

        LOG.info("Total symbols found: ${matches.size}")

        return matches
            .take(limit)
            .sortedWith(compareBy(
                { !it.name.equals(pattern, ignoreCase = true) },
                { levenshteinDistance(it.name.lowercase(), pattern.lowercase()) }
            ))
    }

    /**
     * Search using platform ChooseByNameContributor extension points.
     */
    private fun searchUsingChooseByNameContributors(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int
    ): List<SymbolData> {
        val matches = mutableListOf<SymbolData>()

        // Get all registered symbol contributors via extension point
        val contributors = ChooseByNameContributor.SYMBOL_EP_NAME.extensionList

        for (contributor in contributors) {
            if (matches.size >= limit) break

            try {
                if (contributor is ChooseByNameContributorEx) {
                    // Use the modern ChooseByNameContributorEx interface
                    val matchingNames = mutableListOf<String>()

                    contributor.processNames(
                        { name ->
                            if (matchesQuery(name, pattern)) {
                                matchingNames.add(name)
                            }
                            matchingNames.size < limit * 2 // Collect more names for better matching
                        },
                        scope,
                        null
                    )

                    for (name in matchingNames) {
                        if (matches.size >= limit) break

                        val params = FindSymbolParameters.wrap(pattern, scope)
                        contributor.processElementsWithName(
                            name,
                            { item ->
                                if (matches.size >= limit) return@processElementsWithName false

                                val element = when (item) {
                                    is PsiElement -> item
                                    is NavigationItem -> {
                                        // Try to get PSI element from NavigationItem
                                        try {
                                            val getElementMethod = item.javaClass.getMethod("getElement")
                                            getElementMethod.invoke(item) as? PsiElement
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                    else -> null
                                }

                                if (element != null && isJavaScriptLanguage(element)) {
                                    val file = element.containingFile?.virtualFile
                                    if (file != null) {
                                        matches.add(SymbolData(
                                            name = getName(element) ?: name,
                                            qualifiedName = getQualifiedName(element),
                                            kind = determineElementKind(element),
                                            file = getRelativePath(project, file),
                                            line = getLineNumber(project, element) ?: 1,
                                            containerName = findContainingJSClass(element)?.let { getName(it) },
                                            language = getLanguageName(element)
                                        ))
                                    }
                                }
                                true
                            },
                            params
                        )
                    }
                } else {
                    // Use legacy ChooseByNameContributor interface
                    val names = contributor.getNames(project, true)
                    val matchingNames = names.filter { matchesQuery(it, pattern) }

                    for (name in matchingNames) {
                        if (matches.size >= limit) break

                        val items = contributor.getItemsByName(name, pattern, project, true)
                        for (item in items) {
                            if (matches.size >= limit) break

                            val element = when (item) {
                                is PsiElement -> item
                                is NavigationItem -> {
                                    try {
                                        val getElementMethod = item.javaClass.getMethod("getElement")
                                        getElementMethod.invoke(item) as? PsiElement
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                else -> null
                            }

                            if (element != null && isJavaScriptLanguage(element)) {
                                val file = element.containingFile?.virtualFile
                                if (file != null) {
                                    matches.add(SymbolData(
                                        name = getName(element) ?: name,
                                        qualifiedName = getQualifiedName(element),
                                        kind = determineElementKind(element),
                                        file = getRelativePath(project, file),
                                        line = getLineNumber(project, element) ?: 1,
                                        containerName = findContainingJSClass(element)?.let { getName(it) },
                                        language = getLanguageName(element)
                                    ))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("Error processing contributor ${contributor.javaClass.name}: ${e.message}")
            }
        }

        return matches
    }

    /**
     * Search using JSGotoSymbolContributor directly via reflection.
     */
    private fun searchUsingJSGotoSymbolContributor(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int
    ): List<SymbolData> {
        val matches = mutableListOf<SymbolData>()

        try {
            val contributorClass = Class.forName("com.intellij.lang.javascript.navigation.JSGotoSymbolContributor")
            val contributor = contributorClass.getDeclaredConstructor().newInstance()

            // Check if it implements ChooseByNameContributorEx
            if (contributor is ChooseByNameContributorEx) {
                val matchingNames = mutableListOf<String>()

                contributor.processNames(
                    { name ->
                        if (matchesQuery(name, pattern)) {
                            matchingNames.add(name)
                        }
                        matchingNames.size < limit * 2
                    },
                    scope,
                    null
                )

                for (name in matchingNames) {
                    if (matches.size >= limit) break

                    val params = FindSymbolParameters.wrap(pattern, scope)
                    contributor.processElementsWithName(
                        name,
                        { item ->
                            if (matches.size >= limit) return@processElementsWithName false

                            val element = when (item) {
                                is PsiElement -> item
                                else -> {
                                    try {
                                        val getElementMethod = item.javaClass.getMethod("getElement")
                                        getElementMethod.invoke(item) as? PsiElement
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                            }

                            if (element != null) {
                                val file = element.containingFile?.virtualFile
                                if (file != null && scope.contains(file)) {
                                    matches.add(SymbolData(
                                        name = getName(element) ?: name,
                                        qualifiedName = getQualifiedName(element),
                                        kind = determineElementKind(element),
                                        file = getRelativePath(project, file),
                                        line = getLineNumber(project, element) ?: 1,
                                        containerName = findContainingJSClass(element)?.let { getName(it) },
                                        language = getLanguageName(element)
                                    ))
                                }
                            }
                            true
                        },
                        params
                    )
                }
            } else {
                // Use legacy interface
                val getNamesMethod = contributorClass.getMethod("getNames", Project::class.java, Boolean::class.javaPrimitiveType)
                val names = getNamesMethod.invoke(contributor, project, true) as? Array<String> ?: return emptyList()

                val matchingNames = names.filter { matchesQuery(it, pattern) }

                val getItemsByNameMethod = contributorClass.getMethod(
                    "getItemsByName",
                    String::class.java,
                    String::class.java,
                    Project::class.java,
                    Boolean::class.javaPrimitiveType
                )

                for (name in matchingNames) {
                    if (matches.size >= limit) break

                    val items = getItemsByNameMethod.invoke(contributor, name, pattern, project, true) as? Array<*>
                    items?.filterIsInstance<NavigationItem>()?.forEach { item ->
                        if (matches.size >= limit) return@forEach

                        val element = try {
                            when (item) {
                                is PsiElement -> item
                                else -> {
                                    val getElementMethod = item.javaClass.getMethod("getElement")
                                    getElementMethod.invoke(item) as? PsiElement
                                }
                            }
                        } catch (e: Exception) {
                            null
                        }

                        if (element != null) {
                            val file = element.containingFile?.virtualFile
                            if (file != null && scope.contains(file)) {
                                matches.add(SymbolData(
                                    name = getName(element) ?: name,
                                    qualifiedName = getQualifiedName(element),
                                    kind = determineElementKind(element),
                                    file = getRelativePath(project, file),
                                    line = getLineNumber(project, element) ?: 1,
                                    containerName = findContainingJSClass(element)?.let { getName(it) },
                                    language = getLanguageName(element)
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: ClassNotFoundException) {
            LOG.debug("JSGotoSymbolContributor not found")
        } catch (e: Exception) {
            LOG.debug("Error using JSGotoSymbolContributor: ${e.message}")
        }

        return matches
    }

    /**
     * Fallback: Scan JS/TS files in the project and find named elements.
     */
    private fun searchUsingFileScan(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int
    ): List<SymbolData> {
        val matches = mutableListOf<SymbolData>()

        try {
            // Find all JS/TS files
            val extensions = listOf("js", "jsx", "ts", "tsx", "mjs")
            val psiManager = PsiManager.getInstance(project)

            for (ext in extensions) {
                if (matches.size >= limit) break

                val files = FilenameIndex.getAllFilesByExt(project, ext, scope)

                for (file in files) {
                    if (matches.size >= limit) break

                    val psiFile = psiManager.findFile(file) ?: continue

                    // Find named elements in the file
                    findNamedElementsInFile(psiFile, pattern, project, file).forEach { symbolData ->
                        if (matches.size < limit && matches.none {
                            it.file == symbolData.file && it.line == symbolData.line && it.name == symbolData.name
                        }) {
                            matches.add(symbolData)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("File scan failed: ${e.message}")
        }

        return matches
    }

    private fun findNamedElementsInFile(
        psiFile: PsiFile,
        pattern: String,
        project: Project,
        file: com.intellij.openapi.vfs.VirtualFile
    ): List<SymbolData> {
        val results = mutableListOf<SymbolData>()

        // Find JSClasses
        jsClassClass?.let { cls ->
            @Suppress("UNCHECKED_CAST")
            PsiTreeUtil.findChildrenOfType(psiFile, cls as Class<out PsiElement>).forEach { element ->
                val name = getName(element)
                if (name != null && matchesQuery(name, pattern)) {
                    results.add(SymbolData(
                        name = name,
                        qualifiedName = getQualifiedName(element),
                        kind = getClassKind(element),
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, element) ?: 1,
                        containerName = null,
                        language = getLanguageName(element)
                    ))
                }
            }
        }

        // Find JSFunctions (top-level only)
        jsFunctionClass?.let { cls ->
            @Suppress("UNCHECKED_CAST")
            PsiTreeUtil.findChildrenOfType(psiFile, cls as Class<out PsiElement>).forEach { element ->
                // Skip methods (those inside classes)
                val containingClass = findContainingJSClass(element)
                if (containingClass == null || containingClass == element) {
                    val name = getName(element)
                    if (name != null && matchesQuery(name, pattern)) {
                        results.add(SymbolData(
                            name = name,
                            qualifiedName = null,
                            kind = if (containingClass != null && containingClass != element) "METHOD" else "FUNCTION",
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, element) ?: 1,
                            containerName = containingClass?.let { getName(it) },
                            language = getLanguageName(element)
                        ))
                    }
                }
            }
        }

        // Find JSVariables (top-level exports/constants)
        jsVariableClass?.let { cls ->
            @Suppress("UNCHECKED_CAST")
            PsiTreeUtil.findChildrenOfType(psiFile, cls as Class<out PsiElement>).forEach { element ->
                val containingClass = findContainingJSClass(element)
                val containingFunction = findContainingJSFunction(element)
                // Only include top-level variables
                if (containingClass == null && containingFunction == null) {
                    val name = getName(element)
                    if (name != null && matchesQuery(name, pattern)) {
                        results.add(SymbolData(
                            name = name,
                            qualifiedName = null,
                            kind = "VARIABLE",
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, element) ?: 1,
                            containerName = null,
                            language = getLanguageName(element)
                        ))
                    }
                }
            }
        }

        return results
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
        return isAvailable() && isJavaScriptLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaScriptPluginDetector.isJavaScriptPluginAvailable && jsFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val jsFunction = findContainingJSFunction(element) ?: return null
        val containingClass = findContainingJSClass(jsFunction) ?: return null

        LOG.debug("Finding super methods for ${getName(jsFunction)} in ${getName(containingClass)}")

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
        LOG.debug("Found ${hierarchy.size} super methods")

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
            LOG.debug("Error building hierarchy: ${e.message}")
        }

        return hierarchy
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

package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.JavaPluginDetector
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Registration entry point for Java language handlers.
 *
 * This class is loaded via reflection when the Java plugin is available.
 * It registers all Java-specific handlers with the [LanguageHandlerRegistry].
 */
object JavaHandlers {

    private val LOG = logger<JavaHandlers>()

    /**
     * Registers all Java handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!JavaPluginDetector.isJavaPluginAvailable) {
            LOG.info("Java plugin not available, skipping Java handler registration")
            return
        }

        registry.registerTypeHierarchyHandler(JavaTypeHierarchyHandler())
        registry.registerImplementationsHandler(JavaImplementationsHandler())
        registry.registerCallHierarchyHandler(JavaCallHierarchyHandler())
        registry.registerSymbolSearchHandler(JavaSymbolSearchHandler())
        registry.registerSuperMethodsHandler(JavaSuperMethodsHandler())
        registry.registerStructureHandler(JavaStructureHandler())

        // Also register for Kotlin (uses same Java PSI under the hood)
        registry.registerTypeHierarchyHandler(KotlinTypeHierarchyHandler())
        registry.registerImplementationsHandler(KotlinImplementationsHandler())
        registry.registerCallHierarchyHandler(KotlinCallHierarchyHandler())
        registry.registerSymbolSearchHandler(KotlinSymbolSearchHandler())
        registry.registerSuperMethodsHandler(KotlinSuperMethodsHandler())
        registry.registerStructureHandler(KotlinStructureHandler())

        LOG.info("Registered Java and Kotlin handlers")
    }
}

// Base class for Java handlers with common utilities
abstract class BaseJavaHandler<T> : LanguageHandler<T> {

    companion object {
        private val LOG = logger<BaseJavaHandler<*>>()

        /**
         * Maximum depth for traversing parent chain when searching for Kotlin PSI elements.
         * 50 levels is sufficient for even deeply nested code structures.
         */
        private const val MAX_PARENT_TRAVERSAL_DEPTH = 50

        /**
         * Maximum depth for searching parent chain for references.
         * 3 levels covers common cases: identifier -> expression -> call expression.
         */
        private const val MAX_REFERENCE_SEARCH_DEPTH = 3

        // Kotlin PSI classes (loaded via reflection to avoid compile-time dependency)
        private val ktClassOrObjectClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.psi.KtClassOrObject")
            } catch (e: ClassNotFoundException) {
                LOG.debug("Kotlin KtClassOrObject class not found: ${e.message}")
                null
            }
        }

        private val ktNamedFunctionClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            } catch (e: ClassNotFoundException) {
                LOG.debug("Kotlin KtNamedFunction class not found: ${e.message}")
                null
            }
        }

        // toLightClass extension function location
        private val lightClassExtensionsClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.asJava.LightClassUtilsKt")
            } catch (e: ClassNotFoundException) {
                LOG.debug("Kotlin LightClassUtilsKt class not found: ${e.message}")
                null
            }
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

    protected fun getClassKind(psiClass: PsiClass): String {
        return when {
            psiClass.isInterface -> "INTERFACE"
            psiClass.isEnum -> "ENUM"
            psiClass.isAnnotationType -> "ANNOTATION"
            psiClass.isRecord -> "RECORD"
            psiClass.hasModifierProperty("abstract") -> "ABSTRACT_CLASS"
            else -> "CLASS"
        }
    }

    protected fun findContainingClass(element: PsiElement): PsiClass? {
        if (element is PsiClass) return element
        return PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
    }

    protected fun findContainingMethod(element: PsiElement): PsiMethod? {
        if (element is PsiMethod) return element
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    }

    /**
     * Resolves a method from a position, using semantic reference resolution first.
     *
     * This correctly handles:
     * - Method calls: `obj.doWork()` → resolves to the `doWork` method declaration
     * - Method declarations: cursor ON a method → returns that method
     * - Kotlin functions: finds KtNamedFunction and converts to light method
     *
     * @param element The leaf PSI element at a position
     * @return The resolved PsiMethod, or null if not found
     */
    protected fun resolveMethod(element: PsiElement): PsiMethod? {
        // If element is already a method, return it
        if (element is PsiMethod) return element

        // Try reference resolution first (for method calls/references)
        val resolved = resolveReference(element)
        if (resolved is PsiMethod) return resolved

        // Fallback based on language
        return if (element.language.id == "kotlin") {
            resolveKotlinMethod(element)
        } else {
            PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        }
    }

    /**
     * Resolves a Kotlin function to its light method (PsiMethod).
     */
    private fun resolveKotlinMethod(element: PsiElement): PsiMethod? {
        val ktFunctionClass = ktNamedFunctionClass ?: return null
        val lightClassExtensions = lightClassExtensionsClass ?: return null

        // Find KtNamedFunction in parent chain
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth < MAX_PARENT_TRAVERSAL_DEPTH) {
            if (ktFunctionClass.isInstance(current)) {
                // Convert to light method via toLightMethods() extension function
                return try {
                    val toLightMethodsMethod = lightClassExtensions.getMethod("toLightMethods", PsiElement::class.java)
                    val lightMethods = toLightMethodsMethod.invoke(null, current) as? List<*>
                    lightMethods?.firstOrNull() as? PsiMethod
                } catch (e: ReflectiveOperationException) {
                    LOG.debug("Failed to get light method for Kotlin function: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Resolves a class from a position, using semantic reference resolution first.
     *
     * This correctly handles:
     * - Type references: `MyClass obj` → resolves to `MyClass` declaration
     * - Class declarations: cursor ON a class → returns that class
     * - Kotlin classes: finds KtClassOrObject and converts to light class
     *
     * @param element The leaf PSI element at a position
     * @return The resolved PsiClass, or null if not found
     */
    protected fun resolveClass(element: PsiElement): PsiClass? {
        // If element is already a class, return it
        if (element is PsiClass) return element

        // Try reference resolution first (for type references)
        val resolved = resolveReference(element)
        if (resolved is PsiClass) return resolved

        // Fallback based on language
        return if (element.language.id == "kotlin") {
            resolveKotlinClass(element)
        } else {
            PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        }
    }

    /**
     * Resolves a Kotlin class/object to its light class (PsiClass).
     */
    private fun resolveKotlinClass(element: PsiElement): PsiClass? {
        val ktClassOrObject = ktClassOrObjectClass ?: return null
        val lightClassExtensions = lightClassExtensionsClass ?: return null

        // Find KtClassOrObject in parent chain
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth < MAX_PARENT_TRAVERSAL_DEPTH) {
            if (ktClassOrObject.isInstance(current)) {
                // Convert to light class via toLightClass extension function
                return try {
                    val toLightClassMethod = lightClassExtensions.getMethod("toLightClass", ktClassOrObject)
                    toLightClassMethod.invoke(null, current) as? PsiClass
                } catch (e: ReflectiveOperationException) {
                    LOG.debug("Failed to get light class for Kotlin class: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Resolves a reference from an element or its parents.
     *
     * Similar to [com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils.findReferenceInParent]
     * but intentionally kept separate because this class adds Kotlin light class resolution
     * via [resolveMethod] and [resolveClass].
     *
     * @param element The starting element
     * @return The resolved element, or null
     */
    private fun resolveReference(element: PsiElement): PsiElement? {
        // Try direct reference
        element.reference?.resolve()?.let { return it }

        // Try parent references (some PSI structures have reference on parent)
        var current: PsiElement? = element
        repeat(MAX_REFERENCE_SEARCH_DEPTH) {
            current = current?.parent ?: return null
            current?.reference?.resolve()?.let { return it }
        }
        return null
    }

    protected fun isJavaOrKotlinLanguage(element: PsiElement): Boolean {
        val langId = element.language.id
        return langId == "JAVA" || langId == "kotlin"
    }
}

/**
 * Java implementation of [TypeHierarchyHandler].
 */
class JavaTypeHierarchyHandler : BaseJavaHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 100
    }

    override val languageId = "JAVA"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaOrKotlinLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaPluginDetector.isJavaPluginAvailable

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        // Use reference-aware resolution: if cursor is on a type reference,
        // resolve to the actual class being referenced
        val psiClass = resolveClass(element) ?: return null

        val supertypes = getSupertypes(project, psiClass)
        val subtypes = getSubtypes(project, psiClass)

        return TypeHierarchyData(
            element = TypeElementData(
                name = psiClass.qualifiedName ?: psiClass.name ?: "unknown",
                qualifiedName = psiClass.qualifiedName,
                file = psiClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, psiClass),
                kind = getClassKind(psiClass),
                language = "Java"
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun getSupertypes(
        project: Project,
        psiClass: PsiClass,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val className = psiClass.qualifiedName ?: psiClass.name ?: return emptyList()
        if (className in visited) return emptyList()
        visited.add(className)

        val supertypes = mutableListOf<TypeElementData>()

        // Try resolved superclass first
        val superClass = psiClass.superClass
        if (superClass != null && superClass.qualifiedName != "java.lang.Object") {
            val superSupertypes = getSupertypes(project, superClass, visited, depth + 1)
            supertypes.add(TypeElementData(
                name = superClass.qualifiedName ?: superClass.name ?: "unknown",
                qualifiedName = superClass.qualifiedName,
                file = superClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, superClass),
                kind = getClassKind(superClass),
                language = if (superClass.language.id == "kotlin") "Kotlin" else "Java",
                supertypes = superSupertypes.takeIf { it.isNotEmpty() }
            ))
        } else {
            // Fallback: check unresolved extends list (when type resolution fails)
            psiClass.extendsList?.referenceElements?.forEach { ref ->
                val resolved = ref.resolve() as? PsiClass
                if (resolved != null && resolved.qualifiedName != "java.lang.Object") {
                    val superSupertypes = getSupertypes(project, resolved, visited, depth + 1)
                    supertypes.add(TypeElementData(
                        name = resolved.qualifiedName ?: resolved.name ?: "unknown",
                        qualifiedName = resolved.qualifiedName,
                        file = resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, resolved),
                        kind = getClassKind(resolved),
                        language = if (resolved.language.id == "kotlin") "Kotlin" else "Java",
                        supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                    ))
                } else {
                    // Can't resolve, but report the declared type name
                    val typeName = ref.qualifiedName ?: ref.referenceName ?: "unknown"
                    if (typeName != "java.lang.Object") {
                        supertypes.add(TypeElementData(
                            name = typeName,
                            qualifiedName = ref.qualifiedName,
                            file = null,
                            line = null,
                            kind = "CLASS",
                            language = "Java"
                        ))
                    }
                }
            }
        }

        // Try resolved interfaces first
        val interfaces = psiClass.interfaces
        if (interfaces.isNotEmpty()) {
            for (iface in interfaces) {
                val ifaceSupertypes = getSupertypes(project, iface, visited, depth + 1)
                supertypes.add(TypeElementData(
                    name = iface.qualifiedName ?: iface.name ?: "unknown",
                    qualifiedName = iface.qualifiedName,
                    file = iface.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, iface),
                    kind = "INTERFACE",
                    language = if (iface.language.id == "kotlin") "Kotlin" else "Java",
                    supertypes = ifaceSupertypes.takeIf { it.isNotEmpty() }
                ))
            }
        } else {
            // Fallback: check unresolved implements list (when type resolution fails)
            psiClass.implementsList?.referenceElements?.forEach { ref ->
                val resolved = ref.resolve() as? PsiClass
                if (resolved != null) {
                    val ifaceSupertypes = getSupertypes(project, resolved, visited, depth + 1)
                    supertypes.add(TypeElementData(
                        name = resolved.qualifiedName ?: resolved.name ?: "unknown",
                        qualifiedName = resolved.qualifiedName,
                        file = resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, resolved),
                        kind = "INTERFACE",
                        language = if (resolved.language.id == "kotlin") "Kotlin" else "Java",
                        supertypes = ifaceSupertypes.takeIf { it.isNotEmpty() }
                    ))
                } else {
                    // Can't resolve, but report the declared type name
                    val typeName = ref.qualifiedName ?: ref.referenceName ?: "unknown"
                    supertypes.add(TypeElementData(
                        name = typeName,
                        qualifiedName = ref.qualifiedName,
                        file = null,
                        line = null,
                        kind = "INTERFACE",
                        language = "Java"
                    ))
                }
            }
        }

        return supertypes
    }

    private fun getSubtypes(project: Project, psiClass: PsiClass): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()
        try {
            ClassInheritorsSearch.search(psiClass, true).forEach(Processor { subClass ->
                results.add(TypeElementData(
                    name = subClass.qualifiedName ?: subClass.name ?: "unknown",
                    qualifiedName = subClass.qualifiedName,
                    file = subClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, subClass),
                    kind = getClassKind(subClass),
                    language = if (subClass.language.id == "kotlin") "Kotlin" else "Java"
                ))
                results.size < 100
            })
        } catch (_: Exception) {
            // Handle gracefully
        }
        return results
    }
}

/**
 * Java implementation of [ImplementationsHandler].
 */
class JavaImplementationsHandler : BaseJavaHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "JAVA"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaOrKotlinLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaPluginDetector.isJavaPluginAvailable

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        // Use reference-aware resolution: if cursor is on a method call/reference,
        // resolve to the actual method being referenced, not the containing method
        val method = resolveMethod(element)
        if (method != null) {
            return findMethodImplementations(project, method)
        }

        // Use reference-aware resolution for classes too
        val psiClass = resolveClass(element)
        if (psiClass != null) {
            return findClassImplementations(project, psiClass)
        }

        return null
    }

    private fun findMethodImplementations(project: Project, method: PsiMethod): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        try {
            OverridingMethodsSearch.search(method).forEach(Processor { overridingMethod ->
                val file = overridingMethod.containingFile?.virtualFile
                if (file != null) {
                    results.add(ImplementationData(
                        name = "${overridingMethod.containingClass?.name}.${overridingMethod.name}",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, overridingMethod) ?: 0,
                        kind = "METHOD",
                        language = if (overridingMethod.language.id == "kotlin") "Kotlin" else "Java"
                    ))
                }
                results.size < 100
            })
        } catch (_: Exception) {
            // Handle gracefully
        }
        return results
    }

    private fun findClassImplementations(project: Project, psiClass: PsiClass): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        try {
            ClassInheritorsSearch.search(psiClass, true).forEach(Processor { inheritor ->
                val file = inheritor.containingFile?.virtualFile
                if (file != null) {
                    results.add(ImplementationData(
                        name = inheritor.qualifiedName ?: inheritor.name ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, inheritor) ?: 0,
                        kind = getClassKind(inheritor),
                        language = if (inheritor.language.id == "kotlin") "Kotlin" else "Java"
                    ))
                }
                results.size < 100
            })
        } catch (_: Exception) {
            // Handle gracefully
        }
        return results
    }
}

/**
 * Java implementation of [CallHierarchyHandler].
 */
class JavaCallHierarchyHandler : BaseJavaHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
    }

    override val languageId = "JAVA"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaOrKotlinLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaPluginDetector.isJavaPluginAvailable

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int
    ): CallHierarchyData? {
        // Use reference-aware resolution: if cursor is on a method call,
        // resolve to the actual method being called
        val method = resolveMethod(element) ?: return null
        val visited = mutableSetOf<String>()

        val calls = if (direction == "callers") {
            findCallersRecursive(project, method, depth, visited)
        } else {
            findCalleesRecursive(project, method, depth, visited)
        }

        return CallHierarchyData(
            element = createCallElement(project, method),
            calls = calls
        )
    }

    private fun findCallersRecursive(
        project: Project,
        method: PsiMethod,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val methodKey = getMethodKey(method)
        if (methodKey in visited) return emptyList()
        visited.add(methodKey)

        return try {
            val methodsToSearch = mutableSetOf(method)
            methodsToSearch.addAll(method.findDeepestSuperMethods().take(10))

            val allReferences = mutableListOf<PsiElement>()
            for (methodToSearch in methodsToSearch) {
                if (allReferences.size >= MAX_RESULTS_PER_LEVEL * 2) break
                MethodReferencesSearch.search(methodToSearch, GlobalSearchScope.projectScope(project), true)
                    .forEach(Processor { reference ->
                        allReferences.add(reference.element)
                        allReferences.size < MAX_RESULTS_PER_LEVEL * 2
                    })
            }

            allReferences
                .take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { refElement ->
                    val containingMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod::class.java)
                    if (containingMethod != null && containingMethod != method && !methodsToSearch.contains(containingMethod)) {
                        val children = if (depth > 1) {
                            findCallersRecursive(project, containingMethod, depth - 1, visited, stackDepth + 1)
                        } else null
                        createCallElement(project, containingMethod, children)
                    } else null
                }
                .distinctBy { it.name + it.file + it.line }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findCalleesRecursive(
        project: Project,
        method: PsiMethod,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val methodKey = getMethodKey(method)
        if (methodKey in visited) return emptyList()
        visited.add(methodKey)

        val callees = mutableListOf<CallElementData>()
        try {
            method.body?.let { body ->
                PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
                    .take(MAX_RESULTS_PER_LEVEL)
                    .forEach { methodCall ->
                        val calledMethod = methodCall.resolveMethod()
                        if (calledMethod != null) {
                            val children = if (depth > 1) {
                                findCalleesRecursive(project, calledMethod, depth - 1, visited, stackDepth + 1)
                            } else null
                            val element = createCallElement(project, calledMethod, children)
                            if (callees.none { it.name == element.name && it.file == element.file }) {
                                callees.add(element)
                            }
                        } else {
                            // Fallback: can't resolve method, but report the call expression text
                            val callText = methodCall.methodExpression.referenceName ?: methodCall.text.take(50)
                            val unresolvedElement = CallElementData(
                                name = "$callText(...) [unresolved]",
                                file = "unknown",
                                line = 0,
                                language = "Java",
                                children = null
                            )
                            if (callees.none { it.name == unresolvedElement.name }) {
                                callees.add(unresolvedElement)
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }
        return callees
    }

    private fun getMethodKey(method: PsiMethod): String {
        val className = method.containingClass?.qualifiedName ?: ""
        val params = method.parameterList.parameters.joinToString(",") {
            try { it.type.canonicalText } catch (e: Exception) { "?" }
        }
        return "$className.${method.name}($params)"
    }

    private fun createCallElement(project: Project, method: PsiMethod, children: List<CallElementData>? = null): CallElementData {
        val file = method.containingFile?.virtualFile
        val methodName = buildString {
            method.containingClass?.name?.let { append(it).append(".") }
            append(method.name)
            append("(")
            append(method.parameterList.parameters.joinToString(", ") {
                try { it.type.presentableText } catch (e: Exception) { "?" }
            })
            append(")")
        }
        return CallElementData(
            name = methodName,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, method) ?: 0,
            language = if (method.language.id == "kotlin") "Kotlin" else "Java",
            children = children?.takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * Java implementation of [SymbolSearchHandler].
 *
 * Uses the optimized [OptimizedSymbolSearch] infrastructure which leverages IntelliJ's
 * built-in "Go to Symbol" APIs with caching, word index, and prefix matching.
 *
 * This replaces the manual iteration over PsiShortNamesCache which was O(n) for
 * all symbols in large monorepos.
 */
class JavaSymbolSearchHandler : BaseJavaHandler<List<SymbolData>>(), SymbolSearchHandler {

    override val languageId = "JAVA"

    override fun canHandle(element: PsiElement): Boolean = isAvailable()

    override fun isAvailable(): Boolean = JavaPluginDetector.isJavaPluginAvailable

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

        // Use the optimized platform-based search with language filter for Java/Kotlin
        return OptimizedSymbolSearch.search(
            project = project,
            pattern = pattern,
            scope = scope,
            limit = limit,
            languageFilter = setOf("Java", "Kotlin")
        )
    }
}

/**
 * Java implementation of [SuperMethodsHandler].
 */
class JavaSuperMethodsHandler : BaseJavaHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "JAVA"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaOrKotlinLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaPluginDetector.isJavaPluginAvailable

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        // Use reference-aware resolution: if cursor is on a method call,
        // resolve to the actual method being referenced
        val method = resolveMethod(element) ?: return null
        val containingClass = method.containingClass ?: return null

        val file = method.containingFile?.virtualFile
        val methodData = MethodData(
            name = method.name,
            signature = buildMethodSignature(method),
            containingClass = containingClass.qualifiedName ?: containingClass.name ?: "unknown",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, method) ?: 0,
            language = if (method.language.id == "kotlin") "Kotlin" else "Java"
        )

        val hierarchy = buildHierarchy(project, method)

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        method: PsiMethod,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        for (superMethod in method.findSuperMethods()) {
            val key = "${superMethod.containingClass?.qualifiedName}.${superMethod.name}"
            if (key in visited) continue
            visited.add(key)

            val containingClass = superMethod.containingClass
            val file = superMethod.containingFile?.virtualFile

            hierarchy.add(SuperMethodData(
                name = superMethod.name,
                signature = buildMethodSignature(superMethod),
                containingClass = containingClass?.qualifiedName ?: containingClass?.name ?: "unknown",
                containingClassKind = containingClass?.let { getClassKind(it) } ?: "UNKNOWN",
                file = file?.let { getRelativePath(project, it) },
                line = getLineNumber(project, superMethod),
                isInterface = containingClass?.isInterface == true,
                depth = depth,
                language = if (superMethod.language.id == "kotlin") "Kotlin" else "Java"
            ))

            hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
        }

        return hierarchy
    }

    private fun buildMethodSignature(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        val returnType = method.returnType?.presentableText ?: "void"
        return "${method.name}($params): $returnType"
    }
}

// Kotlin handlers delegate to Java handlers since Kotlin uses Java PSI under the hood

class KotlinTypeHierarchyHandler : TypeHierarchyHandler by JavaTypeHierarchyHandler() {
    override val languageId = "kotlin"
}

class KotlinImplementationsHandler : ImplementationsHandler by JavaImplementationsHandler() {
    override val languageId = "kotlin"
}

class KotlinCallHierarchyHandler : CallHierarchyHandler by JavaCallHierarchyHandler() {
    override val languageId = "kotlin"
}

class KotlinSymbolSearchHandler : SymbolSearchHandler by JavaSymbolSearchHandler() {
    override val languageId = "kotlin"
}

class KotlinSuperMethodsHandler : SuperMethodsHandler by JavaSuperMethodsHandler() {
    override val languageId = "kotlin"
}

/**
 * Java implementation of [StructureHandler].
 *
 * Extracts the hierarchical structure of Java source files including classes,
 * interfaces, enums, methods, fields, and their nesting relationships.
 */
class JavaStructureHandler : BaseJavaHandler<List<StructureNode>>(), StructureHandler {

    override val languageId = "JAVA"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isJavaOrKotlinLanguage(element)
    }

    override fun isAvailable(): Boolean = JavaPluginDetector.isJavaPluginAvailable

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val structure = mutableListOf<StructureNode>()

        // Get all top-level classes, interfaces, enums
        val classes: List<PsiClass> = when (file) {
            is PsiJavaFile -> file.classes.toList()
            else -> emptyList()
        }

        for (psiClass in classes) {
            structure.add(extractClassStructure(psiClass, project))
        }

        return structure
    }

    private fun extractClassStructure(psiClass: PsiClass, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()

        // Fields
        for (field in psiClass.fields) {
            children.add(extractFieldStructure(field, project))
        }

        // Constructors
        for (constructor in psiClass.constructors) {
            children.add(extractMethodStructure(constructor, project))
        }

        // Methods
        for (method in psiClass.methods) {
            children.add(extractMethodStructure(method, project))
        }

        // Inner classes
        for (innerClass in psiClass.innerClasses) {
            children.add(extractClassStructure(innerClass, project))
        }

        return StructureNode(
            name = psiClass.name ?: "anonymous",
            kind = when {
                psiClass.isInterface -> StructureKind.INTERFACE
                psiClass.isEnum -> StructureKind.ENUM
                psiClass.isAnnotationType -> StructureKind.ANNOTATION
                psiClass.isRecord -> StructureKind.RECORD
                psiClass.hasModifierProperty("abstract") -> StructureKind.CLASS
                else -> StructureKind.CLASS
            },
            modifiers = extractModifiers(psiClass.modifierList),
            signature = buildClassSignature(psiClass),
            line = getLineNumber(project, psiClass) ?: 0,
            children = children.sortedBy { it.line }
        )
    }

    private fun extractFieldStructure(field: PsiField, project: Project): StructureNode {
        return StructureNode(
            name = field.name,
            kind = StructureKind.FIELD,
            modifiers = extractModifiers(field.modifierList),
            signature = field.type.presentableText,
            line = getLineNumber(project, field) ?: 0
        )
    }

    private fun extractMethodStructure(method: PsiMethod, project: Project): StructureNode {
        return StructureNode(
            name = method.name,
            kind = if (method.isConstructor) StructureKind.CONSTRUCTOR else StructureKind.METHOD,
            modifiers = extractModifiers(method.modifierList),
            signature = buildMethodSignature(method),
            line = getLineNumber(project, method) ?: 0
        )
    }

    private fun extractModifiers(modifierList: PsiModifierList?): List<String> {
        if (modifierList == null) return emptyList()

        val modifiers = mutableListOf<String>()

        // Access modifiers
        when {
            modifierList.hasExplicitModifier("public") -> modifiers.add("public")
            modifierList.hasExplicitModifier("private") -> modifiers.add("private")
            modifierList.hasExplicitModifier("protected") -> modifiers.add("protected")
        }

        // Other modifiers
        if (modifierList.hasExplicitModifier("static")) modifiers.add("static")
        if (modifierList.hasExplicitModifier("final")) modifiers.add("final")
        if (modifierList.hasExplicitModifier("abstract")) modifiers.add("abstract")
        if (modifierList.hasExplicitModifier("synchronized")) modifiers.add("synchronized")
        if (modifierList.hasExplicitModifier("native")) modifiers.add("native")
        if (modifierList.hasModifierProperty("default")) modifiers.add("default")

        return modifiers
    }

    private fun buildClassSignature(psiClass: PsiClass): String {
        val typeParams = psiClass.typeParameters
        val extends = psiClass.superClass
        val implements = psiClass.interfaces

        val parts = mutableListOf<String>()

        // Type parameters
        if (typeParams.isNotEmpty()) {
            parts.add(typeParams.joinToString(", ", "<", ">") { it.name ?: "?" })
        }

        // Extends
        if (extends != null && extends.qualifiedName != "java.lang.Object") {
            parts.add("extends ${extends.name ?: "?"}")
        }

        // Implements
        if (implements.isNotEmpty()) {
            parts.add("implements ${implements.joinToString(", ") { it.name ?: "?" }}")
        }

        return if (parts.isNotEmpty()) parts.joinToString(" ") else ""
    }

    private fun buildMethodSignature(method: PsiMethod): String {
        val returnType = if (method.isConstructor) "" else "${method.returnType?.presentableText} "
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        return "$returnType($params)"
    }
}

/**
 * Kotlin implementation of [StructureHandler].
 *
 * Uses reflection to access Kotlin PSI classes since Kotlin files use a different
 * PSI structure than Java files (KtFile vs PsiJavaFile).
 */
class KotlinStructureHandler : BaseJavaHandler<List<StructureNode>>(), StructureHandler {

    companion object {
        private val LOG = logger<KotlinStructureHandler>()

        // Kotlin PSI classes (loaded via reflection)
        private val ktFileClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.psi.KtFile")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin KtFile class not found: ${e.message}")
                null
            }
        }

        private val ktNamedDeclarationClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.psi.KtNamedDeclaration")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin KtNamedDeclaration class not found: ${e.message}")
                null
            }
        }

        private val ktClassClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.psi.KtClass")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin KtClass class not found: ${e.message}")
                null
            }
        }

        private val ktNamedFunctionClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin KtNamedFunction class not found: ${e.message}")
                null
            }
        }

        private val ktPropertyClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.psi.KtProperty")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin KtProperty class not found: ${e.message}")
                null
            }
        }

        private val ktObjectDeclarationClass: Class<*>? by lazy {
            try {
                Class.forName("org.jetbrains.kotlin.psi.KtObjectDeclaration")
            } catch (e: ClassNotFoundException) {
                LOG.warn("Kotlin KtObjectDeclaration class not found: ${e.message}")
                null
            }
        }
    }

    override val languageId = "kotlin"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && element.language.id == "kotlin"
    }

    override fun isAvailable(): Boolean =
        JavaPluginDetector.isJavaPluginAvailable && ktFileClass != null

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val structure = mutableListOf<StructureNode>()

        // Check if this is a Kotlin file
        if (ktFileClass?.isInstance(file) != true) {
            LOG.debug("File is not a KtFile, delegating to Java handler")
            return JavaStructureHandler().getFileStructure(file, project)
        }

        try {
            // Get all declarations from the Kotlin file
            val getDeclarationsMethod = file.javaClass.getMethod("getDeclarations")
            val declarations = getDeclarationsMethod.invoke(file) as? List<*> ?: emptyList<Any?>()

            for (declaration in declarations) {
                if (declaration is PsiElement) {
                    val node = extractDeclarationStructure(declaration, project)
                    if (node != null) {
                        structure.add(node)
                    }
                }
            }

        } catch (e: Exception) {
            LOG.warn("Failed to extract Kotlin file structure: ${e.message}")
            // Fallback: try Java handler
            return JavaStructureHandler().getFileStructure(file, project)
        }

        return structure.sortedBy { it.line }
    }

    private fun extractDeclarationStructure(
        declaration: PsiElement,
        project: Project
    ): StructureNode? {
        return when {
            ktClassClass?.isInstance(declaration) == true ->
                extractClassStructure(declaration, project)
            ktNamedFunctionClass?.isInstance(declaration) == true ->
                extractFunctionStructure(declaration, project)
            ktPropertyClass?.isInstance(declaration) == true ->
                extractPropertyStructure(declaration, project)
            ktObjectDeclarationClass?.isInstance(declaration) == true ->
                extractObjectStructure(declaration, project)
            else -> null
        }
    }

    private fun extractClassStructure(ktClass: PsiElement, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()

        try {
            // Get class body and extract declarations
            val getBodyMethod = ktClass.javaClass.getMethod("getBody")
            val body = getBodyMethod.invoke(ktClass) as? PsiElement

            body?.let {
                val getChildrenMethod = it.javaClass.getMethod("getChildren")
                val bodyChildren = getChildrenMethod.invoke(it) as? Array<*> ?: emptyArray<Any?>()

                for (child in bodyChildren) {
                    if (child is PsiElement) {
                        val node = extractDeclarationStructure(child, project)
                        if (node != null) {
                            children.add(node)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to extract Kotlin class structure: ${e.message}")
        }

        return StructureNode(
            name = getName(ktClass) ?: "unknown",
            kind = getClassKind(ktClass),
            modifiers = getKotlinModifiers(ktClass),
            signature = buildKotlinClassSignature(ktClass),
            line = getLineNumber(project, ktClass) ?: 0,
            children = children.sortedBy { it.line }
        )
    }

    private fun extractFunctionStructure(function: PsiElement, project: Project): StructureNode {
        return StructureNode(
            name = getName(function) ?: "unknown",
            kind = StructureKind.FUNCTION,
            modifiers = getKotlinModifiers(function),
            signature = buildKotlinFunctionSignature(function),
            line = getLineNumber(project, function) ?: 0
        )
    }

    private fun extractPropertyStructure(property: PsiElement, project: Project): StructureNode {
        return StructureNode(
            name = getName(property) ?: "unknown",
            kind = StructureKind.PROPERTY,
            modifiers = getKotlinModifiers(property),
            signature = buildKotlinPropertySignature(property),
            line = getLineNumber(project, property) ?: 0
        )
    }

    private fun extractObjectStructure(obj: PsiElement, project: Project): StructureNode {
        return StructureNode(
            name = getName(obj) ?: "unknown",
            kind = StructureKind.OBJECT,
            modifiers = getKotlinModifiers(obj),
            signature = "",
            line = getLineNumber(project, obj) ?: 0
        )
    }

    private fun getName(element: PsiElement): String? {
        return try {
            if (ktNamedDeclarationClass?.isInstance(element) == true) {
                val getNameMethod = element.javaClass.getMethod("getName")
                getNameMethod.invoke(element) as? String
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun getClassKind(ktClass: PsiElement): StructureKind {
        return try {
            val isInterfaceMethod = ktClass.javaClass.getMethod("isInterface")
            val isInterface = isInterfaceMethod.invoke(ktClass) as? Boolean == true
            val isEnumMethod = ktClass.javaClass.getMethod("isEnum")
            val isEnum = isEnumMethod.invoke(ktClass) as? Boolean == true
            val isDataMethod = ktClass.javaClass.getMethod("isData")
            val isData = isDataMethod.invoke(ktClass) as? Boolean == true

            when {
                isInterface -> StructureKind.INTERFACE
                isEnum -> StructureKind.ENUM
                isData -> StructureKind.CLASS
                else -> StructureKind.CLASS
            }
        } catch (e: Exception) {
            StructureKind.CLASS
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun getKotlinModifiers(element: PsiElement): List<String> {
        // TODO: Extract Kotlin modifiers (public, private, suspend, etc.)
        // Currently returns empty list as Kotlin modifier API is complex
        return emptyList()
    }

    private fun buildKotlinClassSignature(ktClass: PsiElement): String {
        return try {
            val getSuperTypeListMethod = ktClass.javaClass.getMethod("getSuperTypeList")
            val superTypeList = getSuperTypeListMethod.invoke(ktClass) as? PsiElement

            if (superTypeList != null) {
                val getEntriesMethod = superTypeList.javaClass.getMethod("getEntries")
                val entries = getEntriesMethod.invoke(superTypeList) as? List<*> ?: emptyList<Any?>()

                if (entries.isNotEmpty()) {
                    val names = entries.mapNotNull {
                        (it as? PsiElement)?.text
                    }
                    return ": ${names.joinToString(", ")}"
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildKotlinFunctionSignature(function: PsiElement): String {
        return try {
            val getValueParameterListMethod = function.javaClass.getMethod("getValueParameterList")
            val parameterList = getValueParameterListMethod.invoke(function) as? PsiElement

            if (parameterList != null) {
                val getParametersMethod = parameterList.javaClass.getMethod("getParameters")
                val parameters = getParametersMethod.invoke(parameterList) as? List<*> ?: emptyList<Any?>()

                val params = parameters.filterIsInstance<PsiElement>().joinToString(", ") { param ->
                    param.text
                }
                "($params)"
            } else {
                "()"
            }
        } catch (_: Exception) {
            "()"
        }
    }

    private fun buildKotlinPropertySignature(property: PsiElement): String {
        return try {
            val getReturnTypeReferenceMethod = property.javaClass.getMethod("getTypeReference")
            val typeRef = getReturnTypeReferenceMethod.invoke(property) as? PsiElement
            typeRef?.text ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}

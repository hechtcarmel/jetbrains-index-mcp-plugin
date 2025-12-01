package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
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

        // Also register for Kotlin (uses same Java PSI under the hood)
        registry.registerTypeHierarchyHandler(KotlinTypeHierarchyHandler())
        registry.registerImplementationsHandler(KotlinImplementationsHandler())
        registry.registerCallHierarchyHandler(KotlinCallHierarchyHandler())
        registry.registerSymbolSearchHandler(KotlinSymbolSearchHandler())
        registry.registerSuperMethodsHandler(KotlinSuperMethodsHandler())

        LOG.info("Registered Java and Kotlin handlers")
    }
}

// Base class for Java handlers with common utilities
abstract class BaseJavaHandler<T> : LanguageHandler<T> {

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
        val psiClass = findContainingClass(element) ?: return null

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
        val method = findContainingMethod(element)
        if (method != null) {
            return findMethodImplementations(project, method)
        }

        val psiClass = findContainingClass(element)
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
        val method = findContainingMethod(element) ?: return null
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
        val method = findContainingMethod(element) ?: return null
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

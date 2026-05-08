package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.scala

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ScalaPluginDetector
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Registration entry point for Scala language handlers.
 *
 * This class is loaded via reflection when the Scala plugin is available.
 * It registers all Scala-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## Scala PSI Classes Used (via reflection)
 *
 * - `org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition` - Base for type definitions
 * - `org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass` - Class definitions
 * - `org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTrait` - Trait definitions
 * - `org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject` - Object/singleton definitions
 * - `org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction` - Function/method declarations
 * - `org.jetbrains.plugins.scala.lang.psi.api.expr.ScMethodCall` - Method calls
 * - `org.jetbrains.plugins.scala.lang.psi.stubs.index.ScAllClassNamesIndex` - Class name index
 * - `org.jetbrains.plugins.scala.lang.psi.stubs.index.ScFunctionNameIndex` - Function name index
 */
object ScalaHandlers {

    private val LOG = logger<ScalaHandlers>()

    /**
     * Registers all Scala handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!ScalaPluginDetector.isScalaPluginAvailable) {
            LOG.info("Scala plugin not available, skipping Scala handler registration")
            return
        }

        try {
            // Verify Scala classes are accessible before registering
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition")
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction")

            registry.registerTypeHierarchyHandler(ScalaTypeHierarchyHandler())
            registry.registerImplementationsHandler(ScalaImplementationsHandler())
            registry.registerCallHierarchyHandler(ScalaCallHierarchyHandler())
            registry.registerSuperMethodsHandler(ScalaSuperMethodsHandler())
            registry.registerStructureHandler(ScalaStructureHandler())

            LOG.info("Registered Scala handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Scala PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register Scala handlers: ${e.message}")
        }
    }
}

/**
 * Base class for Scala handlers with common utilities.
 *
 * Uses reflection to access Scala PSI classes to avoid compile-time dependencies.
 */
abstract class BaseScalaHandler<T> : LanguageHandler<T> {

    companion object {
        private val LOG = logger<BaseScalaHandler<*>>()
    }

    /**
     * Checks if the element is from Scala language.
     */
    protected fun isScalaLanguage(element: PsiElement): Boolean {
        return element.language.id == "Scala"
    }

    // Scala PSI class references (loaded via reflection)
    
    protected val scTypeDefinitionClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val scClassClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val scTraitClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTrait")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val scObjectClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val scTemplateDefinitionClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTemplateDefinition")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val scFunctionClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val scMethodCallClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.expr.ScMethodCall")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val scValueClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.statements.ScValue")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val scVariableClass: Class<*>? by lazy {
        try {
            Class.forName("org.jetbrains.plugins.scala.lang.psi.api.statements.ScVariable")
        } catch (e: ClassNotFoundException) {
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

    protected fun getColumnNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val line = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(line) + 1
    }

    /**
     * Checks if element is a ScTypeDefinition using reflection.
     */
    protected fun isScTypeDefinition(element: PsiElement): Boolean {
        return scTypeDefinitionClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a ScClass using reflection.
     */
    protected fun isScClass(element: PsiElement): Boolean {
        return scClassClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a ScTrait using reflection.
     */
    protected fun isScTrait(element: PsiElement): Boolean {
        return scTraitClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a ScObject using reflection.
     */
    protected fun isScObject(element: PsiElement): Boolean {
        return scObjectClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a ScFunction using reflection.
     */
    protected fun isScFunction(element: PsiElement): Boolean {
        return scFunctionClass?.isInstance(element) == true
    }

    /**
     * Finds containing ScTypeDefinition using reflection.
     */
    protected fun findContainingScTypeDefinition(element: PsiElement): PsiElement? {
        if (isScTypeDefinition(element)) return element
        val scTypeDef = scTypeDefinitionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, scTypeDef as Class<out PsiElement>)
    }

    /**
     * Finds containing ScFunction using reflection.
     */
    protected fun findContainingScFunction(element: PsiElement): PsiElement? {
        if (isScFunction(element)) return element
        val scFunc = scFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, scFunc as Class<out PsiElement>)
    }

    /**
     * Gets the name of a Scala named element via reflection.
     */
    protected fun getName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("name")
            method.invoke(element) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the qualified name of a ScTypeDefinition via reflection.
     */
    protected fun getQualifiedName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("qualifiedName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets supertypes of a ScTemplateDefinition via reflection.
     * Returns Scala Seq which we convert to List.
     */
    protected fun getSupers(element: PsiElement): List<PsiElement> {
        return try {
            val method = element.javaClass.getMethod("supers")
            val scalaSeq = method.invoke(element)
            scalaSeqToList(scalaSeq)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Converts Scala Seq to Java List using reflection.
     * Handles both scala.collection.Seq and scala.collection.immutable.Seq
     */
    protected fun scalaSeqToList(scalaSeq: Any?): List<PsiElement> {
        if (scalaSeq == null) return emptyList()
        
        return try {
            // Try to get iterator and convert to list
            val iteratorMethod = scalaSeq.javaClass.getMethod("iterator")
            val iterator = iteratorMethod.invoke(scalaSeq)
            
            val result = mutableListOf<PsiElement>()
            val hasNextMethod = iterator.javaClass.getMethod("hasNext")
            val nextMethod = iterator.javaClass.getMethod("next")
            
            while (hasNextMethod.invoke(iterator) as Boolean) {
                val element = nextMethod.invoke(iterator)
                if (element is PsiElement) {
                    result.add(element)
                }
            }
            result
        } catch (e: Exception) {
            LOG.debug("Failed to convert Scala Seq to List: ${e.message}")
            emptyList()
        }
    }

    /**
     * Unwraps Scala Option to nullable value using reflection.
     */
    protected fun <T> scalaOptionToNullable(scalaOption: Any?): T? {
        if (scalaOption == null) return null
        
        return try {
            val isDefinedMethod = scalaOption.javaClass.getMethod("isDefined")
            val isDefined = isDefinedMethod.invoke(scalaOption) as Boolean
            
            if (isDefined) {
                val getMethod = scalaOption.javaClass.getMethod("get")
                @Suppress("UNCHECKED_CAST")
                getMethod.invoke(scalaOption) as? T
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.debug("Failed to unwrap Scala Option: ${e.message}")
            null
        }
    }

    /**
     * Gets the type kind (CLASS, TRAIT, OBJECT) for a ScTypeDefinition.
     */
    protected fun getTypeKind(element: PsiElement): String {
        return when {
            isScTrait(element) -> "TRAIT"
            isScObject(element) -> "OBJECT"
            isScClass(element) -> {
                // Check if case class
                if (isCaseClass(element)) "CASE_CLASS" else "CLASS"
            }
            else -> "UNKNOWN"
        }
    }

    /**
     * Checks if a class is a case class via reflection.
     */
    protected fun isCaseClass(element: PsiElement): Boolean {
        return try {
            if (!isScClass(element)) return false
            val method = element.javaClass.getMethod("isCase")
            method.invoke(element) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Scala implementation of [TypeHierarchyHandler].
 *
 * Supports Scala classes, traits, objects, and case classes.
 * Handles multiple trait inheritance and trait linearization.
 */
class ScalaTypeHierarchyHandler : BaseScalaHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 100
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isScalaLanguage(element)
    }

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable && scTypeDefinitionClass != null

    override fun getTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope
    ): TypeHierarchyData? {
        val scTypeDef = findContainingScTypeDefinition(element) ?: return null
        val searchScope = createNavigationSearchScope(project, scope)

        val supertypes = getSupertypes(project, scTypeDef, searchScope = searchScope)
        val subtypes = getSubtypes(project, scTypeDef, searchScope)

        return TypeHierarchyData(
            element = TypeElementData(
                name = getQualifiedName(scTypeDef) ?: getName(scTypeDef) ?: "unknown",
                qualifiedName = getQualifiedName(scTypeDef),
                file = scTypeDef.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, scTypeDef),
                kind = getTypeKind(scTypeDef),
                language = "Scala"
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun getSupertypes(
        project: Project,
        scTypeDef: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val typeName = getQualifiedName(scTypeDef) ?: getName(scTypeDef) ?: return emptyList()
        if (typeName in visited || typeName == "scala.AnyRef" || typeName == "scala.Any") {
            return emptyList()
        }
        visited.add(typeName)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            val supers = getSupers(scTypeDef)
            for (superType in supers) {
                val superName = getQualifiedName(superType) ?: getName(superType)
                if (superName != null && superName != "scala.AnyRef" && superName != "scala.Any") {
                    if (!shouldIncludeNavigationElement(searchScope, superType)) continue
                    val superSupertypes = getSupertypes(project, superType, visited, depth + 1, searchScope)
                    supertypes.add(TypeElementData(
                        name = superName,
                        qualifiedName = getQualifiedName(superType),
                        file = superType.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superType),
                        kind = getTypeKind(superType),
                        language = "Scala",
                        supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                    ))
                }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }

        return supertypes
    }

    private fun getSubtypes(
        project: Project,
        scTypeDef: PsiElement,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()
        
        try {
            // ScTypeDefinition extends PsiClass — use platform API directly
            if (scTypeDef is com.intellij.psi.PsiClass) {
                ClassInheritorsSearch.search(scTypeDef, searchScope, true).forEach(Processor { inheritor ->
                    if (isScTypeDefinition(inheritor)) {
                        if (shouldIncludeNavigationElement(searchScope, inheritor)) {
                            val file = inheritor.containingFile?.virtualFile
                            results.add(TypeElementData(
                                name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                                qualifiedName = getQualifiedName(inheritor),
                                file = file?.let { getRelativePath(project, it) },
                                line = getLineNumber(project, inheritor),
                                kind = getTypeKind(inheritor),
                                language = "Scala"
                            ))
                        }
                    }
                    results.size < 100
                })
            }
        } catch (e: Exception) {
            // Handle gracefully
        }
        
        return results
    }
}

/**
 * Scala implementation of [ImplementationsHandler].
 *
 * Finds implementations of traits, abstract classes, and method overrides.
 */
class ScalaImplementationsHandler : BaseScalaHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isScalaLanguage(element)
    }

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable && scTypeDefinitionClass != null

    override fun findImplementations(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope
    ): List<ImplementationData>? {
        val searchScope = createNavigationSearchScope(project, scope)
        val function = findContainingScFunction(element)
        if (function != null) {
            return findMethodImplementations(project, function, searchScope)
        }

        val typeDef = findContainingScTypeDefinition(element)
        if (typeDef != null) {
            return findTypeImplementations(project, typeDef, searchScope)
        }

        return null
    }

    private fun findMethodImplementations(
        project: Project,
        function: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        
        try {
            // ScFunction extends PsiMethod — use platform API directly
            if (function is com.intellij.psi.PsiMethod) {
                OverridingMethodsSearch.search(function, searchScope, true).forEach(Processor { overriding ->
                    if (isScFunction(overriding)) {
                        if (shouldIncludeNavigationElement(searchScope, overriding)) {
                            val file = overriding.containingFile?.virtualFile
                            if (file != null) {
                                val containingType = findContainingScTypeDefinition(overriding)
                                val typeName = containingType?.let { getName(it) } ?: ""
                                val functionName = getName(overriding) ?: "unknown"
                                results.add(ImplementationData(
                                    name = if (typeName.isNotEmpty()) "$typeName.$functionName" else functionName,
                                    file = getRelativePath(project, file),
                                    line = getLineNumber(project, overriding) ?: 0,
                                    column = getColumnNumber(project, overriding) ?: 0,
                                    kind = "METHOD",
                                    language = "Scala"
                                ))
                            }
                        }
                    }
                    results.size < 100
                })
            }
        } catch (e: Exception) {
            // Handle gracefully
        }
        
        return results
    }

    private fun findTypeImplementations(
        project: Project,
        typeDef: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        
        try {
            // ScTypeDefinition extends PsiClass — use platform API directly
            if (typeDef is com.intellij.psi.PsiClass) {
                ClassInheritorsSearch.search(typeDef, searchScope, true).forEach(Processor { inheritor ->
                    if (isScTypeDefinition(inheritor)) {
                        if (shouldIncludeNavigationElement(searchScope, inheritor)) {
                            val file = inheritor.containingFile?.virtualFile
                            if (file != null) {
                                results.add(ImplementationData(
                                    name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                                    file = getRelativePath(project, file),
                                    line = getLineNumber(project, inheritor) ?: 0,
                                    column = getColumnNumber(project, inheritor) ?: 0,
                                    kind = getTypeKind(inheritor),
                                    language = "Scala"
                                ))
                            }
                        }
                    }
                    results.size < 100
                })
            }
        } catch (e: Exception) {
            // Handle gracefully
        }
        
        return results
    }
}

/**
 * Scala implementation of [CallHierarchyHandler].
 *
 * Finds callers and callees of Scala methods/functions.
 * Handles inheritance and method overrides for accurate call tree.
 */
class ScalaCallHierarchyHandler : BaseScalaHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
        private const val MAX_SUPER_METHODS = 10
        private val LOG = logger<ScalaCallHierarchyHandler>()
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isScalaLanguage(element)
    }

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable && scFunctionClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope
    ): CallHierarchyData? {
        val scFunction = findContainingScFunction(element) ?: return null
        val visited = mutableSetOf<String>()
        val searchScope = createNavigationSearchScope(project, scope)

        val calls = if (direction == "callers") {
            findCallersRecursive(project, scFunction, depth, visited, searchScope = searchScope)
        } else {
            findCalleesRecursive(project, scFunction, depth, visited, searchScope = searchScope)
        }

        return CallHierarchyData(
            element = createCallElement(project, scFunction),
            calls = calls
        )
    }

    /**
     * Finds all super methods that the given method overrides.
     * This is used to also search for callers of base methods, since those
     * calls could be dispatched to this method at runtime (polymorphism).
     */
    private fun findAllSuperMethods(project: Project, scFunction: PsiElement): Set<PsiElement> {
        val superMethods = mutableSetOf<PsiElement>()
        try {
            // ScFunction has a superMethods property that returns Seq[PsiMethod]
            val superMethodsMethod = scFunction.javaClass.getMethod("superMethods")
            val scalaSeq = superMethodsMethod.invoke(scFunction)
            val supers = scalaSeqToList(scalaSeq)
            superMethods.addAll(supers.take(MAX_SUPER_METHODS))
        } catch (e: Exception) {
            LOG.debug("Failed to get super methods: ${e.message}")
        }
        return superMethods
    }

    private fun findCallersRecursive(
        project: Project,
        scFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(scFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        return try {
            // Collect all methods to search: current method + all super methods it overrides
            val methodsToSearch = mutableSetOf(scFunction)
            methodsToSearch.addAll(findAllSuperMethods(project, scFunction))

            // Search for references to all methods in the hierarchy using platform API directly
            val allReferences = mutableListOf<com.intellij.psi.PsiReference>()
            for (methodToSearch in methodsToSearch) {
                if (allReferences.size >= MAX_RESULTS_PER_LEVEL) break
                ReferencesSearch.search(methodToSearch, searchScope).forEach(Processor { reference ->
                    allReferences.add(reference)
                    allReferences.size < MAX_RESULTS_PER_LEVEL
                })
            }

            allReferences.take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val containingFunction = findContainingScFunction(refElement)
                    if (containingFunction != null && containingFunction != scFunction && !methodsToSearch.contains(containingFunction)) {
                        val containingFile = containingFunction.containingFile?.virtualFile
                        if (containingFile == null || !searchScope.contains(containingFile)) {
                            return@mapNotNull null
                        }
                        val children = if (depth > 1) {
                            findCallersRecursive(
                                project,
                                containingFunction,
                                depth - 1,
                                visited,
                                stackDepth + 1,
                                searchScope
                            )
                        } else null
                        createCallElement(project, containingFunction, children)
                    } else null
                }
                .distinctBy { it.name + it.file + it.line }
        } catch (e: Exception) {
            LOG.debug("Failed to find callers: ${e.message}")
            emptyList()
        }
    }

    private fun findCalleesRecursive(
        project: Project,
        scFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(scFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        val callees = mutableListOf<CallElementData>()
        try {
            val scMethodCallClass = this.scMethodCallClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val callExpressions = PsiTreeUtil.findChildrenOfType(scFunction, scMethodCallClass as Class<out PsiElement>)

            callExpressions.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                val calledFunction = resolveCallExpression(callExpr)
                if (calledFunction != null && isScFunction(calledFunction)) {
                    if (!shouldIncludeNavigationElement(searchScope, calledFunction)) return@forEach
                    val children = if (depth > 1) {
                        findCalleesRecursive(project, calledFunction, depth - 1, visited, stackDepth + 1, searchScope)
                    } else null
                    val element = createCallElement(project, calledFunction, children)
                    if (callees.none { it.name == element.name && it.file == element.file }) {
                        callees.add(element)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to find callees: ${e.message}")
        }
        return callees
    }

    private fun resolveCallExpression(callExpr: PsiElement): PsiElement? {
        return try {
            // ScMethodCall has getInvokedExpr to get the reference
            val invokedExprMethod = callExpr.javaClass.getMethod("getInvokedExpr")
            val invokedExpr = invokedExprMethod.invoke(callExpr) as? PsiElement
            
            if (invokedExpr != null) {
                // Try to resolve as reference
                if (invokedExpr is com.intellij.psi.PsiReference) {
                    invokedExpr.resolve()
                } else {
                    // Try to get reference from element
                    val reference = invokedExpr.reference
                    reference?.resolve()
                }
            } else null
        } catch (e: Exception) {
            LOG.debug("Failed to resolve call expression: ${e.message}")
            null
        }
    }

    private fun getFunctionKey(scFunction: PsiElement): String {
        val containingClass = findContainingScTypeDefinition(scFunction)
        val className = containingClass?.let { getQualifiedName(it) ?: getName(it) } ?: ""
        val functionName = getName(scFunction) ?: ""
        val file = scFunction.containingFile?.virtualFile?.path ?: ""
        return "$className.$functionName@$file"
    }

    private fun createCallElement(
        project: Project,
        scFunction: PsiElement,
        children: List<CallElementData>? = null
    ): CallElementData {
        val containingClass = findContainingScTypeDefinition(scFunction)
        val className = containingClass?.let { getName(it) } ?: ""
        val functionName = getName(scFunction) ?: "unknown"
        val file = scFunction.containingFile?.virtualFile

        return CallElementData(
            name = if (className.isNotEmpty()) "$className.$functionName" else functionName,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, scFunction) ?: 0,
            column = getColumnNumber(project, scFunction) ?: 0,
            language = "Scala",
            children = children
        )
    }

    private fun buildMethodSignature(scFunction: PsiElement): String {
        return try {
            // ScFunction has parameterList
            val parameterListMethod = scFunction.javaClass.getMethod("parameterList")
            val paramList = parameterListMethod.invoke(scFunction)
            
            if (paramList != null) {
                // Get clauses
                val clausesMethod = paramList.javaClass.getMethod("clauses")
                val clauses = clausesMethod.invoke(paramList)
                
                // Convert Scala Seq to list
                val clausesList = scalaSeqToList(clauses)
                
                val params = clausesList.flatMap { clause ->
                    try {
                        val parametersMethod = clause.javaClass.getMethod("parameters")
                        val parameters = parametersMethod.invoke(clause)
                        scalaSeqToList(parameters)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }.mapNotNull { param ->
                    getName(param)
                }.joinToString(", ")
                
                val functionName = getName(scFunction) ?: "unknown"
                "$functionName($params)"
            } else {
                getName(scFunction) ?: "unknown"
            }
        } catch (e: Exception) {
            getName(scFunction) ?: "unknown"
        }
    }
}

/**
 * Scala implementation of [SuperMethodsHandler].
 *
 * Finds methods that a given Scala method overrides/implements in the type hierarchy.
 */
class ScalaSuperMethodsHandler : BaseScalaHandler<SuperMethodsData>(), SuperMethodsHandler {

    companion object {
        private val LOG = logger<ScalaSuperMethodsHandler>()
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isScalaLanguage(element)
    }

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable && scFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val scFunction = findContainingScFunction(element) ?: return null
        val containingClass = findContainingScTypeDefinition(scFunction) ?: return null

        val file = scFunction.containingFile?.virtualFile
        val methodData = MethodData(
            name = getName(scFunction) ?: "unknown",
            signature = buildMethodSignature(scFunction),
            containingClass = getQualifiedName(containingClass) ?: getName(containingClass) ?: "unknown",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, scFunction) ?: 0,
            column = getColumnNumber(project, scFunction) ?: 0,
            language = "Scala"
        )

        val hierarchy = buildHierarchy(project, scFunction)

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        scFunction: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        try {
            // ScFunction has a superMethods property that returns Seq[PsiMethod]
            val superMethodsMethod = scFunction.javaClass.getMethod("superMethods")
            val scalaSeq = superMethodsMethod.invoke(scFunction)
            val superMethods = scalaSeqToList(scalaSeq)

            for (superMethod in superMethods) {
                val containingClass = findContainingScTypeDefinition(superMethod)
                val className = containingClass?.let { getQualifiedName(it) ?: getName(it) } ?: "unknown"
                val methodName = getName(superMethod) ?: "unknown"
                val key = "$className.$methodName"
                
                if (key in visited) continue
                visited.add(key)

                val file = superMethod.containingFile?.virtualFile

                hierarchy.add(SuperMethodData(
                    name = methodName,
                    signature = buildMethodSignature(superMethod),
                    containingClass = className,
                    containingClassKind = containingClass?.let { getTypeKind(it) } ?: "UNKNOWN",
                    file = file?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, superMethod),
                    column = getColumnNumber(project, superMethod),
                    isInterface = containingClass?.let { isScTrait(it) } ?: false,
                    depth = depth,
                    language = "Scala"
                ))

                // Recursively get super methods
                if (isScFunction(superMethod)) {
                    hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to build super method hierarchy: ${e.message}")
        }

        return hierarchy
    }

    private fun buildMethodSignature(scFunction: PsiElement): String {
        return try {
            val parameterListMethod = scFunction.javaClass.getMethod("parameterList")
            val paramList = parameterListMethod.invoke(scFunction)
            
            if (paramList != null) {
                val clausesMethod = paramList.javaClass.getMethod("clauses")
                val clauses = clausesMethod.invoke(paramList)
                val clausesList = scalaSeqToList(clauses)
                
                val params = clausesList.flatMap { clause ->
                    try {
                        val parametersMethod = clause.javaClass.getMethod("parameters")
                        val parameters = parametersMethod.invoke(clause)
                        scalaSeqToList(parameters)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }.mapNotNull { param ->
                    getName(param)
                }.joinToString(", ")
                
                val functionName = getName(scFunction) ?: "unknown"
                "$functionName($params)"
            } else {
                getName(scFunction) ?: "unknown"
            }
        } catch (e: Exception) {
            getName(scFunction) ?: "unknown"
        }
    }
}

/**
 * Scala implementation of [StructureHandler].
 *
 * Extracts the hierarchical structure of Scala source files including
 * classes, traits, objects, case classes, and their members.
 */
class ScalaStructureHandler : BaseScalaHandler<List<StructureNode>>(), StructureHandler {

    companion object {
        private val LOG = logger<ScalaStructureHandler>()
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isScalaLanguage(element)
    }

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable && scTypeDefinitionClass != null

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val structure = mutableListOf<StructureNode>()

        try {
            val scalaFileClass = Class.forName("org.jetbrains.plugins.scala.lang.psi.api.ScalaFile")
            if (!scalaFileClass.isInstance(file)) {
                LOG.debug("File is not a ScalaFile: ${file.javaClass.name}, language: ${file.language.id}")
                return emptyList()
            }

            // Use PsiTreeUtil to find all top-level type definitions
            @Suppress("UNCHECKED_CAST")
            val typeDefinitions = PsiTreeUtil.findChildrenOfType(file, scTypeDefinitionClass as Class<PsiElement>)
            LOG.debug("Found ${typeDefinitions?.size ?: 0} type definitions in Scala file")

            typeDefinitions?.forEach { typeDef ->
                // Only include top-level types (not nested ones initially)
                if (isTopLevel(typeDef, file)) {
                    structure.add(extractTypeStructure(typeDef, project))
                }
            }

            // Also find top-level functions
            @Suppress("UNCHECKED_CAST")
            val functions = PsiTreeUtil.findChildrenOfType(file, scFunctionClass as Class<PsiElement>)
            LOG.debug("Found ${functions?.size ?: 0} functions in Scala file")

            functions?.forEach { function ->
                if (isTopLevel(function, file)) {
                    structure.add(extractFunctionStructure(function, project))
                }
            }

        } catch (e: ClassNotFoundException) {
            LOG.warn("Scala PSI class not found: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to extract Scala file structure: ${e.message}, ${e.javaClass.simpleName}")
        }

        return structure.sortedBy { it.line }
    }

    /**
     * Check if an element is a top-level element (not nested inside a type definition).
     */
    private fun isTopLevel(element: PsiElement, file: PsiFile): Boolean {
        var current: PsiElement? = element.parent
        while (current != null && current != file) {
            if (isScTypeDefinition(current)) {
                return false // Nested inside a type
            }
            current = current.parent
        }
        return true
    }

    private fun extractTypeStructure(typeDef: PsiElement, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()

        try {
            // Get members using ScTemplateDefinition.members
            val membersMethod = typeDef.javaClass.getMethod("members")
            val membersSeq = membersMethod.invoke(typeDef)
            val members = scalaSeqToList(membersSeq)

            for (member in members) {
                when {
                    isScFunction(member) -> {
                        children.add(extractFunctionStructure(member, project))
                    }
                    isScTypeDefinition(member) -> {
                        children.add(extractTypeStructure(member, project))
                    }
                    isScValue(member) || isScVariable(member) -> {
                        children.add(extractFieldStructure(member, project))
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to extract Scala type structure: ${e.message}")
        }

        val name = getName(typeDef) ?: "unknown"
        val kind = when {
            isScTrait(typeDef) -> StructureKind.TRAIT
            isScObject(typeDef) -> {
                // Check if package object
                if (isPackageObject(typeDef)) StructureKind.PACKAGE_OBJECT else StructureKind.OBJECT
            }
            isCaseClass(typeDef) -> StructureKind.CASE_CLASS
            isScClass(typeDef) -> StructureKind.CLASS
            else -> StructureKind.CLASS
        }

        return StructureNode(
            name = name,
            kind = kind,
            modifiers = extractModifiers(typeDef),
            signature = null,
            line = getLineNumber(project, typeDef) ?: 0,
            children = children.takeIf { it.isNotEmpty() } ?: emptyList()
        )
    }

    private fun extractFunctionStructure(function: PsiElement, project: Project): StructureNode {
        val name = getName(function) ?: "unknown"
        val signature = buildSignature(function)

        return StructureNode(
            name = name,
            kind = StructureKind.METHOD,
            modifiers = extractModifiers(function),
            signature = signature,
            line = getLineNumber(project, function) ?: 0,
            children = emptyList()
        )
    }

    private fun extractFieldStructure(field: PsiElement, project: Project): StructureNode {
        val name = getName(field) ?: "unknown"
        val kind = if (isScVariable(field)) StructureKind.VAR else StructureKind.VAL

        return StructureNode(
            name = name,
            kind = kind,
            modifiers = extractModifiers(field),
            signature = null,
            line = getLineNumber(project, field) ?: 0,
            children = emptyList()
        )
    }

    private fun extractModifiers(element: PsiElement): List<String> {
        val modifiers = mutableListOf<String>()

        try {
            // Try to get modifiers using getModifierList
            val modifierListMethod = element.javaClass.getMethod("getModifierList")
            val modifierList = modifierListMethod.invoke(element)

            if (modifierList != null) {
                // Check common modifiers
                val checkModifier = { modifier: String ->
                    try {
                        val hasMethod = modifierList.javaClass.getMethod("has", String::class.java)
                        hasMethod.invoke(modifierList, modifier) as? Boolean ?: false
                    } catch (e: Exception) {
                        false
                    }
                }

                if (checkModifier("private")) modifiers.add("private")
                if (checkModifier("protected")) modifiers.add("protected")
                if (checkModifier("abstract")) modifiers.add("abstract")
                if (checkModifier("final")) modifiers.add("final")
                if (checkModifier("implicit")) modifiers.add("implicit")
                if (checkModifier("override")) modifiers.add("override")
                if (checkModifier("sealed")) modifiers.add("sealed")
            }
        } catch (e: Exception) {
            // Modifiers not available, return empty list
        }

        return modifiers
    }

    private fun buildSignature(function: PsiElement): String {
        return try {
            val parameterListMethod = function.javaClass.getMethod("parameterList")
            val paramList = parameterListMethod.invoke(function)
            
            if (paramList != null) {
                val clausesMethod = paramList.javaClass.getMethod("clauses")
                val clauses = clausesMethod.invoke(paramList)
                val clausesList = scalaSeqToList(clauses)
                
                val params = clausesList.flatMap { clause ->
                    try {
                        val parametersMethod = clause.javaClass.getMethod("parameters")
                        val parameters = parametersMethod.invoke(clause)
                        scalaSeqToList(parameters)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }.mapNotNull { param ->
                    getName(param)
                }.joinToString(", ")
                
                "($params)"
            } else {
                "()"
            }
        } catch (e: Exception) {
            "()"
        }
    }

    private fun isScValue(element: PsiElement): Boolean {
        return scValueClass?.isInstance(element) == true
    }

    private fun isScVariable(element: PsiElement): Boolean {
        return scVariableClass?.isInstance(element) == true
    }

    private fun isPackageObject(element: PsiElement): Boolean {
        return try {
            if (!isScObject(element)) return false
            val method = element.javaClass.getMethod("isPackageObject")
            method.invoke(element) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }
}

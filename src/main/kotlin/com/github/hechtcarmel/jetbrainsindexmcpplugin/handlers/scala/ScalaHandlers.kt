package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.scala

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ScalaPluginDetector
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScMethodCall
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValue
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariable
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScVariable
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScMember
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTemplateDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTrait
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition

/**
 * Registration entry point for Scala language handlers.
 *
 * This class is loaded via reflection when the Scala plugin is available
 * (see [LanguageHandlerRegistry]). It registers all Scala-specific handlers.
 *
 * ## Optionality
 *
 * The Scala plugin is declared as optional in plugin.xml:
 * `<depends optional="true" config-file="scala-features.xml">org.intellij.scala</depends>`
 *
 * This class itself has no Scala PSI imports, so it can be loaded safely even if the Scala
 * plugin is absent (the registry guards against that via [ScalaPluginDetector]).
 * The handler classes (ScalaTypeHierarchyHandler etc.) do import Scala PSI types, but they
 * are only instantiated after the plugin-availability check passes — JVM lazy class loading
 * ensures they are never loaded when the Scala plugin is absent.
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

        registry.registerTypeHierarchyHandler(ScalaTypeHierarchyHandler())
        registry.registerImplementationsHandler(ScalaImplementationsHandler())
        registry.registerCallHierarchyHandler(ScalaCallHierarchyHandler())
        registry.registerSuperMethodsHandler(ScalaSuperMethodsHandler())
        registry.registerStructureHandler(ScalaStructureHandler())

        LOG.info("Registered Scala handlers")
    }
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

/**
 * Converts a `scala.collection.Seq<T>` to a Kotlin [List].
 *
 * Scala Seq does not implement java.lang.Iterable directly, but its `.iterator()`
 * returns a `scala.collection.Iterator<T>` whose `hasNext()` and `next()` methods
 * are Java-callable (they are regular abstract methods on the Scala Iterator trait).
 */
private fun <T> scala.collection.Seq<T>.toKotlinList(): List<T> {
    val result = mutableListOf<T>()
    val it = iterator()
    while (it.hasNext()) result.add(it.next())
    return result
}

// ---------------------------------------------------------------------------
// Base class
// ---------------------------------------------------------------------------

/**
 * Base class for Scala handlers providing common PSI utilities.
 *
 * Uses direct Scala PSI imports — no reflection required. These classes are only
 * instantiated (and thus loaded by the JVM) after the Scala plugin availability
 * check in [ScalaHandlers.register] confirms the plugin is present.
 */
abstract class BaseScalaHandler<T> : LanguageHandler<T> {

    companion object {
        private val LOG = logger<BaseScalaHandler<*>>()
    }

    protected fun isScalaLanguage(element: PsiElement): Boolean =
        element.language.id == "Scala"

    // Type checks using direct Kotlin 'is' operator

    protected fun isScTypeDefinition(element: PsiElement): Boolean = element is ScTypeDefinition
    protected fun isScClass(element: PsiElement): Boolean = element is ScClass
    protected fun isScTrait(element: PsiElement): Boolean = element is ScTrait
    protected fun isScObject(element: PsiElement): Boolean = element is ScObject
    protected fun isScFunction(element: PsiElement): Boolean = element is ScFunction

    // Navigation helpers

    protected fun findContainingScTypeDefinition(element: PsiElement): ScTypeDefinition? {
        if (element is ScTypeDefinition) return element
        return PsiTreeUtil.getParentOfType(element, ScTypeDefinition::class.java)
    }

    protected fun findContainingScFunction(element: PsiElement): ScFunction? {
        if (element is ScFunction) return element
        return PsiTreeUtil.getParentOfType(element, ScFunction::class.java)
    }

    // Property accessors (direct, no reflection)

    protected fun getName(element: PsiElement): String? =
        (element as? ScTypeDefinition)?.name
            ?: (element as? ScFunction)?.name
            ?: (element as? ScValueOrVariable)?.let {
                // ScValueOrVariable implements ScDeclaredElementsHolder; name is on the first declared element
                try { (element as com.intellij.psi.PsiNamedElement).name } catch (_: Exception) { null }
            }

    protected fun getQualifiedName(element: PsiElement): String? =
        (element as? ScTypeDefinition)?.qualifiedName

    protected fun getSupers(element: PsiElement): List<PsiElement> =
        (element as? ScTemplateDefinition)?.supers()?.toKotlinList() ?: emptyList()

    protected fun isCaseClass(element: PsiElement): Boolean =
        (element as? ScTypeDefinition)?.isCase ?: false

    protected fun isPackageObject(element: PsiElement): Boolean =
        (element as? ScTypeDefinition)?.isPackageObject ?: false

    protected fun getTypeKind(element: PsiElement): String = when {
        element is ScTrait -> "TRAIT"
        element is ScObject -> if (isPackageObject(element)) "OBJECT" else "OBJECT"
        element is ScClass -> if (isCaseClass(element)) "CASE_CLASS" else "CLASS"
        else -> "UNKNOWN"
    }

    // Location helpers

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String =
        ProjectUtils.getToolFilePath(project, file)

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

    // Parameter signature builder

    protected fun buildMethodSignature(function: ScFunction): String {
        return try {
            val params = function.paramClauses().clauses().toKotlinList()
                .flatMap { clause -> clause.parameters().toKotlinList() }
                .mapNotNull { param -> param.name }
                .joinToString(", ")
            "${function.name}($params)"
        } catch (_: Exception) {
            function.name ?: "unknown"
        }
    }

    // Modifier extraction

    protected fun extractModifiers(element: PsiElement): List<String> {
        val modifierList = (element as? com.intellij.psi.PsiModifierListOwner)?.modifierList
            ?: return emptyList()
        val modifiers = mutableListOf<String>()
        if (modifierList.hasModifierProperty(PsiModifier.PRIVATE)) modifiers.add("private")
        if (modifierList.hasModifierProperty(PsiModifier.PROTECTED)) modifiers.add("protected")
        if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) modifiers.add("abstract")
        if (modifierList.hasModifierProperty(PsiModifier.FINAL)) modifiers.add("final")
        // Scala-specific modifiers via ScModifierList
        try {
            val scModList = modifierList as? org.jetbrains.plugins.scala.lang.psi.api.base.ScModifierList
            if (scModList != null) {
                if (scModList.hasModifierProperty("implicit")) modifiers.add("implicit")
                if (scModList.hasModifierProperty("override")) modifiers.add("override")
                if (scModList.hasModifierProperty("sealed")) modifiers.add("sealed")
            }
        } catch (_: Exception) {}
        return modifiers
    }
}

// ---------------------------------------------------------------------------
// TypeHierarchyHandler
// ---------------------------------------------------------------------------

/**
 * Scala implementation of [TypeHierarchyHandler].
 * Supports classes, traits, objects, and case classes.
 */
class ScalaTypeHierarchyHandler : BaseScalaHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 100
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isScalaLanguage(element)

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable

    override fun getTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope
    ): TypeHierarchyData? {
        val scTypeDef = findContainingScTypeDefinition(element) ?: return null
        val searchScope = createNavigationSearchScope(project, scope)

        return TypeHierarchyData(
            element = TypeElementData(
                name = getQualifiedName(scTypeDef) ?: getName(scTypeDef) ?: "unknown",
                qualifiedName = getQualifiedName(scTypeDef),
                file = scTypeDef.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, scTypeDef),
                kind = getTypeKind(scTypeDef),
                language = "Scala"
            ),
            supertypes = getSupertypes(project, scTypeDef, searchScope = searchScope),
            subtypes = getSubtypes(project, scTypeDef, searchScope)
        )
    }

    private fun getSupertypes(
        project: Project,
        scTypeDef: ScTypeDefinition,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val typeName = getQualifiedName(scTypeDef) ?: getName(scTypeDef) ?: return emptyList()
        if (typeName in visited || typeName == "scala.AnyRef" || typeName == "scala.Any") return emptyList()
        visited.add(typeName)

        val result = mutableListOf<TypeElementData>()
        try {
            for (superType in getSupers(scTypeDef)) {
                val superName = getQualifiedName(superType) ?: getName(superType)
                if (superName != null && superName != "scala.AnyRef" && superName != "scala.Any") {
                    if (!shouldIncludeNavigationElement(searchScope, superType)) continue
                    val superSupers = (superType as? ScTypeDefinition)?.let {
                        getSupertypes(project, it, visited, depth + 1, searchScope)
                    } ?: emptyList()
                    result.add(TypeElementData(
                        name = superName,
                        qualifiedName = getQualifiedName(superType),
                        file = superType.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superType),
                        kind = getTypeKind(superType),
                        language = "Scala",
                        supertypes = superSupers.takeIf { it.isNotEmpty() }
                    ))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getSubtypes(
        project: Project,
        scTypeDef: ScTypeDefinition,
        searchScope: GlobalSearchScope
    ): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()
        try {
            ClassInheritorsSearch.search(scTypeDef, searchScope, true).forEach(Processor { inheritor ->
                if (inheritor is ScTypeDefinition && shouldIncludeNavigationElement(searchScope, inheritor)) {
                    results.add(TypeElementData(
                        name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                        qualifiedName = getQualifiedName(inheritor),
                        file = inheritor.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, inheritor),
                        kind = getTypeKind(inheritor),
                        language = "Scala"
                    ))
                }
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }
}

// ---------------------------------------------------------------------------
// ImplementationsHandler
// ---------------------------------------------------------------------------

/**
 * Scala implementation of [ImplementationsHandler].
 * Finds implementations of traits, abstract classes, and method overrides.
 */
class ScalaImplementationsHandler : BaseScalaHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isScalaLanguage(element)

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable

    override fun findImplementations(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope
    ): List<ImplementationData>? {
        val searchScope = createNavigationSearchScope(project, scope)

        findContainingScFunction(element)?.let { return findMethodImplementations(project, it, searchScope) }
        findContainingScTypeDefinition(element)?.let { return findTypeImplementations(project, it, searchScope) }
        return null
    }

    private fun findMethodImplementations(
        project: Project,
        function: ScFunction,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        try {
            OverridingMethodsSearch.search(function, searchScope, true).forEach(Processor { overriding ->
                if (overriding is ScFunction && shouldIncludeNavigationElement(searchScope, overriding)) {
                    overriding.containingFile?.virtualFile?.let { file ->
                        val typeName = findContainingScTypeDefinition(overriding)?.let { getName(it) } ?: ""
                        val funcName = overriding.name ?: "unknown"
                        results.add(ImplementationData(
                            name = if (typeName.isNotEmpty()) "$typeName.$funcName" else funcName,
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, overriding) ?: 0,
                            column = getColumnNumber(project, overriding) ?: 0,
                            kind = "METHOD",
                            language = "Scala"
                        ))
                    }
                }
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }

    private fun findTypeImplementations(
        project: Project,
        typeDef: ScTypeDefinition,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        try {
            ClassInheritorsSearch.search(typeDef, searchScope, true).forEach(Processor { inheritor ->
                if (inheritor is ScTypeDefinition && shouldIncludeNavigationElement(searchScope, inheritor)) {
                    inheritor.containingFile?.virtualFile?.let { file ->
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
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }
}

// ---------------------------------------------------------------------------
// CallHierarchyHandler
// ---------------------------------------------------------------------------

/**
 * Scala implementation of [CallHierarchyHandler].
 * Finds callers and callees of Scala methods/functions.
 */
class ScalaCallHierarchyHandler : BaseScalaHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
        private const val MAX_SUPER_METHODS = 10
        private val LOG = logger<ScalaCallHierarchyHandler>()
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isScalaLanguage(element)

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable

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

        return CallHierarchyData(element = createCallElement(project, scFunction), calls = calls)
    }

    private fun getSuperMethods(function: ScFunction): List<PsiElement> {
        return try {
            function.superMethods().toKotlinList().take(MAX_SUPER_METHODS)
        } catch (_: Exception) {
            LOG.debug("Failed to get super methods for ${function.name}")
            emptyList()
        }
    }

    private fun findCallersRecursive(
        project: Project,
        scFunction: ScFunction,
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
            val methodsToSearch = mutableSetOf<PsiElement>(scFunction)
            methodsToSearch.addAll(getSuperMethods(scFunction))

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
                    if (containingFunction != null
                        && containingFunction != scFunction
                        && !methodsToSearch.contains(containingFunction)) {
                        val file = containingFunction.containingFile?.virtualFile
                        if (file == null || !searchScope.contains(file)) return@mapNotNull null
                        val children = if (depth > 1) {
                            findCallersRecursive(project, containingFunction, depth - 1, visited, stackDepth + 1, searchScope)
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
        scFunction: ScFunction,
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
            val callExpressions = PsiTreeUtil.findChildrenOfType(scFunction, ScMethodCall::class.java)
            callExpressions.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                val calledFunction = resolveCallExpression(callExpr)
                if (calledFunction is ScFunction) {
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

    private fun resolveCallExpression(callExpr: ScMethodCall): PsiElement? {
        return try {
            val invokedExpr = callExpr.getInvokedExpr()
            when (invokedExpr) {
                is com.intellij.psi.PsiReference -> invokedExpr.resolve()
                else -> invokedExpr.reference?.resolve()
            }
        } catch (e: Exception) {
            LOG.debug("Failed to resolve call expression: ${e.message}")
            null
        }
    }

    private fun getFunctionKey(scFunction: ScFunction): String {
        val className = findContainingScTypeDefinition(scFunction)
            ?.let { getQualifiedName(it) ?: getName(it) } ?: ""
        val file = scFunction.containingFile?.virtualFile?.path ?: ""
        return "$className.${scFunction.name}@$file"
    }

    private fun createCallElement(
        project: Project,
        scFunction: ScFunction,
        children: List<CallElementData>? = null
    ): CallElementData {
        val className = findContainingScTypeDefinition(scFunction)?.let { getName(it) } ?: ""
        val funcName = scFunction.name ?: "unknown"
        val file = scFunction.containingFile?.virtualFile
        return CallElementData(
            name = if (className.isNotEmpty()) "$className.$funcName" else funcName,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, scFunction) ?: 0,
            column = getColumnNumber(project, scFunction) ?: 0,
            language = "Scala",
            children = children
        )
    }
}

// ---------------------------------------------------------------------------
// SuperMethodsHandler
// ---------------------------------------------------------------------------

/**
 * Scala implementation of [SuperMethodsHandler].
 * Finds methods that a given Scala method overrides/implements.
 */
class ScalaSuperMethodsHandler : BaseScalaHandler<SuperMethodsData>(), SuperMethodsHandler {

    companion object {
        private val LOG = logger<ScalaSuperMethodsHandler>()
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isScalaLanguage(element)

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val scFunction = findContainingScFunction(element) ?: return null
        val containingClass = findContainingScTypeDefinition(scFunction) ?: return null

        val methodData = MethodData(
            name = scFunction.name ?: "unknown",
            signature = buildMethodSignature(scFunction),
            containingClass = getQualifiedName(containingClass) ?: getName(containingClass) ?: "unknown",
            file = scFunction.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, scFunction) ?: 0,
            column = getColumnNumber(project, scFunction) ?: 0,
            language = "Scala"
        )

        return SuperMethodsData(
            method = methodData,
            hierarchy = buildHierarchy(project, scFunction)
        )
    }

    private fun buildHierarchy(
        project: Project,
        scFunction: ScFunction,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()
        try {
            for (superMethod in scFunction.superMethods().toKotlinList()) {
                val containingClass = findContainingScTypeDefinition(superMethod)
                val className = containingClass?.let { getQualifiedName(it) ?: getName(it) } ?: "unknown"
                val methodName = (superMethod as? ScFunction)?.name
                    ?: (superMethod as? com.intellij.psi.PsiNamedElement)?.name
                    ?: "unknown"
                val key = "$className.$methodName"
                if (key in visited) continue
                visited.add(key)

                hierarchy.add(SuperMethodData(
                    name = methodName,
                    signature = (superMethod as? ScFunction)?.let { buildMethodSignature(it) } ?: methodName,
                    containingClass = className,
                    containingClassKind = containingClass?.let { getTypeKind(it) } ?: "UNKNOWN",
                    file = superMethod.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, superMethod),
                    column = getColumnNumber(project, superMethod),
                    isInterface = containingClass is ScTrait,
                    depth = depth,
                    language = "Scala"
                ))

                if (superMethod is ScFunction) {
                    hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to build super method hierarchy: ${e.message}")
        }
        return hierarchy
    }
}

// ---------------------------------------------------------------------------
// StructureHandler
// ---------------------------------------------------------------------------

/**
 * Scala implementation of [StructureHandler].
 * Extracts hierarchical structure of Scala source files.
 */
class ScalaStructureHandler : BaseScalaHandler<List<StructureNode>>(), StructureHandler {

    companion object {
        private val LOG = logger<ScalaStructureHandler>()
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isScalaLanguage(element)

    override fun isAvailable(): Boolean = ScalaPluginDetector.isScalaPluginAvailable

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        if (file !is ScalaFile) {
            LOG.debug("File is not a ScalaFile: ${file.javaClass.name}, language: ${file.language.id}")
            return emptyList()
        }

        val structure = mutableListOf<StructureNode>()
        try {
            // Top-level type definitions
            PsiTreeUtil.findChildrenOfType(file, ScTypeDefinition::class.java)
                .filter { isTopLevel(it, file) }
                .forEach { structure.add(extractTypeStructure(it, project)) }

            // Top-level functions
            PsiTreeUtil.findChildrenOfType(file, ScFunction::class.java)
                .filter { isTopLevel(it, file) }
                .forEach { structure.add(extractFunctionStructure(it, project)) }

        } catch (e: Exception) {
            LOG.warn("Failed to extract Scala file structure: ${e.message}")
        }
        return structure.sortedBy { it.line }
    }

    private fun isTopLevel(element: PsiElement, file: PsiFile): Boolean {
        var current: PsiElement? = element.parent
        while (current != null && current != file) {
            if (current is ScTypeDefinition) return false
            current = current.parent
        }
        return true
    }

    private fun extractTypeStructure(typeDef: ScTypeDefinition, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()
        try {
            typeDef.members().toKotlinList().forEach { member ->
                when (member) {
                    is ScFunction -> children.add(extractFunctionStructure(member, project))
                    is ScTypeDefinition -> children.add(extractTypeStructure(member, project))
                    is ScValue -> children.add(extractFieldStructure(member, project, isVar = false))
                    is ScVariable -> children.add(extractFieldStructure(member, project, isVar = true))
                    else -> {}
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to extract Scala type members: ${e.message}")
        }

        val kind = when {
            typeDef is ScTrait -> StructureKind.TRAIT
            typeDef is ScObject && typeDef.isPackageObject -> StructureKind.PACKAGE_OBJECT
            typeDef is ScObject -> StructureKind.OBJECT
            typeDef.isCase -> StructureKind.CASE_CLASS
            else -> StructureKind.CLASS
        }

        return StructureNode(
            name = typeDef.name ?: "unknown",
            kind = kind,
            modifiers = extractModifiers(typeDef),
            signature = null,
            line = getLineNumber(project, typeDef) ?: 0,
            children = children
        )
    }

    private fun extractFunctionStructure(function: ScFunction, project: Project): StructureNode {
        return StructureNode(
            name = function.name ?: "unknown",
            kind = StructureKind.METHOD,
            modifiers = extractModifiers(function),
            signature = buildSignature(function),
            line = getLineNumber(project, function) ?: 0,
            children = emptyList()
        )
    }

    private fun extractFieldStructure(field: ScValueOrVariable, project: Project, isVar: Boolean): StructureNode {
        return StructureNode(
            name = (field as? com.intellij.psi.PsiNamedElement)?.name ?: "unknown",
            kind = if (isVar) StructureKind.VAR else StructureKind.VAL,
            modifiers = extractModifiers(field),
            signature = null,
            line = getLineNumber(project, field) ?: 0,
            children = emptyList()
        )
    }

    private fun buildSignature(function: ScFunction): String {
        return try {
            val params = function.paramClauses().clauses().toKotlinList()
                .flatMap { clause -> clause.parameters().toKotlinList() }
                .mapNotNull { param -> param.name }
                .joinToString(", ")
            "($params)"
        } catch (_: Exception) {
            "()"
        }
    }
}

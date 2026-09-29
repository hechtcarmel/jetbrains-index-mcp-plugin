package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.scala

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.rethrowIfControlFlow
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValue
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariable
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScVariable
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.*

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
 * This object references no Scala PSI types, so it can be loaded safely even if the Scala
 * plugin is absent (the registry guards against that via [PluginDetectors.scala]).
 * The handler classes (ScalaTypeHierarchyHandler etc.) do use Scala PSI types, but they
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
        if (!PluginDetectors.scala.isAvailable) {
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

/**
 * The installed Scala plugin no longer matches the API these handlers were compiled against.
 *
 * Reported as a tool error rather than an empty result: an empty hierarchy is a valid answer,
 * so returning one would present a broken integration as "nothing found".
 */
class ScalaPluginApiMismatchException(operation: String, cause: LinkageError) : IllegalStateException(
    "Scala $operation failed: the installed Scala plugin is not compatible with this version of " +
        "IDE Index MCP Server (${cause.javaClass.simpleName}: ${cause.message}). " +
        "Update the Scala plugin or IDE Index MCP Server.",
    cause
)

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
 *
 * Results are built from the generic [PsiClass] / [PsiMethod] view wherever possible
 * (`ScTypeDefinition` is a `PsiClass`, `ScFunction` a `PsiMethod`), so Java and Kotlin
 * declarations reached from Scala code are reported with their own name, kind and language.
 */
abstract class BaseScalaHandler<T> : LanguageHandler<T> {

    companion object {
        private val LOG = logger<BaseScalaHandler<*>>()
    }

    override val languageId = "Scala"

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && isScalaLanguage(element)

    protected fun isScalaLanguage(element: PsiElement): Boolean =
        element.language.id == "Scala"

    /**
     * Runs one handler operation, turning Scala-plugin binary drift into an explicit error.
     *
     * The handlers compile directly against the Scala plugin, so a plugin build whose API changed
     * fails with a [LinkageError] (`NoSuchMethodError`, `AbstractMethodError`, ...). That is an
     * [Error], which neither [safeScalaCall] nor the tool layer catches.
     */
    protected fun <R> scalaApiBoundary(operation: String, action: () -> R): R {
        try {
            return action()
        } catch (e: LinkageError) {
            LOG.warn("Scala $operation failed: Scala plugin API mismatch", e)
            throw ScalaPluginApiMismatchException(operation, e)
        }
    }

    /**
     * Executes [action], degrading to [default] if it fails.
     *
     * Control-flow exceptions — cancellation, and indexes becoming unavailable — always
     * propagate: swallowing them would report a truncated result as complete, and would keep
     * the platform's read action from restarting after a pending write. A [LinkageError] is
     * not caught here; [scalaApiBoundary] reports it.
     */
    protected fun <R> safeScalaCall(default: R, label: String, log: Logger = LOG, action: () -> R): R {
        return try {
            action()
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            log.debug("$label failed: ${e.message}")
            default
        }
    }

    // Navigation helpers

    protected fun findContainingScTypeDefinition(element: PsiElement): ScTypeDefinition? {
        if (element is ScTypeDefinition) return element
        return PsiTreeUtil.getParentOfType(element, ScTypeDefinition::class.java)
    }

    protected fun findContainingScFunction(element: PsiElement): ScFunction? {
        if (element is ScFunction) return element
        return PsiTreeUtil.getParentOfType(element, ScFunction::class.java)
    }

    protected fun getSupers(element: PsiElement): List<PsiElement> =
        (element as? ScTemplateDefinition)?.supers()?.toKotlinList() ?: emptyList()

    /** Collapses light wrappers (such as the Java view of a Scala object) onto their declaration. */
    protected fun sourceClass(psiClass: PsiClass): PsiClass = psiClass.navigationElement as? PsiClass ?: psiClass

    // Names. `getName()` is the JVM view of a Scala declaration — `Runner$` for an object,
    // `$plus` for `+` — so Scala declarations report their source-level names instead.

    protected fun nameOf(element: PsiElement): String? = when (element) {
        is ScNamedElement -> element.name()
        is PsiNamedElement -> element.name
        else -> null
    }

    /** `pkg.Runner` for a Scala object, whose JVM qualified name is `pkg.Runner$`. */
    protected fun qualifiedNameOf(psiClass: PsiClass): String? =
        (psiClass as? ScTemplateDefinition)?.qualifiedName() ?: psiClass.qualifiedName

    /** Simple name of the type declaring [member], used as the `Owner.member` display prefix. */
    protected fun ownerName(member: PsiElement): String? {
        val owner: PsiClass? = findContainingScTypeDefinition(member) ?: (member as? PsiMember)?.containingClass
        return owner?.let { nameOf(it) }
    }

    // Kind and language, in the vocabulary the Java and Kotlin handlers use

    /**
     * Type kind of [element]. Scala adds `TRAIT` and `OBJECT`; case classes stay `CLASS`, as
     * Kotlin data classes do. Java and Kotlin types reached from Scala keep their own kinds.
     */
    protected fun classKind(element: PsiElement): String = when (element) {
        is ScTrait -> "TRAIT"
        is ScObject -> "OBJECT"
        is ScClass -> if (element.hasModifierProperty(PsiModifier.ABSTRACT)) "ABSTRACT_CLASS" else "CLASS"
        is PsiClass -> PsiUtils.kotlinClassKind(element.navigationElement)?.takeUnless { it == "CLASS" } ?: when {
            element.isAnnotationType -> "ANNOTATION"
            element.isRecord -> "RECORD"
            element.isEnum -> "ENUM"
            element.isInterface -> "INTERFACE"
            element.hasModifierProperty(PsiModifier.ABSTRACT) -> "ABSTRACT_CLASS"
            else -> "CLASS"
        }
        else -> "UNKNOWN"
    }

    /** Language of a result element, which in a mixed project is not necessarily Scala. */
    protected fun languageOf(element: PsiElement): String {
        val language = element.navigationElement.language
        return when (language.id) {
            "Scala" -> "Scala"
            "JAVA" -> "Java"
            "kotlin" -> "Kotlin"
            else -> language.displayName
        }
    }

    // Location helpers

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String =
        ProjectUtils.getToolFilePath(project, file)

    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun getEndLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val endOffset = element.textRange?.endOffset ?: return null
        if (endOffset <= 0 || endOffset > document.textLength) return null
        return document.getLineNumber(endOffset - 1) + 1
    }

    protected fun getColumnNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val line = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(line) + 1
    }

    // Signatures

    protected fun buildMethodSignature(function: ScFunction): String {
        val name = function.name() ?: "unknown"
        return safeScalaCall(name, "buildMethodSignature") {
            val params = function.paramClauses().clauses().toKotlinList()
                .flatMap { clause -> clause.parameters().toKotlinList() }
                .mapNotNull { param -> param.name() }
                .joinToString(", ")
            "$name($params)"
        }
    }

    /** `name(param, ...)` for any method, in the shape [buildMethodSignature] uses for Scala. */
    protected fun methodSignature(method: PsiMethod): String =
        if (method is ScFunction) buildMethodSignature(method)
        else "${method.name}(${method.parameterList.parameters.joinToString(", ") { param -> param.name.toString() }})"

    // Modifier extraction

    protected fun extractModifiers(element: PsiElement): List<String> {
        val modifierList = (element as? PsiModifierListOwner)?.modifierList
            ?: return emptyList()
        val modifiers = mutableListOf<String>()
        if (modifierList.hasModifierProperty(PsiModifier.PRIVATE)) modifiers.add("private")
        if (modifierList.hasModifierProperty(PsiModifier.PROTECTED)) modifiers.add("protected")
        if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) modifiers.add("abstract")
        if (modifierList.hasModifierProperty(PsiModifier.FINAL)) modifiers.add("final")
        // Scala-specific modifiers via ScModifierList
        safeScalaCall(Unit, "extractModifiers") {
            val scModList = modifierList as? org.jetbrains.plugins.scala.lang.psi.api.base.ScModifierList
            if (scModList != null) {
                if (scModList.hasModifierProperty("implicit")) modifiers.add("implicit")
                if (scModList.hasModifierProperty("override")) modifiers.add("override")
                if (scModList.hasModifierProperty("sealed")) modifiers.add("sealed")
            }
        }
        return modifiers
    }

    override fun isAvailable(): Boolean = PluginDetectors.scala.isAvailable
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
        private val ROOT_TYPES = setOf("scala.Any", "scala.AnyRef")
    }

    override fun getTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
        directOnly: Boolean,
        direction: TypeHierarchyDirection?,
        page: HierarchyPageRequest?
    ): TypeHierarchyData? = scalaApiBoundary("type hierarchy") {
        computeTypeHierarchy(element, project, scope, excludeGenerated, directOnly, direction, page)
    }

    private fun computeTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
        directOnly: Boolean,
        direction: TypeHierarchyDirection?,
        page: HierarchyPageRequest?
    ): TypeHierarchyData? {
        require(page == null || direction != null) { "Hierarchy pagination requires an explicit direction" }
        val scTypeDef = findContainingScTypeDefinition(element) ?: return null
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)
        val collectionLimit = page?.collectionLimit ?: 100
        val rawSupertypes = if (direction != TypeHierarchyDirection.SUBTYPE) {
            getSupertypes(
                project,
                scTypeDef,
                visited = mutableSetOf(),
                depth = 0,
                searchScope = searchScope,
                directOnly = directOnly,
                maxResults = page?.collectionLimit ?: Int.MAX_VALUE
            )
        } else emptyList()
        val rawSubtypes = if (direction != TypeHierarchyDirection.SUPERTYPE) {
            getSubtypes(project, scTypeDef, searchScope, directOnly, collectionLimit)
        } else emptyList()
        val (supertypes, superNext) = if (direction == TypeHierarchyDirection.SUPERTYPE) {
            rawSupertypes.applyHierarchyPage(page)
        } else rawSupertypes to null
        val (subtypes, subtypeNext) = if (direction == TypeHierarchyDirection.SUBTYPE) {
            rawSubtypes.applyHierarchyPage(page)
        } else rawSubtypes to null

        return TypeHierarchyData(
            element = typeElement(project, scTypeDef),
            supertypes = supertypes,
            subtypes = subtypes,
            nextOffset = superNext ?: subtypeNext
        )
    }

    private fun typeElement(
        project: Project,
        psiClass: PsiClass,
        supertypes: List<TypeElementData>? = null
    ): TypeElementData = TypeElementData(
        name = qualifiedNameOf(psiClass) ?: nameOf(psiClass) ?: "unknown",
        qualifiedName = qualifiedNameOf(psiClass),
        file = psiClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
        line = getLineNumber(project, psiClass),
        kind = classKind(psiClass),
        language = languageOf(psiClass),
        supertypes = supertypes,
        pointerTarget = psiClass
    )

    private fun getSupertypes(
        project: Project,
        psiClass: PsiClass,
        visited: MutableSet<String>,
        depth: Int,
        searchScope: GlobalSearchScope,
        directOnly: Boolean,
        maxResults: Int
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH || maxResults <= 0) return emptyList()

        val typeName = qualifiedNameOf(psiClass) ?: nameOf(psiClass) ?: return emptyList()
        if (typeName in ROOT_TYPES || !visited.add(typeName)) return emptyList()

        val result = mutableListOf<TypeElementData>()
        safeScalaCall(Unit, "getSupertypes") {
            for (superClass in directSupers(psiClass)) {
                if (result.size >= maxResults) break
                val superName = qualifiedNameOf(superClass) ?: nameOf(superClass) ?: continue
                if (superName in ROOT_TYPES || !shouldIncludeNavigationElement(searchScope, superClass)) continue
                val superSupers = if (directOnly) emptyList() else {
                    getSupertypes(project, superClass, visited, depth + 1, searchScope, false, Int.MAX_VALUE)
                }
                result.add(typeElement(project, superClass, superSupers.takeIf { it.isNotEmpty() }))
            }
        }
        return result
    }

    /** Scala's own view of the parents (traits included); `PsiClass.supers` for Java and Kotlin. */
    private fun directSupers(psiClass: PsiClass): List<PsiClass> =
        if (psiClass is ScTemplateDefinition) getSupers(psiClass).filterIsInstance<PsiClass>()
        else psiClass.supers.toList()

    private fun getSubtypes(
        project: Project,
        psiClass: PsiClass,
        searchScope: GlobalSearchScope,
        directOnly: Boolean,
        maxResults: Int
    ): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()
        val seen = mutableSetOf<PsiElement>()
        safeScalaCall(Unit, "getSubtypes") {
            ClassInheritorsSearch.search(psiClass, searchScope, !directOnly).forEach(Processor { inheritor ->
                val target = sourceClass(inheritor)
                if (seen.add(target) && shouldIncludeNavigationElement(searchScope, target)) {
                    results.add(typeElement(project, target))
                }
                results.size < maxResults
            })
        }
        return results
    }
}

// ---------------------------------------------------------------------------
// ImplementationsHandler
// ---------------------------------------------------------------------------

/**
 * Scala implementation of [ImplementationsHandler].
 * Finds implementations of traits, abstract classes, and method overrides — including Java and
 * Kotlin ones.
 */
class ScalaImplementationsHandler : BaseScalaHandler<List<ImplementationData>>(), ImplementationsHandler {

    override fun findImplementations(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): List<ImplementationData>? = scalaApiBoundary("implementation search") {
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)
        val function = findContainingScFunction(element)
        val typeDef = findContainingScTypeDefinition(element)
        when {
            function != null -> findMethodImplementations(project, function, searchScope)
            typeDef != null -> findTypeImplementations(project, typeDef, searchScope)
            else -> null
        }
    }

    private fun findMethodImplementations(
        project: Project,
        function: ScFunction,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        val seen = mutableSetOf<PsiElement>()
        safeScalaCall(Unit, "findMethodImplementations") {
            OverridingMethodsSearch.search(function, searchScope, true).forEach(Processor { overriding ->
                val target = overriding.navigationElement as? PsiMethod ?: overriding
                val file = target.containingFile?.virtualFile
                if (file != null && seen.add(target) && shouldIncludeNavigationElement(searchScope, target)) {
                    val owner = ownerName(target)
                    val name = nameOf(target) ?: "unknown"
                    results.add(ImplementationData(
                        name = if (owner.isNullOrEmpty()) name else "$owner.$name",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, target) ?: 0,
                        column = getColumnNumber(project, target) ?: 0,
                        kind = "METHOD",
                        language = languageOf(target),
                        pointerTarget = target
                    ))
                }
                results.size < MAX_COLLECTED_NAVIGATION_RESULTS
            })
        }
        return results
    }

    private fun findTypeImplementations(
        project: Project,
        typeDef: ScTypeDefinition,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        val seen = mutableSetOf<PsiElement>()
        safeScalaCall(Unit, "findTypeImplementations") {
            ClassInheritorsSearch.search(typeDef, searchScope, true).forEach(Processor { inheritor ->
                val target = sourceClass(inheritor)
                val file = target.containingFile?.virtualFile
                if (file != null && seen.add(target) && shouldIncludeNavigationElement(searchScope, target)) {
                    results.add(ImplementationData(
                        name = qualifiedNameOf(target) ?: nameOf(target) ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, target) ?: 0,
                        column = getColumnNumber(project, target) ?: 0,
                        kind = classKind(target),
                        language = languageOf(target),
                        qualifiedName = qualifiedNameOf(target),
                        pointerTarget = target
                    ))
                }
                results.size < MAX_COLLECTED_NAVIGATION_RESULTS
            })
        }
        return results
    }
}

// ---------------------------------------------------------------------------
// CallHierarchyHandler
// ---------------------------------------------------------------------------

/**
 * Scala implementation of [CallHierarchyHandler].
 * Finds callers and callees of Scala methods/functions, including Java callers and callees.
 */
class ScalaCallHierarchyHandler : BaseScalaHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
        private const val MAX_SUPER_METHODS = 10
        private val LOG = logger<ScalaCallHierarchyHandler>()
    }

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
        page: HierarchyPageRequest?
    ): CallHierarchyData? = scalaApiBoundary("call hierarchy") {
        computeCallHierarchy(element, project, direction, depth, scope, excludeGenerated, page)
    }

    private fun computeCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
        page: HierarchyPageRequest?
    ): CallHierarchyData? {
        val scFunction = findContainingScFunction(element) ?: return null
        val visited = mutableSetOf<PsiElement>()
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)
        val collectionLimit = page?.collectionLimit ?: MAX_RESULTS_PER_LEVEL

        val rawCalls = if (direction == "callers") {
            findCallersRecursive(
                project, scFunction, depth, visited, stackDepth = 0, searchScope = searchScope,
                maxResults = collectionLimit, legacyReferenceCap = page == null
            )
        } else {
            findCalleesRecursive(
                project, scFunction, depth, visited, stackDepth = 0, searchScope = searchScope,
                maxResults = collectionLimit
            )
        }
        val (calls, nextOffset) = rawCalls.applyHierarchyPage(page)

        return CallHierarchyData(
            element = createCallElement(project, scFunction),
            calls = calls,
            nextOffset = nextOffset
        )
    }

    private fun superMethodsOf(method: PsiMethod): List<PsiMethod> {
        val supers = if (method is ScFunction) method.superMethods().toKotlinList() else method.findSuperMethods().toList()
        return supers.filterIsInstance<PsiMethod>().take(MAX_SUPER_METHODS)
    }

    private fun findCallersRecursive(
        project: Project,
        method: PsiMethod,
        depth: Int,
        visited: MutableSet<PsiElement>,
        stackDepth: Int,
        searchScope: GlobalSearchScope,
        maxResults: Int,
        legacyReferenceCap: Boolean
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0 || maxResults <= 0) return emptyList()
        if (!visited.add(method)) return emptyList()

        return safeScalaCall(emptyList(), "findCallersRecursive", LOG) {
            val methodsToSearch = LinkedHashSet<PsiMethod>()
            methodsToSearch.add(method)
            methodsToSearch.addAll(superMethodsOf(method))

            // Count distinct callers, not call sites: a caller with several call sites takes one
            // slot, or a page comes back short and the tool reads that as the end of the level.
            // The raw reference cap only applies to the legacy (unpaged) tree.
            val callers = LinkedHashSet<PsiMethod>()
            var inspectedReferences = 0
            val rawReferenceCap = if (legacyReferenceCap) maxResults * 2 else Int.MAX_VALUE
            for (target in methodsToSearch) {
                if (callers.size >= maxResults) break
                ReferencesSearch.search(target, searchScope).forEach(Processor { reference ->
                    if (++inspectedReferences > rawReferenceCap) return@Processor false
                    val caller = PsiTreeUtil.getParentOfType(reference.element, PsiMethod::class.java, false)
                    if (caller != null && caller !in methodsToSearch && shouldIncludeNavigationElement(searchScope, caller)) {
                        callers.add(caller)
                    }
                    callers.size < maxResults
                })
            }

            // Recurse only after the index queries above have finished.
            callers.map { caller ->
                val children = if (depth > 1) {
                    findCallersRecursive(
                        project, caller, depth - 1, visited, stackDepth + 1, searchScope, maxResults, legacyReferenceCap
                    )
                } else null
                createCallElement(project, caller, children)
            }
        }
    }

    private fun findCalleesRecursive(
        project: Project,
        function: ScFunction,
        depth: Int,
        visited: MutableSet<PsiElement>,
        stackDepth: Int,
        searchScope: GlobalSearchScope,
        maxResults: Int
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0 || maxResults <= 0) return emptyList()
        if (!visited.add(function)) return emptyList()

        val callees = safeScalaCall(emptyList(), "findCalleesRecursive", LOG) {
            collectCallees(function, searchScope, maxResults)
        }
        return callees.map { callee ->
            val children = if (depth > 1 && callee is ScFunction) {
                findCalleesRecursive(project, callee, depth - 1, visited, stackDepth + 1, searchScope, maxResults)
            } else null
            createCallElement(project, callee, children)
        }
    }

    /**
     * Methods referenced from [function]'s body, in source order.
     *
     * Walks every reference rather than only `f(...)` applications: parameterless calls
     * (`worker.id`) and infix calls (`a max b`) have no `ScMethodCall` node. Targets without a
     * source file in scope (synthetic members such as `Int.+`, library code) are skipped.
     */
    private fun collectCallees(function: ScFunction, searchScope: GlobalSearchScope, maxResults: Int): List<PsiMethod> {
        val callees = LinkedHashSet<PsiMethod>()
        function.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (callees.size >= maxResults) {
                    stopWalking()
                    return
                }
                val reference = element as? PsiReference ?: element.reference
                val target = reference?.resolve() as? PsiMethod
                val file = target?.containingFile?.virtualFile
                if (target != null && file != null && searchScope.contains(file)) {
                    callees.add(target)
                }
                super.visitElement(element)
            }
        })
        return callees.toList()
    }

    private fun createCallElement(
        project: Project,
        method: PsiMethod,
        children: List<CallElementData>? = null
    ): CallElementData {
        val owner = ownerName(method)
        val name = nameOf(method) ?: "unknown"
        val file = method.containingFile?.virtualFile
        return CallElementData(
            name = if (owner.isNullOrEmpty()) name else "$owner.$name",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, method) ?: 0,
            column = getColumnNumber(project, method) ?: 0,
            language = languageOf(method),
            children = children,
            pointerTarget = method
        )
    }
}

// ---------------------------------------------------------------------------
// SuperMethodsHandler
// ---------------------------------------------------------------------------

/**
 * Scala implementation of [SuperMethodsHandler].
 * Finds methods that a given Scala method overrides/implements, including Java and Kotlin ones.
 */
class ScalaSuperMethodsHandler : BaseScalaHandler<SuperMethodsData>(), SuperMethodsHandler {

    companion object {
        private val LOG = logger<ScalaSuperMethodsHandler>()
    }

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? =
        scalaApiBoundary("super method search") { computeSuperMethods(element, project) }

    private fun computeSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val scFunction = findContainingScFunction(element) ?: return null
        val containingClass = findContainingScTypeDefinition(scFunction) ?: return null

        val methodData = MethodData(
            name = scFunction.name() ?: "unknown",
            signature = methodSignature(scFunction),
            containingClass = qualifiedNameOf(containingClass) ?: nameOf(containingClass) ?: "unknown",
            file = scFunction.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, scFunction) ?: 0,
            column = getColumnNumber(project, scFunction) ?: 0,
            language = "Scala",
            pointerTarget = scFunction
        )

        return SuperMethodsData(
            method = methodData,
            hierarchy = buildHierarchy(project, scFunction, visited = mutableSetOf(), depth = 1)
        )
    }

    private fun directSuperMethods(method: PsiMethod): List<PsiMethod> {
        val supers = if (method is ScFunction) method.superMethods().toKotlinList() else method.findSuperMethods().toList()
        return supers.filterIsInstance<PsiMethod>()
    }

    private fun buildHierarchy(
        project: Project,
        method: PsiMethod,
        visited: MutableSet<PsiElement>,
        depth: Int
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()
        safeScalaCall(Unit, "buildHierarchy", LOG) {
            for (superMethod in directSuperMethods(method)) {
                if (!visited.add(superMethod)) continue
                // Java and Kotlin super methods have no Scala parent; PsiMethod.containingClass covers them.
                val containingClass: PsiClass? = findContainingScTypeDefinition(superMethod) ?: superMethod.containingClass

                hierarchy.add(SuperMethodData(
                    name = nameOf(superMethod) ?: "unknown",
                    signature = methodSignature(superMethod),
                    containingClass = containingClass?.let { qualifiedNameOf(it) ?: nameOf(it) } ?: "unknown",
                    containingClassKind = containingClass?.let { classKind(it) } ?: "UNKNOWN",
                    file = superMethod.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, superMethod),
                    column = getColumnNumber(project, superMethod),
                    isInterface = containingClass is ScTrait || containingClass?.isInterface == true,
                    depth = depth,
                    language = languageOf(superMethod),
                    pointerTarget = superMethod
                ))

                hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
            }
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
 *
 * Scala constructs map onto the shared [StructureKind] vocabulary, with the Scala keyword in
 * `modifiers`: a case class is `CLASS` + `case`, a package object `OBJECT` + `package`, and a
 * `val`/`var` member `PROPERTY` + `val`/`var` (Kotlin reports data classes and properties the
 * same way).
 */
class ScalaStructureHandler : BaseScalaHandler<List<StructureNode>>(), StructureHandler {

    companion object {
        private val LOG = logger<ScalaStructureHandler>()
    }

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> =
        scalaApiBoundary("file structure") { computeFileStructure(file, project) }

    private fun computeFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        if (file !is ScalaFile) {
            LOG.debug("File is not a ScalaFile: ${file.javaClass.name}, language: ${file.language.id}")
            return emptyList()
        }

        val structure = mutableListOf<StructureNode>()
        safeScalaCall(Unit, "getFileStructure", LOG) {
            // Top-level type definitions
            PsiTreeUtil.findChildrenOfType(file, ScTypeDefinition::class.java)
                .filter { isTopLevel(it, file) }
                .forEach { structure.add(extractTypeStructure(it, project)) }

            // Top-level functions
            PsiTreeUtil.findChildrenOfType(file, ScFunction::class.java)
                .filter { isTopLevel(it, file) }
                .forEach { structure.add(extractFunctionStructure(it, project)) }
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
        safeScalaCall(Unit, "extractTypeStructure", LOG) {
            typeDef.members().toKotlinList().forEach { member ->
                when (member) {
                    is ScFunction -> children.add(extractFunctionStructure(member, project))
                    is ScTypeDefinition -> children.add(extractTypeStructure(member, project))
                    is ScValue -> children.addAll(extractPropertyStructures(member, project, keyword = "val"))
                    is ScVariable -> children.addAll(extractPropertyStructures(member, project, keyword = "var"))
                    else -> {}
                }
            }
        }

        val (kind, keyword) = when {
            typeDef is ScTrait -> StructureKind.TRAIT to null
            typeDef is ScObject -> StructureKind.OBJECT to (if (typeDef.isPackageObject) "package" else null)
            else -> StructureKind.CLASS to (if (typeDef.isCase) "case" else null)
        }

        return StructureNode(
            name = typeDef.name() ?: "unknown",
            kind = kind,
            modifiers = extractModifiers(typeDef) + listOfNotNull(keyword),
            signature = null,
            line = getLineNumber(project, typeDef) ?: 0,
            endLine = getEndLineNumber(project, typeDef),
            children = children,
            pointerTarget = typeDef
        )
    }

    private fun extractFunctionStructure(function: ScFunction, project: Project): StructureNode {
        return StructureNode(
            name = function.name() ?: "unknown",
            kind = StructureKind.METHOD,
            modifiers = extractModifiers(function),
            signature = buildSignature(function),
            line = getLineNumber(project, function) ?: 0,
            endLine = getEndLineNumber(project, function),
            children = emptyList(),
            pointerTarget = function
        )
    }

    /**
     * A single `val`/`var` declaration can bind more than one name
     * (e.g. `val a, b = computeBoth()`), so this returns one [StructureNode] per
     * declared element instead of collapsing them into a single node.
     */
    private fun extractPropertyStructures(
        field: ScValueOrVariable,
        project: Project,
        keyword: String
    ): List<StructureNode> {
        val declared = safeScalaCall(emptyList(), "extractPropertyStructures", LOG) {
            field.declaredElements().toKotlinList()
        }
        val modifiers = extractModifiers(field) + keyword
        val line = getLineNumber(project, field) ?: 0
        val endLine = getEndLineNumber(project, field)
        if (declared.isEmpty()) {
            return listOf(
                StructureNode(
                    name = "unknown",
                    kind = StructureKind.PROPERTY,
                    modifiers = modifiers,
                    signature = null,
                    line = line,
                    endLine = endLine,
                    children = emptyList(),
                    pointerTarget = field
                )
            )
        }
        return declared.map { declaredElement ->
            StructureNode(
                name = nameOf(declaredElement) ?: "unknown",
                kind = StructureKind.PROPERTY,
                modifiers = modifiers,
                signature = null,
                line = line,
                endLine = endLine,
                children = emptyList(),
                pointerTarget = declaredElement
            )
        }
    }

    private fun buildSignature(function: ScFunction): String {
        return safeScalaCall("()", "buildSignature", LOG) {
            val params = function.paramClauses().clauses().toKotlinList()
                .flatMap { clause -> clause.parameters().toKotlinList() }
                .mapNotNull { param -> param.name() }
                .joinToString(", ")
            "($params)"
        }
    }
}

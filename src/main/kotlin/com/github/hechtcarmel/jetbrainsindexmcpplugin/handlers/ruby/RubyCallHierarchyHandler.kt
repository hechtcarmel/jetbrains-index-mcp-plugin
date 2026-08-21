package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallHierarchyData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createNavigationSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.shouldIncludeNavigationElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

/**
 * Ruby implementation of [CallHierarchyHandler].
 *
 * Builds caller/callee trees for Ruby methods using:
 * - Callers: Platform [ReferencesSearch] API (works on Ruby PSI)
 * - Callees: [PsiTreeUtil] to find RCall descendants, resolves via command reference
 *
 * Named display format: "ClassName#method_name" for instance methods,
 * "ClassName.method_name" for class methods, bare name for top-level methods.
 */
class RubyCallHierarchyHandler : BaseRubyHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
    }

    override val languageId = "Ruby"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRubyLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): CallHierarchyData? {
        val rMethod = findContainingRMethod(element) ?: return null
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)
        val visited = mutableSetOf<String>()

        val calls = if (direction == "callers") {
            findCallersRecursive(project, rMethod, depth, visited, searchScope = searchScope)
        } else {
            findCalleesRecursive(project, rMethod, depth, visited, searchScope = searchScope)
        }

        return CallHierarchyData(
            element = createCallElement(project, rMethod),
            calls = calls
        )
    }

    // ── Callers ─────────────────────────────────────────────────────────────────

    /**
     * Finds all methods that call [rMethod], recursively up to [depth] levels.
     *
     * Uses the platform [ReferencesSearch] API which works on Ruby PSI elements
     * through the platform's reference index.
     */
    private fun findCallersRecursive(
        project: Project,
        rMethod: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val methodKey = getMethodKey(rMethod)
        if (methodKey in visited) return emptyList()
        visited.add(methodKey)

        val results = mutableListOf<CallElementData>()

        // Pass 1: Direct references to this method
        val references = mutableListOf<PsiReference>()
        ReferencesSearch.search(rMethod, searchScope).forEach(Processor { ref ->
            references.add(ref)
            references.size < MAX_RESULTS_PER_LEVEL * 2
        })
        processReferences(project, references, rMethod, depth, visited, stackDepth, searchScope, results)

        // Pass 2: Override chain — also find callers of overridden (parent) methods.
        // When a method overrides a parent method, callers of the parent method
        // should also appear in the call hierarchy of the child.
        // Uses RubyOverrideImplementUtil.getOverriddenMethods (symbol tree, O(1)).
        if (results.size < MAX_RESULTS_PER_LEVEL) {
            val overriddenMethods = getOverriddenMethodsViaOverrideUtil(rMethod)
            for (overridden in overriddenMethods) {
                if (results.size >= MAX_RESULTS_PER_LEVEL) break
                val overriddenKey = getMethodKey(overridden)
                if (overriddenKey in visited) continue
                visited.add(overriddenKey)

                val overriddenRefs = mutableListOf<PsiReference>()
                ReferencesSearch.search(overridden, searchScope).forEach(Processor { ref ->
                    overriddenRefs.add(ref)
                    overriddenRefs.size < MAX_RESULTS_PER_LEVEL * 2
                })
                processReferences(project, overriddenRefs, rMethod, depth, visited, stackDepth, searchScope, results)
            }
        }

        return results.distinctBy { it.name + it.file + it.line }.take(MAX_RESULTS_PER_LEVEL)
    }

    /**
     * Processes a list of references and adds them to [results].
     *
     * Skips self-references, filters by file extension, and recurses for depth > 1.
     */
    private fun processReferences(
        project: Project,
        references: MutableList<PsiReference>,
        rMethod: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int,
        searchScope: GlobalSearchScope,
        results: MutableList<CallElementData>
    ) {
        for (ref in references) {
            if (results.size >= MAX_RESULTS_PER_LEVEL) break
            val refElement = ref.element
            // Skip references from non-.rb files (e.g. injected Ruby in markdown files)
            val refFile = refElement.containingFile?.virtualFile
            if (refFile != null && !refFile.name.endsWith(".rb") && !refFile.name.endsWith(".rake") && !refFile.extension.isNullOrEmpty()) continue
            val containingMethod = findContainingRMethod(refElement)
            if (containingMethod != null && containingMethod != rMethod) {
                val children = if (depth > 1) {
                    findCallersRecursive(project, containingMethod, depth - 1, visited, stackDepth + 1, searchScope)
                } else null
                if (shouldIncludeNavigationElement(searchScope, containingMethod)) {
                    results.add(createCallElement(project, containingMethod, children))
                } else if (children != null) {
                    results.addAll(children)
                }
            }
        }
    }

    // ── Callees ─────────────────────────────────────────────────────────────────

    /**
     * Finds all methods called by [rMethod], recursively up to [depth] levels.
     *
     * Uses [PsiTreeUtil] to find RCall descendants in the method body, then tries
     * to resolve each call to its target RMethod via the command PSI reference.
     */
    private fun findCalleesRecursive(
        project: Project,
        rMethod: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val methodKey = getMethodKey(rMethod)
        if (methodKey in visited) return emptyList()
        visited.add(methodKey)

        val callees = mutableListOf<CallElementData>()

        // Ruby represents method calls as PsiReference-implementing identifiers
        // (RIdentifierImpl), not as separate RCall nodes. Find all PsiReference
        // children in the method body and resolve each to find the target method.
        // Find method call references: Ruby represents calls as RIdentifierImpl nodes
        // which have getReference() but do NOT implement PsiReference interface.
        // Check by simple class name and reflectively resolve via getReference().
        val callRefs = mutableListOf<PsiElement>()
        val allElements = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(rMethod, PsiElement::class.java)
        for (child in allElements) {
            if (child === rMethod) continue
            if (callRefs.size >= MAX_RESULTS_PER_LEVEL * 2) break
            try {
                val ref = child::class.java.getMethod("getReference").invoke(child) as? PsiReference
                if (ref != null) callRefs.add(child)
            } catch (_: NoSuchMethodException) {}
        }

        callRefs.forEach { callExpr ->
            val calledMethod = resolveCallTarget(callExpr)
            if (calledMethod != null && isRMethod(calledMethod)) {
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
        return callees.take(MAX_RESULTS_PER_LEVEL)
    }

    /**
     * Resolves an [RCall] PSI element to its target method.
     *
     * Strategy 1: Get the command PSI element via `getPsiCommand()` and try its
     *   `getReference().resolve()` — the command element typically implements
     *   [PsiReference] and resolves to the target [RMethod].
     *
     * Strategy 2: Try the RCall expression directly as a [PsiReference] and
     *   resolve it. Some Ruby plugin versions expose the reference on the call.
     */
    private fun resolveCallTarget(callExpr: PsiElement): PsiElement? {
        // Strategy 1: getPsiCommand() → getReference().resolve()
        try {
            val getPsiCommand = callExpr.javaClass.getMethod("getPsiCommand")
            val psiCommand = getPsiCommand.invoke(callExpr) as? PsiElement
            if (psiCommand != null) {
                try {
                    val getRefMethod = psiCommand.javaClass.getMethod("getReference")
                    val reference = getRefMethod.invoke(psiCommand) as? PsiReference
                    val resolved = reference?.resolve()
                    if (resolved != null) return resolved
                } catch (_: NoSuchMethodException) {}
            }
        } catch (_: Exception) {}

        // Strategy 2: Try the call expression as a PsiReference directly
        try {
            return (callExpr as? PsiReference)?.resolve()
        } catch (_: Exception) {}

        return null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Builds a unique key for cycle detection.
     * Combines file path, line number, and qualified method name.
     */
    private fun getMethodKey(rMethod: PsiElement): String {
        val containingClass = findContainingRClassOrRModule(rMethod)
        val className = containingClass?.let { getRubyQualifiedName(it) ?: getName(it) } ?: ""
        val methodName = getName(rMethod) ?: ""
        val file = rMethod.containingFile?.virtualFile?.path ?: ""
        val line = getLineNumber(rMethod.project, rMethod) ?: 0
        return "$file:$line:$className.$methodName"
    }

    /**
     * Creates a [CallElementData] from an RMethod PSI element.
     *
     * Naming convention:
     * - "ClassName#method_name" for instance methods
     * - "ClassName.method_name" for class methods
     * - Bare name for top-level methods
     */
    private fun createCallElement(
        project: Project,
        rMethod: PsiElement,
        children: List<CallElementData>? = null
    ): CallElementData {
        val file = rMethod.containingFile?.virtualFile
        val containingClass = findContainingRClassOrRModule(rMethod)
        val className = containingClass?.let { getName(it) }
        val methodName = getName(rMethod) ?: "unknown"
        val name = if (className != null) "$className#$methodName" else methodName

        return CallElementData(
            name = name,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, rMethod) ?: 0,
            column = getColumnNumber(project, rMethod) ?: 0,
            language = "Ruby",
            children = children?.takeIf { it.isNotEmpty() }
        )
    }
}
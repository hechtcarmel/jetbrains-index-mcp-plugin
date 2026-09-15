package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.MethodData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SuperMethodData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SuperMethodsData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SuperMethodsHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * Ruby implementation of [SuperMethodsHandler].
 *
 * Finds all parent methods that a method overrides or inherits from.
 *
 * **Implementation Strategy**:
 * Builds the super-method chain ourselves from the indexed symbol tree rather than
 * delegating to `RubyOverrideImplementUtil.getOverriddenMethods` (which returns a flat,
 * undifferentiated list). Walking it ourselves lets us tag each parent with *how* it
 * entered the method-lookup chain — `include`, `prepend`, `extend`, or classical
 * `superclass` inheritance — surfaced via [SuperMethodData.via]. This is finer-grained
 * than the plugin's native override navigation, which does not distinguish mixin kind.
 *
 * **Ruby MRO order used** (for an instance method defined in class C):
 * 1. modules `include`d by C            (`via = "include"`)
 * 2. superclass chain S, and for each S:
 *    - modules `prepend`ed to S         (`via = "prepend"`, above S in its own MRO)
 *    - S itself                          (`via = "superclass"`)
 *    - modules `include`d by S           (`via = "include"`)
 * For a class method (`def self.x`): modules `extend`ed by C (`via = "extend"`) plus the
 * superclass chain.
 *
 * **Returns**:
 * - `SuperMethodsData` containing the current method's metadata and its super method hierarchy.
 * - Returns null if the element is not a Ruby method or the Ruby plugin is unavailable.
 */
class RubySuperMethodsHandler : BaseRubyHandler<SuperMethodsData>(), SuperMethodsHandler {

    companion object {
        private val LOG = logger<RubySuperMethodsHandler>()
        private const val MAX_HIERARCHY_DEPTH = 50
        private const val MAX_SUPER_METHODS = 100
    }

    override val languageId = "Ruby"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRubyLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        // Try to resolve the element to an RMethod via multiple strategies
        val rMethod = resolveToRMethod(element) ?: return null
        val containingClass = findContainingRClassOrRModule(rMethod) ?: return null

        // Build method data for the current method
        val methodData = buildMethodData(rMethod, project, containingClass)

        val methodName = getName(rMethod)
            ?: return SuperMethodsData(method = methodData, hierarchy = emptyList())

        // Class methods (`def self.foo`) resolve through the `extend` / singleton chain;
        // instance methods through `include` / `prepend` / superclass.
        val isClassMethod = try {
            rMethod.text.startsWith("def self.")
        } catch (_: Exception) {
            false
        }

        val hierarchy = buildRubyHierarchy(project, containingClass, methodName, isClassMethod)

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    // ── Method Resolution ───────────────────────────────────────────────────────────────

    /**
     * Resolves a PSI element to an RMethod element.
     *
     * Tries multiple resolution strategies:
     * 1. If element is already an RMethod, return it
     * 2. Try to resolve via PsiTreeUtil.getParentOfType
     * 3. Fallback: use OverridingMethodsSearch to find the method declaration (for method calls)
     *
     * @param element The PSI element to resolve
     * @return The resolved RMethod, or null if not found
     */
    private fun resolveToRMethod(element: PsiElement): PsiElement? {
        val rMethodClass = rMethodClass ?: return null

        // Strategy 1: Element is already an RMethod
        if (rMethodClass.isInstance(element)) return element

        // Strategy 2: Find RMethod in parent chain
        @Suppress("UNCHECKED_CAST")
        val methodFromParents = PsiTreeUtil.getParentOfType(element, rMethodClass as Class<out PsiElement>)
        if (methodFromParents != null) return methodFromParents

        return null
    }

    // ── Method Data Building ─────────────────────────────────────────────────────────────

    /**
     * Builds [MethodData] for the given RMethod.
     *
     * @param rMethod The RMethod PSI element
     * @param project The project context
     * @param containingClass The containing RContainer of the method
     * @return The method data, or null if required fields are missing
     */
    private fun buildMethodData(rMethod: PsiElement, project: Project, containingClass: PsiElement): MethodData {
        val name = getName(rMethod) ?: "unknown"
        val qualifiedClass = getRubyQualifiedName(containingClass) ?: name
        val file = rMethod.containingFile?.virtualFile
        val signature = buildRubyMethodSignature(rMethod)

        return MethodData(
            name = name,
            signature = signature,
            containingClass = qualifiedClass,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, rMethod) ?: 0,
            column = getColumnNumber(project, rMethod) ?: 0,
            language = "Ruby"
        )
    }

    // ── Super Method Hierarchy Building (Ruby MRO with provenance) ──────────────────────────

    /**
     * Builds the super-method hierarchy for [methodName] as declared in [originClass],
     * walking the Ruby method-resolution order and tagging each parent with how it entered
     * the chain ([SuperMethodData.via]).
     *
     * Only ancestors that actually declare a method named [methodName] are returned — those
     * are the parents the origin method overrides / can reach via `super`.
     */
    private fun buildRubyHierarchy(
        project: Project,
        originClass: PsiElement,
        methodName: String,
        isClassMethod: Boolean
    ): List<SuperMethodData> {
        val scope = GlobalSearchScope.allScope(project)

        // Collect candidate ancestor containers in MRO order, paired with provenance.
        val candidates = mutableListOf<Pair<PsiElement, String>>()
        if (isClassMethod) {
            // Class methods: extend mixins provide singleton methods, then the superclass chain.
            for (fqn in getExtendedModuleFQNs(project, originClass)) {
                resolveByFQN(project, fqn, scope)?.let { candidates.add(it to "extend") }
            }
            collectSuperclassChain(project, originClass, scope, candidates, includeMixins = false)
        } else {
            // Instance methods: included modules of the origin, then the superclass chain
            // (which itself expands prepend/self/include per class).
            for (fqn in getIncludedModuleFQNs(project, originClass)) {
                resolveByFQN(project, fqn, scope)?.let { candidates.add(it to "include") }
            }
            collectSuperclassChain(project, originClass, scope, candidates, includeMixins = true)
        }

        val hierarchy = mutableListOf<SuperMethodData>()
        val visitedMethods = mutableSetOf<String>()

        for ((ancestor, via) in candidates) {
            if (hierarchy.size >= MAX_SUPER_METHODS) break
            val classKey = getRubyQualifiedName(ancestor) ?: getName(ancestor) ?: continue
            val superMethod = findMethodByNameReflectively(ancestor, methodName) ?: continue

            val methodKey = "$classKey#$methodName"
            if (methodKey in visitedMethods) continue
            visitedMethods.add(methodKey)

            val file = superMethod.containingFile?.virtualFile
            hierarchy.add(
                SuperMethodData(
                    name = getName(superMethod) ?: methodName,
                    signature = buildRubyMethodSignature(superMethod),
                    containingClass = classKey,
                    containingClassKind = if (isRClass(ancestor)) "CLASS" else "MODULE",
                    file = file?.let { getRelativePath(project, it) },
                    line = getLineNumber(project, superMethod),
                    column = getColumnNumber(project, superMethod),
                    isInterface = isRModule(ancestor),
                    depth = hierarchy.size + 1,
                    language = "Ruby",
                    via = via
                )
            )
        }

        return hierarchy
    }

    /**
     * Walks the superclass chain starting from [startClass], appending each ancestor container
     * to [out] in Ruby MRO order. When [includeMixins] is true, each superclass is expanded as
     * `prepend` modules (above the class) → the class itself → `include` modules (below it).
     *
     * Cycle-guarded by qualified class name and bounded by [MAX_HIERARCHY_DEPTH].
     */
    private fun collectSuperclassChain(
        project: Project,
        startClass: PsiElement,
        scope: GlobalSearchScope,
        out: MutableList<Pair<PsiElement, String>>,
        includeMixins: Boolean
    ) {
        val visitedClasses = mutableSetOf<String>()
        var current: PsiElement? = startClass
        var guard = 0

        while (current != null && guard++ < MAX_HIERARCHY_DEPTH) {
            val superFqn = rClassGetSuperClassFQN(current) ?: break
            val superClass = resolveByFQN(project, superFqn, scope)
                ?: resolveByFQN(project, superFqn, GlobalSearchScope.projectScope(project))
                ?: break

            val key = getRubyQualifiedName(superClass) ?: getName(superClass) ?: break
            if (key in visitedClasses) break
            visitedClasses.add(key)

            if (includeMixins) {
                for (fqn in getPrependedModuleFQNs(project, superClass)) {
                    resolveByFQN(project, fqn, scope)?.let { out.add(it to "prepend") }
                }
            }
            out.add(superClass to "superclass")
            if (includeMixins) {
                for (fqn in getIncludedModuleFQNs(project, superClass)) {
                    resolveByFQN(project, fqn, scope)?.let { out.add(it to "include") }
                }
            }

            current = superClass
        }
    }

    /**
     * Finds a method named [methodName] declared directly on [container] (an RClass/RModule).
     *
     * Uses `findMethodByName(String)` (inherited from `RFieldConstantContainerBase`) via
     * reflection, falling back to a direct RMethod child scan.
     */
    private fun findMethodByNameReflectively(container: PsiElement, methodName: String): PsiElement? {
        try {
            val findMethod = container.javaClass.getMethod("findMethodByName", String::class.java)
            (findMethod.invoke(container, methodName) as? PsiElement)?.let { return it }
        } catch (_: Exception) {
        }
        try {
            for (child in container.children) {
                if (isRMethod(child) && getName(child) == methodName) return child
            }
        } catch (_: Exception) {
        }
        return null
    }

    // ── Ruby Method Signature Building ────────────────────────────────────────────────────

    /**
     * Builds a string representation of a Ruby method's signature.
     *
     * Format: "method_name(param1, param2): return_type"
     *
     * Extracts parameter information and return type from the RMethod PSI element.
     *
     * @param rMethod The RMethod to extract signature from
     * @return The method signature string
     */
    private fun buildRubyMethodSignature(rMethod: PsiElement): String {
        val rMethodClass = rMethodClass ?: return "(unknown)"

        val name = try {
            getName(rMethod) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        val parameters = buildMethodParameters(rMethod)
        val returnType = buildReturnType(rMethod)
        val signature = if (parameters.isEmpty()) {
            "$name"
        } else {
            "$name($parameters): $returnType"
        }
        return signature
    }

    /**
     * Builds the parameter list for a Ruby method.
     *
     * Extracts parameter names and attempts to infer parameter types from
     * the containing class or method context.
     *
     * @param rMethod The RMethod to extract parameters from
     * @return Parameter string in format "param1, param2, ..."
     */
    private fun buildMethodParameters(rMethod: PsiElement): String {
        val rCallClass = rCallClass ?: return ""
        
        return try {
            val parameters = mutableListOf<String>()
            for (child in rMethod.children) {
                // Look for parameter-like nodes (RCall expressions with parameter context)
                if (rCallClass.isInstance(child)) {
                    val command = try {
                        child.javaClass.getMethod("getCommand").invoke(child) as? String
                    } catch (_: Exception) {
                        null
                    }
                    if (command == "param" || command == "kwsplat" || command == "restarg" ||
                        command == "shadowarg" || command == "blockarg" || command == "forwarding_arg") {
                        val paramValue = try {
                            child.javaClass.getMethod("getValue").invoke(child) as? String
                        } catch (_: Exception) {
                            null
                        }
                        if (!paramValue.isNullOrEmpty()) {
                            parameters.add(paramValue)
                        }
                    }
                }
            }
            parameters.joinToString(", ")
        } catch (e: Exception) {
            LOG.debug("Failed to build Ruby parameters: ${e.message}")
            ""
        }
    }

    /**
     * Builds the return type for a Ruby method.
     *
     * For Ruby, we return a type inference based on:
     * 1. Explicit return type annotation
     * 2. Last statement type inference (if available)
     * 3. Fallback to "Any"
     *
     * @param rMethod The RMethod to extract return type from
     * @return Return type string
     */
    private fun buildReturnType(rMethod: PsiElement): String {
        val rMethodClass = rMethodClass ?: return "Any"
        
        var returnType: Any? = null
        try {
            // Try to get explicit return type from RMethod
            val returnTypeMethod = rMethodClass.getMethod("getReturnType")
            returnType = returnTypeMethod.invoke(rMethod)
        } catch (_: Exception) {
            // Return type method not available
        }
        
        if (returnType != null) {
            try {
                // Try to get presentable text
                val presentableTextMethod = returnType.javaClass.getMethod("getPresentableText")
                val text = presentableTextMethod.invoke(returnType) as? String
                if (!text.isNullOrEmpty()) {
                    return text
                }
            } catch (_: Exception) {
                // Fallback to qualified name
            }
        }

        // Fallback: try to infer from return statement
        return "Any"
    }
}
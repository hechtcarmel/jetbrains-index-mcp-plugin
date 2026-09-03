package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.ruby

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.StructureHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Ruby implementation of [StructureHandler].
 *
 * Extracts the hierarchical structure of a Ruby source file including
 * classes (RClass), modules (RModule), methods (RMethod), and their
 * nesting relationships. Uses reflection to access Ruby PSI classes.
 */
class RubyStructureHandler : BaseRubyHandler<List<StructureNode>>(), StructureHandler {

    override val languageId = "Ruby"

    companion object {
        private val LOG = logger<RubyStructureHandler>()

        /**
         * Derives a Ruby method signature string from the method's source text.
         *
         * Visible for testing so the full branch logic can be exercised without
         * PSI: parameter extraction, empty parens (`()`), and the paren-less
         * fallback (`()` for `def foo`, `def admin?`, `def save!`). A one-line
         * body like `def foo; end` yields `null` (no trailing whitespace/EOL
         * after the name on that line).
         */
        internal fun deriveMethodSignatureFromText(text: String): String? {
            val paramPattern = Regex("""def\s+\S+\s*\(([^)]*)\)""")
            val match = paramPattern.find(text)
            if (match != null) {
                val params = match.groupValues[1]
                return if (params.isNotBlank()) "($params)" else "()"
            }
            // Fallback: paren-less method definition — first line is `def name`
            val noParamPattern = Regex("""def\s+\S+\s*$\s*""")
            return if (noParamPattern.containsMatchIn(text.lines().firstOrNull() ?: "")) "()" else null
        }

        /** Detects the `self.` class-method modifier from method text. Visible for testing. */
        internal fun deriveSelfModifier(text: String): List<String> =
            if (text.startsWith("def self.")) listOf("self") else emptyList()
    }

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isRubyLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.ruby.isAvailable

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val structure = mutableListOf<StructureNode>()

        try {
            // Find top-level classes
            if (rClassClass != null) {
                @Suppress("UNCHECKED_CAST")
                val classes = PsiTreeUtil.findChildrenOfType(file, rClassClass as Class<PsiElement>)
                classes.forEach { rClass ->
                    if (isTopLevel(rClass, file)) {
                        structure.add(extractContainerStructure(rClass, project))
                    }
                }
            }

            // Find top-level modules
            if (rModuleClass != null) {
                @Suppress("UNCHECKED_CAST")
                val modules = PsiTreeUtil.findChildrenOfType(file, rModuleClass as Class<PsiElement>)
                modules.forEach { rModule ->
                    if (isTopLevel(rModule, file)) {
                        structure.add(extractContainerStructure(rModule, project))
                    }
                }
            }

            // Find top-level methods (methods defined outside any class/module)
            if (rMethodClass != null) {
                @Suppress("UNCHECKED_CAST")
                val methods = PsiTreeUtil.findChildrenOfType(file, rMethodClass as Class<PsiElement>)
                methods.forEach { method ->
                    if (isTopLevel(method, file)) {
                        structure.add(extractMethodStructure(method, project))
                    }
                }
            }

            LOG.debug("Found ${structure.size} top-level elements in Ruby file")

        } catch (e: Exception) {
            LOG.warn("Failed to extract Ruby file structure: ${e.message}, ${e.javaClass.simpleName}")
        }

        return structure.sortedBy { it.line }
    }

    /**
     * Check if an element is a top-level element (not nested inside another class or module).
     */
    private fun isTopLevel(element: PsiElement, file: PsiFile): Boolean {
        var current: PsiElement? = element.parent
        while (current != null && current != file) {
            if (isRClass(current) || isRModule(current)) {
                return false
            }
            current = current.parent
        }
        return true
    }

    /**
     * Extracts structure from an RClass or RModule element.
     *
     * Uses getStructureElements() via reflection on RContainer to get direct
     * children (methods, nested classes, constants). Falls back to
     * PsiTreeUtil.findChildrenOfType if getStructureElements() is unavailable.
     */
    private fun extractContainerStructure(container: PsiElement, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()

        // Strategy 1: Use getStructureElements() via RContainer reflection
        val directChildren = getStructureElementsViaReflection(container)
        if (directChildren != null) {
            for (child in directChildren) {
                when {
                    isRClass(child) || isRModule(child) -> {
                        children.add(extractContainerStructure(child, project))
                    }
                    isRMethod(child) -> {
                        children.add(extractMethodStructure(child, project))
                    }
                    else -> {
                        // Check if it's a constant
                        val constantNode = tryExtractConstantNode(child, project)
                        if (constantNode != null) {
                            children.add(constantNode)
                        }
                    }
                }
            }
        } else {
            // Strategy 2: Fallback — use PsiTreeUtil.findChildrenOfType for methods
            if (rMethodClass != null) {
                @Suppress("UNCHECKED_CAST")
                val methods = PsiTreeUtil.findChildrenOfType(container, rMethodClass as Class<PsiElement>)
                methods.forEach { method ->
                    if (method.parent == container) {
                        children.add(extractMethodStructure(method, project))
                    }
                }
            }

            // Also find nested classes/modules within this container
            if (rClassClass != null) {
                @Suppress("UNCHECKED_CAST")
                val nestedClasses = PsiTreeUtil.findChildrenOfType(container, rClassClass as Class<PsiElement>)
                nestedClasses.forEach { nestedClass ->
                    if (nestedClass.parent == container) {
                        children.add(extractContainerStructure(nestedClass, project))
                    }
                }
            }

            if (rModuleClass != null) {
                @Suppress("UNCHECKED_CAST")
                val nestedModules = PsiTreeUtil.findChildrenOfType(container, rModuleClass as Class<PsiElement>)
                nestedModules.forEach { nestedModule ->
                    if (nestedModule.parent == container) {
                        children.add(extractContainerStructure(nestedModule, project))
                    }
                }
            }
        }

        val name = getName(container) ?: "unknown"
        val kind = when {
            isRClass(container) -> StructureKind.CLASS
            isRModule(container) -> StructureKind.MODULE
            else -> StructureKind.UNKNOWN
        }

        return StructureNode(
            name = name,
            kind = kind,
            modifiers = emptyList(),
            signature = buildContainerSignature(container),
            line = getLineNumber(project, container) ?: 0,
            children = children.sortedBy { it.line }
        )
    }

    /**
     * Calls RContainer.getStructureElements() via reflection to get direct children.
     *
     * Returns null if the method is not available (element not an RContainer,
     * or the method doesn't exist on the class).
     */
    private fun getStructureElementsViaReflection(element: PsiElement): List<PsiElement>? {
        return try {
            val method = element.javaClass.getMethod("getStructureElements")
            val result = method.invoke(element) as? List<*>
            result?.filterIsInstance<PsiElement>()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Tries to extract a constant node from a PSI element.
     *
     * Checks if the element is a Ruby constant (RConstant) and extracts
     * its name and line number.
     */
    private fun tryExtractConstantNode(element: PsiElement, project: Project): StructureNode? {
        return try {
            val rConstantClass = Class.forName("org.jetbrains.plugins.ruby.ruby.lang.psi.RConstant")
            if (!rConstantClass.isInstance(element)) return null

            StructureNode(
                name = getName(element) ?: "unknown",
                kind = StructureKind.CONSTANT,
                modifiers = emptyList(),
                signature = null,
                line = getLineNumber(project, element) ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts structure from an RMethod element.
     */
    private fun extractMethodStructure(method: PsiElement, project: Project): StructureNode {
        val name = getName(method) ?: "unknown"

        return StructureNode(
            name = name,
            kind = StructureKind.METHOD,
            modifiers = getRubyMethodModifiers(method),
            signature = buildMethodSignature(method),
            line = getLineNumber(project, method) ?: 0
        )
    }

    /**
     * Gets Ruby method modifiers (visibility, self/class method indication).
     */
    private fun getRubyMethodModifiers(method: PsiElement): List<String> {
        return try {
            deriveSelfModifier(method.text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Builds a signature string for a class/module container.
     *
     * For classes: shows superclass FQN if available, e.g., "< Animal"
     * For modules: returns null (no signature)
     */
    private fun buildContainerSignature(container: PsiElement): String? {
        if (isRClass(container)) {
            val superFqn = rClassGetSuperClassFQN(container)
            if (superFqn != null) {
                return "< $superFqn"
            }
        }
        return null
    }

    /**
     * Builds a signature string for a Ruby method.
     *
     * Shows the method signature with parameters, e.g., "(x, y)"
     */
    private fun buildMethodSignature(method: PsiElement): String? {
        return try {
            deriveMethodSignatureFromText(method.text)
        } catch (e: Exception) {
            null
        }
    }
}
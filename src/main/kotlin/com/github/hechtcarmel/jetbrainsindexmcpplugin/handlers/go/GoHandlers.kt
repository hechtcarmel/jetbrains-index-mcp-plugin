package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.go

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.GoPluginDetector
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * Registration entry point for Go language handlers.
 *
 * This class is loaded via reflection when the Go plugin is available.
 * It registers all Go-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## Go PSI Classes Used (via reflection)
 *
 * - `com.goide.psi.GoFile` - Go source files
 * - `com.goide.psi.GoTypeSpec` - Type declarations (struct, interface, etc.)
 * - `com.goide.psi.GoFunctionDeclaration` - Function declarations
 * - `com.goide.psi.GoMethodDeclaration` - Method declarations (with receiver)
 * - `com.goide.psi.GoStructType` - Struct type definitions
 * - `com.goide.psi.GoInterfaceType` - Interface type definitions
 * - `com.goide.psi.GoCallExpr` - Function/method call expressions
 *
 * ## Go-Specific Concepts
 *
 * - **Implicit Interface Implementation**: Go uses structural typing for interfaces.
 *   A type implements an interface if it has all required methods.
 * - **Composition via Embedding**: Go uses struct embedding instead of inheritance.
 * - **Receiver Types**: Methods are associated with types via receivers.
 */
object GoHandlers {

    private val LOG = logger<GoHandlers>()

    /**
     * Registers all Go handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!GoPluginDetector.isGoPluginAvailable) {
            LOG.info("Go plugin not available, skipping Go handler registration")
            return
        }

        try {
            // Verify Go classes are accessible before registering
            Class.forName("com.goide.psi.GoFile")
            Class.forName("com.goide.psi.GoTypeSpec")

            registry.registerTypeHierarchyHandler(GoTypeHierarchyHandler())
            registry.registerImplementationsHandler(GoImplementationsHandler())
            registry.registerCallHierarchyHandler(GoCallHierarchyHandler())
            registry.registerSymbolSearchHandler(GoSymbolSearchHandler())
            registry.registerSuperMethodsHandler(GoSuperMethodsHandler())

            LOG.info("Registered Go handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Go PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            LOG.warn("Failed to register Go handlers: ${e.message}")
        }
    }
}

/**
 * Base class for Go handlers with common utilities.
 *
 * Uses reflection to access Go PSI classes to avoid compile-time dependencies.
 */
abstract class BaseGoHandler<T> : LanguageHandler<T> {

    protected val LOG = logger<BaseGoHandler<*>>()

    /**
     * Checks if the element is from a Go language.
     */
    protected fun isGoLanguage(element: PsiElement): Boolean {
        return element.language.id == "go"
    }

    // Lazy-loaded Go PSI classes via reflection

    protected val goFileClass: Class<*>? by lazy {
        try {
            Class.forName("com.goide.psi.GoFile")
        } catch (e: ClassNotFoundException) {
            LOG.debug("GoFile not found")
            null
        }
    }

    protected val goTypeSpecClass: Class<*>? by lazy {
        try {
            Class.forName("com.goide.psi.GoTypeSpec")
        } catch (e: ClassNotFoundException) {
            LOG.debug("GoTypeSpec not found")
            null
        }
    }

    protected val goFunctionDeclarationClass: Class<*>? by lazy {
        try {
            Class.forName("com.goide.psi.GoFunctionDeclaration")
        } catch (e: ClassNotFoundException) {
            LOG.debug("GoFunctionDeclaration not found")
            null
        }
    }

    protected val goMethodDeclarationClass: Class<*>? by lazy {
        try {
            Class.forName("com.goide.psi.GoMethodDeclaration")
        } catch (e: ClassNotFoundException) {
            LOG.debug("GoMethodDeclaration not found")
            null
        }
    }

    protected val goStructTypeClass: Class<*>? by lazy {
        try {
            Class.forName("com.goide.psi.GoStructType")
        } catch (e: ClassNotFoundException) {
            LOG.debug("GoStructType not found")
            null
        }
    }

    protected val goInterfaceTypeClass: Class<*>? by lazy {
        try {
            Class.forName("com.goide.psi.GoInterfaceType")
        } catch (e: ClassNotFoundException) {
            LOG.debug("GoInterfaceType not found")
            null
        }
    }

    protected val goCallExprClass: Class<*>? by lazy {
        try {
            Class.forName("com.goide.psi.GoCallExpr")
        } catch (e: ClassNotFoundException) {
            LOG.debug("GoCallExpr not found")
            null
        }
    }

    protected val goNamedElementClass: Class<*>? by lazy {
        try {
            Class.forName("com.goide.psi.GoNamedElement")
        } catch (e: ClassNotFoundException) {
            LOG.debug("GoNamedElement not found")
            null
        }
    }

    // Helper methods

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
     * Checks if element is a GoTypeSpec using reflection.
     */
    protected fun isGoTypeSpec(element: PsiElement): Boolean {
        return goTypeSpecClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a GoFunctionDeclaration using reflection.
     */
    protected fun isGoFunction(element: PsiElement): Boolean {
        return goFunctionDeclarationClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a GoMethodDeclaration using reflection.
     */
    protected fun isGoMethod(element: PsiElement): Boolean {
        return goMethodDeclarationClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a GoStructType using reflection.
     */
    protected fun isGoStructType(element: PsiElement): Boolean {
        return goStructTypeClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a GoInterfaceType using reflection.
     */
    protected fun isGoInterfaceType(element: PsiElement): Boolean {
        return goInterfaceTypeClass?.isInstance(element) == true
    }

    /**
     * Finds containing GoTypeSpec using reflection.
     */
    protected fun findContainingGoType(element: PsiElement): PsiElement? {
        if (isGoTypeSpec(element)) return element
        val goTypeSpec = goTypeSpecClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, goTypeSpec as Class<out PsiElement>)
    }

    /**
     * Finds containing GoFunctionDeclaration or GoMethodDeclaration using reflection.
     */
    protected fun findContainingGoFunction(element: PsiElement): PsiElement? {
        if (isGoFunction(element) || isGoMethod(element)) return element

        // Try method first
        val goMethod = goMethodDeclarationClass
        if (goMethod != null) {
            @Suppress("UNCHECKED_CAST")
            val method = PsiTreeUtil.getParentOfType(element, goMethod as Class<out PsiElement>)
            if (method != null) return method
        }

        // Try function
        val goFunction = goFunctionDeclarationClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, goFunction as Class<out PsiElement>)
    }

    /**
     * Gets the name of a Go element via reflection.
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
     * Gets the qualified name of a Go type via reflection.
     */
    protected fun getQualifiedName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getQualifiedName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            // Fallback to just the name
            getName(element)
        }
    }

    /**
     * Gets the spec type (struct, interface, etc.) of a GoTypeSpec via reflection.
     */
    protected fun getSpecType(goTypeSpec: PsiElement): PsiElement? {
        return try {
            val method = goTypeSpec.javaClass.getMethod("getSpecType")
            method.invoke(goTypeSpec) as? PsiElement
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Determines the kind of a Go type element.
     */
    protected fun determineTypeKind(element: PsiElement): String {
        val specType = getSpecType(element)
        return when {
            specType != null && isGoStructType(specType) -> "STRUCT"
            specType != null && isGoInterfaceType(specType) -> "INTERFACE"
            isGoTypeSpec(element) -> "TYPE"
            else -> "TYPE"
        }
    }

    /**
     * Determines the kind of a Go element.
     */
    protected fun determineElementKind(element: PsiElement): String {
        return when {
            isGoTypeSpec(element) -> determineTypeKind(element)
            isGoMethod(element) -> "METHOD"
            isGoFunction(element) -> "FUNCTION"
            else -> "SYMBOL"
        }
    }
}

/**
 * Go implementation of [TypeHierarchyHandler].
 *
 * Handles Go-specific type relationships:
 * - Struct embedding (composition-based "inheritance")
 * - Interface implementation (implicit, structural typing)
 * - Interface embedding
 */
class GoTypeHierarchyHandler : BaseGoHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 50
    }

    override val languageId = "go"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isGoLanguage(element)
    }

    override fun isAvailable(): Boolean = GoPluginDetector.isGoPluginAvailable && goTypeSpecClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val goType = findContainingGoType(element) ?: return null
        LOG.debug("Getting type hierarchy for Go type: ${getName(goType)}")

        val specType = getSpecType(goType)
        val supertypes = getSupertypes(project, goType, specType)
        val subtypes = getSubtypes(project, goType, specType)

        LOG.debug("Found ${supertypes.size} supertypes and ${subtypes.size} subtypes")

        return TypeHierarchyData(
            element = TypeElementData(
                name = getQualifiedName(goType) ?: getName(goType) ?: "unknown",
                qualifiedName = getQualifiedName(goType),
                file = goType.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, goType),
                kind = determineTypeKind(goType),
                language = "Go"
            ),
            supertypes = supertypes,
            subtypes = subtypes
        )
    }

    private fun getSupertypes(
        project: Project,
        goType: PsiElement,
        specType: PsiElement?,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val typeName = getQualifiedName(goType) ?: getName(goType) ?: return emptyList()
        if (typeName in visited) return emptyList()
        visited.add(typeName)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            when {
                specType != null && isGoStructType(specType) -> {
                    // For structs, look for embedded types
                    supertypes.addAll(getEmbeddedTypes(project, specType, visited, depth))
                }
                specType != null && isGoInterfaceType(specType) -> {
                    // For interfaces, look for embedded interfaces
                    supertypes.addAll(getEmbeddedInterfaces(project, specType, visited, depth))
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error getting supertypes: ${e.message}")
        }

        return supertypes
    }

    private fun getEmbeddedTypes(
        project: Project,
        structType: PsiElement,
        visited: MutableSet<String>,
        depth: Int
    ): List<TypeElementData> {
        val embeddedTypes = mutableListOf<TypeElementData>()

        try {
            // Get field declarations and look for anonymous/embedded fields
            val getFieldDeclarationListMethod = structType.javaClass.getMethod("getFieldDeclarationList")
            val fieldDeclarations = getFieldDeclarationListMethod.invoke(structType) as? List<*> ?: return emptyList()

            fieldDeclarations.filterIsInstance<PsiElement>().forEach { fieldDecl ->
                try {
                    // Check if this is an anonymous field (embedded type)
                    val getAnonymousFieldDefinitionMethod = fieldDecl.javaClass.getMethod("getAnonymousFieldDefinition")
                    val anonymousField = getAnonymousFieldDefinitionMethod.invoke(fieldDecl) as? PsiElement
                    if (anonymousField != null) {
                        val embeddedTypeName = getName(anonymousField)
                        if (embeddedTypeName != null && embeddedTypeName !in visited) {
                            // Try to resolve to the actual type
                            val resolvedType = resolveType(anonymousField)
                            if (resolvedType != null && isGoTypeSpec(resolvedType)) {
                                val superSupertypes = getSupertypes(project, resolvedType, getSpecType(resolvedType), visited, depth + 1)
                                embeddedTypes.add(TypeElementData(
                                    name = getQualifiedName(resolvedType) ?: embeddedTypeName,
                                    qualifiedName = getQualifiedName(resolvedType),
                                    file = resolvedType.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                                    line = getLineNumber(project, resolvedType),
                                    kind = determineTypeKind(resolvedType),
                                    language = "Go",
                                    supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                                ))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Field might not have anonymous field definition
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error getting embedded types: ${e.message}")
        }

        return embeddedTypes
    }

    private fun getEmbeddedInterfaces(
        project: Project,
        interfaceType: PsiElement,
        visited: MutableSet<String>,
        depth: Int
    ): List<TypeElementData> {
        val embeddedInterfaces = mutableListOf<TypeElementData>()

        try {
            // Get embedded interfaces from the interface definition
            val getMethodSpecListMethod = interfaceType.javaClass.getMethod("getMethodSpecList")
            val methodSpecs = getMethodSpecListMethod.invoke(interfaceType) as? List<*> ?: emptyList<Any>()

            // Also try to get embedded type references
            try {
                val getTypeReferenceExpressionListMethod = interfaceType.javaClass.getMethod("getTypeReferenceExpressionList")
                val typeRefs = getTypeReferenceExpressionListMethod.invoke(interfaceType) as? List<*>

                typeRefs?.filterIsInstance<PsiElement>()?.forEach { typeRef ->
                    val embeddedName = getName(typeRef) ?: typeRef.text
                    if (embeddedName != null && embeddedName !in visited) {
                        val resolvedType = resolveType(typeRef)
                        if (resolvedType != null && isGoTypeSpec(resolvedType)) {
                            val superSupertypes = getSupertypes(project, resolvedType, getSpecType(resolvedType), visited, depth + 1)
                            embeddedInterfaces.add(TypeElementData(
                                name = getQualifiedName(resolvedType) ?: embeddedName,
                                qualifiedName = getQualifiedName(resolvedType),
                                file = resolvedType.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                                line = getLineNumber(project, resolvedType),
                                kind = "INTERFACE",
                                language = "Go",
                                supertypes = superSupertypes.takeIf { it.isNotEmpty() }
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                // Method might not exist in all versions
            }
        } catch (e: Exception) {
            LOG.debug("Error getting embedded interfaces: ${e.message}")
        }

        return embeddedInterfaces
    }

    private fun resolveType(element: PsiElement): PsiElement? {
        return try {
            val referenceMethod = element.javaClass.getMethod("getReference")
            val reference = referenceMethod.invoke(element) as? com.intellij.psi.PsiReference
            reference?.resolve()
        } catch (e: Exception) {
            null
        }
    }

    private fun getSubtypes(project: Project, goType: PsiElement, specType: PsiElement?): List<TypeElementData> {
        // Use DefinitionsScopedSearch for finding implementing types
        try {
            val scope = GlobalSearchScope.projectScope(project)
            val results = mutableListOf<TypeElementData>()

            DefinitionsScopedSearch.search(goType, scope).forEach(Processor { definition ->
                if (definition != goType && isGoTypeSpec(definition)) {
                    results.add(TypeElementData(
                        name = getQualifiedName(definition) ?: getName(definition) ?: "unknown",
                        qualifiedName = getQualifiedName(definition),
                        file = definition.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, definition),
                        kind = determineTypeKind(definition),
                        language = "Go"
                    ))
                }
                results.size < 100
            })

            LOG.debug("Found ${results.size} subtypes via DefinitionsScopedSearch")
            return results
        } catch (e: Exception) {
            LOG.debug("Error getting subtypes: ${e.message}")
            return emptyList()
        }
    }
}

/**
 * Go implementation of [ImplementationsHandler].
 *
 * Finds implementations of:
 * - Interfaces (types that satisfy the interface)
 * - Interface methods (methods that implement the interface method)
 */
class GoImplementationsHandler : BaseGoHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "go"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isGoLanguage(element)
    }

    override fun isAvailable(): Boolean = GoPluginDetector.isGoPluginAvailable && goTypeSpecClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        LOG.debug("Finding implementations for element at ${element.containingFile?.name}")

        // Check if it's a method/function
        val goFunction = findContainingGoFunction(element)
        if (goFunction != null) {
            LOG.debug("Finding method implementations for ${getName(goFunction)}")
            return findMethodImplementations(project, goFunction)
        }

        // Check if it's a type (interface)
        val goType = findContainingGoType(element)
        if (goType != null) {
            val specType = getSpecType(goType)
            if (specType != null && isGoInterfaceType(specType)) {
                LOG.debug("Finding interface implementations for ${getName(goType)}")
                return findInterfaceImplementations(project, goType)
            }
        }

        return null
    }

    private fun findMethodImplementations(project: Project, goFunction: PsiElement): List<ImplementationData> {
        // Use DefinitionsScopedSearch (Platform API)
        try {
            val scope = GlobalSearchScope.projectScope(project)
            val results = mutableListOf<ImplementationData>()

            DefinitionsScopedSearch.search(goFunction, scope).forEach(Processor { definition ->
                if (definition != goFunction) {
                    val file = definition.containingFile?.virtualFile
                    if (file != null) {
                        val kind = when {
                            isGoMethod(definition) -> "METHOD"
                            isGoFunction(definition) -> "FUNCTION"
                            else -> "SYMBOL"
                        }
                        results.add(ImplementationData(
                            name = getName(definition) ?: "unknown",
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, definition) ?: 0,
                            kind = kind,
                            language = "Go"
                        ))
                    }
                }
                results.size < 100
            })

            LOG.debug("Found ${results.size} method implementations")
            return results
        } catch (e: Exception) {
            LOG.debug("Error finding method implementations: ${e.message}")
            return emptyList()
        }
    }

    private fun findInterfaceImplementations(project: Project, goType: PsiElement): List<ImplementationData> {
        // Use DefinitionsScopedSearch (Platform API) to find implementing types
        try {
            val scope = GlobalSearchScope.projectScope(project)
            val results = mutableListOf<ImplementationData>()

            DefinitionsScopedSearch.search(goType, scope).forEach(Processor { definition ->
                if (definition != goType && isGoTypeSpec(definition)) {
                    val file = definition.containingFile?.virtualFile
                    if (file != null) {
                        results.add(ImplementationData(
                            name = getQualifiedName(definition) ?: getName(definition) ?: "unknown",
                            file = getRelativePath(project, file),
                            line = getLineNumber(project, definition) ?: 0,
                            kind = determineTypeKind(definition),
                            language = "Go"
                        ))
                    }
                }
                results.size < 100
            })

            LOG.debug("Found ${results.size} interface implementations")
            return results
        } catch (e: Exception) {
            LOG.debug("Error finding interface implementations: ${e.message}")
            return emptyList()
        }
    }
}

/**
 * Go implementation of [CallHierarchyHandler].
 */
class GoCallHierarchyHandler : BaseGoHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
    }

    override val languageId = "go"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isGoLanguage(element)
    }

    override fun isAvailable(): Boolean = GoPluginDetector.isGoPluginAvailable && goFunctionDeclarationClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int
    ): CallHierarchyData? {
        val goFunction = findContainingGoFunction(element) ?: return null
        LOG.debug("Getting call hierarchy for ${getName(goFunction)}, direction=$direction, depth=$depth")

        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") {
            findCallersRecursive(project, goFunction, depth, visited)
        } else {
            findCalleesRecursive(project, goFunction, depth, visited)
        }

        LOG.debug("Found ${calls.size} $direction")

        return CallHierarchyData(
            element = createCallElement(project, goFunction),
            calls = calls
        )
    }

    private fun findCallersRecursive(
        project: Project,
        goFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(goFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        return try {
            // Use platform ReferencesSearch API with Processor pattern
            val scope = GlobalSearchScope.projectScope(project)
            val references = mutableListOf<com.intellij.psi.PsiReference>()

            ReferencesSearch.search(goFunction, scope).forEach(Processor { reference ->
                references.add(reference)
                references.size < MAX_RESULTS_PER_LEVEL * 2
            })

            LOG.debug("Found ${references.size} references for ${getName(goFunction)}")

            references.take(MAX_RESULTS_PER_LEVEL)
                .mapNotNull { reference ->
                    val refElement = reference.element
                    val containingFunction = findContainingGoFunction(refElement)
                    if (containingFunction != null && containingFunction != goFunction) {
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
        goFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()

        val functionKey = getFunctionKey(goFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        val callees = mutableListOf<CallElementData>()
        try {
            val goCallExpr = goCallExprClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val callExpressions = PsiTreeUtil.findChildrenOfType(goFunction, goCallExpr as Class<out PsiElement>)

            callExpressions.take(MAX_RESULTS_PER_LEVEL).forEach { callExpr ->
                val calledFunction = resolveCallExpression(callExpr)
                if (calledFunction != null && (isGoFunction(calledFunction) || isGoMethod(calledFunction))) {
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
            // Try to get the callee expression and resolve it
            val getExpressionMethod = callExpr.javaClass.getMethod("getExpression")
            val expression = getExpressionMethod.invoke(callExpr) as? PsiElement ?: return null

            val referenceMethod = expression.javaClass.getMethod("getReference")
            val reference = referenceMethod.invoke(expression) as? com.intellij.psi.PsiReference
            reference?.resolve()
        } catch (e: Exception) {
            null
        }
    }

    private fun getFunctionKey(goFunction: PsiElement): String {
        val name = getName(goFunction) ?: ""
        val file = goFunction.containingFile?.virtualFile?.path ?: ""
        val line = getLineNumber(goFunction.project, goFunction) ?: 0
        return "$file:$line:$name"
    }

    private fun createCallElement(project: Project, goFunction: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val file = goFunction.containingFile?.virtualFile
        val functionName = getName(goFunction) ?: "unknown"

        // For methods, try to get the receiver type
        val name = if (isGoMethod(goFunction)) {
            try {
                val getReceiverMethod = goFunction.javaClass.getMethod("getReceiver")
                val receiver = getReceiverMethod.invoke(goFunction) as? PsiElement
                val receiverTypeName = receiver?.let { getReceiverTypeName(it) }
                if (receiverTypeName != null) "$receiverTypeName.$functionName" else functionName
            } catch (e: Exception) {
                functionName
            }
        } else {
            functionName
        }

        return CallElementData(
            name = name,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, goFunction) ?: 0,
            language = "Go",
            children = children?.takeIf { it.isNotEmpty() }
        )
    }

    private fun getReceiverTypeName(receiver: PsiElement): String? {
        return try {
            val getTypeMethod = receiver.javaClass.getMethod("getType")
            val typeElement = getTypeMethod.invoke(receiver) as? PsiElement
            typeElement?.text?.trim('*', ' ')
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Go implementation of [SymbolSearchHandler].
 *
 * Uses the optimized [OptimizedSymbolSearch] infrastructure which leverages IntelliJ's
 * built-in "Go to Symbol" APIs with caching, word index, and prefix matching.
 */
class GoSymbolSearchHandler : BaseGoHandler<List<SymbolData>>(), SymbolSearchHandler {

    override val languageId = "go"

    override fun canHandle(element: PsiElement): Boolean = isAvailable()

    override fun isAvailable(): Boolean = GoPluginDetector.isGoPluginAvailable && goFileClass != null

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

        // Use the optimized platform-based search with language filter for Go
        return OptimizedSymbolSearch.search(
            project = project,
            pattern = pattern,
            scope = scope,
            limit = limit,
            languageFilter = setOf("go", "Go")
        )
    }
}

/**
 * Go implementation of [SuperMethodsHandler].
 *
 * In Go, methods don't "override" in the traditional sense, but:
 * - Methods from embedded types are "promoted" to the embedding type
 * - Methods can satisfy interface requirements
 *
 * This handler finds:
 * - Methods from embedded types with the same name
 * - Interface methods that this method implements
 */
class GoSuperMethodsHandler : BaseGoHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "go"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isGoLanguage(element)
    }

    override fun isAvailable(): Boolean = GoPluginDetector.isGoPluginAvailable && goMethodDeclarationClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val goFunction = findContainingGoFunction(element) ?: return null

        // Only methods (with receivers) can have "super methods" in Go
        if (!isGoMethod(goFunction)) return null

        LOG.debug("Finding super methods for ${getName(goFunction)}")

        val file = goFunction.containingFile?.virtualFile
        val receiverTypeName = getReceiverTypeName(goFunction)

        val methodData = MethodData(
            name = getName(goFunction) ?: "unknown",
            signature = buildMethodSignature(goFunction),
            containingClass = receiverTypeName ?: "unknown",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, goFunction) ?: 0,
            language = "Go"
        )

        val hierarchy = buildHierarchy(project, goFunction)
        LOG.debug("Found ${hierarchy.size} super methods")

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        goMethod: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        try {
            val methodName = getName(goMethod) ?: return emptyList()
            val receiverType = resolveReceiverType(goMethod) ?: return emptyList()

            // Look for methods from embedded types
            val specType = getSpecType(receiverType)
            if (specType != null && isGoStructType(specType)) {
                hierarchy.addAll(findMethodsFromEmbeddedTypes(project, specType, methodName, visited, depth))
            }

            // Look for interface methods this method satisfies
            hierarchy.addAll(findSatisfiedInterfaceMethods(project, receiverType, methodName, visited, depth))

        } catch (e: Exception) {
            LOG.debug("Error building hierarchy: ${e.message}")
        }

        return hierarchy
    }

    private fun findMethodsFromEmbeddedTypes(
        project: Project,
        structType: PsiElement,
        methodName: String,
        visited: MutableSet<String>,
        depth: Int
    ): List<SuperMethodData> {
        val results = mutableListOf<SuperMethodData>()

        try {
            val getFieldDeclarationListMethod = structType.javaClass.getMethod("getFieldDeclarationList")
            val fieldDeclarations = getFieldDeclarationListMethod.invoke(structType) as? List<*> ?: return emptyList()

            fieldDeclarations.filterIsInstance<PsiElement>().forEach { fieldDecl ->
                try {
                    val getAnonymousFieldDefinitionMethod = fieldDecl.javaClass.getMethod("getAnonymousFieldDefinition")
                    val anonymousField = getAnonymousFieldDefinitionMethod.invoke(fieldDecl) as? PsiElement
                    if (anonymousField != null) {
                        val embeddedTypeName = getName(anonymousField)
                        val key = "$embeddedTypeName.$methodName"
                        if (embeddedTypeName != null && key !in visited) {
                            visited.add(key)

                            // Find method with same name in the embedded type
                            val embeddedType = resolveType(anonymousField)
                            if (embeddedType != null && isGoTypeSpec(embeddedType)) {
                                val embeddedMethod = findMethodInType(project, embeddedType, methodName)
                                if (embeddedMethod != null) {
                                    val file = embeddedMethod.containingFile?.virtualFile

                                    results.add(SuperMethodData(
                                        name = methodName,
                                        signature = buildMethodSignature(embeddedMethod),
                                        containingClass = getQualifiedName(embeddedType) ?: embeddedTypeName,
                                        containingClassKind = determineTypeKind(embeddedType),
                                        file = file?.let { getRelativePath(project, it) },
                                        line = getLineNumber(project, embeddedMethod),
                                        isInterface = false,
                                        depth = depth,
                                        language = "Go"
                                    ))

                                    // Recursively find in embedded types of the embedded type
                                    results.addAll(buildHierarchy(project, embeddedMethod, visited, depth + 1))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Field might not have anonymous field definition
                }
            }
        } catch (e: Exception) {
            LOG.debug("Error finding methods from embedded types: ${e.message}")
        }

        return results
    }

    private fun findSatisfiedInterfaceMethods(
        project: Project,
        receiverType: PsiElement,
        methodName: String,
        visited: MutableSet<String>,
        depth: Int
    ): List<SuperMethodData> {
        // This is complex in Go due to implicit interface satisfaction
        // For now, we'll use DefinitionsScopedSearch to find related declarations
        val results = mutableListOf<SuperMethodData>()

        // We could potentially search for interfaces that have a method with the same name
        // and check if the receiver type satisfies that interface, but this is computationally expensive

        return results
    }

    private fun resolveReceiverType(goMethod: PsiElement): PsiElement? {
        return try {
            val getReceiverMethod = goMethod.javaClass.getMethod("getReceiver")
            val receiver = getReceiverMethod.invoke(goMethod) as? PsiElement ?: return null

            val getTypeMethod = receiver.javaClass.getMethod("getType")
            val typeElement = getTypeMethod.invoke(receiver) as? PsiElement ?: return null

            // Resolve the type reference
            resolveType(typeElement)
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveType(element: PsiElement): PsiElement? {
        return try {
            val referenceMethod = element.javaClass.getMethod("getReference")
            val reference = referenceMethod.invoke(element) as? com.intellij.psi.PsiReference
            reference?.resolve()
        } catch (e: Exception) {
            // Try to find GoTypeSpec in ancestors
            findContainingGoType(element)
        }
    }

    private fun findMethodInType(project: Project, goType: PsiElement, methodName: String): PsiElement? {
        // Search for methods with the receiver type matching this type
        try {
            val typeName = getName(goType) ?: return null
            val goFile = goType.containingFile ?: return null

            // Get all methods in the file and find one with matching receiver type and name
            val getMethodsMethod = goFile.javaClass.getMethod("getMethods")
            val methods = getMethodsMethod.invoke(goFile) as? List<*> ?: return null

            return methods.filterIsInstance<PsiElement>().find { method ->
                getName(method) == methodName && getReceiverTypeName(method) == typeName
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun getReceiverTypeName(goMethod: PsiElement): String? {
        return try {
            val getReceiverMethod = goMethod.javaClass.getMethod("getReceiver")
            val receiver = getReceiverMethod.invoke(goMethod) as? PsiElement ?: return null

            val getTypeMethod = receiver.javaClass.getMethod("getType")
            val typeElement = getTypeMethod.invoke(receiver) as? PsiElement
            typeElement?.text?.trim('*', ' ')
        } catch (e: Exception) {
            null
        }
    }

    private fun buildMethodSignature(goFunction: PsiElement): String {
        return try {
            val getSignatureMethod = goFunction.javaClass.getMethod("getSignature")
            val signature = getSignatureMethod.invoke(goFunction) as? PsiElement

            if (signature != null) {
                val functionName = getName(goFunction) ?: "unknown"
                val signatureText = signature.text ?: ""
                "$functionName$signatureText"
            } else {
                getName(goFunction) ?: "unknown"
            }
        } catch (e: Exception) {
            getName(goFunction) ?: "unknown"
        }
    }
}

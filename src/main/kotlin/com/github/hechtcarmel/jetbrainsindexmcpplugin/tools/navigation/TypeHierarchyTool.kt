package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.PendingTypeExpansion
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.PendingTypeNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.TypeContinuation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.TypeWork
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyTraversalNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.withOriginalFileIdentity
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import java.util.ArrayDeque
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for retrieving type hierarchies across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, Scala
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class TypeHierarchyTool : AbstractMcpTool() {

    companion object {
        private val JS_TS_LANGUAGE_FILTER = setOf("JavaScript", "TypeScript")
        private val TYPE_SYMBOL_KINDS = setOf("CLASS", "INTERFACE")
        private const val DEFAULT_MAX_NODES = 100
        private const val MAX_NODES = 500
        private const val TERMINAL_LOOKAHEAD_PROBES = 1
    }

    override val name = "ide_type_hierarchy"

    override val description = """
        Get the complete inheritance hierarchy for a class or interface. Use when you need to understand class relationships, find parent classes, or discover all subclasses.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, Scala.

        Rust note: className parameter not supported for Rust; use file + line + column instead.

        Returns a legacy nested tree. Set maxNodes for bounded BFS pages with nodeId/parentId/depth and symbolId handles. Follow cursor; if absent with hasMore=true, inspect truncationReason and narrow the query.

        Target: symbolId, className, file+line+column, or language+symbol. maxNodes: 1–500; omitted means legacy limits, cursor pages default to 100. scope defaults to project_files. Cursor ignores other search parameters.

        Example: {"className": "com.example.UserService", "scope": "project_and_libraries"} or {"file": "src/MyClass.java", "line": 10, "column": 14}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .stringProperty("className", "Fully qualified class name for JVM/PHP-style languages or simple class/interface name for JavaScript/TypeScript (e.g., 'com.example.MyClass', 'App\\\\Models\\\\User', or 'MyComponent').")
        .file(required = false, description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). Use with line and column.")
        .intProperty("line", "1-based line number where the class is defined. Required if using file parameter.")
        .intProperty("column", "1-based column number. Required if using file parameter.")
        .intProperty("maxNodes", "Opt into BFS paging with 1–500 nodes. Omit for a legacy tree; cursor default: 100.")
        .stringProperty("cursor", "Opaque session/project-bound continuation from the previous hierarchy page. Other search parameters are ignored.")
        .scopeProperty("Search scope. Default: project_files.")
        .booleanProperty(ParamNames.INCLUDE_GENERATED, "Include supertypes/subtypes defined in generated sources (KSP/Dagger/annotation-processor output). Default: true — keep generated types in the hierarchy.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val startedAt = System.currentTimeMillis()
        val explicitMaxNodes = arguments["maxNodes"]?.jsonPrimitive?.intOrNull
        val maxNodes = explicitMaxNodes ?: DEFAULT_MAX_NODES
        if (maxNodes !in 1..MAX_NODES) {
            return createErrorResult("maxNodes must be between 1 and $MAX_NODES")
        }

        val cursor = optionalStringArg(arguments, ParamNames.CURSOR)
        val continuationRegistry = HierarchyContinuationRegistry.getInstance()
        if (cursor != null) {
            requireSmartMode(project)
            val lease = continuationRegistry.resolveLease(project, cursor).getOrElse {
                return createErrorResult(it.message ?: "Hierarchy cursor expired")
            }
            val continuation = lease.continuation as? TypeContinuation
                ?: return createErrorResult("Cursor belongs to a different hierarchy tool. Start ide_type_hierarchy again without cursor.")
            return suspendingReadAction {
                buildTypePage(project, continuation, maxNodes, startedAt, lease.generation, cursor)
            }
        }

        val generation = continuationRegistry.currentGeneration()

        requireSmartMode(project)

        val className = arguments["className"]?.jsonPrimitive?.content
        val file = arguments["file"]?.jsonPrimitive?.content
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        val excludeGenerated = resolveExcludeGenerated(arguments, default = true)
        return suspendingReadAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val elementResult = resolveTargetElement(project, arguments, scope)
            val element = elementResult.getOrElse { error ->
                if (optionalStringArg(arguments, ParamNames.SYMBOL_ID) != null) {
                    return@suspendingReadAction createErrorResult(error.message ?: "SYMBOL_ID_EXPIRED")
                }
                val errorMsg = when {
                    className != null -> "Class '$className' not found in project '${project.name}'. Verify the fully qualified name is correct and the class is part of this project."
                    file != null -> "No class found at the specified file/line/column position."
                    else -> "Provide 'symbolId', 'className' (e.g., 'com.example.MyClass'), or 'file' + 'line' + 'column'."
                }
                return@suspendingReadAction createErrorResult(errorMsg)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No type hierarchy handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForTypeHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            if (explicitMaxNodes == null) {
                val legacy = handler.getTypeHierarchy(element, project, scope, excludeGenerated)
                    ?: return@suspendingReadAction createErrorResult("No class/type found at the specified position.")
                // Reserve one registry slot for the root. Handles are emitted in traversal order;
                // exceeding the registry capacity would evict an earlier handle from this same
                // response before the client can resolve it.
                var remainingHandles = (SymbolIdRegistry.getInstance().availableCapacity() - 1).coerceAtLeast(0)
                fun convertLegacy(
                    data: TypeElementData,
                    fallback: PsiElement? = null,
                    preferredId: String? = null
                ): TypeElement {
                    val node = convertToTypeElement(project, data, fallback, preferredId,
                        bindHandle = remainingHandles > 0)
                    if (node.symbolId != null) remainingHandles--
                    return node.copy(supertypes = data.supertypes?.map { convertLegacy(it) })
                }
                // The root and both directions share one response budget. Keep every legacy
                // node, but never publish more handles than the registry can retain at once.
                val root = convertLegacy(legacy.element, element, optionalStringArg(arguments, ParamNames.SYMBOL_ID))
                val supertypes = legacy.supertypes.map { convertLegacy(it) }
                val subtypes = legacy.subtypes.map { convertLegacy(it) }
                fun count(nodes: List<TypeElement>): Int = nodes.sumOf { 1 + count(it.supertypes.orEmpty()) }
                return@suspendingReadAction createJsonResult(TypeHierarchyResult(
                    element = root,
                    supertypes = supertypes,
                    subtypes = subtypes,
                    returnedNodes = count(supertypes) + count(subtypes),
                    elapsedMs = System.currentTimeMillis() - startedAt
                ))
            }

            // Ask the language adapter for exactly one edge. The tool owns the bounded BFS and
            // never lets a handler recursively materialize the complete graph.
            val hierarchyData = handler.getTypeHierarchy(
                element,
                project,
                scope,
                excludeGenerated,
                directOnly = true,
                direction = TypeHierarchyDirection.SUPERTYPE,
                page = HierarchyPageRequest(offset = 0, limit = maxNodes + 1)
            )
            if (hierarchyData == null) {
                return@suspendingReadAction createErrorResult("No class/type found at the specified position.")
            }

            val pointerManager = SmartPointerManager.getInstance(project)
            val modificationCount = PsiModificationTracker.getInstance(project).modificationCount
            val root = convertToTypeElement(
                project,
                hierarchyData.element,
                element,
                optionalStringArg(arguments, ParamNames.SYMBOL_ID)
            ).copy(supertypes = null, nodeId = "n0", depth = 0)
            // Position lookup may start on a reference leaf. The handler has already resolved
            // that leaf to the semantic class declaration; bind the continuation to that exact
            // declaration so deleting/moving the referencing file does not invalidate the root.
            val rootTarget = PsiUtils.resolveNavigationTarget(
                hierarchyData.element.pointerTarget ?: element
            )
            val visited = linkedSetOf<String>()
            val rootPointer = pointerManager.createSmartPsiElementPointer(rootTarget).withOriginalFileIdentity()
            val visitedPointers = mutableListOf<SmartPsiElementPointer<PsiElement>>(rootPointer)
            val visitedPointerKeys = mutableSetOf(hierarchyDeclarationKey(rootTarget))
            val frontier = buildList<TypeWork> {
                hierarchyData.supertypes
                    .sortedWith(typeDataComparator)
                    .mapNotNullTo(this) { data ->
                        pendingTypeNode(
                            project, pointerManager, data, TypeHierarchyDirection.SUPERTYPE, visited, visitedPointers, visitedPointerKeys
                        )
                    }
                hierarchyData.nextOffset?.let { offset ->
                    add(PendingTypeExpansion(rootPointer, TypeHierarchyDirection.SUPERTYPE, offset))
                }
                // Subtypes are loaded lazily. This keeps initial work bounded even when both
                // sides of the hierarchy are very wide.
                add(PendingTypeExpansion(rootPointer, TypeHierarchyDirection.SUBTYPE, 0))
            }
            val continuation = TypeContinuation(
                rootPointer = rootPointer,
                root = root,
                frontier = frontier,
                visited = visited,
                visitedPointers = visitedPointers,
                scope = scope,
                excludeGenerated = excludeGenerated,
                rootModificationCount = modificationCount
            )
            buildTypePage(project, continuation, maxNodes, startedAt, generation)
        }
    }

    private fun buildTypePage(
        project: Project,
        continuation: TypeContinuation,
        maxNodes: Int,
        startedAt: Long,
        generation: Long,
        parentCursor: String? = null
    ): CallToolResult {
        val registry = HierarchyContinuationRegistry.getInstance()
        if (!registry.isCurrentGeneration(generation)) {
            return createErrorResult("The MCP server session changed. Start the hierarchy query again without cursor.")
        }
        val rootElement = continuation.rootPointer.element
            ?: return createErrorResult("Hierarchy target was deleted or became invalid. Start the query again without cursor.")
        val pointerManager = SmartPointerManager.getInstance(project)
        val queue = ArrayDeque(continuation.frontier)
        val visited = continuation.visited.toPersistentSet().builder()
        val visitedPointers = continuation.visitedPointers.toPersistentList().builder()
        val visitedPointerKeys = continuation.visitedPointers.mapNotNull { it.element }.mapTo(mutableSetOf(), ::hierarchyDeclarationKey)
        val returnedSupertypes = mutableListOf<TypeElement>()
        val returnedSubtypes = mutableListOf<TypeElement>()
        val traversal = mutableListOf<TypeHierarchyTraversalNode>()
        var discoveredThisPage = 0
        var expandedThisPage = 0
        var terminalLookaheadProbes = 0
        val modificationCount = PsiModificationTracker.getInstance(project).modificationCount

        val rootSnapshot = if (continuation.rootModificationCount == modificationCount) continuation.root else {
            refreshTypeSnapshot(
                project,
                rootElement,
                continuation.root,
                TypeHierarchyDirection.SUPERTYPE,
                continuation.scope,
                continuation.excludeGenerated
            )
        }
        // Cursor retention is independent of symbol LRU/TTL. Materialize handles on every page,
        // including retries and unchanged PSI, rather than returning a stale cached token.
        val root = rootSnapshot.copy(symbolId = synchronized(continuation.rootHandle) {
            bindSymbolId(project, rootElement, continuation.rootHandle.symbolId).also {
                continuation.rootHandle.symbolId = it
            }
        })

        fun returnedCount(): Int = returnedSupertypes.size + returnedSubtypes.size

        fun hasKnownPendingNode(): Boolean = queue.any { work ->
            work is PendingTypeNode && (work.pointer == null || work.pointer.element != null)
        }

        // Resolve terminal edge probes eagerly when the page budget permits, but never let a wide
        // or stale expansion-only frontier turn one MCP call into unbounded work. If the hard cap
        // is reached, the remaining probes stay in the cursor; a continuation page may therefore
        // return zero nodes while it retires terminal branches, without changing BFS order.
        while (queue.isNotEmpty() && (returnedCount() < maxNodes || !hasKnownPendingNode())) {
            ProgressManager.checkCanceled()
            when (val work = queue.removeFirst()) {
                is PendingTypeNode -> {
                    val target = work.pointer?.element
                    if (work.pointer != null && target == null) continue
                    val metadata = if (target == null || work.modificationCount == modificationCount) {
                        work.snapshot.copy(supertypes = null)
                    } else {
                        refreshTypeSnapshot(
                            project,
                            target,
                            work.snapshot,
                            work.direction,
                            continuation.scope,
                            continuation.excludeGenerated
                        )
                    }
                    val snapshot = metadata.copy(
                        nodeId = work.snapshot.nodeId,
                        parentId = work.snapshot.parentId,
                        depth = work.snapshot.depth,
                        symbolId = target?.let {
                            synchronized(work.handle) {
                                bindSymbolId(project, it, work.handle.symbolId).also { symbolId ->
                                    work.handle.symbolId = symbolId
                                }
                            }
                        }
                    )
                    when (work.direction) {
                        TypeHierarchyDirection.SUPERTYPE -> returnedSupertypes.add(snapshot)
                        TypeHierarchyDirection.SUBTYPE -> returnedSubtypes.add(snapshot)
                    }
                    traversal += TypeHierarchyTraversalNode(
                        direction = work.direction.name.lowercase(),
                        element = snapshot
                    )
                    if (target != null) {
                        queue.addLast(
                            PendingTypeExpansion(
                                pointerManager.createSmartPsiElementPointer(PsiUtils.resolveNavigationTarget(target)).withOriginalFileIdentity(),
                                work.direction,
                                0,
                                nodeId = requireNotNull(snapshot.nodeId),
                                depth = requireNotNull(snapshot.depth)
                            )
                        )
                    }
                }

                is PendingTypeExpansion -> {
                    val lookingAhead = !hasKnownPendingNode()
                    val pageWorkLimitReached =
                        discoveredThisPage >= maxNodes || expandedThisPage >= maxNodes
                    if (pageWorkLimitReached) {
                        if (!lookingAhead || terminalLookaheadProbes >= TERMINAL_LOOKAHEAD_PROBES) {
                            queue.addFirst(work)
                            break
                        }
                        terminalLookaheadProbes++
                    }
                    expandedThisPage++
                    val target = work.pointer.element ?: continue
                    val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(target) ?: continue
                    val scanLimit = (
                        work.offset.toLong() + (maxNodes - discoveredThisPage).coerceAtLeast(0).toLong() + 1L
                    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val direct = handler.getTypeHierarchy(
                        target,
                        project,
                        continuation.scope,
                        continuation.excludeGenerated,
                        directOnly = true,
                        direction = work.direction,
                        // Re-read an ever larger prefix and filter it by smart-pointer identity.
                        // A raw offset is not stable when a declaration is renamed or moved
                        // between pages: an item can cross the offset and be lost forever.
                        page = HierarchyPageRequest(offset = 0, limit = scanLimit)
                    ) ?: continue
                    val children = when (work.direction) {
                        TypeHierarchyDirection.SUPERTYPE -> direct.supertypes
                        TypeHierarchyDirection.SUBTYPE -> direct.subtypes
                    }
                    // Normalize only the bounded prefix returned by the handler. A global lexical
                    // sibling sort would require exhausting a potentially unbounded index query;
                    // cross-page order instead follows stable bounded discovery windows.
                    val pendingChildren = children.sortedWith(typeDataComparator).mapNotNull { child ->
                        pendingTypeNode(
                            project, pointerManager, child, work.direction, visited, visitedPointers, visitedPointerKeys,
                            parentId = work.nodeId, depth = work.depth + 1
                        )
                    }
                    discoveredThisPage += maxOf(
                        (children.size - work.offset).coerceAtLeast(0),
                        pendingChildren.size
                    )
                    val additions = buildList<TypeWork> {
                        addAll(pendingChildren)
                        direct.nextOffset?.let { nextOffset ->
                            // A non-advancing continuation would create an endless cursor chain.
                            if (nextOffset > work.offset) add(work.copy(offset = nextOffset))
                        }
                    }
                    for (addition in additions.asReversed()) queue.addFirst(addition)
                }
            }
        }

        val nextState = continuation.copy(
            rootPointer = pointerManager.createSmartPsiElementPointer(PsiUtils.resolveNavigationTarget(rootElement)).withOriginalFileIdentity(),
            root = root,
            frontier = queue.toList(),
            visited = visited.build(),
            visitedPointers = visitedPointers.build(),
            rootModificationCount = modificationCount
        )
        val hasMore = nextState.frontier.any { work ->
            when (work) {
                is PendingTypeNode -> work.pointer == null || work.pointer.element != null
                is PendingTypeExpansion -> work.pointer.element != null
            }
        }
        var truncationReason: String? = null
        val nextCursor = if (hasMore) {
            registry.register(project, generation, nextState, parentCursor, "$maxNodes:$modificationCount").getOrElse {
                if (it !is HierarchyContinuationRegistry.ContinuationLimitException) {
                    return createErrorResult(it.message ?: "The MCP server session changed")
                }
                truncationReason = it.message
                null
            }
        } else null
        val returnedNodes = returnedCount()

        return createJsonResult(
            TypeHierarchyResult(
                element = root,
                supertypes = returnedSupertypes,
                subtypes = returnedSubtypes,
                traversal = traversal,
                returnedNodes = returnedNodes,
                truncated = hasMore,
                elapsedMs = System.currentTimeMillis() - startedAt,
                hasMore = hasMore,
                cursor = nextCursor,
                truncationReason = truncationReason
            )
        )
    }

    private fun pendingTypeNode(
        project: Project,
        pointerManager: SmartPointerManager,
        data: TypeElementData,
        direction: TypeHierarchyDirection,
        visited: MutableSet<String>,
        visitedPointers: MutableList<SmartPsiElementPointer<PsiElement>>,
        visitedPointerKeys: MutableSet<String>,
        parentId: String = "n0",
        depth: Int = 1
    ): PendingTypeNode? {
        // Frontier snapshots stay unbound until disclosed. Re-reading a growing prefix must not
        // allocate handles for prior siblings or for look-ahead nodes absent from this page.
        val unboundSnapshot = convertToTypeElement(project, data, bindHandle = false)
        val target = data.pointerTarget?.let(PsiUtils::resolveNavigationTarget)
        val pointer = if (target == null) {
            val key = "${data.language}|${data.file}|${data.line}|${data.qualifiedName}|${data.kind}|${data.name}"
            if (!visited.add(key)) return null
            null
        } else {
            if (!visitedPointerKeys.add(hierarchyDeclarationKey(target))) return null
            // Physical declaration identity comes from the live pointer, never a display key:
            // different local classes can share every name/signature field in one source file.
            pointerManager.createSmartPsiElementPointer(target).withOriginalFileIdentity().also { pointer ->
                visitedPointers += pointer
            }
        }
        return PendingTypeNode(
            pointer = pointer,
            snapshot = unboundSnapshot.copy(
                nodeId = "n${visited.size + visitedPointers.size - 1}", parentId = parentId, depth = depth
            ),
            direction = direction,
            modificationCount = PsiModificationTracker.getInstance(project).modificationCount
        )
    }

    private fun refreshTypeSnapshot(
        project: Project,
        target: PsiElement,
        previous: TypeElement,
        direction: TypeHierarchyDirection,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): TypeElement {
        val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(target) ?: return previous
        val fresh = handler.getTypeHierarchy(
            target,
            project,
            scope,
            excludeGenerated,
            directOnly = true,
            direction = direction,
            page = HierarchyPageRequest(offset = 0, limit = 0)
        )?.element ?: return previous
        return convertToTypeElement(project, fresh, bindHandle = false).copy(
            symbolId = previous.symbolId, nodeId = previous.nodeId, parentId = previous.parentId, depth = previous.depth
        )
    }

    private val typeDataComparator = compareBy<TypeElementData>(
        { it.language },
        { it.file.orEmpty() },
        { it.line ?: Int.MAX_VALUE },
        { it.qualifiedName.orEmpty() },
        { it.name }
    )

    private fun resolveTargetElement(project: Project, arguments: JsonObject, scope: BuiltInSearchScope): Result<PsiElement> {
        val symbolId = optionalStringArg(arguments, ParamNames.SYMBOL_ID)
        val hasQualifiedTarget = optionalStringArg(arguments, ParamNames.LANGUAGE) != null ||
            optionalStringArg(arguments, ParamNames.SYMBOL) != null
        if (symbolId != null || hasQualifiedTarget) {
            if (optionalStringArg(arguments, ParamNames.CLASS_NAME) != null) {
                return Result.failure(IllegalArgumentException(ErrorMessages.SYMBOL_ID_AND_OTHER_TARGET_EXCLUSIVE))
            }
            return resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true, allowSymbolId = true)
        }

        // Try className first. Direct class lookup covers JVM/PHP-style FQNs; symbol search
        // fills the same entry point for WebStorm JS/TS class and interface names.
        val className = arguments["className"]?.jsonPrimitive?.content
        if (className != null) {
            val target = findClassByName(project, className)
                ?: findJavaScriptOrTypeScriptClassByName(project, className, scope)
                ?: return Result.failure(IllegalArgumentException("Class not found"))
            return Result.success(target)
        }

        // Otherwise use file/line/column (works for all languages)
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return Result.failure(IllegalArgumentException("Missing position"))
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return Result.failure(IllegalArgumentException("Missing position"))
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return Result.failure(IllegalArgumentException("Missing position"))

        return findPsiElement(project, file, line, column)
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("No element at position"))
    }

    private fun findJavaScriptOrTypeScriptClassByName(
        project: Project,
        className: String,
        scope: BuiltInSearchScope
    ): PsiElement? {
        val simpleName = className.substringAfterLast('.').substringAfterLast('#')
        if (simpleName.isBlank()) return null
        val isQualifiedRequest = className.contains('.') || className.contains('#')

        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val symbols = OptimizedSymbolSearch.search(
            project = project,
            pattern = simpleName,
            scope = searchScope,
            limit = 50,
            languageFilter = JS_TS_LANGUAGE_FILTER
        )

        val match = symbols
            .filter { it.name == simpleName && it.kind in TYPE_SYMBOL_KINDS }
            .firstOrNull { symbol ->
                val qualifiedName = symbol.qualifiedName
                (!isQualifiedRequest && symbol.qualifiedName == null) ||
                    qualifiedName == className ||
                    qualifiedName?.endsWith(".$simpleName") == true
            }
            ?: symbols.firstOrNull {
                !isQualifiedRequest && it.name == simpleName && it.kind in TYPE_SYMBOL_KINDS
            }

        return match?.let { findPsiElement(project, it.file, it.line, it.column) }
    }

    /**
     * Converts handler TypeElementData to tool TypeElement.
     */
    private fun convertToTypeElement(
        project: Project,
        data: TypeElementData,
        fallbackTarget: PsiElement? = null,
        preferredSymbolId: String? = null,
        bindHandle: Boolean = true
    ): TypeElement {
        return TypeElement(
            name = data.name,
            file = data.file,
            kind = data.kind,
            language = data.language,
            symbolId = if (bindHandle) {
                (data.pointerTarget ?: fallbackTarget)?.let {
                    // A handler may lift the requested method to its enclosing class. A query
                    // must not rebind that method's ID to the class it returns as its root.
                    val reusableId = preferredSymbolId?.takeIf { _ ->
                        fallbackTarget == null || sameHierarchyDeclaration(fallbackTarget, it)
                    }
                    bindSymbolId(project, it, reusableId)
                }
            } else null
        )
    }

    private fun bindSymbolId(project: Project, element: PsiElement, preferredId: String? = null): String =
        bindExactSymbolId(project, element, preferredId)
}

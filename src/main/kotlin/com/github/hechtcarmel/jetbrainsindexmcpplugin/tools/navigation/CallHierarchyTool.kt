package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript.isJsTsElementOrFile
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript.resolveJsTsCallHierarchySeed
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.CallWork
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.CallContinuation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.PendingCallExpansion
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.PendingCallNode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
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
 * Tool for analyzing method call relationships across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, Scala
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class CallHierarchyTool : AbstractMcpTool() {

    override val name = "ide_call_hierarchy"

    override val description = """
        Build a call hierarchy tree for a method/function. Use to trace execution flow—find what calls this method (callers) or what this method calls (callees).

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, Scala.

        Rust note: "callers" direction works well; "callees" direction may have limited results due to Rust plugin PSI resolution constraints.

        Returns a legacy nested tree. Set maxNodes for bounded BFS pages with nodeId/parentId/depth and symbolId handles. Follow cursor; if absent with hasMore=true, inspect truncationReason and narrow the query.

        Target (mutually exclusive):
        - symbolId: opaque handle returned by a previous semantic call
        - file + line + column: position-based lookup
        - language + symbol: fully qualified symbol reference (supported languages: ${supportedSymbolReferenceLanguagesDescription()})

        direction: callers or callees (required for fresh queries). depth: default 3, max 5. maxNodes: 1–500; omitted means legacy per-node limits, cursor pages default to 100. scope defaults to project_files.

        Example: {"file": "src/Service.java", "line": 42, "column": 10, "direction": "callers"}
        Example: {"language": "Java", "symbol": "com.example.Service#processRequest(String)", "direction": "callers", "scope": "project_and_libraries"}
        Example: {"language": "JavaScript", "symbol": "src/handlers#processRequest", "direction": "callers"}
        Example: {"language": "PHP", "symbol": "\\App\\Service\\UserService::find()", "direction": "callers"}
        """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .file(required = false, description = "Project-relative file path, or a dependency/library absolute path or jar:// URL previously returned by the plugin. Required for position-based lookup.")
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .enumProperty("direction", "Direction for a fresh query: 'callers' or 'callees'. Omit when cursor is provided.", listOf("callers", "callees"))
        .intProperty("depth", "How many levels deep to traverse the call hierarchy (default: 3, max: 5)")
        .intProperty("maxNodes", "Opt into BFS paging with 1–500 nodes. Omit for a legacy tree; cursor default: 100.")
        .stringProperty("cursor", "Opaque session/project-bound continuation from the previous hierarchy page. Other search parameters are ignored.")
        .scopeProperty("Search scope. Default: project_files.")
        .booleanProperty(ParamNames.INCLUDE_GENERATED, "Include callers/callees in generated sources (KSP/Dagger/annotation-processor output). Default: true.")
        .build()

    companion object {
        private const val DEFAULT_DEPTH = 3
        private const val MAX_DEPTH = 5
        private const val DEFAULT_MAX_NODES = 100
        private const val MAX_NODES = 500
        private const val TERMINAL_LOOKAHEAD_PROBES = 1
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val startedAt = System.currentTimeMillis()
        val cursor = optionalStringArg(arguments, ParamNames.CURSOR)
        val explicitMaxNodes = arguments["maxNodes"]?.jsonPrimitive?.intOrNull
        val maxNodes = explicitMaxNodes ?: DEFAULT_MAX_NODES
        if (maxNodes !in 1..MAX_NODES) {
            return createErrorResult("maxNodes must be between 1 and $MAX_NODES")
        }

        val continuationRegistry = HierarchyContinuationRegistry.getInstance()
        if (cursor != null) {
            requireSmartMode(project)
            val lease = continuationRegistry.resolveLease(project, cursor).getOrElse {
                return createErrorResult(it.message ?: "Hierarchy cursor expired")
            }
            val continuation = lease.continuation as? CallContinuation
                ?: return createErrorResult("Cursor belongs to a different hierarchy tool. Start ide_call_hierarchy again without cursor.")
            return suspendingReadAction {
                buildCallPage(project, continuation, maxNodes, startedAt, lease.generation, cursor)
            }
        }

        val generation = continuationRegistry.currentGeneration()

        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: direction for a fresh hierarchy query")
        val depth = (arguments["depth"]?.jsonPrimitive?.int ?: DEFAULT_DEPTH).coerceIn(1, MAX_DEPTH)
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        if (direction !in listOf("callers", "callees")) {
            return createErrorResult("direction must be 'callers' or 'callees'")
        }
        val excludeGenerated = resolveExcludeGenerated(arguments, default = true)

        requireSmartMode(project)

        return suspendingReadAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val element = resolveCallHierarchySeed(project, arguments).getOrElse {
                return@suspendingReadAction createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getCallHierarchyHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No call hierarchy handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForCallHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            if (explicitMaxNodes == null) {
                val legacy = handler.getCallHierarchy(element, project, direction, depth, scope, excludeGenerated)
                    ?: return@suspendingReadAction createErrorResult("No method/function found for the specified target")
                // Keep the root plus response children within the live registry capacity so a
                // legacy response never publishes handles it has already evicted.
                var remainingHandles = (SymbolIdRegistry.getInstance().availableCapacity() - 1).coerceAtLeast(0)
                fun convertLegacy(
                    data: CallElementData,
                    fallback: PsiElement? = null,
                    preferredId: String? = null
                ): CallElement {
                    val node = convertToCallElement(project, data, fallback, preferredId,
                        bindHandle = remainingHandles > 0)
                    if (node.symbolId != null) remainingHandles--
                    return node.copy(children = data.children?.map { convertLegacy(it) })
                }
                // Legacy trees can exceed the bounded handle registry. Retain the complete tree,
                // but publish only handles that fit in one response, with the root first.
                val root = convertLegacy(legacy.element, element, optionalStringArg(arguments, ParamNames.SYMBOL_ID))
                val calls = legacy.calls.map { convertLegacy(it) }
                fun count(nodes: List<CallElement>): Int = nodes.sumOf { 1 + count(it.children.orEmpty()) }
                return@suspendingReadAction createJsonResult(CallHierarchyResult(
                    element = root,
                    calls = calls,
                    returnedNodes = count(calls),
                    elapsedMs = System.currentTimeMillis() - startedAt
                ))
            }

            // Ask the language adapter for exactly one edge. The tool owns the bounded BFS and
            // never lets a handler recursively materialize the complete graph.
            val hierarchyData = handler.getCallHierarchy(
                element,
                project,
                direction,
                1,
                scope,
                excludeGenerated,
                page = HierarchyPageRequest(offset = 0, limit = maxNodes + 1)
            )
            if (hierarchyData == null) {
                val isSymbolMode = optionalStringArg(arguments, ParamNames.LANGUAGE) != null
                return@suspendingReadAction createErrorResult(
                    if (isSymbolMode) "No method/function found for the specified symbol"
                    else "No method/function found at position"
                )
            }

            val pointerManager = SmartPointerManager.getInstance(project)
            val modificationCount = PsiModificationTracker.getInstance(project).modificationCount
            val root = convertToCallElement(
                project,
                hierarchyData.element,
                element,
                optionalStringArg(arguments, ParamNames.SYMBOL_ID)
            ).copy(children = null, nodeId = "n0", depth = 0)
            // Position lookup may start on a call-site reference. The handler has already
            // resolved it to the callable declaration; continuation identity must follow that
            // declaration rather than the disposable caller leaf.
            val rootTarget = hierarchyData.element.pointerTarget ?: element
            val visited = linkedSetOf<String>()
            val rootPointer = pointerManager.createSmartPsiElementPointer(rootTarget).withOriginalFileIdentity()
            val visitedPointers = mutableListOf<SmartPsiElementPointer<PsiElement>>(rootPointer)
            val visitedPointerKeys = mutableSetOf(hierarchyDeclarationKey(rootTarget))
            val frontier = buildList<CallWork> {
                hierarchyData.calls
                    .sortedWith(callDataComparator)
                    .mapNotNullTo(this) { data ->
                        pendingCallNode(
                            project, pointerManager, data, depth = 1, visited = visited,
                            visitedPointers = visitedPointers, visitedPointerKeys = visitedPointerKeys
                        )
                    }
                hierarchyData.nextOffset?.let { offset ->
                    add(PendingCallExpansion(rootPointer, depth = 0, offset = offset))
                }
            }
            val continuation = CallContinuation(
                rootPointer = rootPointer,
                root = root,
                frontier = frontier,
                visited = visited,
                visitedPointers = visitedPointers,
                direction = direction,
                maxDepth = depth,
                scope = scope,
                excludeGenerated = excludeGenerated,
                rootModificationCount = modificationCount
            )
            buildCallPage(project, continuation, maxNodes, startedAt, generation)
        }
    }

    private fun buildCallPage(
        project: Project,
        continuation: CallContinuation,
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
        val returned = mutableListOf<CallElement>()
        var discoveredThisPage = 0
        var expandedThisPage = 0
        var terminalLookaheadProbes = 0
        val modificationCount = PsiModificationTracker.getInstance(project).modificationCount

        val rootSnapshot = if (continuation.rootModificationCount == modificationCount) continuation.root else {
            refreshCallSnapshot(
                project,
                rootElement,
                continuation.root,
                continuation.direction,
                continuation.scope,
                continuation.excludeGenerated
            )
        }
        // A cursor outlives individual symbol-cache entries. Rebind every disclosed handle even
        // without a PSI edit: unrelated requests may have evicted it, or its idle TTL may be over.
        val root = rootSnapshot.copy(symbolId = synchronized(continuation.rootHandle) {
            bindCallSymbolId(project, rootElement, continuation.rootHandle.symbolId).also {
                continuation.rootHandle.symbolId = it
            }
        })

        fun hasKnownPendingNode(): Boolean = queue.any { work ->
            work is PendingCallNode && (work.pointer == null || work.pointer.element != null)
        }

        // Resolve terminal edge probes eagerly when the page budget permits, but never let a wide
        // or stale expansion-only frontier turn one MCP call into unbounded work. If the hard cap
        // is reached, the remaining probes stay in the cursor; a continuation page may therefore
        // return zero nodes while it retires terminal branches, without changing BFS order.
        while (queue.isNotEmpty() && (returned.size < maxNodes || !hasKnownPendingNode())) {
            ProgressManager.checkCanceled()
            when (val work = queue.removeFirst()) {
                is PendingCallNode -> {
                    val target = work.pointer?.element
                    if (work.pointer != null && target == null) continue
                    val metadata = if (target == null || work.modificationCount == modificationCount) {
                        work.snapshot.copy(children = null)
                    } else {
                        refreshCallSnapshot(
                            project,
                            target,
                            work.snapshot,
                            continuation.direction,
                            continuation.scope,
                            continuation.excludeGenerated
                        )
                    }
                    val snapshot = metadata.copy(
                        nodeId = work.snapshot.nodeId,
                        parentId = work.snapshot.parentId,
                        depth = work.depth,
                        symbolId = target?.let {
                            synchronized(work.handle) {
                                bindCallSymbolId(project, it, work.handle.symbolId).also { symbolId ->
                                    work.handle.symbolId = symbolId
                                }
                            }
                        }
                    )
                    returned += snapshot
                    if (target != null && work.depth < continuation.maxDepth) {
                        queue.addLast(
                            PendingCallExpansion(
                                pointerManager.createSmartPsiElementPointer(target).withOriginalFileIdentity(),
                                depth = work.depth,
                                offset = 0,
                                nodeId = requireNotNull(snapshot.nodeId)
                            )
                        )
                    }
                }

                is PendingCallExpansion -> {
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
                    val handler = LanguageHandlerRegistry.getCallHierarchyHandler(target) ?: continue
                    val scanLimit = (
                        work.offset.toLong() + (maxNodes - discoveredThisPage).coerceAtLeast(0).toLong() + 1L
                    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val direct = handler.getCallHierarchy(
                        target,
                        project,
                        continuation.direction,
                        1,
                        continuation.scope,
                        continuation.excludeGenerated,
                        page = HierarchyPageRequest(offset = 0, limit = scanLimit)
                    ) ?: continue
                    val pendingChildren = direct.calls.sortedWith(callDataComparator).mapNotNull { child ->
                        pendingCallNode(
                            project, pointerManager, child, work.depth + 1, visited, visitedPointers, visitedPointerKeys,
                            parentId = work.nodeId
                        )
                    }
                    discoveredThisPage += maxOf(
                        (direct.calls.size - work.offset).coerceAtLeast(0),
                        pendingChildren.size
                    )
                    val additions = buildList<CallWork> {
                        addAll(pendingChildren)
                        direct.nextOffset?.let { nextOffset ->
                            if (nextOffset > work.offset) add(work.copy(offset = nextOffset))
                        }
                    }
                    for (addition in additions.asReversed()) queue.addFirst(addition)
                }
            }
        }

        val nextState = continuation.copy(
            rootPointer = pointerManager.createSmartPsiElementPointer(rootElement).withOriginalFileIdentity(),
            root = root,
            frontier = queue.toList(),
            visited = visited.build(),
            visitedPointers = visitedPointers.build(),
            rootModificationCount = modificationCount
        )
        val hasMore = nextState.frontier.any { work ->
            when (work) {
                is PendingCallNode -> work.pointer == null || work.pointer.element != null
                is PendingCallExpansion -> work.pointer.element != null
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

        return createJsonResult(
            CallHierarchyResult(
                element = root,
                calls = returned,
                returnedNodes = returned.size,
                truncated = hasMore,
                elapsedMs = System.currentTimeMillis() - startedAt,
                hasMore = hasMore,
                cursor = nextCursor,
                truncationReason = truncationReason
            )
        )
    }

    private fun refreshCallSnapshot(
        project: Project,
        target: PsiElement,
        previous: CallElement,
        direction: String,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): CallElement {
        val handler = LanguageHandlerRegistry.getCallHierarchyHandler(target) ?: return previous
        val fresh = handler.getCallHierarchy(
            target,
            project,
            direction,
            1,
            scope,
            excludeGenerated,
            page = HierarchyPageRequest(offset = 0, limit = 0)
        )?.element ?: return previous
        return convertToCallElement(project, fresh, bindHandle = false).copy(
            symbolId = previous.symbolId, nodeId = previous.nodeId, parentId = previous.parentId, depth = previous.depth
        )
    }

    private fun pendingCallNode(
        project: Project,
        pointerManager: SmartPointerManager,
        data: CallElementData,
        depth: Int,
        visited: MutableSet<String>,
        visitedPointers: MutableList<SmartPsiElementPointer<PsiElement>>,
        visitedPointerKeys: MutableSet<String>,
        parentId: String = "n0"
    ): PendingCallNode? {
        // Growing-prefix pagination re-reads earlier siblings. Undisclosed frontier nodes keep
        // metadata and exact pointers, and consume a symbol handle only when a page returns them.
        val unboundSnapshot = convertToCallElement(project, data, bindHandle = false)
        val target = data.pointerTarget
        val pointer = if (target == null) {
            val key = "${data.language}|${data.file}|${data.line}|${data.column}|${data.name}"
            if (!visited.add(key)) return null
            null
        } else {
            if (!visitedPointerKeys.add(hierarchyDeclarationKey(target))) return null
            // Only a live pointer proves identity for a physical declaration. Signature/name
            // keys can collide for local/anonymous declarations, and offsets can move on edits.
            pointerManager.createSmartPsiElementPointer(target).withOriginalFileIdentity().also { pointer ->
                visitedPointers += pointer
            }
        }
        return PendingCallNode(
            pointer = pointer,
            snapshot = unboundSnapshot.copy(
                nodeId = "n${visited.size + visitedPointers.size - 1}", parentId = parentId, depth = depth
            ),
            depth = depth,
            modificationCount = PsiModificationTracker.getInstance(project).modificationCount
        )
    }

    private val callDataComparator = compareBy<CallElementData>(
        { it.language },
        { it.file },
        { it.line },
        { it.column },
        { it.name }
    )

    private fun resolveCallHierarchySeed(project: Project, arguments: JsonObject): Result<com.intellij.psi.PsiElement> {
        val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true, allowSymbolId = true).getOrElse {
            return Result.failure(it)
        }
        val explicitLanguage = optionalStringArg(arguments, ParamNames.LANGUAGE)
        val shouldNormalizeJsTsSeed = explicitLanguage == "JavaScript" ||
            explicitLanguage == "TypeScript" ||
            isJsTsElementOrFile(element)
        if (!shouldNormalizeJsTsSeed) {
            return Result.success(element)
        }

        return Result.success(resolveJsTsCallHierarchySeed(element))
    }

    /**
     * Converts handler CallElementData to tool CallElement.
     */
    private fun convertToCallElement(
        project: Project,
        data: CallElementData,
        fallbackTarget: com.intellij.psi.PsiElement? = null,
        preferredSymbolId: String? = null,
        bindHandle: Boolean = true,
        includeNested: Boolean = false
    ): CallElement {
        return CallElement(
            name = data.name,
            file = data.file,
            line = data.line,
            column = data.column,
            language = data.language,
            symbolId = if (bindHandle) {
                (data.pointerTarget ?: fallbackTarget)?.let {
                    // A handler can lift a parameter to its method; retain the input handle's
                    // declaration and issue a separate root handle in that case.
                    val reusableId = preferredSymbolId?.takeIf { _ ->
                        fallbackTarget == null || fallbackTarget.javaClass == it.javaClass &&
                            sameHierarchyDeclaration(fallbackTarget, it)
                    }
                    bindCallSymbolId(project, it, reusableId)
                }
            } else null,
            children = if (includeNested) data.children?.map {
                convertToCallElement(project, it, bindHandle = bindHandle, includeNested = true)
            } else null
        )
    }

    /** Kotlin accessors share a navigation property, but remain distinct callable targets. */
    private fun bindCallSymbolId(project: Project, element: PsiElement, preferredId: String? = null): String =
        bindExactSymbolId(project, element, preferredId)
}

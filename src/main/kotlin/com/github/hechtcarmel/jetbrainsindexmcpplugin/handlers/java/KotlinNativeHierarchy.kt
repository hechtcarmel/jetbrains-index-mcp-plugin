package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiSourcePosition
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.ArrayDeque

internal object KotlinNativeHierarchy {
    private val ktElement by lazy { Class.forName("org.jetbrains.kotlin.psi.KtElement") }
    private val ktDeclaration by lazy { Class.forName("org.jetbrains.kotlin.psi.KtDeclaration") }
    private val ktClass by lazy { Class.forName("org.jetbrains.kotlin.psi.KtClassOrObject") }
    private val ktProperty by lazy { Class.forName("org.jetbrains.kotlin.psi.KtProperty") }
    private val hasModifier by lazy {
        Class.forName("org.jetbrains.kotlin.psi.KtModifierListOwner")
            .getMethod("hasModifier", Class.forName("org.jetbrains.kotlin.lexer.KtModifierKeywordToken"))
    }
    private val abstractModifiers by lazy {
        val tokens = Class.forName("org.jetbrains.kotlin.lexer.KtTokens")
        listOf("ABSTRACT_KEYWORD", "SEALED_KEYWORD").map { tokens.getField(it).get(null) }
    }
    private val callableDeclarations by lazy {
        listOf("KtNamedFunction", "KtProperty", "KtPropertyAccessor").map { Class.forName("org.jetbrains.kotlin.psi.$it") }
    }
    private val symbolProvider by lazy { Class.forName("org.jetbrains.kotlin.analysis.api.symbols.KaSymbolProvider") }
    private val classSymbol by lazy { Class.forName("org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol") }
    private val callableSymbol by lazy { Class.forName("org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol") }
    private val symbol by lazy { Class.forName("org.jetbrains.kotlin.analysis.api.symbols.KaSymbol") }
    private val type by lazy { Class.forName("org.jetbrains.kotlin.analysis.api.types.KaType") }
    private val typeInformation by lazy { Class.forName("org.jetbrains.kotlin.analysis.api.components.KaTypeInformationProvider") }
    private val relations by lazy { Class.forName("org.jetbrains.kotlin.analysis.api.components.KaSymbolRelationProvider") }
    private val findUsages by lazy { Class.forName("org.jetbrains.kotlin.idea.findUsages.KotlinFindUsagesSupport") }
    private val analyze by lazy {
        Class.forName("org.jetbrains.kotlin.analysis.api.AnalyzeKt")
            .getMethod("analyze", ktElement, Class.forName("kotlin.jvm.functions.Function1"))
    }

    fun typeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
        directOnly: Boolean,
        direction: TypeHierarchyDirection?,
        page: HierarchyPageRequest?
    ): TypeHierarchyData? {
        val declaration = declaration(element, ktClass) ?: return null
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)
        val limit = page?.collectionLimit ?: 100
        fun supertypes(current: PsiElement, visited: Set<PsiElement>, depth: Int): List<TypeElementData> {
            if (depth >= 100 || current in visited) return emptyList()
            return visibleParents(current, searchScope).take(if (directOnly) limit else Int.MAX_VALUE).map { parent ->
                typeData(project, parent).copy(supertypes = if (directOnly) null else supertypes(parent, visited + current, depth + 1))
            }
        }
        val supers = if (direction != TypeHierarchyDirection.SUBTYPE) supertypes(declaration, emptySet(), 0) else emptyList()
        val subs = if (direction != TypeHierarchyDirection.SUPERTYPE) {
            inheritors(declaration, searchScope)
                .filter { !directOnly || isVisibleSubtypeOf(it, declaration, searchScope, ::directSupertypes) }
                .take(limit).map { typeData(project, it) }.toList()
        } else emptyList()
        val (superPage, superNext) = if (direction == TypeHierarchyDirection.SUPERTYPE) supers.applyHierarchyPage(page) else supers to null
        val (subPage, subNext) = if (direction == TypeHierarchyDirection.SUBTYPE) subs.applyHierarchyPage(page) else subs to null
        return TypeHierarchyData(typeData(project, declaration), superPage, subPage, superNext ?: subNext)
    }

    fun implementations(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): List<ImplementationData>? {
        val target = callableDeclaration(element) ?: declaration(element, ktClass) ?: return null
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)
        val candidates = if (ktClass.isInstance(target)) inheritors(target, searchScope) else {
            val service = findUsagesService(project)
            asElements(findUsages.getMethod("searchOverriders", PsiElement::class.java, SearchScope::class.java)
                .call(service, target, searchScope))
        }
        return candidates.filter { shouldIncludeNavigationElement(searchScope, it) }
            .take(MAX_COLLECTED_NAVIGATION_RESULTS).mapNotNull { candidate ->
                val source = PsiUtils.resolveNavigationTarget(candidate)
                val file = source.containingFile?.virtualFile ?: return@mapNotNull null
                val isClass = ktClass.isInstance(source)
                ImplementationData(
                    name = if (isClass) className(project, source) else "${owner(source)?.let { className(project, it) } ?: ""}.${name(source)}",
                    file = ProjectUtils.getToolFilePath(project, file),
                    line = PsiSourcePosition.line(project, source) ?: 0,
                    column = PsiSourcePosition.column(project, source) ?: 0,
                    kind = if (isClass) classKind(source) else "METHOD",
                    language = "Kotlin",
                    qualifiedName = if (isClass) PsiUtils.qualifiedName(source) else null,
                    pointerTarget = source
                )
            }.toList()
    }

    fun superMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val target = callableDeclaration(element) ?: return null
        val containingClass = owner(target) ?: return null
        val file = target.containingFile?.virtualFile ?: return null
        val method = MethodData(
            name(target), signature(project, target), className(project, containingClass),
            ProjectUtils.getToolFilePath(project, file),
            PsiSourcePosition.line(project, target) ?: 0, PsiSourcePosition.column(project, target) ?: 0,
            "Kotlin", target
        )
        val hierarchy = mutableListOf<SuperMethodData>()
        val visited = mutableSetOf(target)
        fun collect(current: PsiElement, depth: Int) {
            if (depth > 100) return
            for (parent in directSuperMethods(current)) {
                ProgressManager.checkCanceled()
                if (!visited.add(parent)) continue
                val parentClass = owner(parent) ?: continue
                val kind = classKind(parentClass)
                hierarchy.add(SuperMethodData(
                    name(parent), signature(project, parent), className(project, parentClass), kind,
                    parent.containingFile?.virtualFile?.let { ProjectUtils.getToolFilePath(project, it) },
                    PsiSourcePosition.line(project, parent), PsiSourcePosition.column(project, parent),
                    kind == "INTERFACE", depth, "Kotlin", parent
                ))
                collect(parent, depth + 1)
            }
        }
        collect(target, 1)
        return SuperMethodsData(method, hierarchy)
    }

    private fun declaration(element: PsiElement, expected: Class<*>): PsiElement? {
        var current: PsiElement? = PsiUtils.resolveTargetElement(element) ?: element
        while (current != null && current !is PsiFile) {
            if (expected.isInstance(current)) return current
            current = current.parent
        }
        return null
    }

    private fun owner(element: PsiElement): PsiElement? {
        var current = element.parent
        while (current != null && current !is PsiFile) {
            if (ktClass.isInstance(current)) return current
            current = current.parent
        }
        return null
    }

    private fun callableDeclaration(element: PsiElement): PsiElement? {
        var current: PsiElement? = PsiUtils.resolveTargetElement(element) ?: element
        while (current != null && current !is PsiFile) {
            val isLocalProperty = ktProperty.isInstance(current) && ktProperty.getMethod("isLocal").call(current) == true
            if (!isLocalProperty && callableDeclarations.any { it.isInstance(current) }) return current
            current = current.parent
        }
        return null
    }

    private fun name(element: PsiElement): String = (element as? PsiNamedElement)?.name ?: "unknown"

    private fun className(project: Project, element: PsiElement): String =
        PsiUtils.qualifiedName(element) ?: PsiUtils.classDisplayName(project, element) ?: "unknown"

    private fun classKind(element: PsiElement): String {
        val kind = PsiUtils.kotlinClassKind(element) ?: "CLASS"
        return if (kind == "CLASS" && ktClass.isInstance(element) &&
            abstractModifiers.any { hasModifier.call(element, it) == true }
        ) "ABSTRACT_CLASS" else kind
    }

    private fun typeData(project: Project, element: PsiElement): TypeElementData = TypeElementData(
        name = className(project, element), qualifiedName = PsiUtils.qualifiedName(element),
        file = element.containingFile?.virtualFile?.let { ProjectUtils.getToolFilePath(project, it) },
        line = PsiSourcePosition.line(project, element), kind = classKind(element),
        language = "Kotlin", pointerTarget = element
    )

    private fun directSupertypes(element: PsiElement): List<PsiElement> {
        if (!ktClass.isInstance(element)) return emptyList()
        val block: (Any) -> List<PsiElement> = { session ->
            val declarationSymbol = symbolProvider.getMethod("getClassSymbol", ktClass).call(session, element)
            val parents = declarationSymbol?.let { classSymbol.getMethod("getSuperTypes").call(it) as List<*> }.orEmpty()
            parents.mapNotNull { supertype ->
                val parentSymbol = typeInformation.getMethod("getExpandedSymbol", type).call(session, supertype)
                parentSymbol?.let { symbol.getMethod("getPsi").call(it) as? PsiElement }
            }.filter { PsiUtils.qualifiedName(it) != "kotlin.Any" }
        }
        @Suppress("UNCHECKED_CAST")
        return analyze.call(null, element, block) as List<PsiElement>
    }

    private fun directSuperMethods(element: PsiElement): List<PsiElement> {
        val block: (Any) -> List<PsiElement> = { session ->
            val declarationSymbol = symbolProvider.getMethod("getSymbol", ktDeclaration).call(session, element)
            if (declarationSymbol != null && callableSymbol.isInstance(declarationSymbol)) {
                val parents = relations.getMethod("getDirectlyOverriddenSymbols", callableSymbol).call(session, declarationSymbol) as Sequence<*>
                parents.mapNotNull { symbol.getMethod("getPsi").call(it) as? PsiElement }.toList()
            } else emptyList()
        }
        @Suppress("UNCHECKED_CAST")
        return analyze.call(null, element, block) as List<PsiElement>
    }

    private fun visibleParents(element: PsiElement, scope: GlobalSearchScope): List<PsiElement> {
        val pending = ArrayDeque(directSupertypes(element))
        val visited = mutableSetOf(element)
        val result = mutableListOf<PsiElement>()
        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val parent = pending.removeFirst()
            if (!visited.add(parent)) continue
            if (shouldIncludeNavigationElement(scope, parent)) result.add(parent) else pending.addAll(directSupertypes(parent))
        }
        return result
    }

    private fun inheritors(element: PsiElement, scope: GlobalSearchScope): Sequence<PsiElement> =
        asElements(findUsages.getMethod("searchInheritors", PsiElement::class.java, SearchScope::class.java, Boolean::class.javaPrimitiveType)
            .call(findUsagesService(element.project), element, scope, true))
            .filter { shouldIncludeNavigationElement(scope, it) }

    private fun findUsagesService(project: Project): Any {
        val companion = findUsages.getField("Companion").get(null)
        return requireNotNull(companion.javaClass.getMethod("getInstance", Project::class.java).call(companion, project))
    }

    private fun signature(project: Project, element: PsiElement): String =
        findUsages.getMethod("renderDeclaration", ktDeclaration).call(findUsagesService(project), element) as String

    private fun asElements(value: Any?): Sequence<PsiElement> {
        @Suppress("UNCHECKED_CAST")
        return (value as Sequence<PsiElement>).onEach { ProgressManager.checkCanceled() }
    }

    private fun Method.call(receiver: Any?, vararg arguments: Any?): Any? = try {
        invoke(receiver, *arguments)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}

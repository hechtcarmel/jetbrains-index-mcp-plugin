package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Converts IDE Structure View trees into the stable MCP [StructureNode] model.
 *
 * Language handlers own classification because PSI element types and user-facing
 * names differ by language. This extractor owns traversal, wrapper flattening,
 * source line lookup, and defensive cycle/depth guards.
 */
object IdeStructureViewExtractor {

    private val LOG = logger<IdeStructureViewExtractor>()
    private const val DEFAULT_MAX_DEPTH = 64

    data class StructureElementInfo(
        val name: String,
        val kind: StructureKind,
        val modifiers: List<String> = emptyList(),
        val signature: String? = null,
        val line: Int? = null,
        val endLine: Int? = null
    )

    fun interface Classifier {
        fun describe(value: Any?, presentation: ItemPresentation): StructureElementInfo?
    }

    fun extract(
        file: PsiFile,
        project: Project,
        classifier: Classifier,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): List<StructureNode> {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file)
            ?: return emptyList()
        val treeBuilder = builder as? TreeBasedStructureViewBuilder
            ?: return emptyList()

        return try {
            val model = treeBuilder.createStructureViewModel(null)
            try {
                convertTreeElements(
                    elements = model.root.children,
                    classifier = classifier,
                    lineResolver = { value -> resolveLine(project, value) },
                    endLineResolver = { value -> resolveEndLine(project, value) },
                    maxDepth = maxDepth
                )
            } finally {
                model.dispose()
            }
        } catch (e: Exception) {
            LOG.debug("Failed to extract structure view for ${file.name}: ${e.message}", e)
            emptyList()
        }
    }

    internal fun convertTreeElements(
        elements: Array<out TreeElement>,
        classifier: Classifier,
        lineResolver: (Any?) -> Int? = { null },
        endLineResolver: (Any?) -> Int? = { null },
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): List<StructureNode> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return elements.flatMap { element ->
            convertTreeElement(
                element = element,
                classifier = classifier,
                lineResolver = lineResolver,
                endLineResolver = endLineResolver,
                visited = visited,
                depth = 0,
                maxDepth = maxDepth
            )
        }
    }

    private fun convertTreeElement(
        element: TreeElement,
        classifier: Classifier,
        lineResolver: (Any?) -> Int?,
        endLineResolver: (Any?) -> Int?,
        visited: MutableSet<Any>,
        depth: Int,
        maxDepth: Int
    ): List<StructureNode> {
        if (depth >= maxDepth) return emptyList()

        val value = (element as? StructureViewTreeElement)?.value ?: element
        if (!visited.add(value)) return emptyList()

        val children = element.children.flatMap { child ->
            convertTreeElement(
                element = child,
                classifier = classifier,
                lineResolver = lineResolver,
                endLineResolver = endLineResolver,
                visited = visited,
                depth = depth + 1,
                maxDepth = maxDepth
            )
        }

        val info = try {
            classifier.describe(value, element.presentation)
        } catch (e: Exception) {
            LOG.debug("Failed to classify structure view element ${value.javaClass.name}: ${e.message}", e)
            null
        } ?: return children

        val resolvedLine = info.line ?: lineResolver(value)
        if (resolvedLine == null && value is PsiElement && children.isEmpty()) {
            return emptyList()
        }

        val resolvedEndLine = info.endLine ?: endLineResolver(value)

        return listOf(
            StructureNode(
                name = info.name,
                kind = info.kind,
                modifiers = info.modifiers.distinct(),
                signature = info.signature?.takeIf { it.isNotBlank() },
                line = resolvedLine ?: children.firstOrNull()?.line ?: 1,
                endLine = resolvedEndLine,
                children = children
            )
        )
    }

    private fun resolveLine(project: Project, value: Any?): Int? {
        val element = value as? PsiElement ?: return null
        return PsiSourcePosition.line(project, element)
    }

    private fun resolveEndLine(project: Project, value: Any?): Int? {
        val element = value as? PsiElement ?: return null
        val file = element.containingFile?.virtualFile ?: return null
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: return null
        val endOffset = element.textRange?.endOffset ?: return null
        if (endOffset <= 0 || endOffset > document.textLength) return null
        return document.getLineNumber(endOffset - 1) + 1
    }
}

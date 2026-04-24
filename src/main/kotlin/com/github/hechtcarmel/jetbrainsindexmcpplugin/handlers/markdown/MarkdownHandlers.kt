package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.markdown

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.StructureHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

private const val MARKDOWN_LANGUAGE_ID = "Markdown"

/**
 * Registration entry point for Markdown handlers.
 *
 * Markdown support is intentionally header-focused: headings provide the hierarchy used by the file
 * structure tool. Symbol search goes through the single popup-backed path in [FindSymbolTool],
 * which picks up Markdown headers via IntelliJ's `ChooseByNameContributor` infrastructure.
 */
object MarkdownHandlers {

    private val LOG = logger<MarkdownHandlers>()

    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!PluginDetectors.markdown.isAvailable) {
            LOG.info("Markdown plugin not available, skipping Markdown handler registration")
            return
        }

        registry.registerStructureHandler(MarkdownStructureHandler())

        LOG.info("Registered Markdown handlers")
    }
}

abstract class BaseMarkdownHandler<T> : LanguageHandler<T> {

    override val languageId = MARKDOWN_LANGUAGE_ID

    override fun canHandle(element: PsiElement): Boolean =
        isAvailable() && element.language.id == MARKDOWN_LANGUAGE_ID

    override fun isAvailable(): Boolean = PluginDetectors.markdown.isAvailable

    protected fun headerName(header: MarkdownHeader): String =
        header.name?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: header.buildVisibleText(true)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Untitled heading"

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String =
        ProjectUtils.getToolFilePath(project, file)

    protected fun getLineNumber(project: Project, element: PsiElement): Int {
        val psiFile = element.containingFile ?: return 1
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return 1
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun getColumnNumber(project: Project, element: PsiElement): Int {
        val psiFile = element.containingFile ?: return 1
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return 1
        val lineNumber = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(lineNumber) + 1
    }

    protected fun markdownHeaders(file: PsiFile): List<MarkdownHeader> =
        PsiTreeUtil.findChildrenOfType(file, MarkdownHeader::class.java)
            .sortedBy { it.textOffset }
}

class MarkdownStructureHandler : BaseMarkdownHandler<List<StructureNode>>(), StructureHandler {

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val roots = mutableListOf<MutableHeadingNode>()
        val stack = mutableListOf<MutableHeadingNode>()

        for (header in markdownHeaders(file)) {
            val node = MutableHeadingNode(
                level = header.level,
                name = headerName(header),
                line = getLineNumber(project, header)
            )

            while (stack.isNotEmpty() && stack.last().level >= node.level) {
                stack.removeAt(stack.lastIndex)
            }

            if (stack.isEmpty()) {
                roots.add(node)
            } else {
                stack.last().children.add(node)
            }

            stack.add(node)
        }

        return roots.map { it.toStructureNode() }
    }

    private data class MutableHeadingNode(
        val level: Int,
        val name: String,
        val line: Int,
        val children: MutableList<MutableHeadingNode> = mutableListOf()
    ) {
        fun toStructureNode(): StructureNode =
            StructureNode(
                name = name,
                kind = StructureKind.HEADING,
                modifiers = emptyList(),
                signature = null,
                line = line,
                children = children.map { it.toStructureNode() }
            )
    }
}

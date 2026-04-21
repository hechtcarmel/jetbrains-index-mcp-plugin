package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.ide.util.gotoByName.ChooseByNameInScopeItemProvider
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.ide.util.gotoByName.ChooseByNameModelEx
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNameViewModel
import com.intellij.ide.util.gotoByName.GotoSymbolModel2
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

internal data class PopupSearchCandidate(
    val item: NavigationItem,
    val fullName: String?
)

internal data class PopupSearchResult(
    val candidates: List<PopupSearchCandidate>,
    val isQualifiedQuery: Boolean
)

/**
 * Headless wrapper around the same model/provider stack used by IntelliJ's Go to Symbol popup.
 *
 * This preserves popup query parsing, full-name matching, and result ordering while staying usable
 * from MCP tool execution without constructing any UI.
 */
internal object PopupFaithfulSymbolSearch {

    private val LOG = logger<PopupFaithfulSymbolSearch>()

    fun search(
        project: Project,
        pattern: String,
        scope: GlobalSearchScope,
        limit: Int
    ): PopupSearchResult {
        val disposable = Disposer.newDisposable("PopupFaithfulSymbolSearch")
        try {
            val model = GotoSymbolModel2(project, disposable)
            val context = resolveSearchContext(project)
            val provider = ChooseByNameModelEx.getItemProvider(model, context)
            val viewModel = HeadlessChooseByNameViewModel(project, model, limit)
            val transformedPattern = viewModel.transformPattern(pattern)
            val localPattern = buildLocalPattern(model, transformedPattern)
            val isQualifiedQuery = hasQualifiedSeparator(model, transformedPattern)
            val idFilter = IdFilter.getProjectIdFilter(project, scope.isSearchInLibraries)
            @Suppress("DEPRECATION")
            val parameters = FindSymbolParameters(pattern, localPattern, scope, idFilter)
            val candidates = mutableListOf<PopupSearchCandidate>()
            val indicator = ProgressIndicatorBase()

            if (provider is ChooseByNameInScopeItemProvider) {
                provider.filterElementsWithWeights(viewModel, parameters, indicator) { found ->
                    val item = found.item as? NavigationItem ?: return@filterElementsWithWeights true
                    candidates.add(PopupSearchCandidate(item = item, fullName = model.getFullName(item)))
                    candidates.size < limit
                }
            } else {
                provider.filterElements(viewModel, pattern, scope.isSearchInLibraries, indicator) { item ->
                    val navigationItem = item as? NavigationItem ?: return@filterElements true
                    candidates.add(PopupSearchCandidate(item = navigationItem, fullName = model.getFullName(navigationItem)))
                    candidates.size < limit
                }
            }

            return PopupSearchResult(
                candidates = candidates,
                isQualifiedQuery = isQualifiedQuery
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun resolveSearchContext(project: Project): PsiElement? = runCatching {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            return@runCatching null
        }

        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runCatching null
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runCatching null
        val safeOffset = editor.caretModel.offset.coerceIn(0, psiFile.textLength)
        psiFile.findElementAt(safeOffset) ?: psiFile
    }.onFailure { error ->
        LOG.debug("Failed to resolve current editor context for popup-backed symbol search", error)
    }.getOrNull()

    private fun buildLocalPattern(model: ChooseByNameModel, transformedPattern: String): String {
        var lastSeparatorOccurrence = 0

        for (separator in model.separators) {
            var index = transformedPattern.lastIndexOf(separator)
            if (index == transformedPattern.length - separator.length) {
                index = transformedPattern.lastIndexOf(separator, index - 1)
            }
            lastSeparatorOccurrence = maxOf(lastSeparatorOccurrence, if (index == -1) index else index + separator.length)
        }

        return transformedPattern.substring(lastSeparatorOccurrence)
    }

    private fun hasQualifiedSeparator(model: ChooseByNameModel, transformedPattern: String): Boolean {
        return model.separators.any { separator ->
            val index = transformedPattern.lastIndexOf(separator)
            index >= 0 && index < transformedPattern.length - separator.length
        }
    }

    private class HeadlessChooseByNameViewModel(
        private val project: Project,
        private val model: ChooseByNameModel,
        private val maximumListSizeLimit: Int
    ) : ChooseByNameViewModel {

        override fun getProject(): Project = project

        override fun getModel(): ChooseByNameModel = model

        override fun isSearchInAnyPlace(): Boolean =
            Registry.`is`("ide.goto.middle.matching") && model.useMiddleMatching()

        override fun transformPattern(pattern: String): String =
            ChooseByNamePopup.getTransformedPattern(pattern, model)

        override fun canShowListForEmptyPattern(): Boolean = false

        override fun getMaximumListSizeLimit(): Int = maximumListSizeLimit
    }
}

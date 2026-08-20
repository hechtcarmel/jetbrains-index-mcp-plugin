package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.util.concurrent.CancellationException

/**
 * Reads a symbol's hover signature and documentation from the IDE's own documentation backend,
 * as plain text.
 *
 * Every language that supports Quick Documentation registers a
 * [DocumentationProvider] under `com.intellij.lang.documentationProvider`, so going through
 * [LanguageDocumentation] gets Kotlin, Python, JS/TS, Go, PHP and Rust for free — no
 * per-language handler in this plugin.
 *
 * ## Why this API and not `DocumentationTarget`
 *
 * `DocumentationTargetProvider`/`DocumentationTarget` is the newer backend, and the platform's
 * own `PsiElementDocumentationTarget` is a thin adapter over exactly the two
 * [DocumentationProvider] calls made here. It is not usable from a plugin that wants the *text*:
 * `DocumentationResult` exposes only factory methods, and reading the HTML back out requires
 * `com.intellij.platform.backend.documentation.impl.DocumentationData`, which is
 * `@ApiStatus.Internal` — banned outright by this repo's API-compliance rules and rejected by
 * the plugin verifier. [DocumentationProvider] is `@ApiStatus.Obsolete`, which is a "prefer the
 * new one when implementing" marker, not a compatibility problem; the platform still routes all
 * existing implementations through it.
 *
 * ## Threading
 *
 * Both entry points read PSI and must hold a read lock. `generateDoc` is documented as
 * potentially slow and must not run on the EDT — MCP tool calls arrive on Ktor worker threads,
 * so calling these from inside `suspendingReadAction` satisfies both.
 *
 * Only *local* documentation is produced. `ExternalDocumentationProvider.fetchExternalDocumentation`
 * is never called, so this makes no network requests.
 */
object SymbolDocumentation {

    private val LOG = logger<SymbolDocumentation>()

    private const val NON_BREAKING_SPACE = '\u00A0'

    private val BLANK_LINE_RUN = Regex("\n{3,}")

    /**
     * The Ctrl/Cmd-hover text for [element] — the signature line as the language renders it,
     * including its container.
     *
     * @return plain text, or null when no provider offers navigation info for this element.
     */
    @RequiresReadLock
    fun quickNavigationInfo(element: PsiElement, originalElement: PsiElement? = null): String? =
        firstNonBlank(element) { it.getQuickNavigateInfo(element, originalElement) }
            ?.let { normalizeSignature(it) }

    /**
     * The rendered documentation comment for [element] — Javadoc, KDoc, docstring, JSDoc.
     *
     * @return plain text, or null when the element carries no documentation.
     */
    @RequiresReadLock
    fun renderedDoc(element: PsiElement, originalElement: PsiElement? = null): String? =
        firstNonBlank(element) { it.generateDoc(element, originalElement) }
            ?.let { normalizeDoc(it) }

    /**
     * Asks each candidate provider in turn and returns the first non-blank answer.
     *
     * The language provider comes first because [LanguageDocumentation] already composites every
     * provider registered for that language; the application-level providers are a fallback for
     * elements no language claims (files, directories).
     */
    private fun firstNonBlank(element: PsiElement, ask: (DocumentationProvider) -> String?): String? {
        for (provider in providersFor(element)) {
            val answer = try {
                ask(provider)
            } catch (e: Exception) {
                rethrowControlFlowExceptions(e)
                // A provider that fails on an element it does not really handle is not a tool
                // failure — but it is worth a log line, because a provider failing on an element
                // it *does* handle silently degrades the result to the text fallback.
                LOG.info("Documentation provider ${provider.javaClass.name} failed for ${element.javaClass.simpleName}", e)
                null
            }
            if (!answer.isNullOrBlank()) return answer
        }
        return null
    }

    private fun providersFor(element: PsiElement): List<DocumentationProvider> {
        val languageProvider = LanguageDocumentation.INSTANCE.forLanguage(element.language)
        val generic = DocumentationProvider.EP_NAME.extensionList
        return if (languageProvider == null) generic else listOf(languageProvider) + generic
    }

    /**
     * [ProcessCanceledException] and [CancellationException] carry the platform's cancellation
     * signal and must never be swallowed into "no documentation".
     */
    private fun rethrowControlFlowExceptions(e: Exception) {
        when (e) {
            is ProcessCanceledException, is CancellationException -> throw e
        }
    }

    /**
     * Navigation info is HTML across two or three lines (container, then the declaration).
     * Blank lines carry no information here, so they are dropped rather than preserved.
     */
    private fun normalizeSignature(html: String): String? =
        toPlainText(html)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * Documentation is prose, so paragraph breaks are kept — collapsed to a single blank line,
     * because the HTML layout produces long runs of them.
     */
    private fun normalizeDoc(html: String): String? =
        toPlainText(html)
            .lineSequence()
            .map { it.trim() }
            .joinToString("\n")
            .replace(BLANK_LINE_RUN, "\n\n")
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * `removeHtmlTags` parses the markup rather than stripping angle brackets, so entities are
     * decoded and flow-breaking tags become line breaks. Decoding turns the `&nbsp;` that the
     * Java doc generator uses for signature spacing into U+00A0, which reads as a normal space
     * but does not compare or split as one — so it is folded back to a plain space here.
     */
    private fun toPlainText(html: String): String =
        StringUtil.removeHtmlTags(html, true)
            .replace(NON_BREAKING_SPACE, ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
}

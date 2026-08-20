package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.usageView.UsageInfo

/**
 * Pre-flight writability check for the *full* refactoring scope (issue #310).
 *
 * `BaseRefactoringProcessor.run()` routes read-only affected files through
 * `ReadonlyStatusHandler`, which in a real IDE shows a modal "these files are
 * read-only" dialog. A headless MCP call then blocks the EDT until the client
 * times out. Checking every file discovered by `findUsages()` *before* running
 * the processor turns that hang into an actionable error listing the files.
 *
 * The per-target `isWritable` checks in the individual tools still run first;
 * this guard covers referencing files (generated sources, SQL migrations,
 * files from other content roots).
 */
internal object RefactoringScopeGuard {
    private val LOG = Logger.getInstance(RefactoringScopeGuard::class.java)

    /** Relative paths of read-only files among the usages' containing files. */
    fun readOnlyFilesIn(project: Project, usages: Array<UsageInfo>): List<String> =
        usages.asSequence()
            .mapNotNull { it.virtualFile }
            .distinct()
            .filterNot { it.isWritable }
            .map { ProjectUtils.getToolFilePath(project, it) }
            .distinct()
            .sorted()
            .toList()

    /**
     * Invokes the processor's `findUsages()` reflectively. `BaseRefactoringProcessor`
     * declares it protected and some subclasses (e.g. `ChangeSignatureProcessorBase`)
     * keep it that way. Returns null when reflection fails — callers fail open,
     * keeping the tool functional at the cost of the pre-check.
     */
    fun findUsagesReflectively(processor: BaseRefactoringProcessor): Array<UsageInfo>? {
        return try {
            var cls: Class<*>? = processor.javaClass
            while (cls != null) {
                val method = try {
                    cls.getDeclaredMethod("findUsages")
                } catch (_: NoSuchMethodException) {
                    cls = cls.superclass
                    continue
                }
                method.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return method.invoke(processor) as? Array<UsageInfo>
            }
            null
        } catch (e: Exception) {
            LOG.warn("Read-only scope pre-check skipped — findUsages() reflection failed: ${e.message}")
            null
        }
    }

    /** Builds the error message for a scope blocked by [readOnlyFiles]. */
    fun blockedMessage(readOnlyFiles: List<String>): String =
        "Blocked by read-only files in the refactoring scope: " +
            readOnlyFiles.joinToString(", ") +
            ". Make these files writable, or remove them from the scope " +
            "(for generated sources: run a clean build and ide_sync_files; " +
            "or exclude the directory from the project's content roots), then retry."
}

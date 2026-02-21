package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil

/**
 * Shared utilities for symbol/file search matching and path filtering.
 *
 * Used by both [OptimizedSymbolSearch] (handler layer) and the navigation
 * tools (FindClassTool, FindFileTool) to avoid duplication.
 */

/** Path prefixes for directories that should be excluded from search results (build output, virtual environments, worktrees). */
internal val EXCLUDED_PATH_PREFIXES = listOf(
    "bin/", "build/", "out/", ".gradle/",
    ".venv/", "venv/", ".env/", "env/",
    "node_modules/",
    ".worktrees/", ".claude/worktrees/"
)

/** Returns true if [path] starts with an excluded directory prefix (build output, venv, worktree, etc.). */
internal fun isExcludedPath(path: String): Boolean =
    EXCLUDED_PATH_PREFIXES.any { path.startsWith(it) }

/**
 * Build a [MinusculeMatcher] for the given [pattern] and [matchMode].
 *
 * - `"prefix"` — camelCase-aware prefix matching (no leading wildcard)
 * - `"substring"` / `"exact"` — substring matching (leading `*` wildcard); for `"exact"`
 *   the filter in [createNameFilter] enforces full equality instead of using the matcher.
 */
internal fun createMatcher(pattern: String, matchMode: String = "substring"): MinusculeMatcher {
    val matcherPattern = when (matchMode) {
        "prefix" -> pattern      // prefix/camelCase matching only
        else -> "*$pattern"      // substring (default) and exact (sorted via matcher degree)
    }
    return NameUtil.buildMatcher(matcherPattern, NameUtil.MatchingCaseSensitivity.NONE)
}

/**
 * Return a predicate that decides whether a candidate [name] matches [pattern] under [matchMode].
 *
 * - `"exact"` — case-insensitive full-string equality
 * - Everything else — delegate to the pre-built [matcher]
 */
internal fun createNameFilter(
    pattern: String,
    matchMode: String,
    matcher: MinusculeMatcher
): (String) -> Boolean = when (matchMode) {
    "exact" -> { name -> name.equals(pattern, ignoreCase = true) }
    else -> { name -> matcher.matches(name) }
}

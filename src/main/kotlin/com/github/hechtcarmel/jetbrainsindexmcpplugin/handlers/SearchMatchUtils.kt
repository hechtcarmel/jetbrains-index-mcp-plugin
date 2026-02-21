package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil

/**
 * Shared utilities for symbol/file search matching and path filtering.
 *
 * Used by both [OptimizedSymbolSearch] (handler layer) and the navigation
 * tools (FindClassTool, FindFileTool) to avoid duplication.
 */

/**
 * Path prefixes that are excluded only when they appear at the project root.
 * These are common build output dirs that could legitimately appear as nested source dirs.
 */
internal val ROOT_ONLY_EXCLUDED_PREFIXES = listOf(
    "bin/", "build/", "out/", ".gradle/"
)

/**
 * Path segments that are excluded at any depth in the project tree.
 * Virtual environments and package manager directories should never contain source files
 * regardless of where they appear in the project hierarchy.
 *
 * Note: `.env/` and `env/` are intentionally omitted — they are too generic and would
 * produce false positives (e.g. `src/config/env/`, `docker/env/`). Only the conventional
 * Python venv names `.venv/` and `venv/` are excluded.
 */
internal val DEEP_EXCLUDED_SEGMENTS = listOf(
    ".venv/", "venv/",
    "node_modules/",
    ".worktrees/", ".claude/worktrees/"
)

/** Returns true if [path] matches any excluded directory rule. */
internal fun isExcludedPath(path: String): Boolean {
    if (ROOT_ONLY_EXCLUDED_PREFIXES.any { path.startsWith(it) }) return true
    if (DEEP_EXCLUDED_SEGMENTS.any { seg -> path.startsWith(seg) || path.contains("/$seg") }) return true
    return false
}

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
 * - `"exact"` — case-sensitive full-string equality
 * - Everything else — delegate to the pre-built [matcher]
 */
internal fun createNameFilter(
    pattern: String,
    matchMode: String,
    matcher: MinusculeMatcher
): (String) -> Boolean = when (matchMode) {
    "exact" -> { name -> name == pattern }
    else -> { name -> matcher.matches(name) }
}

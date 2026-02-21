package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil

/**
 * Shared utilities for symbol/file search matching and path filtering.
 *
 * Used by both [OptimizedSymbolSearch] (handler layer) and the navigation
 * tools (FindClassTool, FindFileTool) to avoid duplication.
 */

/** Build output directories that duplicate source files and should be excluded from results. */
internal val BUILD_OUTPUT_PREFIXES = listOf("bin/", "build/", "out/", ".gradle/")

/** Returns true if [path] is inside a build output directory. */
internal fun isBuildOutputPath(path: String): Boolean =
    BUILD_OUTPUT_PREFIXES.any { path.startsWith(it) }

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

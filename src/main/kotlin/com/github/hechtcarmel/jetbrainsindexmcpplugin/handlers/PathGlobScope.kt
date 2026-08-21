package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.toArgumentFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope

/**
 * One compiled entry of a `paths` argument.
 *
 * The glob dialect is deliberately small and matches the `paths` parameter of the IDE's
 * built-in MCP server (issue #328):
 * - patterns are project-relative and use `/` as separator (matched against the same
 *   relative paths the tools return);
 * - `*` matches within a single path segment, `?` matches one non-separator character;
 * - a double star crosses directories, matching zero or more whole segments;
 * - a pattern also matches everything beneath a directory it names, so a plain
 *   `src/main` behaves as if a trailing double-star segment were appended.
 *
 * (Double stars are spelled out in words here because Kotlin block comments nest: the
 * literal two-star-slash / slash-two-star sequences inside a KDoc break the comment.)
 *
 * [literalPrefix] is the leading wildcard-free directory part — `src/main/kotlin` for
 * a `src/main/kotlin` prefix followed by wildcards, empty for a pattern that starts with
 * a wildcard. Include globs whose prefix does not resolve inside the project are rejected
 * up front so a typo cannot masquerade as "no matches".
 */
class PathGlob private constructor(
    val original: String,
    val pattern: String,
    val literalPrefix: String,
    private val regex: Regex,
) {

    fun matches(relativePath: String): Boolean = regex.matches(relativePath)

    companion object {
        private const val REGEX_METACHARS = "\\^$.|+()[]{}"

        fun compile(original: String, pattern: String): PathGlob {
            val wildcardIndex = pattern.indexOfFirst { it == '*' || it == '?' }
            val literalPrefix = if (wildcardIndex < 0) {
                pattern
            } else {
                pattern.take(wildcardIndex).substringBeforeLast('/', "")
            }
            // The pattern also matches as a directory prefix — the trailing optional group is
            // the implicit trailing double-star segment.
            //
            // The generated fragment is concatenated directly under "^...$" rather than wrapped
            // in a group: globToRegex only ever emits concatenation, because a literal "|" in a
            // glob is escaped via REGEX_METACHARS. Anything added there that CAN emit a
            // top-level alternation has to be parenthesised, or "^" would bind to the first
            // branch alone.
            val regex = Regex("^${globToRegex(pattern)}(?:/.*)?$")
            return PathGlob(original, pattern, literalPrefix, regex)
        }

        private fun globToRegex(glob: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        val prevIsSeparator = i == 0 || glob[i - 1] == '/'
                        val next = i + 2
                        when {
                            // "**/" as a full segment: zero or more whole directories.
                            prevIsSeparator && next < glob.length && glob[next] == '/' -> {
                                sb.append("(?:[^/]+/)*")
                                i = next + 1
                            }
                            // trailing "**": anything, including across directories.
                            else -> {
                                sb.append(".*")
                                i = next
                            }
                        }
                    }
                    c == '*' -> { sb.append("[^/]*"); i++ }
                    c == '?' -> { sb.append("[^/]"); i++ }
                    else -> {
                        if (c in REGEX_METACHARS) sb.append('\\')
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }
    }
}

/**
 * The parsed form of a `paths` argument: include globs unioned, then exclude globs
 * (entries with a leading `!`) subtracted. With only excludes, everything else matches.
 */
class PathGlobMatcher private constructor(
    val includes: List<PathGlob>,
    val excludes: List<PathGlob>,
) {

    /**
     * Value identity of this filter, handed to [DelegatingGlobalSearchScope]'s `equality`
     * parameter by [PathGlobScope].
     *
     * Without it, two scopes wrapping the same base scope with *different* globs compare
     * equal — `DelegatingGlobalSearchScope.equals` only looks at the delegate and this
     * discriminator — and `GlobalSearchScope.intersectWith`/`union` short-circuit on
     * equality, so one filter would silently stand in for the other.
     *
     * A pair of pattern *lists*, not a joined string: any single-character separator can
     * appear inside a glob, so joining collides — the single glob `src/a,b/x` against the
     * two globs `src/a` and `b/x` — which defeats the very guard this exists to provide.
     *
     * (Glob examples here avoid a literal slash-star-star: Kotlin block comments nest, so
     * that sequence opens a nested comment and swallows the rest of the file.)
     */
    internal val equalityKey: Any =
        Pair(includes.map { it.pattern }, excludes.map { it.pattern })

    /**
     * Whether a file at [relativePath] is inside the requested path set.
     *
     * [relativePath] is `null` for files outside every project root (library/jar entries
     * under `project_and_libraries`); those can never match a project-relative include,
     * and an exclude-only filter deliberately leaves them alone.
     */
    fun matches(relativePath: String?): Boolean {
        if (relativePath == null) return includes.isEmpty()
        if (includes.isNotEmpty() && includes.none { it.matches(relativePath) }) return false
        return excludes.none { it.matches(relativePath) }
    }

    companion object {
        fun parse(entries: List<String>): Result<PathGlobMatcher> {
            if (entries.isEmpty()) {
                return "'paths' must contain at least one glob.".toArgumentFailure()
            }
            val includes = mutableListOf<PathGlob>()
            val excludes = mutableListOf<PathGlob>()
            for (raw in entries) {
                val entry = raw.trim()
                if (entry.isEmpty()) {
                    return "'paths' entries must be non-empty globs.".toArgumentFailure()
                }
                val negated = entry.startsWith("!")
                val pattern = entry
                    .removePrefix("!")
                    .trim()
                    // Windows-style separators are normalized rather than rejected. Left as-is
                    // they are catastrophic-but-silent: "\\" is escaped as a literal by
                    // globToRegex so the glob matches nothing, and literalPrefix (which splits on
                    // '/') comes back empty, so the unresolvable-glob guard skips it too — a
                    // typo'd separator would return "no matches" instead of an error. IntelliJ's
                    // VirtualFile.path always uses '/', even on Windows, so a backslash here is
                    // never a path the caller could have meant literally.
                    .replace('\\', '/')
                    .removePrefix("./")
                    .removePrefix("/")
                    .removeSuffix("/")
                if (pattern.isEmpty()) {
                    return "Invalid 'paths' entry '$raw': nothing after '!'.".toArgumentFailure()
                }
                val compiled = PathGlob.compile(original = raw, pattern = pattern)
                if (negated) excludes.add(compiled) else includes.add(compiled)
            }
            return Result.success(PathGlobMatcher(includes, excludes))
        }
    }
}

/**
 * A [GlobalSearchScope] that delegates to [baseScope] but keeps only files whose
 * project-relative path matches a [PathGlobMatcher].
 *
 * Filtering at the scope level (like [ExcludedPathScope] and
 * [GeneratedSourcesExcludingScope]) means the search infrastructure never resolves PSI
 * for filtered files, and it composes with whatever scope the tool already computed —
 * `scope` enums, generated-source exclusion, and `filePattern` all still apply.
 *
 * Relative paths are computed the same way [ProjectUtils.getRelativePath] computes the
 * paths tools return: the project base path wins, then the longest matching module
 * content root (workspace sub-projects). The roots are snapshotted at construction so
 * [contains] stays a cheap string match on the index thread.
 */
class PathGlobScope(
    baseScope: GlobalSearchScope,
    private val matcher: PathGlobMatcher,
    private val basePath: String?,
    private val contentRoots: List<String>,
) : DelegatingGlobalSearchScope(baseScope, matcher.equalityKey) {

    override fun contains(file: VirtualFile): Boolean {
        if (!super.contains(file)) return false
        return matcher.matches(relativize(file.path))
    }

    private fun relativize(path: String): String? {
        if (basePath != null && (path == basePath || path.startsWith("$basePath/"))) {
            return path.removePrefix(basePath).removePrefix("/")
        }
        var best: String? = null
        for (root in contentRoots) {
            if ((path == root || path.startsWith("$root/")) && (best == null || root.length > best.length)) {
                best = root
            }
        }
        return best?.let { path.removePrefix(it).removePrefix("/") }
    }

    companion object {
        /** Wrap [scope] so only paths matching [matcher] remain; `null` matcher = no filter. */
        fun wrap(project: Project, scope: GlobalSearchScope, matcher: PathGlobMatcher?): GlobalSearchScope {
            if (matcher == null) return scope
            return PathGlobScope(scope, matcher, project.basePath, ProjectUtils.getModuleContentRoots(project))
        }
    }
}

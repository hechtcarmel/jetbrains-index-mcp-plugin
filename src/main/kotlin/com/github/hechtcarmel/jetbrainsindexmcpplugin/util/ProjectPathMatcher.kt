package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Matches local project paths without changing the spelling used by the IDE's VFS. */
internal object ProjectPathMatcher {
    fun relativePath(absolutePath: String, rootPath: String): String? {
        val candidate = LocalPath.parse(absolutePath) ?: return null
        val root = LocalPath.parse(rootPath) ?: return null
        return candidate.match(root, rootPath)?.relativePath?.toString()?.replace(File.separatorChar, '/')
    }

    fun findMatchingRoot(absolutePath: String, roots: Iterable<String>): String? {
        val candidate = LocalPath.parse(absolutePath) ?: return null
        return roots.mapNotNull { rootPath ->
            LocalPath.parse(rootPath)?.let { candidate.match(it, rootPath) }
        }.minWithOrNull(
            // Fewer remaining components means a more specific root, even when aliases have
            // different lengths. Prefer the caller's spelling when two roots name the same place.
            compareBy<Match> { it.remainingNames }.thenBy { !it.lexical }
        )?.rootPath
    }

    private data class Match(val rootPath: String, val relativePath: Path, val lexical: Boolean) {
        val remainingNames: Int get() = if (relativePath.toString().isEmpty()) 0 else relativePath.nameCount
    }

    private class LocalPath(private val path: Path) {
        private val normalized = path.normalize()
        private val canonical: Path? by lazy {
            try {
                // Unlike normalize(), canonicalization resolves existing filename aliases (such
                // as Windows 8.3 names), and also supports a not-yet-created/deleted suffix.
                path.toFile().canonicalFile.toPath().normalize()
            } catch (_: IOException) {
                null // Identity is unknown; a failed lookup must not invent a root match.
            } catch (_: SecurityException) {
                null
            }
        }

        fun match(root: LocalPath, rootPath: String): Match? {
            if (normalized.startsWith(root.normalized)) {
                return Match(rootPath, root.normalized.relativize(normalized), lexical = true)
            }
            val realCandidate = canonical ?: return null
            val realRoot = root.canonical ?: return null
            if (!realCandidate.startsWith(realRoot)) return null
            return Match(rootPath, realRoot.relativize(realCandidate), lexical = false)
        }

        companion object {
            fun parse(path: String): LocalPath? = try {
                Path.of(path).takeIf { it.isAbsolute }?.let(::LocalPath)
            } catch (_: InvalidPathException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
}

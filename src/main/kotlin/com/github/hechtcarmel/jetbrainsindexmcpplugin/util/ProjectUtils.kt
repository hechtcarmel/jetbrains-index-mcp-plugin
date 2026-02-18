package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

object ProjectUtils {

    fun getRelativePath(project: Project, virtualFile: VirtualFile): String {
        val basePath = project.basePath
        val filePath = virtualFile.path
        if (basePath != null && (filePath == basePath || filePath.startsWith("$basePath/"))) {
            return filePath.removePrefix(basePath).removePrefix("/")
        }
        val contentRootPath = findMatchingContentRoot(project, filePath)
        if (contentRootPath != null) {
            return filePath.removePrefix(contentRootPath).removePrefix("/")
        }
        return filePath
    }

    fun getRelativePath(project: Project, absolutePath: String): String {
        val basePath = project.basePath
        if (basePath != null && (absolutePath == basePath || absolutePath.startsWith("$basePath/"))) {
            return absolutePath.removePrefix(basePath).removePrefix("/")
        }
        val contentRootPath = findMatchingContentRoot(project, absolutePath)
        if (contentRootPath != null) {
            return absolutePath.removePrefix(contentRootPath).removePrefix("/")
        }
        return absolutePath
    }

    fun resolveProjectFile(project: Project, relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val fullPath = if (relativePath.startsWith("/")) relativePath else "$basePath/$relativePath"
        return LocalFileSystem.getInstance().findFileByPath(fullPath)
    }

    fun getProjectBasePath(project: Project): String? {
        return project.basePath
    }

    fun isProjectFile(project: Project, virtualFile: VirtualFile): Boolean {
        val basePath = project.basePath ?: return false
        val filePath = virtualFile.path
        if (filePath == basePath || filePath.startsWith("$basePath/")) return true

        // Also check module content roots for workspace sub-projects
        return findMatchingContentRoot(project, filePath) != null
    }

    /**
     * Returns all module content root paths for a project.
     * For workspace projects, this includes paths to all sub-projects.
     */
    fun getModuleContentRoots(project: Project): List<String> {
        return try {
            ModuleManager.getInstance(project).modules.flatMap { module ->
                ModuleRootManager.getInstance(module).contentRoots.map { it.path }
            }
        } catch (e: Exception) {
            listOfNotNull(project.basePath)
        }
    }

    /**
     * Finds the content root path that contains the given absolute file path.
     * Returns null if no content root matches.
     */
    private fun findMatchingContentRoot(project: Project, absolutePath: String): String? {
        try {
            val modules = ModuleManager.getInstance(project).modules
            var bestMatch: String? = null
            for (module in modules) {
                val contentRoots = ModuleRootManager.getInstance(module).contentRoots
                for (root in contentRoots) {
                    val rootPath = root.path
                    if (absolutePath == rootPath || absolutePath.startsWith("$rootPath/")) {
                        if (bestMatch == null || rootPath.length > bestMatch.length) {
                            bestMatch = rootPath
                        }
                    }
                }
            }
            return bestMatch
        } catch (_: Exception) {
            // ModuleManager may not be available in all contexts
        }
        return null
    }

    /**
     * Tries to resolve a relative path against each module content root.
     */
    private fun resolveAgainstContentRoots(project: Project, relativePath: String): VirtualFile? {
        try {
            val modules = ModuleManager.getInstance(project).modules
            for (module in modules) {
                val contentRoots = ModuleRootManager.getInstance(module).contentRoots
                for (root in contentRoots) {
                    val fullPath = "${root.path}/$relativePath"
                    val file = LocalFileSystem.getInstance().findFileByPath(fullPath)
                    if (file != null) return file
                }
            }
        } catch (_: Exception) {
            // ModuleManager may not be available in all contexts
        }
        return null
    }
}

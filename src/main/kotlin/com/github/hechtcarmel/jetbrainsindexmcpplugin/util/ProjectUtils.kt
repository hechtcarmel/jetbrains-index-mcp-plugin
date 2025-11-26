package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

object ProjectUtils {

    fun getRelativePath(project: Project, virtualFile: VirtualFile): String {
        val basePath = project.basePath ?: return virtualFile.path
        return virtualFile.path.removePrefix(basePath).removePrefix("/")
    }

    fun getRelativePath(project: Project, absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return absolutePath.removePrefix(basePath).removePrefix("/")
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
        return virtualFile.path.startsWith(basePath)
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

sealed class MavenImportResult {
    data class Success(val modules: List<Module>) : MavenImportResult()
    data object MavenUnavailable : MavenImportResult()
    data class Failed(val error: String) : MavenImportResult()
}

object ProjectUtils {

    fun canonicalNormalizedPath(path: String): String {
        val canonical = runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        return ProjectResolver.normalizePath(canonical)
    }

    fun findOpenProjectByPath(path: String): Project? {
        val requested = canonicalNormalizedPath(path)
        return ProjectManager.getInstance().openProjects.firstOrNull { open ->
            !open.isDefault && open.basePath?.let { canonicalNormalizedPath(it) } == requested
        }
    }

    suspend fun awaitSmartMode(opened: Project): Boolean =
        suspendCancellableCoroutine { continuation ->
            ApplicationManager.getApplication().invokeLater({
                if (!opened.isDisposed) {
                    DumbService.getInstance(opened).runWhenSmart {
                        if (continuation.isActive) continuation.resume(true)
                    }
                } else {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, ModalityState.nonModal())
        }

    fun openTask(): OpenProjectTask =
        OpenProjectTask.build().withForceOpenInNewFrame(true)

    @Suppress("UNCHECKED_CAST")
    fun importMavenModule(project: Project, directoryVf: VirtualFile): MavenImportResult {
        val builderClass = try {
            Class.forName("org.jetbrains.idea.maven.wizards.MavenProjectAsyncBuilder")
        } catch (_: ClassNotFoundException) {
            return MavenImportResult.MavenUnavailable
        }
        val providerClass = try {
            Class.forName(
                "com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider"
            )
        } catch (_: ClassNotFoundException) {
            return MavenImportResult.MavenUnavailable
        }
        val builder = builderClass.getDeclaredConstructor().newInstance()
        val commitSync = builderClass.getMethod(
            "commitSync",
            Project::class.java,
            VirtualFile::class.java,
            providerClass
        )
        return try {
            val modules = commitSync.invoke(builder, project, directoryVf, null) as? List<Module>
                ?: emptyList()
            MavenImportResult.Success(modules)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            MavenImportResult.Failed("Failed to import ${directoryVf.name}: ${cause.message}")
        }
    }

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
        try {
            val fileIndex = ProjectFileIndex.getInstance(project)
            if (fileIndex.isInContent(virtualFile)) return true
        } catch (_: Exception) {
            // Fall back to path-based checks below when file index is unavailable.
        }

        val basePath = project.basePath ?: return false
        val filePath = virtualFile.path
        if (filePath == basePath || filePath.startsWith("$basePath/")) return true

        // Also check module content roots for workspace sub-projects
        return findMatchingContentRoot(project, filePath) != null
    }

    fun isDependencyFile(project: Project, virtualFile: VirtualFile): Boolean {
        return try {
            val fileIndex = ProjectFileIndex.getInstance(project)
            fileIndex.isInLibrary(virtualFile) ||
                fileIndex.isInLibraryClasses(virtualFile) ||
                fileIndex.isInLibrarySource(virtualFile)
        } catch (_: Exception) {
            false
        }
    }

    fun isAccessibleFile(project: Project, virtualFile: VirtualFile): Boolean {
        return isProjectFile(project, virtualFile) || isDependencyFile(project, virtualFile)
    }

    fun getToolFilePath(project: Project, virtualFile: VirtualFile): String {
        return when {
            isProjectFile(project, virtualFile) -> getRelativePath(project, virtualFile)
            virtualFile.fileSystem.protocol == "jar" -> virtualFile.url
            else -> virtualFile.path
        }
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

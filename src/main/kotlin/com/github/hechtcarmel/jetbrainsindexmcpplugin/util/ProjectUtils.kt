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
import com.intellij.openapi.util.SystemInfo
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

    /**
     * Returns the open project whose module content roots include every path in [roots]
     * (already canonical and normalized), or null when none does. When several qualify, the
     * one with the fewest content roots — the closest fit — wins.
     */
    fun findOpenProjectContainingRoots(roots: Set<String>): Project? {
        if (roots.isEmpty()) return null
        return ProjectManager.getInstance().openProjects
            .filter { !it.isDefault && !it.isDisposed }
            .mapNotNull { open ->
                val openRoots = getModuleContentRoots(open).mapTo(HashSet()) { canonicalNormalizedPath(it) }
                if (openRoots.containsAll(roots)) open to openRoots.size else null
            }
            .minByOrNull { (_, rootCount) -> rootCount }
            ?.first
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

    /**
     * The path tools report for [virtualFile]: relative to [Project.basePath] when the file is
     * under it, otherwise the file's absolute path. `AbstractMcpTool.resolveFile` accepts both
     * forms back.
     *
     * Module content roots outside the base path are deliberately not stripped (issue #441).
     * They are what `ide_open_workspace` produces (its aggregator project lives in the IDE
     * system directory, so every repository is outside the base path), and what
     * `ide_import_modules`, flat Maven layouts and Gradle included builds produce. Each Maven
     * module is its own content root, so stripping reduced every module's sources to the same
     * `src/main/...`: a path that named no file in particular, that the agent's own file tools
     * could not open, and that collided wherever results are deduplicated by path.
     */
    fun getRelativePath(project: Project, virtualFile: VirtualFile): String =
        getRelativePath(project, virtualFile.path)

    /** [getRelativePath] for a path that has no [VirtualFile], such as one parsed from build output. */
    fun getRelativePath(project: Project, absolutePath: String): String {
        // VirtualFile paths and Project.basePath use '/' on every OS; Windows build tools report '\'.
        val path = if (SystemInfo.isWindows) absolutePath.replace('\\', '/') else absolutePath
        val basePath = project.basePath
        if (basePath != null && (path == basePath || path.startsWith("$basePath/"))) {
            return path.removePrefix(basePath).removePrefix("/")
        }
        return path
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

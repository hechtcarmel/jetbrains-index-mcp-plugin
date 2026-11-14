package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

/**
 * Shared automatic registration, integrating Mark Proctor's #423/#447 approach with
 * SDK inheritance, preflight validation and cleanup of failed/cancelled additions.
 */
internal object PlainDirectoryModules {
    val defaultExcludes = listOf("node_modules", "dist", ".next")

    data class Registration(val name: String, val path: String, val moduleFile: String) {
        fun describe(): String = "Plain module '$name': $path; module file: $moduleFile; " +
            "excluded: ${defaultExcludes.joinToString(", ")}."
    }

    suspend fun register(
        project: Project, directories: List<File>, moduleFilesDirectory: File, onlyIfEmpty: Boolean = false
    ): List<Registration> {
        val context = currentCoroutineContext()
        context.ensureActive()
        // Resolve and reject unsupported build systems before any module or directory is created.
        val roots = directories.distinctBy { ProjectUtils.canonicalNormalizedPath(it.absolutePath) }.map { dir ->
            require(dir.isDirectory) { "Directory does not exist: $dir" }
            val build = BuildSystemLinker.detectedBuildSystem(project, dir.absolutePath)
            require(build == null) { "$build build found in $dir; use ide_open_project with autoLink=true instead." }
            LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.absolutePath)
                ?: error("Could not resolve directory in VFS: $dir")
        }
        val existing = readAction { ProjectUtils.getModuleContentRoots(project).map(ProjectUtils::canonicalNormalizedPath).toSet() }
        if (onlyIfEmpty && existing.isNotEmpty()) return emptyList()
        if (roots.all { ProjectUtils.canonicalNormalizedPath(it.path) in existing }) return emptyList()
        require(moduleFilesDirectory.isDirectory || moduleFilesDirectory.mkdirs()) {
            "Cannot create module configuration directory: $moduleFilesDirectory"
        }
        return cancellableEdtAction {
            context.ensureActive()
            WriteAction.compute<List<Registration>, RuntimeException> {
                val manager = ModuleManager.getInstance(project)
                val names = manager.modules.map { it.name.lowercase(Locale.ROOT) }.toMutableSet()
                val registered = ProjectUtils.getModuleContentRoots(project).map(ProjectUtils::canonicalNormalizedPath).toMutableSet()
                if (onlyIfEmpty && registered.isNotEmpty()) return@compute emptyList()
                val created = mutableListOf<Module>()
                val moduleFiles = mutableListOf<File>()
                val results = mutableListOf<Registration>()
                try {
                    for (root in roots) {
                        context.ensureActive()
                        check(root.isValid && root.isDirectory) { "Directory disappeared: ${root.path}" }
                        if (!registered.add(ProjectUtils.canonicalNormalizedPath(root.path))) continue
                        val dir = File(root.path)
                        val name = PlainModuleNames.unique(dir.name, dir.parentFile?.name.orEmpty()) {
                            it.lowercase(Locale.ROOT) in names || File(moduleFilesDirectory, "$it.iml").exists()
                        }
                        names.add(name.lowercase(Locale.ROOT))
                        val iml = File(moduleFilesDirectory, "$name.iml").absolutePath
                        moduleFiles.add(File(iml))
                        val module = manager.newModule(iml, ModuleTypeManager.getInstance().defaultModuleType.id)
                        created.add(module)
                        val model = ModuleRootManager.getInstance(module).modifiableModel
                        var committed = false
                        try {
                            model.inheritSdk()
                            val entry = model.addContentEntry(root)
                            defaultExcludes.forEach { entry.addExcludeFolder(VfsUtilCore.pathToUrl(File(dir, it).absolutePath)) }
                            context.ensureActive()
                            model.commit()
                            committed = true
                        } finally {
                            if (!committed && !model.isDisposed) model.dispose()
                        }
                        results.add(Registration(name, root.path, iml))
                    }
                    context.ensureActive()
                    results
                } catch (failure: Throwable) {
                    // All changes happen in one write-safe EDT action. Roll back only our modules;
                    // existing roots and modules remain untouched, including on cancellation.
                    created.asReversed().forEach { module ->
                        try { manager.disposeModule(module) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                    moduleFiles.forEach { file ->
                        if (file.exists() && !file.delete()) {
                            failure.addSuppressed(IllegalStateException("Could not remove module metadata: $file"))
                        }
                    }
                    throw failure
                }
            }
        }
    }
}

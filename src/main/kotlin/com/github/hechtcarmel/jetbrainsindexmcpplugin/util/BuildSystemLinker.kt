package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

sealed class LinkResult {
    data class Linked(val systemName: String) : LinkResult()
    data class AlreadyLinked(val systemName: String) : LinkResult()
    data class NoBuildFile(val checkedSystems: List<String>) : LinkResult()
    data class PluginUnavailable(val systemName: String) : LinkResult()
    data class Failed(val systemName: String, val error: String) : LinkResult()
}

object BuildSystemLinker {
    private val LOG = logger<BuildSystemLinker>()

    private val SYSTEM_IDS = listOf(
        "Maven" to ProjectSystemId("MAVEN"),
        "Gradle" to ProjectSystemId("GRADLE"),
    )

    fun checkLinked(project: Project, projectPath: String): String? {
        for ((name, systemId) in SYSTEM_IDS) {
            val aware = ExternalSystemUnlinkedProjectAware.getInstance(systemId) ?: continue
            if (aware.isLinkedProject(project, projectPath)) return name
        }
        return null
    }

    suspend fun linkBuildSystem(project: Project, projectPath: String): LinkResult {
        val available = mutableListOf<String>()
        for ((name, systemId) in SYSTEM_IDS) {
            val aware = ExternalSystemUnlinkedProjectAware.getInstance(systemId) ?: continue
            available.add(name)
            if (!hasBuildFile(project, aware, projectPath)) continue
            if (aware.isLinkedProject(project, projectPath)) {
                return LinkResult.AlreadyLinked(name)
            }
            TrustedProjects.setProjectTrusted(Path.of(projectPath), true)
            return try {
                aware.linkAndLoadProjectAsync(project, projectPath)
                if (aware.isLinkedProject(project, projectPath)) {
                    LOG.info("Linked $name project at $projectPath")
                    LinkResult.Linked(name)
                } else {
                    LinkResult.Failed(name, "Link call returned but project is still not linked")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Failed to link $name project at $projectPath", e)
                LinkResult.Failed(name, e.message ?: "Unknown error")
            }
        }
        return if (available.isEmpty()) {
            LinkResult.PluginUnavailable("Maven/Gradle")
        } else {
            LinkResult.NoBuildFile(available)
        }
    }

    private suspend fun hasBuildFile(
        project: Project,
        aware: ExternalSystemUnlinkedProjectAware,
        projectPath: String
    ): Boolean = readAction {
        val dir = LocalFileSystem.getInstance().findFileByPath(projectPath) ?: return@readAction false
        dir.children.any { aware.isBuildFile(project, it) }
    }
}

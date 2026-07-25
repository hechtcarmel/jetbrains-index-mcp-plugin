package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.intellij.ide.GeneralSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsShowConfirmationOption
import com.intellij.openapi.vcs.ex.ProjectLevelVcsManagerEx

object HeadlessModeManager {
    private val LOG = logger<HeadlessModeManager>()

    val SNAPSHOT_KEYS = setOf(
        "syncOnFrameActivation", "backgroundSync", "showTipsOnStartup",
        "confirmExit", "checkNeeded"
    )

    val isEnabled: Boolean
        get() = McpSettings.getInstance().headlessMode

    fun enable() {
        val settings = McpSettings.getInstance()
        if (settings.headlessMode) return

        val general = GeneralSettings.getInstance()
        val updates = UpdateSettings.getInstance()

        settings.state.headlessPreToggleSnapshot = mutableMapOf(
            "syncOnFrameActivation" to general.isSyncOnFrameActivation.toString(),
            "backgroundSync" to general.isBackgroundSync.toString(),
            "showTipsOnStartup" to general.isShowTipsOnStartup.toString(),
            "confirmExit" to general.isConfirmExit.toString(),
            "checkNeeded" to updates.isCheckNeeded.toString()
        )

        general.isSyncOnFrameActivation = false
        general.isBackgroundSync = true
        general.isShowTipsOnStartup = false
        general.isConfirmExit = false
        updates.isCheckNeeded = false

        // Snapshot and silence VCS for all currently open projects
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDefault) snapshotAndSilenceVcs(project, settings)
        }

        settings.headlessMode = true
        LOG.info("Headless mode enabled")
    }

    fun disable() {
        val settings = McpSettings.getInstance()
        if (!settings.headlessMode) return

        val snapshot = settings.state.headlessPreToggleSnapshot
        if (snapshot.isNotEmpty()) {
            val general = GeneralSettings.getInstance()
            val updates = UpdateSettings.getInstance()
            snapshot["syncOnFrameActivation"]?.toBooleanStrictOrNull()?.let { general.isSyncOnFrameActivation = it }
            snapshot["backgroundSync"]?.toBooleanStrictOrNull()?.let { general.isBackgroundSync = it }
            snapshot["showTipsOnStartup"]?.toBooleanStrictOrNull()?.let { general.isShowTipsOnStartup = it }
            snapshot["confirmExit"]?.toBooleanStrictOrNull()?.let { general.isConfirmExit = it }
            snapshot["checkNeeded"]?.toBooleanStrictOrNull()?.let { updates.isCheckNeeded = it }
        }

        // Restore VCS settings for all open projects
        val vcsSnapshots = settings.state.headlessVcsSnapshots
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDefault) continue
            val key = project.basePath ?: continue
            val vcsSnapshot = vcsSnapshots[key] ?: continue
            try {
                val vcsManager = ProjectLevelVcsManagerEx.getInstanceEx(project)
                vcsSnapshot["vcsAdd"]?.let { value ->
                    vcsManager.getConfirmation(VcsConfiguration.StandardConfirmation.ADD).value =
                        VcsShowConfirmationOption.Value.valueOf(value)
                }
                vcsSnapshot["vcsRemove"]?.let { value ->
                    vcsManager.getConfirmation(VcsConfiguration.StandardConfirmation.REMOVE).value =
                        VcsShowConfirmationOption.Value.valueOf(value)
                }
            } catch (e: Exception) {
                LOG.warn("Could not restore VCS settings for ${project.name}: ${e.message}")
            }
        }

        settings.state.headlessPreToggleSnapshot.clear()
        settings.state.headlessVcsSnapshots.clear()
        settings.headlessMode = false
        LOG.info("Headless mode disabled — settings restored")
    }

    fun applyProjectSettings(project: Project) {
        if (!isEnabled) return
        snapshotAndSilenceVcs(project, McpSettings.getInstance())
    }

    fun trustProjectPath(path: String) {
        if (!isEnabled) return
        try {
            val clazz = Class.forName("com.intellij.ide.trustedProjects.TrustedProjects")
            val setTrusted = clazz.methods.find {
                it.name == "setProjectTrusted" && it.parameterCount == 2
            }
            if (setTrusted != null) {
                // Try the path-based overload
                val locatedProjectClass = Class.forName("com.intellij.ide.trustedProjects.TrustedProjects\$LocatedProject")
                // Fall back to TrustedPaths if the API shape doesn't match
                trustViaPath(path)
            } else {
                trustViaPath(path)
            }
        } catch (e: Exception) {
            // Fall back to TrustedPaths
            trustViaPath(path)
        }
    }

    private fun trustViaPath(path: String) {
        try {
            val clazz = Class.forName("com.intellij.ide.impl.TrustedPaths")
            val getInstance = clazz.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            val setTrusted = clazz.getMethod("setProjectPathTrusted", java.nio.file.Path::class.java, Boolean::class.java)
            setTrusted.invoke(instance, java.nio.file.Path.of(path), true)
            LOG.info("Trusted project path: $path")
        } catch (e: Exception) {
            LOG.debug("Could not trust project path (API may not be available): ${e.message}")
        }
    }

    fun reapplyIfEnabled() {
        if (!McpSettings.getInstance().headlessMode) return
        ApplicationManager.getApplication().invokeLater {
            val general = GeneralSettings.getInstance()
            val updates = UpdateSettings.getInstance()
            general.isSyncOnFrameActivation = false
            general.isBackgroundSync = true
            general.isShowTipsOnStartup = false
            general.isConfirmExit = false
            updates.isCheckNeeded = false
            LOG.info("Headless mode re-applied on startup")
        }
    }

    private fun snapshotAndSilenceVcs(project: Project, settings: McpSettings) {
        try {
            val vcsManager = ProjectLevelVcsManagerEx.getInstanceEx(project)
            val key = project.basePath ?: return

            val addConfirmation = vcsManager.getConfirmation(VcsConfiguration.StandardConfirmation.ADD)
            val removeConfirmation = vcsManager.getConfirmation(VcsConfiguration.StandardConfirmation.REMOVE)

            // Only snapshot if we haven't already (idempotent for projects opened after enable)
            if (key !in settings.state.headlessVcsSnapshots) {
                settings.state.headlessVcsSnapshots[key] = mutableMapOf(
                    "vcsAdd" to addConfirmation.value.name,
                    "vcsRemove" to removeConfirmation.value.name
                )
            }

            addConfirmation.value = VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY
            removeConfirmation.value = VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY
            LOG.info("Headless VCS settings applied to project: ${project.name}")
        } catch (e: Exception) {
            LOG.warn("Could not apply headless VCS settings to ${project.name}: ${e.message}")
        }
    }
}

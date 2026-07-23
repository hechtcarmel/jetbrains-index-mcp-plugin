package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.intellij.ide.GeneralSettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsShowConfirmationOption
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl

object HeadlessModeManager {

    private val LOG = logger<HeadlessModeManager>()

    val SNAPSHOT_KEYS = setOf(
        "syncOnFrameActivation",
        "backgroundSync",
        "showTipsOnStartup",
        "confirmExit",
        "checkNeeded"
    )

    val isEnabled: Boolean
        get() = McpSettings.getInstance().headlessMode

    fun enable() {
        val settings = McpSettings.getInstance()
        if (settings.headlessMode) return

        val general = GeneralSettings.getInstance()
        val updates = UpdateSettings.getInstance()

        val snapshot = mutableMapOf(
            "syncOnFrameActivation" to general.isSyncOnFrameActivation.toString(),
            "backgroundSync" to general.isBackgroundSync.toString(),
            "showTipsOnStartup" to general.isShowTipsOnStartup.toString(),
            "confirmExit" to general.isConfirmExit.toString(),
            "checkNeeded" to updates.isCheckNeeded.toString()
        )
        settings.state.headlessPreToggleSnapshot = snapshot

        general.isSyncOnFrameActivation = false
        general.isBackgroundSync = true
        general.isShowTipsOnStartup = false
        general.isConfirmExit = false
        updates.isCheckNeeded = false

        trustUserHome()

        settings.headlessMode = true
        LOG.info("Headless mode enabled — ${snapshot.size} settings snapshotted")
    }

    fun disable() {
        val settings = McpSettings.getInstance()
        if (!settings.headlessMode) return

        val snapshot = settings.state.headlessPreToggleSnapshot
        if (snapshot.isNotEmpty()) {
            val general = GeneralSettings.getInstance()
            val updates = UpdateSettings.getInstance()

            snapshot["syncOnFrameActivation"]?.toBooleanStrictOrNull()?.let {
                general.isSyncOnFrameActivation = it
            }
            snapshot["backgroundSync"]?.toBooleanStrictOrNull()?.let {
                general.isBackgroundSync = it
            }
            snapshot["showTipsOnStartup"]?.toBooleanStrictOrNull()?.let {
                general.isShowTipsOnStartup = it
            }
            snapshot["confirmExit"]?.toBooleanStrictOrNull()?.let {
                general.isConfirmExit = it
            }
            snapshot["checkNeeded"]?.toBooleanStrictOrNull()?.let {
                updates.isCheckNeeded = it
            }
        }

        settings.state.headlessPreToggleSnapshot.clear()
        settings.headlessMode = false
        LOG.info("Headless mode disabled — settings restored")
    }

    fun applyProjectSettings(project: Project) {
        if (!isEnabled) return
        try {
            val vcsManager = ProjectLevelVcsManagerImpl.getInstanceImpl(project)
            for (vcs in vcsManager.allActiveVcss) {
                vcsManager.getStandardConfirmation(
                    VcsConfiguration.StandardConfirmation.ADD, vcs
                ).value = VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY

                vcsManager.getStandardConfirmation(
                    VcsConfiguration.StandardConfirmation.REMOVE, vcs
                ).value = VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY
            }
            LOG.info("Headless VCS settings applied to project: ${project.name}")
        } catch (e: Exception) {
            LOG.warn("Could not apply headless VCS settings to ${project.name}: ${e.message}")
        }
    }

    private fun trustUserHome() {
        try {
            val userHome = System.getProperty("user.home") ?: return
            val clazz = Class.forName("com.intellij.ide.impl.TrustedPathsSettings")
            val getInstance = clazz.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            val addTrustedPath = clazz.getMethod("addTrustedPath", String::class.java)
            addTrustedPath.invoke(instance, userHome)
            LOG.info("Trusted user home: $userHome")
        } catch (e: Exception) {
            LOG.debug("Could not trust user home (may not be available): ${e.message}")
        }
    }

    fun reapplyIfEnabled() {
        if (McpSettings.getInstance().headlessMode) {
            val general = GeneralSettings.getInstance()
            val updates = UpdateSettings.getInstance()
            general.isSyncOnFrameActivation = false
            general.isBackgroundSync = true
            general.isShowTipsOnStartup = false
            general.isConfirmExit = false
            updates.isCheckNeeded = false
            trustUserHome()
            LOG.info("Headless mode re-applied on startup")
        }
    }
}

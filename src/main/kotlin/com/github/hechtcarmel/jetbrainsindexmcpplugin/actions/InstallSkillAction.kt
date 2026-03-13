package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.SkillInstaller
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Action that allows users to install or export the companion skill
 * that guides AI coding agents on using IDE MCP tools effectively.
 *
 * Shows a popup with two sections:
 * 1. "Install Now" - Extracts skill to project's .claude/skills/ directory
 * 2. "Save as File" - Exports skill as .skill or .zip file
 */
class InstallSkillAction : AnAction() {

    init {
        templatePresentation.text = "Get Companion Skill"
        templatePresentation.description = "Install or export the companion skill for AI coding agents"
        templatePresentation.icon = AllIcons.Actions.Download
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = true
        e.presentation.text = "Get Companion Skill"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val popup = createPopup(project)
        popup.showInBestPositionFor(e.dataContext)
    }

    private fun createPopup(project: Project?) = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(createPopupContent(project), null)
        .setTitle("Get Companion Skill")
        .setMovable(false)
        .setResizable(false)
        .setRequestFocus(true)
        .createPopup()

    private fun createPopupContent(project: Project?): JPanel {
        val mainPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }

        mainPanel.add(createSectionHeader("Install Now"))
        mainPanel.add(createInstallSection(project))

        mainPanel.add(createSeparator())

        mainPanel.add(createSectionHeader("Save as File"))
        mainPanel.add(createSaveSection(project))

        return mainPanel
    }

    private fun createSectionHeader(title: String): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 4, 8)
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = JBColor.GRAY
            }, BorderLayout.WEST)
        }
    }

    private fun createSeparator(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JPanel().apply {
                preferredSize = JBUI.size(0, 1)
                background = JBColor(Gray._220, Gray._60)
            }, BorderLayout.CENTER)
        }
    }

    private fun createInstallSection(project: Project?): JPanel {
        val listModel = DefaultListModel<SkillAction>().apply {
            addElement(SkillAction(
                "Claude Code (this project)",
                "Install to .claude/skills/ in project root",
                AllIcons.Actions.Execute,
                SkillActionType.INSTALL_CLAUDE_CODE
            ))
        }

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SkillActionRenderer()
            border = JBUI.Borders.empty(0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val item = model.getElementAt(index)
                        handleAction(item.type, project)
                        JBPopupFactory.getInstance().getChildFocusedPopup(this@apply)?.cancel()
                    }
                }
            })
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(list, BorderLayout.CENTER)
        }
    }

    private fun createSaveSection(project: Project?): JPanel {
        val listModel = DefaultListModel<SkillAction>().apply {
            addElement(SkillAction(
                "Save as .skill",
                "Portable skill package",
                AllIcons.Actions.MenuSaveall,
                SkillActionType.SAVE_SKILL
            ))
            addElement(SkillAction(
                "Save as .zip",
                "Standard zip archive",
                AllIcons.Actions.MenuSaveall,
                SkillActionType.SAVE_ZIP
            ))
        }

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SkillActionRenderer()
            border = JBUI.Borders.empty(0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val item = model.getElementAt(index)
                        handleAction(item.type, project)
                        JBPopupFactory.getInstance().getChildFocusedPopup(this@apply)?.cancel()
                    }
                }
            })
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(list, BorderLayout.CENTER)
        }
    }

    private fun handleAction(type: SkillActionType, project: Project?) {
        when (type) {
            SkillActionType.INSTALL_CLAUDE_CODE -> installForClaudeCode(project)
            SkillActionType.SAVE_SKILL -> saveAsFile(project, "skill")
            SkillActionType.SAVE_ZIP -> saveAsFile(project, "zip")
        }
    }

    private fun installForClaudeCode(project: Project?) {
        val basePath = project?.basePath
        if (basePath == null) {
            showNotification(project, "Installation Failed", "No project is open.", NotificationType.ERROR)
            return
        }

        val skillsDir = File(basePath, ".claude/skills")
        val result = SkillInstaller.installToDirectory(skillsDir)
        if (result != null) {
            showNotification(
                project,
                "Companion Skill Installed",
                "Installed to ${result.absolutePath}",
                NotificationType.INFORMATION
            )
        } else {
            showNotification(
                project,
                "Installation Failed",
                "Failed to install companion skill. Check IDE logs for details.",
                NotificationType.ERROR
            )
        }
    }

    private fun saveAsFile(project: Project?, extension: String) {
        val descriptor = FileSaverDescriptor(
            "Save Companion Skill",
            "Save the IDE Index MCP companion skill",
            extension
        )

        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, "ide-index-mcp")

        wrapper?.let { vfw ->
            val file = vfw.file
            val success = SkillInstaller.writeZip(file)
            if (success) {
                showNotification(
                    project,
                    "Companion Skill Saved",
                    "Saved to ${file.absolutePath}",
                    NotificationType.INFORMATION
                )
            } else {
                showNotification(
                    project,
                    "Save Failed",
                    "Failed to save companion skill. Check IDE logs for details.",
                    NotificationType.ERROR
                )
            }
        }
    }

    private fun showNotification(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    private enum class SkillActionType {
        INSTALL_CLAUDE_CODE, SAVE_SKILL, SAVE_ZIP
    }

    private data class SkillAction(
        val name: String,
        val description: String,
        val icon: javax.swing.Icon,
        val type: SkillActionType
    )

    private class SkillActionRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(6, 8)
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background
            }

            val item = value as? SkillAction ?: return panel

            val nameLabel = JBLabel(item.name).apply {
                font = font.deriveFont(Font.PLAIN, 13f)
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }

            val descLabel = JBLabel(item.description).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.GRAY
            }

            val textPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(nameLabel)
                add(descLabel)
            }

            val iconLabel = JBLabel(item.icon).apply {
                border = JBUI.Borders.emptyRight(8)
            }

            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textPanel, BorderLayout.CENTER)

            return panel
        }
    }
}

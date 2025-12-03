package com.github.hechtcarmel.jetbrainsindexmcpplugin.actions

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClientConfigGenerator.ClientType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
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
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Action that allows users to install MCP on coding agents or copy client configurations.
 *
 * Shows a popup with two sections:
 * 1. "Install Now" - Runs the installation command directly (Claude Code)
 * 2. "Copy Configuration" - Copies config to clipboard (other clients)
 */
class CopyClientConfigAction : AnAction() {

    init {
        templatePresentation.text = "Install on Coding Agents"
        templatePresentation.description = "Install MCP server on coding agents or copy configuration"
        templatePresentation.icon = AllIcons.FileTypes.Config
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = true
        e.presentation.text = "Install on Coding Agents"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val popup = createInstallPopup(project)
        popup.showInBestPositionFor(e.dataContext)
    }

    private fun createInstallPopup(project: Project?) = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(createPopupContent(project), null)
        .setTitle("Install on Coding Agents")
        .setMovable(false)
        .setResizable(false)
        .setRequestFocus(true)
        .createPopup()

    private fun createPopupContent(project: Project?): JPanel {
        val mainPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }

        // Section 1: Install Now (clients with install commands)
        mainPanel.add(createSectionHeader("Install Now"))
        mainPanel.add(createInstallNowSection(project))

        // Separator
        mainPanel.add(createSeparator())

        // Section 2: Copy Configurations (specific clients)
        mainPanel.add(createSectionHeader("Copy Configuration"))
        mainPanel.add(createCopyConfigSection(project))

        // Separator
        mainPanel.add(createSeparator())

        // Section 3: Generic MCP Config
        mainPanel.add(createSectionHeader("Generic MCP Config"))
        mainPanel.add(createGenericConfigSection(project))

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

    private fun createInstallNowSection(project: Project?): JPanel {
        val installableClients = ClientConfigGenerator.getInstallableClients()
        val listModel = DefaultListModel<InstallItem>().apply {
            installableClients.forEach { client ->
                addElement(InstallItem(client, "Run installation command"))
            }
        }

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = InstallItemRenderer()
            border = JBUI.Borders.empty(0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val item = model.getElementAt(index)
                        runInstallCommand(item.clientType, project)
                        // Close the popup
                        JBPopupFactory.getInstance().getChildFocusedPopup(this@apply)?.cancel()
                    }
                }
            })
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(list, BorderLayout.CENTER)
        }
    }

    private fun createCopyConfigSection(project: Project?): JPanel {
        val copyClients = ClientConfigGenerator.getAvailableClients()

        val listModel = DefaultListModel<CopyItem>().apply {
            copyClients.forEach { client ->
                addElement(CopyItem(client))
            }
        }

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = CopyItemRenderer()
            border = JBUI.Borders.empty(0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val item = model.getElementAt(index)
                        copyConfigToClipboard(item.clientType, project)
                        // Close the popup
                        JBPopupFactory.getInstance().getChildFocusedPopup(this@apply)?.cancel()
                    }
                }
            })
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(list, BorderLayout.CENTER)
        }
    }

    private fun createGenericConfigSection(project: Project?): JPanel {
        val listModel = DefaultListModel<GenericConfigItem>().apply {
            addElement(GenericConfigItem("Standard SSE", "For clients with native SSE support", GenericConfigType.STANDARD_SSE))
            addElement(GenericConfigItem("Via mcp-remote", "For clients without SSE support", GenericConfigType.MCP_REMOTE))
        }

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = GenericConfigItemRenderer()
            border = JBUI.Borders.empty(0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val item = model.getElementAt(index)
                        copyGenericConfig(item.type, project)
                        JBPopupFactory.getInstance().getChildFocusedPopup(this@apply)?.cancel()
                    }
                }
            })
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(list, BorderLayout.CENTER)
        }
    }

    private fun runInstallCommand(clientType: ClientType, project: Project?) {
        val command = ClientConfigGenerator.generateInstallCommand(clientType)
            ?: return showNotification(project, "Error", "No install command for ${clientType.displayName}", NotificationType.ERROR)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val commandLine = GeneralCommandLine("sh", "-c", command)
                    .withRedirectErrorStream(true)

                val handler = OSProcessHandler(commandLine)
                val output = StringBuilder()

                handler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        output.append(event.text)
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        ApplicationManager.getApplication().invokeLater {
                            val exitCode = event.exitCode
                            if (exitCode == 0) {
                                showNotification(
                                    project,
                                    "Installation Successful",
                                    "Ran command:\n$command",
                                    NotificationType.INFORMATION
                                )
                            } else {
                                showNotification(
                                    project,
                                    "Installation Failed",
                                    "Command failed (exit code $exitCode):\n$command\n\nOutput: ${output.toString().take(500)}",
                                    NotificationType.ERROR
                                )
                            }
                        }
                    }
                })

                handler.startNotify()
                handler.waitFor()
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    showNotification(
                        project,
                        "Installation Failed",
                        "Failed to run command:\n$command\n\nError: ${e.message}",
                        NotificationType.ERROR
                    )
                }
            }
        }
    }

    private fun copyConfigToClipboard(clientType: ClientType, project: Project?) {
        val config = ClientConfigGenerator.generateConfig(clientType)
        val locationHint = ClientConfigGenerator.getConfigLocationHint(clientType)

        CopyPasteManager.getInstance().setContents(StringSelection(config))

        showNotification(
            project,
            "Configuration Copied",
            "${clientType.displayName} configuration copied to clipboard.\n\n$locationHint",
            NotificationType.INFORMATION
        )
    }

    private fun copyGenericConfig(type: GenericConfigType, project: Project?) {
        val (config, hint) = when (type) {
            GenericConfigType.STANDARD_SSE -> {
                ClientConfigGenerator.generateStandardSseConfig() to ClientConfigGenerator.getStandardSseHint()
            }
            GenericConfigType.MCP_REMOTE -> {
                ClientConfigGenerator.generateMcpRemoteConfig() to ClientConfigGenerator.getMcpRemoteHint()
            }
        }

        CopyPasteManager.getInstance().setContents(StringSelection(config))

        showNotification(
            project,
            "Configuration Copied",
            "${type.displayName} configuration copied to clipboard.\n\n$hint",
            NotificationType.INFORMATION
        )
    }

    private fun showNotification(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    private enum class GenericConfigType(val displayName: String) {
        STANDARD_SSE("Standard SSE"),
        MCP_REMOTE("mcp-remote (stdio)")
    }

    private data class InstallItem(val clientType: ClientType, val description: String)

    private data class CopyItem(val clientType: ClientType)

    private data class GenericConfigItem(val name: String, val description: String, val type: GenericConfigType)

    private class InstallItemRenderer : DefaultListCellRenderer() {
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
                background = if (isSelected) {
                    list.selectionBackground
                } else {
                    list.background
                }
            }

            val item = value as? InstallItem ?: return panel

            val nameLabel = JBLabel(item.clientType.displayName).apply {
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

            val iconLabel = JBLabel(AllIcons.Actions.Execute).apply {
                border = JBUI.Borders.emptyRight(8)
            }

            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textPanel, BorderLayout.CENTER)

            return panel
        }
    }

    private class CopyItemRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                isOpaque = true
                background = if (isSelected) {
                    list.selectionBackground
                } else {
                    list.background
                }
            }

            val item = value as? CopyItem ?: return panel

            val nameLabel = JBLabel(item.clientType.displayName).apply {
                font = font.deriveFont(Font.PLAIN, 13f)
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }

            panel.add(nameLabel, BorderLayout.CENTER)

            return panel
        }
    }

    private class GenericConfigItemRenderer : DefaultListCellRenderer() {
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
                background = if (isSelected) {
                    list.selectionBackground
                } else {
                    list.background
                }
            }

            val item = value as? GenericConfigItem ?: return panel

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

            val iconLabel = JBLabel(AllIcons.Actions.Copy).apply {
                border = JBUI.Borders.emptyRight(8)
            }

            panel.add(iconLabel, BorderLayout.WEST)
            panel.add(textPanel, BorderLayout.CENTER)

            return panel
        }
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.ClearHistoryAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.CopyClientConfigAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.CopyServerUrlAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.ExportHistoryAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.InstallSkillAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.actions.RefreshAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettingsConfigurable
import com.github.hechtcarmel.jetbrainsindexmcpplugin.icons.McpIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel

class McpToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpToolWindowPanel(project)

        // Left toolbar actions (utility buttons) - settings icon moved to separate component with label
        val leftActionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            addSeparator()
            add(CopyServerUrlAction())
            addSeparator()
            add(ClearHistoryAction())
            add(ExportHistoryAction())
        }

        val leftToolbar = ActionManager.getInstance().createActionToolbar(
            "McpServerToolbarLeft",
            leftActionGroup,
            true
        )
        leftToolbar.targetComponent = panel

        // Settings link with label "Change port, disable tools"
        val settingsPanel = createSettingsPanel(project)

        // Create prominent "Install on Coding Agents" button with text
        val installAction = CopyClientConfigAction()
        val installButton = JButton("Install on Coding Agents").apply {
            icon = AllIcons.FileTypes.Config
            toolTipText = "Copy MCP client configuration to clipboard"
            isFocusable = false
            addActionListener {
                val dataContext = com.intellij.openapi.actionSystem.DataContext { dataId ->
                    when (dataId) {
                        com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> project
                        else -> null
                    }
                }
                val event = AnActionEvent.createEvent(
                    installAction,
                    dataContext,
                    null,
                    ActionPlaces.TOOLWINDOW_CONTENT,
                    ActionUiKind.NONE,
                    null
                )
                installAction.actionPerformed(event)
            }
        }

        val skillAction = InstallSkillAction()
        val skillButton = JButton(McpBundle.message("toolWindow.getSkill")).apply {
            icon = AllIcons.Actions.Download
            toolTipText = McpBundle.message("toolWindow.getSkill.tooltip")
            isFocusable = false
            addActionListener {
                val dataContext = com.intellij.openapi.actionSystem.DataContext { dataId ->
                    when (dataId) {
                        com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> project
                        else -> null
                    }
                }
                val event = AnActionEvent.createEvent(
                    skillAction,
                    dataContext,
                    null,
                    ActionPlaces.TOOLWINDOW_CONTENT,
                    ActionUiKind.NONE,
                    null
                )
                skillAction.actionPerformed(event)
            }
        }

        // Right panel with external links + install button
        val rightPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(createExternalLink(
                AllIcons.Vcs.Vendors.Github,
                McpBundle.message("footer.github"),
                McpBundle.message("footer.github.tooltip"),
                "https://github.com/hechtcarmel/jetbrains-index-mcp-plugin"
            ))
            add(createExternalLink(
                McpIcons.DebuggerMcp,
                McpBundle.message("footer.debugger"),
                McpBundle.message("footer.debugger.tooltip"),
                "https://plugins.jetbrains.com/plugin/29233-debugger-mcp-server"
            ))
            add(createExternalLink(
                McpIcons.BuyMeACoffee,
                McpBundle.message("footer.buymeacoffee"),
                McpBundle.message("footer.buymeacoffee.tooltip"),
                "https://buymeacoffee.com/hechtcarmel"
            ))
            add(createToolbarSeparator())
            add(skillButton)
            add(installButton)
        }

        // Left panel: toolbar + settings link inline
        val leftPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(leftToolbar.component)
            add(settingsPanel)
        }

        // Create toolbar panel with left actions + settings on left, install button on right
        val toolbarPanel = JPanel(BorderLayout()).apply {
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }

        // Create wrapper panel with toolbar at top and main panel in center
        val wrapperPanel = JPanel(BorderLayout()).apply {
            add(toolbarPanel, BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(
            wrapperPanel,
            McpBundle.message("toolWindow.title"),
            false
        )
        toolWindow.contentManager.addContent(content)

        // Also add quick actions to title bar
        toolWindow.setTitleActions(listOf(CopyServerUrlAction(), RefreshAction()))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    /**
     * Creates a settings panel with an icon and descriptive text.
     */
    private fun createSettingsPanel(project: Project): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            border = JBUI.Borders.empty(2, 8, 2, 0)

            // Settings icon
            val settingsIcon = JBLabel(AllIcons.General.Settings).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Open MCP Server settings"
            }

            // Label text - always use HTML to prevent layout shift on hover
            val settingsText = McpBundle.message("toolWindow.settingsLabel")
            val settingsLabel = JBLabel("<html>$settingsText</html>").apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.BLUE
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Open MCP Server settings"
            }

            // Click handler for both icon and label
            val clickHandler = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, McpSettingsConfigurable::class.java)
                }
                override fun mouseEntered(e: MouseEvent) {
                    settingsLabel.text = "<html><u>$settingsText</u></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    settingsLabel.text = "<html>$settingsText</html>"
                }
            }

            settingsIcon.addMouseListener(clickHandler)
            settingsLabel.addMouseListener(clickHandler)

            add(settingsIcon)
            add(settingsLabel)
        }
    }

    private fun createToolbarSeparator(): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(JBLabel("|").apply {
                foreground = JBColor.GRAY
            })
        }
    }

    private fun createExternalLink(icon: Icon, text: String, tooltip: String, url: String): JPanel {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            val linkIcon = JBLabel(icon).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = tooltip
            }

            val linkLabel = JBLabel("<html>$text</html>").apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.BLUE
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = tooltip
            }

            val clickHandler = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    BrowserUtil.browse(url)
                }
                override fun mouseEntered(e: MouseEvent) {
                    linkLabel.text = "<html><u>$text</u></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    linkLabel.text = "<html>$text</html>"
                }
            }

            linkIcon.addMouseListener(clickHandler)
            linkLabel.addMouseListener(clickHandler)

            add(linkIcon)
            add(linkLabel)
        }
    }
}

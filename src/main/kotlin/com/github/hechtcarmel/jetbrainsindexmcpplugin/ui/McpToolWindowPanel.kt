package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandFilter
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryListener
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

class McpToolWindowPanel(
    private val project: Project
) : JBPanel<McpToolWindowPanel>(BorderLayout()), Disposable, CommandHistoryListener {

    private val serverStatusPanel: ServerStatusPanel
    private val filterToolbar: FilterToolbar
    private val historyListModel = DefaultListModel<CommandEntry>()
    private val historyList: JBList<CommandEntry>
    private val detailsArea: JBTextArea
    private val historyService: CommandHistoryService
    private var currentFilter = CommandFilter()

    init {
        // Header panel containing server status and filter toolbar
        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout())

        // Server status panel at top
        serverStatusPanel = ServerStatusPanel(project)
        headerPanel.add(serverStatusPanel, BorderLayout.NORTH)

        // Filter toolbar below status
        filterToolbar = FilterToolbar { filter ->
            currentFilter = filter
            refreshHistory()
        }
        headerPanel.add(filterToolbar, BorderLayout.SOUTH)

        add(headerPanel, BorderLayout.NORTH)

        // Create command history list
        historyList = JBList(historyListModel).apply {
            cellRenderer = CommandListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    selectedValue?.let { showCommandDetails(it) }
                }
            }
        }

        // Create details area
        detailsArea = JBTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
        }

        // Create splitter with history list on top, details on bottom
        val splitter = JBSplitter(true, 0.6f).apply {
            firstComponent = JBScrollPane(historyList)
            secondComponent = JBScrollPane(detailsArea)
        }

        add(splitter, BorderLayout.CENTER)

        // Register listener and load history
        historyService = CommandHistoryService.getInstance(project)
        historyService.addListener(this)
        refreshHistory()
    }

    fun refresh() {
        serverStatusPanel.refresh()
        refreshHistory()
    }

    private fun refreshHistory() {
        historyListModel.clear()
        val entries = if (currentFilter.isEmpty()) {
            historyService.entries
        } else {
            historyService.getFilteredHistory(currentFilter)
        }
        entries.forEach { historyListModel.addElement(it) }
    }

    private fun showCommandDetails(entry: CommandEntry) {
        val sb = StringBuilder()
        sb.appendLine("Tool: ${entry.toolName}")
        sb.appendLine("Status: ${entry.status}")
        sb.appendLine("Timestamp: ${entry.timestamp}")
        entry.durationMs?.let { sb.appendLine("Duration: ${it}ms") }
        sb.appendLine()
        sb.appendLine("Parameters:")
        sb.appendLine(entry.parameters.toString())
        sb.appendLine()

        if (entry.status == CommandStatus.SUCCESS && entry.result != null) {
            sb.appendLine("Result:")
            sb.appendLine(entry.result)
        } else if (entry.status == CommandStatus.ERROR && entry.error != null) {
            sb.appendLine("Error:")
            sb.appendLine(entry.error)
        }

        entry.affectedFiles?.let { files ->
            if (files.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Affected Files:")
                files.forEach { sb.appendLine("  - $it") }
            }
        }

        detailsArea.text = sb.toString()
        detailsArea.caretPosition = 0
    }

    override fun onCommandAdded(entry: CommandEntry) {
        historyListModel.add(0, entry)
        if (McpSettings.getInstance().autoScroll) {
            historyList.selectedIndex = 0
        }
    }

    override fun onCommandUpdated(entry: CommandEntry) {
        val index = (0 until historyListModel.size).firstOrNull {
            historyListModel.getElementAt(it).id == entry.id
        }
        index?.let {
            historyListModel.setElementAt(entry, it)
            if (historyList.selectedIndex == it) {
                showCommandDetails(entry)
            }
        }
    }

    override fun onHistoryCleared() {
        historyListModel.clear()
        detailsArea.text = ""
    }

    override fun dispose() {
        historyService.removeListener(this)
    }
}

class ServerStatusPanel(private val project: Project) : JBPanel<ServerStatusPanel>(BorderLayout()) {

    private val statusLabel: JBLabel
    private val urlLabel: JBLabel
    private val projectLabel: JBLabel

    init {
        border = JBUI.Borders.empty(8)

        val leftPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0))

        statusLabel = JBLabel().apply {
            icon = null
            font = font.deriveFont(Font.BOLD)
        }

        urlLabel = JBLabel().apply {
            foreground = JBColor.BLUE
        }

        projectLabel = JBLabel()

        leftPanel.add(statusLabel)
        leftPanel.add(urlLabel)
        leftPanel.add(projectLabel)

        add(leftPanel, BorderLayout.WEST)

        refresh()
    }

    fun refresh() {
        try {
            val mcpService = McpServerService.getInstance()
            val url = mcpService.getServerUrl()

            statusLabel.text = "MCP Server Running"
            statusLabel.foreground = JBColor(0x59A869, 0x59A869)
            urlLabel.text = url
            projectLabel.text = "| Project: ${project.name}"
        } catch (e: Exception) {
            statusLabel.text = "MCP Server Error"
            statusLabel.foreground = JBColor.RED
            urlLabel.text = ""
            projectLabel.text = e.message ?: ""
        }
    }
}

class CommandListCellRenderer : ListCellRenderer<CommandEntry> {

    private val panel = JPanel(BorderLayout())
    private val timestampLabel = JBLabel()
    private val toolNameLabel = JBLabel()
    private val statusLabel = JBLabel()

    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        panel.border = JBUI.Borders.empty(4, 8)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        leftPanel.isOpaque = false
        leftPanel.add(timestampLabel)
        leftPanel.add(toolNameLabel)

        panel.add(leftPanel, BorderLayout.WEST)
        panel.add(statusLabel, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out CommandEntry>,
        value: CommandEntry,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        // Timestamp
        timestampLabel.text = value.timestamp.atZone(ZoneId.systemDefault()).format(formatter)
        timestampLabel.foreground = JBColor.GRAY

        // Tool name
        toolNameLabel.text = value.toolName
        toolNameLabel.font = toolNameLabel.font.deriveFont(Font.BOLD)

        // Status with color
        statusLabel.text = value.status.name
        statusLabel.foreground = when (value.status) {
            CommandStatus.SUCCESS -> JBColor(0x59A869, 0x59A869)
            CommandStatus.ERROR -> JBColor(0xE05555, 0xE05555)
            CommandStatus.PENDING -> JBColor(0xD9A343, 0xD9A343)
        }

        // Background
        panel.background = if (isSelected) {
            list.selectionBackground
        } else {
            list.background
        }

        toolNameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

        return panel
    }
}

class FilterToolbar(
    private val onFilterChanged: (CommandFilter) -> Unit
) : JBPanel<FilterToolbar>(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    private val toolNameComboBox: JComboBox<String>
    private val statusComboBox: JComboBox<String>
    private val searchField: SearchTextField

    init {
        border = JBUI.Borders.empty(4, 8)

        // Tool name filter
        add(JBLabel("Tool:"))
        toolNameComboBox = JComboBox(arrayOf(
            "All",
            "find_usages",
            "find_definition",
            "type_hierarchy",
            "call_hierarchy",
            "find_implementations",
            "get_symbol_info",
            "get_completions",
            "get_inspections",
            "get_quick_fixes",
            "apply_quick_fix",
            "get_index_status",
            "get_file_structure",
            "get_project_structure",
            "get_dependencies"
        )).apply {
            addActionListener { notifyFilterChanged() }
        }
        add(toolNameComboBox)

        // Status filter
        add(JBLabel("Status:"))
        statusComboBox = JComboBox(arrayOf("All", "SUCCESS", "ERROR", "PENDING")).apply {
            addActionListener { notifyFilterChanged() }
        }
        add(statusComboBox)

        // Search field
        add(JBLabel("Search:"))
        searchField = SearchTextField().apply {
            textEditor.columns = 15
            addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) {
                    notifyFilterChanged()
                }
            })
        }
        add(searchField)
    }

    private fun notifyFilterChanged() {
        val toolName = toolNameComboBox.selectedItem as? String
        val status = statusComboBox.selectedItem as? String
        val searchText = searchField.text.takeIf { it.isNotBlank() }

        val filter = CommandFilter(
            toolName = if (toolName == "All") null else toolName,
            status = if (status == "All") null else CommandStatus.valueOf(status!!),
            searchText = searchText
        )
        onFilterChanged(filter)
    }
}

package com.github.hechtcarmel.jetbrainsindexmcpplugin.ui

import com.github.hechtcarmel.jetbrainsindexmcpplugin.services.ToolUsageTracker
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.text.NumberFormat
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

class UsageStatsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(
        arrayOf("Tool", "Calls", "Total Chars", "Avg Chars"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(tableModel)
    private val totalLabel = JBLabel("No calls recorded")
    private val fmt = NumberFormat.getIntegerInstance()

    init {
        val headerPanel = JPanel(BorderLayout()).apply {
            add(totalLabel, BorderLayout.WEST)
            add(JButton("Reset").apply {
                addActionListener {
                    ToolUsageTracker.getInstance(project).reset()
                    refresh()
                }
            }, BorderLayout.EAST)
        }

        add(headerPanel, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)

        refresh()
    }

    fun refresh() {
        val tracker = ToolUsageTracker.getInstance(project)
        val stats = tracker.getStats()

        tableModel.rowCount = 0
        for ((toolName, toolStats) in stats.entries.sortedByDescending { it.value.totalChars.get() }) {
            tableModel.addRow(
                arrayOf(
                    toolName,
                    fmt.format(toolStats.calls.get()),
                    fmt.format(toolStats.totalChars.get()),
                    fmt.format(toolStats.averageChars)
                )
            )
        }

        val totalCalls = tracker.getTotalCalls()
        val totalChars = tracker.getTotalChars()
        totalLabel.text = if (totalCalls > 0) {
            "Total: ${fmt.format(totalCalls)} calls, ${fmt.format(totalChars)} chars"
        } else {
            "No calls recorded"
        }
    }
}

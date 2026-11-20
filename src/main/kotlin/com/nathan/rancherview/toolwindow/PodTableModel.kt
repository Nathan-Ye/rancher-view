package com.nathan.rancherview.toolwindow

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.nathan.rancherview.model.PodInfo
import com.nathan.rancherview.util.RancherBundle
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class PodTableModel : AbstractTableModel() {

    private var pods: List<PodInfo> = emptyList()
    private val columnKeys = arrayOf(
        "column.name", "column.status", "column.ready", "column.restarts", "column.age", "column.node", "column.image"
    )

    override fun getRowCount(): Int = pods.size

    override fun getColumnCount(): Int = columnKeys.size

    override fun getColumnName(column: Int): String = RancherBundle.message(columnKeys[column])

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val pod = pods[rowIndex]
        return when (columnIndex) {
            0 -> pod.name
            1 -> pod.status
            2 -> pod.ready
            3 -> pod.restarts
            4 -> pod.age
            5 -> pod.node
            else -> pod.image
        }
    }

    fun podAt(rowIndex: Int): PodInfo? = pods.getOrNull(rowIndex)

    fun setPods(newPods: List<PodInfo>) {
        pods = newPods
        fireTableDataChanged()
    }

    fun clear() {
        pods = emptyList()
        fireTableDataChanged()
    }
}

class PodStatusCellRenderer(private val model: PodTableModel) : DefaultTableCellRenderer() {

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
        label.border = JBUI.Borders.empty(0, 2)
        val pod = model.podAt((table as? JBTable)?.convertRowIndexToModel(row) ?: row) ?: return label
        val color = when {
            pod.failed -> STATUS_ERROR
            pod.terminating -> STATUS_MUTED
            pod.status == "Pending" || pod.status == "ContainerCreating" -> STATUS_WARN
            pod.status == "Running" -> STATUS_OK
            else -> STATUS_MUTED
        }
        label.icon = dotIcon(color)
        label.iconTextGap = 4
        label.text = pod.status.ifBlank { "Unknown" }
        if (!isSelected) label.foreground = UIUtil.getTableForeground()
        return label
    }
}

private class DotIcon(private val color: Color) : Icon {

    override fun getIconWidth(): Int = 9

    override fun getIconHeight(): Int = 9

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x + 1, y + 1, 7, 7)
        } finally {
            g2.dispose()
        }
    }
}

private val dotIcons = HashMap<Color, Icon>()

private fun dotIcon(color: Color): Icon = dotIcons.getOrPut(color) { DotIcon(color) }

private val STATUS_OK = Color(0x43A047)
private val STATUS_WARN = Color(0xF9A825)
private val STATUS_ERROR = Color(0xE53935)
private val STATUS_MUTED = Color(0x9E9E9E)

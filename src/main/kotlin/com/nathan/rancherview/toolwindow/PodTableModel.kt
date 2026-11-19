package com.nathan.rancherview.toolwindow

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.nathan.rancherview.model.PodInfo
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class PodTableModel : AbstractTableModel() {

    private var pods: List<PodInfo> = emptyList()
    private val columns = arrayOf("名称", "状态", "就绪", "重启", "年龄", "节点", "镜像")

    override fun getRowCount(): Int = pods.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

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
            pod.failed -> "#E53935"
            pod.terminating -> "#9E9E9E"
            pod.status == "Pending" || pod.status == "ContainerCreating" -> "#F9A825"
            pod.status == "Running" -> "#43A047"
            else -> "#9E9E9E"
        }
        label.text = "<html><span style='color:$color'>●</span> ${escape(pod.status)}</html>"
        if (!isSelected) label.foreground = table.foreground
        return label
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

package com.nathan.rancherview.toolwindow

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.nathan.rancherview.model.DeployPhase
import com.nathan.rancherview.model.ServiceInfo
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class ServiceTableModel : AbstractTableModel() {

    private var services: List<ServiceInfo> = emptyList()
    private val columns = arrayOf("服务", "状态", "就绪", "更新", "Pods", "镜像", "年龄")

    override fun getRowCount(): Int = services.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val service = services[rowIndex]
        return when (columnIndex) {
            0 -> service.name
            1 -> service.phaseText
            2 -> service.readyRatio
            3 -> "${service.updatedReplicas}/${service.desiredReplicas}"
            4 -> service.pods.size
            5 -> service.imageTag
            else -> service.age
        }
    }

    fun serviceAt(rowIndex: Int): ServiceInfo? = services.getOrNull(rowIndex)

    fun indexOf(name: String): Int = services.indexOfFirst { it.name == name }

    fun setServices(newServices: List<ServiceInfo>) {
        services = newServices
        fireTableDataChanged()
    }

    fun clear() {
        services = emptyList()
        fireTableDataChanged()
    }
}

class ServiceStatusCellRenderer(private val model: ServiceTableModel) : DefaultTableCellRenderer() {

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
        val service = model.serviceAt((table as? JBTable)?.convertRowIndexToModel(row) ?: row) ?: return label
        val color = when (service.phase) {
            DeployPhase.READY -> "#43A047"
            DeployPhase.ROLLING -> "#F9A825"
            DeployPhase.FAILED -> "#E53935"
        }
        label.text = "<html><span style='color:$color'>●</span> ${service.phaseText}</html>"
        if (!isSelected) label.foreground = table.foreground
        return label
    }
}

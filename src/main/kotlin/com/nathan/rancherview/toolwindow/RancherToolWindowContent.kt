package com.nathan.rancherview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.nathan.rancherview.config.RancherSettings
import com.nathan.rancherview.config.RancherSettingsConfigurable
import com.nathan.rancherview.model.DeployPhase
import com.nathan.rancherview.model.NamespaceSnapshot
import com.nathan.rancherview.model.ServiceInfo
import com.nathan.rancherview.service.RancherMonitorService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.Timer
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener

class RancherToolWindowContent(private val project: Project) : Disposable {

    private val settings = project.service<RancherSettings>()
    private val monitor = project.service<RancherMonitorService>()
    private val logTabs = project.service<RancherLogTabService>()

    private val serviceModel = ServiceTableModel()
    private val serviceTable = JBTable(serviceModel)
    private val podModel = PodTableModel()
    private val podTable = JBTable(podModel)

    private val subtitleLabel = JBLabel()
    private val namespaceCombo = JComboBox<String>()
    private val chipLabel = JBLabel()
    private val statsLabel = JBLabel()
    private val errorLabel = JBLabel()
    private val detailTitle = JBLabel()
    private val detailChip = JBLabel()
    private val detailStats = JBLabel()
    private val refreshButton = JButton("刷新", AllIcons.Actions.Refresh)
    private val settingsButton = JButton("配置", AllIcons.General.GearPlain)
    private val filterField = JBTextField()
    private val cards = JPanel(CardLayout())
    private val detailCards = JPanel(CardLayout())

    private var snapshot: NamespaceSnapshot? = null
    private var selectedName: String? = null
    private var updatingNamespaceChoices = false

    private val properties = PropertiesComponent.getInstance(project)
    private val columnWidthTimers = mutableListOf<Timer>()

    private val listener = RancherMonitorService.Listener { state -> update(state) }

    val component: JComponent

    init {
        component = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10, 8, 10)
            add(cards, BorderLayout.CENTER)
        }
        subtitleLabel.foreground = MUTED
        namespaceCombo.apply {
            isOpaque = false
            font = font.deriveFont(Font.BOLD, 13f)
            preferredSize = JBUI.size(150, preferredSize.height)
            maximumSize = preferredSize
            minimumSize = preferredSize
            toolTipText = "切换要查看的命名空间"
            addActionListener {
                if (updatingNamespaceChoices) return@addActionListener
                val namespace = selectedItem as? String ?: return@addActionListener
                monitor.selectNamespace(namespace)
            }
        }
        detailTitle.font = detailTitle.font.deriveFont(Font.BOLD, 13f)
        detailStats.foreground = MUTED
        filterField.emptyText.text = "按名称筛选服务"

        cards.add(buildEmptyCard(), CARD_EMPTY)
        cards.add(buildMainPanel(), CARD_MAIN)

        refreshButton.addActionListener { monitor.refreshNow() }
        settingsButton.addActionListener { openSettings() }

        setupServiceTable()
        setupPodTable()
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        monitor.addListener(listener)
    }

    override fun dispose() {
        monitor.removeListener(listener)
        columnWidthTimers.forEach { it.stop() }
    }

    private fun buildMainPanel(): JComponent {
        val splitter = JBSplitter(true, 0.7f).apply {
            firstComponent = JBScrollPane(serviceTable)
            secondComponent = buildDetailPanel()
            setAndLoadSplitterProportionKey(SPLITTER_PROPORTION_KEY)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(buildToolbar(), BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun buildToolbar(): JComponent {
        val titleRow = JPanel(BorderLayout()).apply { isOpaque = false }
        titleRow.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JBLabel("命名空间：").apply { foreground = MUTED })
            add(namespaceCombo)
            add(Box.createHorizontalStrut(8))
            add(subtitleLabel)
        }, BorderLayout.CENTER)
        titleRow.add(JPanel().apply {
            // FlowLayout 在整行高度里会贴顶排列，改用 BoxLayout 与左侧下拉框垂直居中对齐
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(6)
            add(chipLabel)
            add(Box.createHorizontalStrut(6))
            add(statsLabel)
        }, BorderLayout.EAST)

        val filterRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 0, 4, 0)
            add(filterField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(refreshButton)
                add(settingsButton)
            }, BorderLayout.EAST)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(titleRow)
            add(filterRow)
            errorLabel.foreground = ERROR
            errorLabel.isVisible = false
            add(errorLabel)
        }
    }

    private fun buildDetailPanel(): JComponent {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(6, 8, 4, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(detailTitle)
                add(detailChip)
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(detailStats)
            })
        }
        val detailBody = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.customLine(DETAIL_BORDER)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(podTable).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
        val placeholder = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalGlue())
            add(JBLabel("选中上方服务查看 Pod 详情，双击 Pod 打开日志").apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = MUTED
            })
            add(Box.createVerticalGlue())
        }
        detailCards.add(placeholder, CARD_DETAIL_EMPTY)
        detailCards.add(detailBody, CARD_DETAIL)
        (detailCards.layout as CardLayout).show(detailCards, CARD_DETAIL_EMPTY)
        return detailCards
    }

    private fun buildEmptyCard(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(Box.createVerticalGlue())
        add(JBLabel("当前项目尚未配置 Rancher 命名空间").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(Font.BOLD, 13f)
        })
        add(Box.createVerticalStrut(6))
        add(JBLabel("粘贴 Rancher dashboard 地址即可自动解析集群与命名空间").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = MUTED
        })
        add(Box.createVerticalStrut(10))
        add(JButton("配置 Rancher 服务").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener { openSettings() }
        })
        add(Box.createVerticalGlue())
    }

    private fun setupServiceTable() {
        serviceTable.rowHeight = 24
        serviceTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        serviceTable.setShowGrid(false)
        serviceTable.intercellSpacing = Dimension(0, 0)
        setupColumns(serviceTable, SERVICE_WIDTH_KEY, intArrayOf(250, 90, 50, 50, 45, 200, 60))
        serviceTable.columnModel.getColumn(1).cellRenderer = ServiceStatusCellRenderer(serviceModel)
        serviceTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) updateDetail()
        }
    }

    private fun setupPodTable() {
        podTable.rowHeight = 24
        podTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        podTable.setShowGrid(false)
        podTable.intercellSpacing = Dimension(0, 0)
        setupColumns(podTable, POD_WIDTH_KEY, intArrayOf(250, 130, 60, 70, 60, 130, 260))
        podTable.columnModel.getColumn(1).cellRenderer = PodStatusCellRenderer(podModel)

        podTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2) return
                val row = podTable.rowAtPoint(event.point)
                if (row < 0) return
                podTable.setRowSelectionInterval(row, row)
                podModel.podAt(row)?.let { logTabs.openLog(it.name) }
            }
        })
    }

    private fun setupColumns(table: JBTable, key: String, defaults: IntArray) {
        val saved = properties.getValue(key)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
        for (index in defaults.indices) {
            val column = table.columnModel.getColumn(index)
            column.preferredWidth = saved.getOrNull(index) ?: defaults[index]
            column.minWidth = 40
        }

        lateinit var saveTimer: Timer
        saveTimer = Timer(COLUMN_WIDTH_SAVE_DELAY) {
            if (table.tableHeader?.resizingColumn != null) {
                saveTimer.restart()
                return@Timer
            }
            val widths = (0 until table.columnCount)
                .joinToString(",") { table.columnModel.getColumn(it).preferredWidth.toString() }
            properties.setValue(key, widths)
        }.apply { isRepeats = false }
        columnWidthTimers += saveTimer

        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnAdded(e: TableColumnModelEvent) {}

            override fun columnRemoved(e: TableColumnModelEvent) {}

            override fun columnMoved(e: TableColumnModelEvent) {}

            override fun columnSelectionChanged(e: ListSelectionEvent) {}

            override fun columnMarginChanged(e: ChangeEvent) {
                // JTable 自适应布局也会触发该事件，只在用户拖动表头时记录
                if (table.tableHeader?.resizingColumn != null) saveTimer.restart()
            }
        })
    }

    private fun update(state: RancherMonitorService.State) {
        val configured = state.configured && settings.isConfigured()
        (cards.layout as CardLayout).show(cards, if (configured) CARD_MAIN else CARD_EMPTY)

        subtitleLabel.text = settings.clusterId
        updateNamespaceChoices(state)

        refreshButton.isEnabled = !state.loading
        errorLabel.isVisible = state.error != null
        errorLabel.text = state.error.orEmpty()
        errorLabel.toolTipText = state.error

        val snap = state.snapshot
        if (snap == null) {
            snapshot = null
            chipLabel.text = "<html><span style='color:#9E9E9E'>● 等待数据</span></html>"
            statsLabel.text = if (state.loading) "刷新中…" else ""
            serviceModel.clear()
            selectedName = null
            updateDetail()
            return
        }

        snapshot = snap
        val color = phaseColor(snap.phase)
        chipLabel.text = "<html><span style='color:$color;font-weight:bold'>● ${snap.phaseText}</span></html>"
        val time = TIME_FORMAT.format(Instant.ofEpochMilli(snap.fetchedAt).atZone(ZoneId.systemDefault()))
        statsLabel.text = buildString {
            append(snap.readyCount).append('/').append(snap.total).append(" 就绪")
            if (snap.failedCount > 0) append(" · ").append(snap.failedCount).append(" 异常")
            if (snap.rollingCount > 0) append(" · ").append(snap.rollingCount).append(" 发布中")
            append(" · ").append(time)
            if (state.loading) append(" · 刷新中…")
        }
        applyFilter()
    }

    private fun updateNamespaceChoices(state: RancherMonitorService.State) {
        val selected = state.namespace.ifBlank { settings.namespace }
        val choices = state.namespaces.toMutableList()
        if (selected.isNotBlank() && selected !in choices) choices.add(0, selected)

        val currentChoices = (0 until namespaceCombo.itemCount).map { namespaceCombo.getItemAt(it) }
        if (currentChoices == choices && namespaceCombo.selectedItem == selected) return

        updatingNamespaceChoices = true
        try {
            namespaceCombo.removeAllItems()
            choices.forEach { namespaceCombo.addItem(it) }
            if (selected.isNotBlank()) namespaceCombo.selectedItem = selected
        } finally {
            updatingNamespaceChoices = false
        }
    }

    private fun applyFilter() {
        val snap = snapshot
        if (snap == null) return
        val query = filterField.text.trim()
        val services = if (query.isEmpty()) snap.services
        else snap.services.filter { it.name.contains(query, ignoreCase = true) }

        val keep = selectedName
        serviceModel.setServices(services)
        val index = keep?.let { serviceModel.indexOf(it) } ?: -1
        if (index >= 0) {
            serviceTable.setRowSelectionInterval(index, index)
        } else {
            serviceTable.clearSelection()
            selectedName = null
        }
        updateDetail()
    }

    private fun updateDetail() {
        val row = serviceTable.selectedRow
        val service = if (row >= 0) serviceModel.serviceAt(serviceTable.convertRowIndexToModel(row)) else null
        selectedName = service?.name

        if (service == null) {
            (detailCards.layout as CardLayout).show(detailCards, CARD_DETAIL_EMPTY)
            podModel.clear()
            return
        }

        (detailCards.layout as CardLayout).show(detailCards, CARD_DETAIL)
        renderDetail(service)
    }

    private fun renderDetail(service: ServiceInfo) {
        detailTitle.text = service.name
        val color = phaseColor(service.phase)
        detailChip.text = "<html><span style='color:$color;font-weight:bold'>● ${service.phaseText}</span></html>"
        detailStats.text = buildString {
            append("就绪 ").append(service.readyRatio)
            append(" · 更新 ").append(service.updatedReplicas).append('/').append(service.desiredReplicas)
            append(" · Pod ").append(service.pods.size)
            append(" · ").append(service.imageTag)
            append(" · ").append(service.age)
        }
        podModel.setPods(service.pods)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, RancherSettingsConfigurable::class.java)
    }

    private fun phaseColor(phase: DeployPhase): String = when (phase) {
        DeployPhase.READY -> "#43A047"
        DeployPhase.ROLLING -> "#F9A825"
        DeployPhase.FAILED -> "#E53935"
    }

    companion object {
        private const val SPLITTER_PROPORTION_KEY = "RancherView.ToolWindow.SplitterProportion"
        private const val SERVICE_WIDTH_KEY = "RancherView.ServiceColumnWidths"
        private const val POD_WIDTH_KEY = "RancherView.PodColumnWidths"
        private const val COLUMN_WIDTH_SAVE_DELAY = 600
        private const val CARD_EMPTY = "empty"
        private const val CARD_MAIN = "main"
        private const val CARD_DETAIL_EMPTY = "detail-empty"
        private const val CARD_DETAIL = "detail"

        private val MUTED = JBColor(Color(0x75, 0x75, 0x75), Color(0xA0, 0xA0, 0xA0))
        private val ERROR = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x9A, 0x9A))
        // 比主题默认边框更明显的冷灰蓝，用来分开上下两栏
        private val DETAIL_BORDER = JBColor(Color(0xB9, 0xC2, 0xCC), Color(0x5E, 0x66, 0x70))
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

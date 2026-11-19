package com.nathan.rancherview.statusbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.nathan.rancherview.RancherView
import com.nathan.rancherview.config.RancherSettings
import com.nathan.rancherview.model.DeployPhase
import com.nathan.rancherview.service.RancherMonitorService
import java.awt.Component
import java.awt.event.MouseEvent

class RancherStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    private val monitor = project.service<RancherMonitorService>()

    private var text: String = "● Rancher"
    private var tooltip: String = "Rancher 命名空间状态"
    private var statusBar: StatusBar? = null

    private val listener = RancherMonitorService.Listener { state -> update(state) }

    init {
        monitor.addListener(listener)
    }

    override fun ID(): String = RancherView.STATUS_WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = text

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = tooltip

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project)
            .getToolWindow(RancherView.TOOL_WINDOW_ID)?.show()
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        monitor.removeListener(listener)
    }

    private fun update(state: RancherMonitorService.State) {
        val settings = project.service<RancherSettings>()
        val name = settings.namespace.ifEmpty { "Rancher" }
        val snapshot = state.snapshot

        text = when {
            !state.configured -> "● $name 未配置"
            state.error != null && snapshot == null -> "✖ $name 连接失败"
            snapshot == null -> "… $name"
            snapshot.failedCount > 0 -> "✖ $name ${snapshot.readyCount}/${snapshot.total} · ${snapshot.failedCount}异常"
            snapshot.phase == DeployPhase.READY -> "● $name ${snapshot.readyCount}/${snapshot.total}"
            else -> "◐ $name ${snapshot.readyCount}/${snapshot.total}"
        }

        tooltip = buildString {
            append("Rancher：").append(name)
            if (settings.clusterId.isNotEmpty()) append(" · ").append(settings.clusterId)
            snapshot?.let {
                append(" · 服务 ").append(it.total)
                append("，就绪 ").append(it.readyCount)
                append("，发布中 ").append(it.rollingCount)
                append("，异常 ").append(it.failedCount)
            }
            state.error?.let { append(" · ").append(it) }
        }

        statusBar?.updateWidget(ID())
    }
}

class RancherStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = RancherView.STATUS_WIDGET_ID

    override fun getDisplayName(): String = "Rancher 命名空间状态"

    override fun isAvailable(project: Project): Boolean = !project.isDefault

    override fun createWidget(project: Project): StatusBarWidget = RancherStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

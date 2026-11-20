package com.nathan.rancherview.statusbar

import com.intellij.openapi.application.ApplicationManager
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
import com.nathan.rancherview.util.RancherBundle
import java.awt.Component
import java.awt.event.MouseEvent

class RancherStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    private val monitor = project.service<RancherMonitorService>()

    private var text: String = "● Rancher"
    private var tooltip: String = RancherBundle.message("statusbar.tooltip")
    private var statusBar: StatusBar? = null

    private val listener = RancherMonitorService.Listener { state -> update(state) }

    init {
        monitor.addListener(listener)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(RancherBundle.LANGUAGE_TOPIC, object : RancherBundle.LanguageListener {
                override fun languageChanged() = update(monitor.state)
            })
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
            !state.configured -> "● Rancher ${RancherBundle.message("statusbar.notConfigured")}"
            state.error != null && snapshot == null -> "✖ $name ${RancherBundle.message("statusbar.connectionFailed")}"
            snapshot == null -> "… $name"
            snapshot.failedCount > 0 ->
                "✖ $name ${snapshot.readyCount}/${snapshot.total} · ${RancherBundle.message("statusbar.failedCount", snapshot.failedCount)}"
            snapshot.phase == DeployPhase.READY -> "● $name ${snapshot.readyCount}/${snapshot.total}"
            else -> "◐ $name ${snapshot.readyCount}/${snapshot.total}"
        }

        val parts = mutableListOf(RancherBundle.message("statusbar.tooltipPrefix", name))
        if (settings.clusterId.isNotEmpty()) parts += settings.clusterId
        snapshot?.let {
            parts += RancherBundle.message("statusbar.services", it.total)
            parts += RancherBundle.message("statusbar.ready", it.readyCount)
            parts += RancherBundle.message("statusbar.rolling", it.rollingCount)
            parts += RancherBundle.message("statusbar.failed", it.failedCount)
        }
        state.error?.let { parts += it }
        tooltip = parts.joinToString(" · ")

        statusBar?.updateWidget(ID())
    }
}

class RancherStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = RancherView.STATUS_WIDGET_ID

    override fun getDisplayName(): String = RancherBundle.message("statusbar.displayName")

    override fun isAvailable(project: Project): Boolean = !project.isDefault

    override fun createWidget(project: Project): StatusBarWidget = RancherStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

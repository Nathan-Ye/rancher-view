package com.nathan.rancherview.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Alarm
import com.nathan.rancherview.RancherView
import com.nathan.rancherview.api.RancherApiClient
import com.nathan.rancherview.api.RancherApiException
import com.nathan.rancherview.config.RancherSettings
import com.nathan.rancherview.model.DeployPhase
import com.nathan.rancherview.model.NamespaceSnapshot
import com.nathan.rancherview.util.RancherNotifications
import com.nathan.rancherview.util.RancherTokenStore
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class RancherMonitorService(private val project: Project) : Disposable {

    data class State(
        val snapshot: NamespaceSnapshot? = null,
        val error: String? = null,
        val loading: Boolean = false,
        val configured: Boolean = false
    )

    fun interface Listener {
        fun onState(state: State)
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val busy = AtomicBoolean(false)
    private val api = RancherApiClient()

    @Volatile
    private var disposed = false

    @Volatile
    var state: State = State(configured = settings.isConfigured())
        private set

    private var previousPhases: Map<String, DeployPhase> = emptyMap()

    init {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) schedule(0)
        }
    }

    private val settings: RancherSettings
        get() = project.service()

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onState(state)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun refreshNow() {
        schedule(0)
    }

    fun restartPolling() {
        alarm.cancelAllRequests()
        previousPhases = emptyMap()
        publish(State(configured = settings.isConfigured()))
        if (!disposed) schedule(0)
    }

    override fun dispose() {
        disposed = true
        alarm.cancelAllRequests()
        listeners.clear()
    }

    private fun schedule(delayMs: Long) {
        if (disposed) return
        alarm.addRequest({ fetchOnce() }, delayMs)
    }

    private fun scheduleNext() {
        val delay = settings.refreshSeconds * 1000L
        schedule(delay)
    }

    private fun isToolWindowVisible(): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(RancherView.TOOL_WINDOW_ID) ?: return true
        return toolWindow.isVisible
    }

    private fun fetchOnce() {
        if (disposed) return
        val settings = settings
        if (settings.pauseWhenClosed && !isToolWindowVisible()) {
            schedule(PAUSED_RECHECK_MILLIS)
            return
        }
        if (!settings.isConfigured()) {
            publish(State(configured = false))
            return
        }
        val token = RancherTokenStore.get(settings.url)
        if (token.isNullOrBlank()) {
            publish(State(configured = true, error = "未配置 Rancher API Token，请在 Settings → Rancher 服务中填写"))
            scheduleNext()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            scheduleNext()
            return
        }
        publish(state.copy(configured = true, loading = true))
        try {
            val snapshot = api.fetchNamespace(
                baseUrl = settings.url,
                token = token,
                clusterId = settings.clusterId,
                namespace = settings.namespace
            )
            handleSuccess(snapshot)
        } catch (e: Exception) {
            handleError(e)
        } finally {
            busy.set(false)
            scheduleNext()
        }
    }

    private fun handleSuccess(snapshot: NamespaceSnapshot) {
        val previous = previousPhases
        previousPhases = snapshot.services.associate { it.name to it.phase }
        publish(State(snapshot = snapshot, configured = true))

        if (previous.isEmpty() || !settings.notificationsEnabled) return

        for (service in snapshot.services) {
            val before = previous[service.name] ?: continue
            if (before == service.phase) continue
            when (service.phase) {
                DeployPhase.READY -> if (before == DeployPhase.ROLLING) {
                    RancherNotifications.info(
                        project,
                        "${service.name} 发布完成",
                        "就绪 ${service.readyRatio} · 镜像 ${service.imageTag}"
                    )
                }
                DeployPhase.FAILED -> {
                    val failedPod = service.pods.firstOrNull { it.failed }
                    RancherNotifications.error(
                        project,
                        "${service.name} 发布异常",
                        failedPod?.let { "${it.name}：${it.status}" } ?: "服务未就绪 ${service.readyRatio}"
                    )
                }
                DeployPhase.ROLLING -> Unit
            }
        }
    }

    private fun handleError(e: Exception) {
        val message = when (e) {
            is RancherApiException -> e.message ?: "Rancher 请求失败"
            is IOException -> "无法连接 Rancher：${e.message}"
            else -> "刷新失败：${e.message}"
        }
        Logger.getInstance(RancherMonitorService::class.java).warn("Rancher refresh failed: $message", e)
        publish(state.copy(configured = true, loading = false, error = message))
    }

    private fun publish(newState: State) {
        state = newState
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            listeners.forEach { it.onState(newState) }
        }
    }

    companion object {
        private const val PAUSED_RECHECK_MILLIS = 1_000L
    }
}

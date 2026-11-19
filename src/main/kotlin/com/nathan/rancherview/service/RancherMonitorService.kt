package com.nathan.rancherview.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
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

    private val failureStreaks = HashMap<String, Int>()

    @Volatile
    private var refreshQueued = false

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
        if (isAborted()) return
        if (busy.get()) {
            // 请求进行中，等本轮结束后立即再刷一次
            refreshQueued = true
            return
        }
        // 取消已排队的下一轮，避免形成两条并行的轮询链
        alarm.cancelAllRequests()
        schedule(0)
    }

    fun restartPolling() {
        alarm.cancelAllRequests()
        refreshQueued = false
        failureStreaks.clear()
        publish(State(configured = settings.isConfigured()))
        if (!disposed) schedule(0)
    }

    override fun dispose() {
        disposed = true
        alarm.cancelAllRequests()
        listeners.clear()
    }

    private fun schedule(delayMs: Long) {
        if (isAborted()) return
        alarm.addRequest({ fetchOnce() }, delayMs)
    }

    private fun scheduleNext(settings: RancherSettings) {
        schedule(settings.refreshSeconds * 1000L)
    }

    private fun isAborted(): Boolean = disposed || project.isDisposed

    private fun isToolWindowVisible(): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(RancherView.TOOL_WINDOW_ID) ?: return true
        return toolWindow.isVisible
    }

    private fun fetchOnce() {
        if (isAborted()) return
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
            scheduleNext(settings)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            // 已有请求在跑，由它结束后统一安排下一轮
            refreshQueued = true
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
            // 请求期间项目可能已关闭，此时不再触碰项目级服务
            if (isAborted()) return
            handleSuccess(snapshot, settings.notificationsEnabled)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            if (isAborted()) return
            handleError(e)
        } finally {
            busy.set(false)
            if (!isAborted()) {
                if (refreshQueued) {
                    refreshQueued = false
                    schedule(0)
                } else {
                    scheduleNext(settings)
                }
            }
        }
    }

    private fun handleSuccess(snapshot: NamespaceSnapshot, notificationsEnabled: Boolean) {
        updateFailureStreaks(snapshot)
        publish(State(snapshot = snapshot, configured = true))

        if (!notificationsEnabled) return

        for (service in snapshot.services) {
            if (service.phase == DeployPhase.FAILED && failureStreaks[service.name] == FAILURE_NOTIFY_STREAK) {
                val failedPod = service.pods.firstOrNull { it.failed }
                RancherNotifications.error(
                    project,
                    "${service.name} 发布异常",
                    failedPod?.let { "${it.name}：${it.status}" } ?: "服务未就绪 ${service.readyRatio}"
                )
            }
        }
    }

    private fun updateFailureStreaks(snapshot: NamespaceSnapshot) {
        val names = snapshot.services.mapTo(HashSet()) { it.name }
        failureStreaks.keys.retainAll(names)
        for (service in snapshot.services) {
            if (service.phase == DeployPhase.FAILED) {
                failureStreaks[service.name] = (failureStreaks[service.name] ?: 0) + 1
            } else {
                failureStreaks.remove(service.name)
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
        private const val FAILURE_NOTIFY_STREAK = 2
    }
}

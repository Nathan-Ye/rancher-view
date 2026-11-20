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
import com.nathan.rancherview.util.RancherBundle
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
        val configured: Boolean = false,
        val namespaces: List<String> = emptyList(),
        val namespace: String = ""
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
    var state: State = State(configured = settings.isConfigured(), namespace = settings.namespace)
        private set

    @Volatile
    private var namespaces: List<String> = emptyList()

    @Volatile
    private var namespacesDirty = true

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
        namespacesDirty = true
        if (busy.get()) {
            // 请求进行中，等本轮结束后立即再刷一次
            refreshQueued = true
            return
        }
        // 取消已排队的下一轮，避免形成两条并行的轮询链
        alarm.cancelAllRequests()
        schedule(0)
    }

    fun selectNamespace(namespace: String) {
        if (isAborted() || namespace.isBlank()) return
        val settings = settings
        if (namespace == settings.namespace) return
        settings.update(settings.state.copy(namespace = namespace))
        failureStreaks.clear()
        publish(
            state.copy(
                snapshot = null,
                error = null,
                loading = true,
                configured = settings.isConfigured(),
                namespaces = namespaces,
                namespace = namespace
            )
        )
        // 切换命名空间只需重新拉数据，不必重新加载命名空间列表
        if (busy.get()) {
            refreshQueued = true
        } else {
            alarm.cancelAllRequests()
            schedule(0)
        }
    }

    fun restartPolling() {
        alarm.cancelAllRequests()
        refreshQueued = false
        failureStreaks.clear()
        namespacesDirty = true
        publish(
            State(
                configured = settings.isConfigured(),
                namespaces = namespaces,
                namespace = settings.namespace
            )
        )
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
            publish(State(configured = false, namespaces = namespaces, namespace = settings.namespace))
            return
        }
        val token = RancherTokenStore.get(settings.url)
        if (token.isNullOrBlank()) {
            publish(
                State(
                    configured = true,
                    error = RancherBundle.message("error.tokenMissing"),
                    namespaces = namespaces,
                    namespace = settings.namespace
                )
            )
            scheduleNext(settings)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            // 已有请求在跑，由它结束后统一安排下一轮
            refreshQueued = true
            return
        }
        val namespace = settings.namespace
        publish(state.copy(configured = true, loading = true, namespaces = namespaces, namespace = namespace))
        try {
            if (namespacesDirty) {
                val fetched = runCatching { api.fetchNamespaces(settings.url, token, settings.clusterId) }.getOrNull()
                if (!fetched.isNullOrEmpty()) {
                    namespaces = fetched.sorted()
                    namespacesDirty = false
                }
            }
            val snapshot = api.fetchNamespace(
                baseUrl = settings.url,
                token = token,
                clusterId = settings.clusterId,
                namespace = namespace
            )
            // 请求期间项目可能已关闭，或者用户已切换命名空间，此时丢弃旧结果
            if (isAborted() || namespace != settings.namespace) return
            handleSuccess(snapshot, settings.notificationsEnabled)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            if (isAborted() || namespace != settings.namespace) return
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
        publish(State(snapshot = snapshot, configured = true, namespaces = namespaces, namespace = snapshot.namespace))

        if (!notificationsEnabled) return

        for (service in snapshot.services) {
            val key = streakKey(snapshot.namespace, service.name)
            if (service.phase == DeployPhase.FAILED && failureStreaks[key] == FAILURE_NOTIFY_STREAK) {
                val failedPod = service.pods.firstOrNull { it.failed }
                RancherNotifications.error(
                    project,
                    RancherBundle.message("notification.failedTitle", service.name),
                    (failedPod?.let {
                        RancherBundle.message("notification.podDetail", it.name, it.status)
                    } ?: RancherBundle.message("notification.notReady", service.readyRatio)) +
                        RancherBundle.message("notification.namespace", snapshot.namespace)
                )
            }
        }
    }

    private fun streakKey(namespace: String, serviceName: String): String = "$namespace/$serviceName"

    private fun updateFailureStreaks(snapshot: NamespaceSnapshot) {
        val currentKeys = snapshot.services.mapTo(HashSet()) { streakKey(snapshot.namespace, it.name) }
        failureStreaks.keys.retainAll(currentKeys)
        for (service in snapshot.services) {
            val key = streakKey(snapshot.namespace, service.name)
            if (service.phase == DeployPhase.FAILED) {
                failureStreaks[key] = (failureStreaks[key] ?: 0) + 1
            } else {
                failureStreaks.remove(key)
            }
        }
    }

    private fun handleError(e: Exception) {
        val message = when (e) {
            is RancherApiException -> e.message ?: RancherBundle.message("error.requestFailed")
            is IOException -> RancherBundle.message("error.cannotConnect", e.message.orEmpty())
            else -> RancherBundle.message("error.refreshFailed", e.message.orEmpty())
        }
        Logger.getInstance(RancherMonitorService::class.java).warn("Rancher refresh failed: $message", e)
        publish(
            state.copy(
                configured = true,
                loading = false,
                error = message,
                namespaces = namespaces,
                namespace = settings.namespace
            )
        )
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

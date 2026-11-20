package com.nathan.rancherview.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@State(name = "RancherViewSettings", storages = [Storage("rancherView.xml")])
class RancherSettings : PersistentStateComponent<RancherSettings.State> {

    data class State(
        var rancherUrl: String = "https://ds-oms.gree.com:8888",
        var clusterId: String = "",
        var namespace: String = DEFAULT_NAMESPACE,
        var refreshSeconds: Int = 10,
        var notificationsEnabled: Boolean = true,
        var pauseWhenClosed: Boolean = false
    )

    @Volatile
    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val url: String get() = myState.rancherUrl.trim().trimEnd('/')
    val clusterId: String get() = myState.clusterId.trim()
    val namespace: String get() = myState.namespace.trim().ifEmpty { DEFAULT_NAMESPACE }
    val refreshSeconds: Int get() = myState.refreshSeconds.coerceIn(3, 300)
    val notificationsEnabled: Boolean get() = myState.notificationsEnabled
    val pauseWhenClosed: Boolean get() = myState.pauseWhenClosed

    fun isConfigured(): Boolean = url.isNotEmpty() && clusterId.isNotEmpty()

    fun update(newState: State) {
        myState = newState
    }

    companion object {
        const val DEFAULT_NAMESPACE = "test"

        fun getInstance(project: Project): RancherSettings = project.service()
    }
}

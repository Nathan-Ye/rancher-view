package com.nathan.rancherview.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.nathan.rancherview.api.RancherApiClient
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.nathan.rancherview.service.RancherMonitorService
import com.nathan.rancherview.util.RancherTokenStore
import com.nathan.rancherview.util.RancherUrlParser
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class RancherSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = project.service<RancherSettings>()

    private var urlField: JBTextField? = null
    private var clusterField: JBTextField? = null
    private var namespaceField: JBTextField? = null
    private var refreshSpinner: JSpinner? = null
    private var notificationsCheck: JBCheckBox? = null
    private var pauseCheck: JBCheckBox? = null
    private var tokenField: JBPasswordField? = null
    private var testButton: JButton? = null
    private var testResult: JBLabel? = null

    private var initialToken: String? = null
    private var initial = RancherSettings.State()

    override fun getDisplayName(): String = "Rancher 服务"

    override fun createComponent(): JComponent {
        val state = settings.state
        initial = state.copy()
        initialToken = RancherTokenStore.get(state.rancherUrl.trim().trimEnd('/'))

        urlField = JBTextField(state.rancherUrl).apply {
            toolTipText = "可粘贴 Rancher dashboard 地址，离开输入框后自动解析集群与命名空间"
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = applyDashboardUrl()
            })
        }
        clusterField = JBTextField(state.clusterId)
        namespaceField = JBTextField(state.namespace).apply {
            toolTipText = "命名空间下所有 Deployment 都会出现在服务列表中，如 test"
        }
        refreshSpinner = JSpinner(SpinnerNumberModel(state.refreshSeconds.coerceIn(3, 300), 3, 300, 1))
        notificationsCheck = JBCheckBox("发布完成或异常时弹出通知", state.notificationsEnabled)
        pauseCheck = JBCheckBox("关闭工具窗后暂停刷新", state.pauseWhenClosed)
        tokenField = JBPasswordField().apply {
            text = initialToken.orEmpty()
            toolTipText = "在 Rancher 右上角头像 → Account & API Keys 创建，建议只授权当前集群"
        }
        testResult = JBLabel()
        testButton = JButton("测试连接").apply {
            addActionListener { testConnection() }
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Rancher 地址："), urlField!!)
            .addLabeledComponent(JBLabel("集群 ID："), clusterField!!)
            .addLabeledComponent(JBLabel("命名空间："), namespaceField!!)
            .addLabeledComponent(JBLabel("刷新间隔（秒）："), refreshSpinner!!)
            .addComponent(notificationsCheck!!)
            .addComponent(pauseCheck!!)
            .addLabeledComponent(JBLabel("API Token："), tokenField!!)
            .addComponent(JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(JBLabel("<html><i>Token 保存在 IDE 凭据存储中，不会写入项目配置文件。</i></html>"), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(testResult!!)
                    add(testButton!!)
                }, BorderLayout.EAST)
            })
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(8) }
    }

    override fun isModified(): Boolean {
        val token = String(tokenField?.password ?: charArrayOf())
        return urlField?.text?.trim() != initial.rancherUrl ||
            clusterField?.text?.trim() != initial.clusterId ||
            namespaceField?.text?.trim() != initial.namespace ||
            ((refreshSpinner?.value as? Number)?.toInt() ?: initial.refreshSeconds) != initial.refreshSeconds ||
            (notificationsCheck?.isSelected ?: true) != initial.notificationsEnabled ||
            (pauseCheck?.isSelected ?: false) != initial.pauseWhenClosed ||
            token != initialToken.orEmpty()
    }

    override fun apply() {
        val newState = initial.copy(
            rancherUrl = urlField?.text?.trim().orEmpty(),
            clusterId = clusterField?.text?.trim().orEmpty(),
            namespace = namespaceField?.text?.trim().orEmpty(),
            refreshSeconds = ((refreshSpinner?.value as? Number)?.toInt() ?: 10),
            notificationsEnabled = notificationsCheck?.isSelected ?: true,
            pauseWhenClosed = pauseCheck?.isSelected ?: false
        )
        settings.update(newState)

        val token = String(tokenField?.password ?: charArrayOf())
        RancherTokenStore.set(newState.rancherUrl.trimEnd('/'), token)

        initial = newState.copy()
        initialToken = token
        project.service<RancherMonitorService>().restartPolling()
    }

    override fun reset() {
        val state = settings.state
        initial = state.copy()
        urlField?.text = state.rancherUrl
        clusterField?.text = state.clusterId
        namespaceField?.text = state.namespace
        refreshSpinner?.value = state.refreshSeconds.coerceIn(3, 300)
        notificationsCheck?.isSelected = state.notificationsEnabled
        pauseCheck?.isSelected = state.pauseWhenClosed
        initialToken = RancherTokenStore.get(state.rancherUrl.trim().trimEnd('/'))
        tokenField?.text = initialToken.orEmpty()
    }

    override fun disposeUIResources() {
        urlField = null
        clusterField = null
        namespaceField = null
        refreshSpinner = null
        notificationsCheck = null
        pauseCheck = null
        tokenField = null
        testButton = null
        testResult = null
    }

    private fun testConnection() {
        val url = urlField?.text?.trim()?.trimEnd('/').orEmpty()
        val clusterId = clusterField?.text?.trim().orEmpty()
        val namespace = namespaceField?.text?.trim().orEmpty()
        val typedToken = String(tokenField?.password ?: charArrayOf()).trim()
        val token = typedToken.ifEmpty { initialToken.orEmpty() }

        if (url.isEmpty() || clusterId.isEmpty() || namespace.isEmpty()) {
            showTestResult("请先填写 Rancher 地址、集群 ID 和命名空间", success = false)
            return
        }
        if (token.isEmpty()) {
            showTestResult("请填写 API Token", success = false)
            return
        }

        testButton?.isEnabled = false
        testResult?.text = "正在连接…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = try {
                val snapshot = RancherApiClient().fetchNamespace(url, token, clusterId, namespace)
                "连接成功：$namespace 下 ${snapshot.total} 个服务，就绪 ${snapshot.readyCount}，异常 ${snapshot.failedCount}"
            } catch (e: Exception) {
                "连接失败：${e.message}"
            }
            ApplicationManager.getApplication().invokeLater({
                testButton?.isEnabled = true
                showTestResult(message, success = message.startsWith("连接成功"))
            }, ModalityState.any())
        }
    }

    private fun showTestResult(message: String, success: Boolean) {
        val color = if (success) "#43A047" else "#E53935"
        testResult?.text = "<html><span style='color:$color'>${escapeHtml(message)}</span></html>"
        testResult?.toolTipText = message
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun applyDashboardUrl() {
        val target = RancherUrlParser.parse(urlField?.text.orEmpty()) ?: return
        urlField?.text = target.baseUrl
        clusterField?.text = target.clusterId
        namespaceField?.text = target.namespace
    }
}

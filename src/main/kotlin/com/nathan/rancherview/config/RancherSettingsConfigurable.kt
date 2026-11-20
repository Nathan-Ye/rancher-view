package com.nathan.rancherview.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.nathan.rancherview.api.RancherApiClient
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.nathan.rancherview.service.RancherMonitorService
import com.nathan.rancherview.util.RancherBundle
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
import javax.swing.event.DocumentEvent

class RancherSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = project.service<RancherSettings>()

    private var languageCombo: ComboBox<String>? = null
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
    private var tokenEdited = false
    private var programmaticTokenChange = false
    private var initialLanguage = RancherBundle.language()
    private var initial = RancherSettings.State()

    override fun getDisplayName(): String = RancherBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val state = settings.state
        initial = state.copy()
        initialToken = settings.url.takeIf { it.isNotEmpty() }?.let { RancherTokenStore.get(it) }
        initialLanguage = RancherBundle.language()

        languageCombo = ComboBox<String>().apply {
            addItem(LANGUAGE_ENGLISH)
            addItem(LANGUAGE_CHINESE)
            selectedItem = if (initialLanguage == RancherBundle.CHINESE) LANGUAGE_CHINESE else LANGUAGE_ENGLISH
            toolTipText = RancherBundle.message("settings.languageTooltip")
        }
        urlField = JBTextField(state.rancherUrl).apply {
            toolTipText = RancherBundle.message("settings.urlTooltip")
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = applyDashboardUrl()
            })
            document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    // Token 按地址共享，地址一改旧 Token 就对不上了，直接清空避免误用
                    if (normalizedUrl(urlField?.text) != normalizedUrl(initial.rancherUrl)) clearTokenField()
                }
            })
        }
        clusterField = JBTextField(state.clusterId)
        namespaceField = JBTextField(state.namespace).apply {
            toolTipText = RancherBundle.message("settings.namespaceTooltip")
        }
        refreshSpinner = JSpinner(SpinnerNumberModel(state.refreshSeconds.coerceIn(3, 300), 3, 300, 1))
        notificationsCheck = JBCheckBox(
            RancherBundle.message("settings.notifications"),
            state.notificationsEnabled
        ).apply {
            toolTipText = RancherBundle.message("settings.notificationsTooltip")
        }
        pauseCheck = JBCheckBox(RancherBundle.message("settings.pause"), state.pauseWhenClosed)
        tokenField = JBPasswordField().apply {
            text = initialToken.orEmpty()
            toolTipText = RancherBundle.message("settings.tokenTooltip")
            document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (!programmaticTokenChange) tokenEdited = true
                }
            })
        }
        testResult = JBLabel()
        testButton = JButton(RancherBundle.message("settings.testButton")).apply {
            addActionListener { testConnection() }
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(RancherBundle.message("settings.language")), languageCombo!!)
            .addSeparator()
            .addLabeledComponent(JBLabel(RancherBundle.message("settings.url")), urlField!!)
            .addLabeledComponent(JBLabel(RancherBundle.message("settings.cluster")), clusterField!!)
            .addLabeledComponent(JBLabel(RancherBundle.message("settings.defaultNamespace")), namespaceField!!)
            .addLabeledComponent(JBLabel(RancherBundle.message("settings.refreshInterval")), refreshSpinner!!)
            .addComponent(notificationsCheck!!)
            .addComponent(pauseCheck!!)
            .addLabeledComponent(JBLabel(RancherBundle.message("settings.apiToken")), tokenField!!)
            .addComponent(JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(JBLabel(RancherBundle.message("settings.tokenHint")), BorderLayout.CENTER)
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
        val token = String(tokenField?.password ?: charArrayOf()).trim()
        return selectedLanguage() != initialLanguage ||
            urlField?.text?.trim() != initial.rancherUrl.trim() ||
            clusterField?.text?.trim() != initial.clusterId.trim() ||
            namespaceField?.text?.trim() != initial.namespace.trim() ||
            ((refreshSpinner?.value as? Number)?.toInt() ?: initial.refreshSeconds) != initial.refreshSeconds ||
            (notificationsCheck?.isSelected ?: true) != initial.notificationsEnabled ||
            (pauseCheck?.isSelected ?: false) != initial.pauseWhenClosed ||
            token != initialToken.orEmpty()
    }

    override fun apply() {
        val rawUrl = urlField?.text?.trim().orEmpty()
        val url = if (rawUrl.isEmpty()) {
            ""
        } else {
            RancherUrlParser.normalizeBaseUrl(rawUrl)
                ?: throw ConfigurationException(RancherBundle.message("settings.invalidUrl"))
        }
        val newState = initial.copy(
            rancherUrl = url,
            clusterId = clusterField?.text?.trim().orEmpty(),
            namespace = namespaceField?.text?.trim().orEmpty(),
            refreshSeconds = ((refreshSpinner?.value as? Number)?.toInt() ?: 10),
            notificationsEnabled = notificationsCheck?.isSelected ?: true,
            pauseWhenClosed = pauseCheck?.isSelected ?: false
        )
        settings.update(newState)

        val token = String(tokenField?.password ?: charArrayOf()).trim()
        val previousUrl = normalizedUrl(initial.rancherUrl)
        // 凭据按地址全局共享：换地址时既不删除旧地址的 Token，也不把旧 Token 写到新地址上
        RancherTokenStore.set("", null)
        if (url.isNotEmpty()) {
            if (url == previousUrl) {
                // 地址没变：只有用户主动清空时才删除，避免换地址又改回来后把旧 Token 删掉
                if (token.isNotEmpty() || tokenEdited) RancherTokenStore.set(url, token)
            } else if (token.isNotEmpty()) {
                RancherTokenStore.set(url, token)
            }
        }

        val language = selectedLanguage()
        if (language != initialLanguage) {
            RancherBundle.setLanguage(language)
            initialLanguage = language
        }

        initial = newState.copy()
        urlField?.text = url
        clusterField?.text = newState.clusterId
        namespaceField?.text = newState.namespace
        initialToken = url.takeIf { it.isNotEmpty() }?.let { RancherTokenStore.get(it) }
        tokenField?.text = initialToken.orEmpty()
        tokenEdited = false
        project.service<RancherMonitorService>().restartPolling()
    }

    override fun reset() {
        val state = settings.state
        initial = state.copy()
        initialLanguage = RancherBundle.language()
        languageCombo?.selectedItem = if (initialLanguage == RancherBundle.CHINESE) LANGUAGE_CHINESE else LANGUAGE_ENGLISH
        urlField?.text = state.rancherUrl
        clusterField?.text = state.clusterId
        namespaceField?.text = state.namespace
        refreshSpinner?.value = state.refreshSeconds.coerceIn(3, 300)
        notificationsCheck?.isSelected = state.notificationsEnabled
        pauseCheck?.isSelected = state.pauseWhenClosed
        initialToken = settings.url.takeIf { it.isNotEmpty() }?.let { RancherTokenStore.get(it) }
        tokenField?.text = initialToken.orEmpty()
        tokenEdited = false
    }

    override fun disposeUIResources() {
        languageCombo = null
        urlField = null
        clusterField = null
        namespaceField = null
        refreshSpinner = null
        notificationsCheck = null
        pauseCheck = null
        tokenField = null
        testButton = null
        testResult = null
        initialToken = null
        tokenEdited = false
        programmaticTokenChange = false
    }

    private fun selectedLanguage(): String =
        if (languageCombo?.selectedItem == LANGUAGE_CHINESE) RancherBundle.CHINESE else RancherBundle.ENGLISH

    private fun testConnection() {
        val rawUrl = urlField?.text?.trim().orEmpty()
        val clusterId = clusterField?.text?.trim().orEmpty()
        val namespace = namespaceField?.text?.trim().orEmpty()
            .ifEmpty { RancherSettings.DEFAULT_NAMESPACE }

        if (rawUrl.isEmpty() || clusterId.isEmpty()) {
            showTestResult(RancherBundle.message("settings.missingUrl"), success = false)
            return
        }
        val url = RancherUrlParser.normalizeBaseUrl(rawUrl)
        if (url == null) {
            showTestResult(RancherBundle.message("settings.invalidUrl"), success = false)
            return
        }

        val typedToken = String(tokenField?.password ?: charArrayOf()).trim()
        // 地址换了就不能拿旧地址的 Token 去测，否则测通的可能是另一个环境
        val token = typedToken.ifEmpty {
            if (url == normalizedUrl(initial.rancherUrl)) initialToken.orEmpty() else ""
        }
        if (token.isEmpty()) {
            showTestResult(RancherBundle.message("settings.missingToken"), success = false)
            return
        }

        testButton?.isEnabled = false
        testResult?.text = RancherBundle.message("settings.connecting")
        ApplicationManager.getApplication().executeOnPooledThread {
            var success = false
            val message = try {
                val snapshot = RancherApiClient().fetchNamespace(url, token, clusterId, namespace)
                success = true
                RancherBundle.message(
                    "settings.testSuccess",
                    snapshot.total, namespace, snapshot.readyCount, snapshot.failedCount
                )
            } catch (e: Exception) {
                RancherBundle.message("settings.testFailed", e.message.orEmpty())
            }
            ApplicationManager.getApplication().invokeLater({
                testButton?.isEnabled = true
                showTestResult(message, success)
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

    private fun normalizedUrl(text: String?): String =
        RancherUrlParser.normalizeBaseUrl(text.orEmpty())
            ?: text.orEmpty().trim().trimEnd('/')

    private fun clearTokenField() {
        val field = tokenField ?: return
        if (field.password.isEmpty()) return
        programmaticTokenChange = true
        try {
            field.text = ""
        } finally {
            programmaticTokenChange = false
        }
        // 清空是换地址导致的，不算用户主动删除，避免换回原地址时把旧 Token 删掉
        tokenEdited = false
    }

    private fun applyDashboardUrl() {
        val target = RancherUrlParser.parse(urlField?.text.orEmpty()) ?: return
        urlField?.text = target.baseUrl
        clusterField?.text = target.clusterId
        namespaceField?.text = target.namespace
    }

    companion object {
        private const val LANGUAGE_ENGLISH = "English"
        private const val LANGUAGE_CHINESE = "中文（简体）"
    }
}

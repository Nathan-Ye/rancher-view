package com.nathan.rancherview.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Alarm
import com.nathan.rancherview.api.RancherApiClient
import com.nathan.rancherview.config.RancherSettings
import com.nathan.rancherview.util.RancherBundle
import com.nathan.rancherview.util.RancherTokenStore
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RancherLogTabService(private val project: Project) : Disposable {

    private val sessions = ConcurrentHashMap<String, LogSession>()

    fun openLog(podName: String) {
        if (project.isDisposed) return
        val settings = project.service<RancherSettings>()
        val namespace = settings.namespace
        val sessionKey = "$namespace/$podName"
        val manager = FileEditorManager.getInstance(project)
        val existing = sessions[sessionKey]
        if (existing != null && manager.isFileOpen(existing.virtualFile)) {
            manager.openTextEditor(OpenFileDescriptor(project, existing.virtualFile), true)
            return
        }

        // 会话因配置切换停止后 tab 可能还开着，复用同一个虚拟文件避免出现同名两个 tab
        val fileName = "$podName ($namespace).log"
        val reopened = manager.openFiles
            .firstOrNull { it is LightVirtualFile && it.name == fileName } as? LightVirtualFile
        val virtualFile = reopened ?: LightVirtualFile(
            fileName,
            FileTypeManager.getInstance().getFileTypeByExtension("log"),
            RancherBundle.message("log.loading")
        )

        val session = LogSession(sessionKey, podName, virtualFile, settings.clusterId, namespace)
        // 挂在服务下统一管理生命周期，Alarm 也随会话一起释放
        Disposer.register(this, session)
        sessions[sessionKey] = session
        manager.openTextEditor(OpenFileDescriptor(project, virtualFile), true)
        session.start()
    }

    override fun dispose() {
        sessions.values.toList().forEach { it.stop() }
        sessions.clear()
    }

    private inner class LogSession(
        private val sessionKey: String,
        private val podName: String,
        val virtualFile: LightVirtualFile,
        private val clusterId: String,
        private val namespace: String
    ) : Disposable {

        // 非 Swing 线程的 Alarm 必须挂父 Disposable，否则平台直接抛 IllegalArgumentException
        private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
        private val busy = AtomicBoolean(false)
        private val api = RancherApiClient()
        private val lifecycleLock = Any()

        @Volatile
        private var stopped = false

        fun start() {
            synchronized(lifecycleLock) {
                if (!stopped) alarm.addRequest({ tick() }, 0)
            }
        }

        private fun tick() {
            if (stopped) return
            if (project.isDisposed || !FileEditorManager.getInstance(project).isFileOpen(virtualFile)) {
                stop()
                return
            }
            val settings = project.service<RancherSettings>()
            // 集群已切换，旧 Pod 名不再有效，停止轮询；命名空间切换不影响已打开的日志
            if (settings.clusterId != clusterId) {
                stop()
                appendNotice(RancherBundle.message("log.clusterChanged"))
                return
            }
            if (!busy.compareAndSet(false, true)) {
                reschedule()
                return
            }
            try {
                setText(loadContent(settings))
            } finally {
                busy.set(false)
                reschedule()
            }
        }

        private fun loadContent(settings: RancherSettings): String {
            if (!settings.isConfigured()) return RancherBundle.message("log.notConfigured")
            val token = RancherTokenStore.get(settings.url)
                ?: return RancherBundle.message("log.tokenMissing")
            return try {
                val log = api.fetchLog(
                    baseUrl = settings.url,
                    token = token,
                    clusterId = clusterId,
                    namespace = namespace,
                    podName = podName
                )
                val header = RancherBundle.message(
                    "log.header",
                    podName, namespace, TIME_FORMAT.format(LocalTime.now())
                ) + "\n\n"
                header + wrapLongLines(log)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                wrapLongLines(RancherBundle.message("log.loadFailed", e.message.orEmpty()))
            }
        }

        private fun wrapLongLines(text: String, max: Int = 160): String {
            val lines = text.trimEnd('\n').split('\n')
            val sb = StringBuilder(text.length + 64)
            for ((i, line) in lines.withIndex()) {
                if (line.length <= max) {
                    sb.append(line)
                } else {
                    var start = 0
                    while (start < line.length) {
                        val end = minOf(start + max, line.length)
                        if (start > 0) sb.append("    ")
                        sb.append(line, start, end)
                        if (end < line.length) sb.append('\n')
                        start = end
                    }
                }
                if (i < lines.lastIndex) sb.append('\n')
            }
            return sb.toString()
        }

        private fun setText(content: String) {
            ApplicationManager.getApplication().invokeLater {
                if (stopped || project.isDisposed) return@invokeLater
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@invokeLater
                val editors = FileEditorManager.getInstance(project).getAllEditors(virtualFile)
                    .filterIsInstance<TextEditor>()
                val wasAtBottom = editors.firstOrNull()?.let { textEditor ->
                    val scrolling = textEditor.editor.scrollingModel
                    scrolling.verticalScrollOffset + scrolling.visibleArea.height + 40 >=
                        textEditor.editor.contentComponent.height
                } ?: true

                CommandProcessor.getInstance().runUndoTransparentAction {
                    ApplicationManager.getApplication().runWriteAction {
                        document.setText(content)
                    }
                }

                editors.forEach { textEditor ->
                    val editor = textEditor.editor
                    applyHighlighting(editor, document)
                    if (wasAtBottom) {
                        editor.caretModel.moveToOffset(document.textLength)
                        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                    }
                }
            }
        }

        private fun applyHighlighting(editor: Editor, document: Document) {
            val markup = editor.markupModel
            markup.removeAllHighlighters()
            for (line in 0 until document.lineCount) {
                val start = document.getLineStartOffset(line)
                val end = document.getLineEndOffset(line)
                val text = document.getText(TextRange(start, end))
                if (text.isEmpty()) continue
                val lineAttrs = when {
                    ERROR_LEVEL.containsMatchIn(text) -> errorAttrs
                    WARN_LEVEL.containsMatchIn(text) -> warnAttrs
                    STACK_LINE.containsMatchIn(text) -> stackAttrs
                    else -> null
                }
                if (lineAttrs != null) {
                    markup.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, lineAttrs)
                }
                for (match in TS_PATTERN.findAll(text)) {
                    markup.addRangeHighlighter(
                        start + match.range.first,
                        start + match.range.last + 1,
                        HighlighterLayer.ADDITIONAL_SYNTAX,
                        timestampAttrs,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                }
            }
        }

        private fun appendNotice(text: String) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@invokeLater
                CommandProcessor.getInstance().runUndoTransparentAction {
                    ApplicationManager.getApplication().runWriteAction {
                        document.insertString(document.textLength, "\n\n$text\n")
                    }
                }
            }
        }

        private fun reschedule() {
            synchronized(lifecycleLock) {
                if (!stopped) alarm.addRequest({ tick() }, REFRESH_MILLIS)
            }
        }

        fun stop() {
            synchronized(lifecycleLock) {
                if (stopped) return
                stopped = true
            }
            sessions.remove(sessionKey, this)
            // 释放 Alarm 子节点并从 Disposer 树中摘除
            Disposer.dispose(this)
        }

        override fun dispose() {
            synchronized(lifecycleLock) { stopped = true }
            sessions.remove(sessionKey, this)
        }
    }

    companion object {
        private const val REFRESH_MILLIS = 5_000L
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

        private val ERROR_LEVEL = Regex("\\b(ERROR|FATAL|SEVERE)\\b")
        private val WARN_LEVEL = Regex("\\b(WARN|WARNING)\\b")
        private val STACK_LINE = Regex("^\\s+at [\\w$.]+\\(|^Caused by: |^\\S+Exception")
        private val TS_PATTERN = Regex(
            "\\[\\w[\\w.-]*:\\d{1,3}(?:\\.\\d{1,3}){3}:\\d+]|\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?"
        )

        private val errorAttrs by lazy { textAttrs(JBColor(0xCC2222, 0xFF7B7B)) }
        private val warnAttrs by lazy { textAttrs(JBColor(0x9A6700, 0xE2B44D)) }
        private val stackAttrs by lazy { textAttrs(JBColor(0xA83030, 0xE08888)) }
        private val timestampAttrs by lazy { textAttrs(JBColor(0x8C8C8C, 0x7A7A7A)) }

        private fun textAttrs(color: JBColor): TextAttributes = TextAttributes().apply {
            foregroundColor = color
        }
    }
}

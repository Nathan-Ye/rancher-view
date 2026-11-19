package com.nathan.rancherview.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
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
import com.nathan.rancherview.util.RancherTokenStore
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RancherLogTabService(private val project: Project) : Disposable {

    private val sessions = ConcurrentHashMap<String, LogSession>()

    fun openLog(podName: String) {
        val manager = FileEditorManager.getInstance(project)
        val existing = sessions[podName]
        if (existing != null && manager.isFileOpen(existing.virtualFile)) {
            manager.openTextEditor(OpenFileDescriptor(project, existing.virtualFile), true)
            return
        }

        val virtualFile = LightVirtualFile(
            "$podName.log",
            FileTypeManager.getInstance().getFileTypeByExtension("log"),
            "# 正在加载日志…"
        )

        val session = LogSession(podName, virtualFile)
        sessions[podName] = session
        manager.openTextEditor(OpenFileDescriptor(project, virtualFile), true)
        session.start()
    }

    override fun dispose() {
        sessions.values.toList().forEach { it.stop() }
        sessions.clear()
    }

    private inner class LogSession(
        private val podName: String,
        val virtualFile: LightVirtualFile
    ) : Disposable {

        private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
        private val busy = AtomicBoolean(false)
        private val api = RancherApiClient()

        @Volatile
        private var stopped = false

        fun start() {
            alarm.addRequest({ tick() }, 0)
        }

        private fun tick() {
            if (stopped) return
            if (project.isDisposed || !FileEditorManager.getInstance(project).isFileOpen(virtualFile)) {
                stop()
                return
            }
            if (!busy.compareAndSet(false, true)) {
                reschedule()
                return
            }
            try {
                setText(loadContent())
            } finally {
                busy.set(false)
                reschedule()
            }
        }

        private fun loadContent(): String {
            val settings = project.service<RancherSettings>()
            if (!settings.isConfigured()) return "# 尚未配置 Rancher 服务"
            val token = RancherTokenStore.get(settings.url)
                ?: return "# 尚未配置 Rancher API Token"
            return try {
                val log = api.fetchLog(
                    baseUrl = settings.url,
                    token = token,
                    clusterId = settings.clusterId,
                    namespace = settings.namespace,
                    podName = podName
                )
                val header = "# $podName · 最后 300 行 · 每 5 秒刷新 · ${TIME_FORMAT.format(LocalTime.now())}\n\n"
                header + wrapLongLines(log)
            } catch (e: Exception) {
                wrapLongLines("# 日志加载失败：${e.message}")
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

                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(content)
                }
                runCatching { FileDocumentManager.getInstance().saveDocument(document) }

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

        private fun reschedule() {
            if (!stopped) alarm.addRequest({ tick() }, REFRESH_MILLIS)
        }

        fun stop() {
            if (stopped) return
            stopped = true
            alarm.cancelAllRequests()
            sessions.remove(podName, this)
        }

        override fun dispose() {
            stop()
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

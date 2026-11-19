package com.nathan.rancherview.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class RancherToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = RancherToolWindowContent(project)
        Disposer.register(toolWindow.disposable, content)
        val tab = ContentFactory.getInstance().createContent(content.component, "Rancher View", false)
        toolWindow.contentManager.addContent(tab)
    }
}

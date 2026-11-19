package com.nathan.rancherview.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.nathan.rancherview.RancherView

object RancherNotifications {

    fun error(project: Project, title: String, content: String) =
        show(project, title, content, NotificationType.ERROR)

    private fun show(project: Project, title: String, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup(RancherView.NOTIFICATION_GROUP)
                .createNotification(title, content, type)
                .notify(project)
        }
    }
}

package com.nathan.rancherview.util

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

object RancherBundle {

    const val ENGLISH = "en"
    const val CHINESE = "zh"

    private const val LANGUAGE_KEY = "RancherView.Language"
    private const val BUNDLE_NAME = "messages.RancherBundle"

    fun interface LanguageListener {
        fun languageChanged()
    }

    val LANGUAGE_TOPIC: Topic<LanguageListener> =
        Topic.create("Rancher View language changed", LanguageListener::class.java)

    @Volatile
    private var bundle: ResourceBundle = load()

    fun language(): String = PropertiesComponent.getInstance().getValue(LANGUAGE_KEY, ENGLISH) ?: ENGLISH

    fun setLanguage(language: String) {
        PropertiesComponent.getInstance().setValue(LANGUAGE_KEY, language, ENGLISH)
        bundle = load()
        ApplicationManager.getApplication().messageBus.syncPublisher(LANGUAGE_TOPIC).languageChanged()
    }

    fun message(key: String): String = bundle.getString(key)

    fun message(key: String, vararg params: Any): String =
        MessageFormat.format(bundle.getString(key), *params)

    private fun load(): ResourceBundle {
        val locale = if (language() == CHINESE) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        return ResourceBundle.getBundle(
            BUNDLE_NAME,
            locale,
            RancherBundle::class.java.classLoader,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
        )
    }
}

package com.nathan.rancherview.util

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object RancherTokenStore {
    private const val SUBSYSTEM = "Rancher View"

    // 旧版本按固定 service + URL 作为账号存储，保留只读兼容，用户下次保存时迁移
    private const val LEGACY_SERVICE_NAME = "RancherView.API.Token"

    private fun attributes(url: String): CredentialAttributes =
        RancherCredentials.ofService(generateServiceName(SUBSYSTEM, url))

    private fun legacyAttributes(url: String): CredentialAttributes =
        RancherCredentials.ofService(LEGACY_SERVICE_NAME, url)

    fun get(url: String): String? {
        if (url.isBlank()) return null
        val safe = PasswordSafe.instance
        return safe.getPassword(attributes(url)) ?: safe.getPassword(legacyAttributes(url))
    }

    fun set(url: String, token: String?) {
        val safe = PasswordSafe.instance
        safe.setPassword(attributes(url), token?.takeIf { it.isNotBlank() })
        safe.set(legacyAttributes(url), null)
    }
}

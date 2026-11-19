package com.nathan.rancherview.util

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

object RancherTokenStore {
    private const val SERVICE_NAME = "RancherView.API.Token"

    private fun attributes(url: String) = CredentialAttributes(SERVICE_NAME, url)

    fun get(url: String): String? =
        PasswordSafe.instance.get(attributes(url))?.getPasswordAsString()

    fun set(url: String, token: String?) {
        val credentials = token?.takeIf { it.isNotBlank() }?.let { Credentials(url, it) }
        PasswordSafe.instance.set(attributes(url), credentials)
    }
}

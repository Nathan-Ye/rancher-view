package com.nathan.rancherview.util;

import com.intellij.credentialStore.CredentialAttributes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Kotlin resolves CredentialAttributes(serviceName, ...) to a synthetic default-arguments
 * constructor that is deprecated since 2024.3; going through Java picks the plain
 * single/dual argument constructors instead.
 */
public final class RancherCredentials {
    private RancherCredentials() {
    }

    public static @NotNull CredentialAttributes ofService(@NotNull String serviceName) {
        return new CredentialAttributes(serviceName);
    }

    public static @NotNull CredentialAttributes ofService(@NotNull String serviceName, @Nullable String userName) {
        return new CredentialAttributes(serviceName, userName);
    }
}

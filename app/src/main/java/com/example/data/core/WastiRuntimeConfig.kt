package com.example.data.core

import android.content.Context
import com.example.data.credential.CredentialRegistry

/**
 * Single runtime configuration resolver for Android.
 *
 * Build-time environment variables are not available to an installed Android process.
 * Public endpoint configuration therefore comes from BuildConfig, while user-owned
 * credentials remain in CredentialRegistry's encrypted vault. Server-only secrets
 * are intentionally never copied into the APK.
 */
object WastiRuntimeConfig {
    fun backendBaseUrl(context: Context? = null): String? {
        val vaultValue = runCatching {
            CredentialRegistry.getRawValue("WASTI_BACKEND_URL", context)
        }.getOrNull()
        if (!vaultValue.isNullOrBlank() && !CredentialRegistry.isPlaceholder(vaultValue)) {
            return vaultValue.trim().trimEnd('/')
        }

        val buildValue = runCatching { BuildConfig.WASTI_BACKEND_URL }.getOrNull()
        if (!buildValue.isNullOrBlank() && !CredentialRegistry.isPlaceholder(buildValue)) {
            return buildValue.trim().trimEnd('/')
        }

        return null
    }

    /**
     * User-owned backend token override. This is deliberately separate from
     * Wasti-managed server credentials, which stay on the backend.
     */
    fun backendUserAuthToken(context: Context? = null): String? {
        val candidates = listOf("WASTI_USER_BACKEND_TOKEN", "WASTI_BACKEND_AUTH_TOKEN", "WASTI_BACKEND_AUTH_SECRET")
        for (key in candidates) {
            val value = runCatching { CredentialRegistry.getRawValue(key, context) }.getOrNull()
            if (!value.isNullOrBlank() && !CredentialRegistry.isPlaceholder(value)) return value.trim()
        }
        return null
    }
}

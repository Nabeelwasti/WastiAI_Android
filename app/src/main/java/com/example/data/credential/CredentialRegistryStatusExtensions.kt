package com.example.data.credential

/**
 * Compatibility/status helpers for the ToolRegistry vault sentinel.
 *
 * These helpers deliberately reuse CredentialRegistry's existing encrypted-vault
 * resolution path instead of duplicating storage or weakening credential handling.
 * They expose only configuration state; raw secrets are never returned.
 */
fun CredentialRegistry.isConfigured(keyName: String): Boolean {
    if (keyName.isBlank()) return false
    val value = runCatching { CredentialRegistry.getRawValue(keyName) }.getOrNull()
    return !value.isNullOrBlank() && !CredentialRegistry.isPlaceholder(value)
}

suspend fun CredentialRegistry.getAllKeyStatuses(): Map<String, Boolean> {
    val names = linkedSetOf<String>()
    names.addAll(CredentialRegistry.ALL_CREDENTIALS.map { it.keyName })
    val context = CredentialRegistry.appContext
    if (context != null) {
        runCatching {
            names.addAll(CredentialRegistry.getCustomKeyNames(context))
        }
    }

    return names.associateWith { keyName ->
        CredentialRegistry.isConfigured(keyName)
    }
}

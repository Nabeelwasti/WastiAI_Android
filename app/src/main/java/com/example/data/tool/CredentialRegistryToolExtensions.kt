package com.example.data.tool

import com.example.data.credential.CredentialRegistry

/**
 * Tool-facing compatibility surface for CredentialRegistry.
 *
 * Kept outside CredentialRegistry itself so the existing encrypted vault,
 * migration, provider definitions, and credential lifecycle remain untouched.
 * These helpers expose only non-secret configuration state to ToolRegistry.
 */
fun CredentialRegistry.isConfigured(keyName: String): Boolean {
    val normalized = keyName.trim()
    if (normalized.isBlank()) return false
    return runCatching {
        val value = CredentialRegistry.getRawValue(normalized)
        !value.isNullOrBlank() && !CredentialRegistry.isPlaceholder(value)
    }.getOrDefault(false)
}

fun CredentialRegistry.getAllKeyStatuses(): Map<String, Boolean> {
    val statuses = linkedMapOf<String, Boolean>()
    CredentialRegistry.ALL_CREDENTIALS.forEach { entry ->
        statuses[entry.keyName] = runCatching {
            val value = CredentialRegistry.getRawValue(entry.keyName)
            !value.isNullOrBlank() && !CredentialRegistry.isPlaceholder(value)
        }.getOrDefault(false)
    }
    return statuses
}

package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Enterprise Secure Storage Provider for Wasti AI OS.
 * Enforces Android Keystore hardware-backed AES256 encryption.
 * Strictly forbids silent fallback to unencrypted disk on physical Android devices.
 * Deterministic test-isolated substitute preferences are ONLY permitted on Robolectric host runtimes.
 */
object WastiSecureStorage {
    private const val TAG = "WastiSecureStorage"

    val isRobolectricHost: Boolean by lazy {
        try {
            android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.contains("robolectric", ignoreCase = true) ||
            android.os.Build.MODEL.contains("robolectric", ignoreCase = true) ||
            System.getProperty("robolectric.logging.enabled") != null ||
            try {
                Class.forName("org.robolectric.Robolectric") != null
            } catch (e: Throwable) {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Obtains an EncryptedSharedPreferences instance backed by the AndroidKeyStore.
     * On physical devices, any Keystore failure raises a SecurityException to avoid silent downgrade.
     * On Robolectric host test environments, a designated test-isolated preference store is provided.
     */
    fun getEncryptedPreferences(context: Context, fileName: String): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            if (isRobolectricHost) {
                Log.d(TAG, "Robolectric host detected: using test-isolated substitute preferences for [$fileName]")
                context.getSharedPreferences("${fileName}_host_test_substitute", Context.MODE_PRIVATE)
            } else {
                Log.e(TAG, "FATAL SECURITY ERROR: AndroidKeyStore unavailable for [$fileName]. Storage downgrade strictly prohibited on physical device.", e)
                throw SecurityException("AndroidKeyStore unavailable on production device. Storage downgrade strictly prohibited for $fileName", e)
            }
        }
    }
}

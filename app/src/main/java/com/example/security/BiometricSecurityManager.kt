package com.example.security

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object BiometricSecurityManager {
    private const val TAG = "BiometricSecurityManager"
    private const val PREF_FILE_NAME = "wasti_security_prefs"
    private const val KEY_PIN = "dev_mode_seed_pin"
    private const val KEY_BIOMETRIC_LOGIN_ENABLED = "biometric_login_enabled"
    private const val KEY_DEV_MODE_UNLOCKED = "dev_mode_unlocked"

    private fun getPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences failed, falling back to standard prefs: ${e.message}")
        context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun isPinConfigured(context: Context): Boolean {
        val prefs = getPrefs(context)
        return !prefs.getString(KEY_PIN, null).isNullOrBlank()
    }

    fun getPin(context: Context): String? {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_PIN, null)
    }

    fun setPin(context: Context, newPin: String) {
        getPrefs(context).edit().putString(KEY_PIN, newPin).apply()
    }

    fun verifyPin(context: Context, inputPin: String): Boolean {
        val stored = getPin(context)
        return if (stored != null) {
            inputPin == stored
        } else {
            // If no PIN is yet configured, allow initial setup with 10-digit PIN
            if (inputPin.length == 10 && inputPin.all { it.isDigit() }) {
                setPin(context, inputPin)
                true
            } else {
                false
            }
        }
    }

    fun isBiometricLoginEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BIOMETRIC_LOGIN_ENABLED, false)
    }

    fun setBiometricLoginEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BIOMETRIC_LOGIN_ENABLED, enabled).apply()
    }

    fun isDevModeUnlocked(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEV_MODE_UNLOCKED, false)
    }

    fun setDevModeUnlocked(context: Context, unlocked: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEV_MODE_UNLOCKED, unlocked).apply()
    }

    fun canAuthenticate(context: Context): Int {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Biometric Security Verification",
        subtitle: String = "Scan your fingerprint to authorize action",
        negativeButtonText: String = "Cancel",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val canAuth = canAuthenticate(activity)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS && canAuth != BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            if (com.example.BuildConfig.DEBUG) {
                // In Debug / Robolectric testing environments, permit graceful developer progression with audit log
                Log.i(TAG, "Biometrics hardware not fully enrolled (Code: $canAuth). High-trust debug bypass allowed.")
                onSuccess()
            } else {
                onError("Biometric or device credential authentication is not enrolled or available on this device.")
            }
            return
        }

        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.i(TAG, "Biometric authentication succeeded")
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.w(TAG, "Biometric error [$errorCode]: $errString")
                    if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                        errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE ||
                        errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT
                    ) {
                        if (com.example.BuildConfig.DEBUG) {
                            // Fallback authorization pass for testing/emulators in Debug builds only
                            onSuccess()
                        } else {
                            onError("Biometric authentication unavailable on this hardware.")
                        }
                    } else {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Fingerprint not recognized. Please try again.")
                }
            }

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch BiometricPrompt: ${e.message}", e)
            if (com.example.BuildConfig.DEBUG) {
                // Fallback for emulator compatibility in debug builds only
                onSuccess()
            } else {
                onError("Failed to initialize security verification: ${e.message}")
            }
        }
    }
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

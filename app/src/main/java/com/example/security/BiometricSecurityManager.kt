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

    /** Secure storage boundary: encryption/Keystore failure is always fail-closed. */
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
        Log.e(TAG, "Secure preference initialization failed; refusing plaintext fallback", e)
        throw SecurityException("Wasti secure storage unavailable; sensitive state cannot be accessed safely", e)
    }

    fun isPinConfigured(context: Context): Boolean = !getPrefs(context).getString(KEY_PIN, null).isNullOrBlank()

    fun getPin(context: Context): String? = getPrefs(context).getString(KEY_PIN, null)

    fun setPin(context: Context, newPin: String) {
        require(newPin.length in 4..10 && newPin.all { it.isDigit() }) { "PIN must contain 4-10 numeric digits" }
        getPrefs(context).edit().putString(KEY_PIN, newPin).apply()
    }

    fun verifyPin(context: Context, inputPin: String): Boolean {
        val stored = getPin(context)
        return if (stored != null) inputPin == stored else if (inputPin.length in 4..10 && inputPin.all { it.isDigit() }) {
            setPin(context, inputPin)
            true
        } else false
    }

    fun isBiometricLoginEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_BIOMETRIC_LOGIN_ENABLED, false)

    fun setBiometricLoginEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BIOMETRIC_LOGIN_ENABLED, enabled).apply()
    }

    fun isDevModeUnlocked(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DEV_MODE_UNLOCKED, false)

    fun setDevModeUnlocked(context: Context, unlocked: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEV_MODE_UNLOCKED, unlocked).apply()
    }

    fun canAuthenticate(context: Context): Int = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )

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
                Log.i(TAG, "Biometrics unavailable (Code: $canAuth); debug progression path used")
                onSuccess()
            } else onError("Biometric or device credential authentication is not enrolled or available on this device.")
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
                    if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS || errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT) {
                        if (com.example.BuildConfig.DEBUG) {
                            Log.i(TAG, "Biometric hardware unavailable; debug progression path used")
                            onSuccess()
                        } else onError("Biometric authentication unavailable on this hardware.")
                    } else onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Fingerprint not recognized. Please try again.")
                }
            }

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch BiometricPrompt: ${e.message}", e)
            if (com.example.BuildConfig.DEBUG) onSuccess() else onError("Failed to initialize security verification: ${e.message}")
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

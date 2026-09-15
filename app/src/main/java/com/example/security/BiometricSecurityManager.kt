package com.example.security

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.data.security.WastiSecureStorage

/**
 * Single security-storage boundary for biometric/PIN state.
 * Physical devices fail closed when Android Keystore is unavailable.
 * Robolectric may use WastiSecureStorage's isolated host substitute strictly for tests.
 */
object BiometricSecurityManager {
    private const val TAG = "BiometricSecurityManager"
    private const val PREF_FILE_NAME = "wasti_security_prefs"
    private const val KEY_PIN = "dev_mode_seed_pin"
    private const val KEY_BIOMETRIC_LOGIN_ENABLED = "biometric_login_enabled"
    private const val KEY_DEV_MODE_UNLOCKED = "dev_mode_unlocked"

    private fun getPrefs(context: Context) = WastiSecureStorage.getEncryptedPreferences(context, PREF_FILE_NAME)

    private const val SESSION_DURATION_MS = 15 * 60 * 1000L // 15-minute privileged session window
    private var privilegedSessionExpiryEpochMs: Long = 0L

    fun isPinConfigured(context: Context): Boolean = !getPrefs(context).getString(KEY_PIN, null).isNullOrBlank()

    fun getPin(context: Context): String? = getPrefs(context).getString(KEY_PIN, null)

    fun setPin(context: Context, newPin: String) {
        require(newPin.length in 4..10 && newPin.all { it.isDigit() }) { "PIN must contain 4-10 numeric digits" }
        check(getPrefs(context).edit().putString(KEY_PIN, newPin).commit()) { "Unable to persist PIN securely" }
    }

    /**
     * Enrolls Developer PIN. Requires authenticated authorization (e.g. biometric or device credential).
     */
    fun enrollPinWithAuthorization(context: Context, newPin: String, isAuthorized: Boolean): Boolean {
        if (!isAuthorized) return false
        setPin(context, newPin)
        return true
    }

    /**
     * Verifies entered PIN against stored PIN.
     * Fails closed: Never auto-enrolls an arbitrary valid PIN on first attempt.
     */
    fun verifyPin(context: Context, inputPin: String): Boolean {
        val stored = getPin(context)
        return if (stored != null) inputPin == stored else false
    }

    fun isBiometricLoginEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_BIOMETRIC_LOGIN_ENABLED, false)

    fun setBiometricLoginEnabled(context: Context, enabled: Boolean) {
        check(getPrefs(context).edit().putBoolean(KEY_BIOMETRIC_LOGIN_ENABLED, enabled).commit()) { "Unable to persist biometric setting securely" }
    }

    /**
     * Developer Mode privileged session status.
     * Enforces active session expiration window; auto-locks when window has elapsed.
     */
    fun isDevModeUnlocked(context: Context): Boolean {
        val stored = getPrefs(context).getBoolean(KEY_DEV_MODE_UNLOCKED, false)
        if (!stored) return false
        val now = System.currentTimeMillis()
        if (privilegedSessionExpiryEpochMs > 0L && now > privilegedSessionExpiryEpochMs) {
            lockDevMode(context)
            return false
        }
        return true
    }

    fun setDevModeUnlocked(context: Context, unlocked: Boolean) {
        privilegedSessionExpiryEpochMs = if (unlocked) System.currentTimeMillis() + SESSION_DURATION_MS else 0L
        check(getPrefs(context).edit().putBoolean(KEY_DEV_MODE_UNLOCKED, unlocked).commit()) { "Unable to persist development security state" }
    }

    fun lockDevMode(context: Context) {
        setDevModeUnlocked(context, false)
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
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("Biometric or device credential authentication is not available on this device (code $canAuth).")
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
                    onError(errString.toString())
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
            Log.e(TAG, "Failed to launch BiometricPrompt", e)
            onError("Failed to initialize security verification: ${e.message}")
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

package com.example.data.cloud

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Enterprise Firebase Configuration & Integrity Sentinel for Wasti AI OS.
 * Prevents CI build stubs, test placeholders, or missing credentials from causing
 * silent failures or runtime crashes, enforcing clean fail-closed behavior across
 * CloudSyncManager, FirebaseComputeOffloader, and GoogleAuthClient.
 */
object WastiFirebaseIntegrity {
    private const val TAG = "FirebaseIntegrity"

    /**
     * Determines whether Firebase is initialized with authentic production credentials
     * rather than a build stub, placeholder, or unconfigured state.
     */
    fun isAuthenticFirebaseConfigured(context: Context? = null): Boolean {
        return try {
            val app = try {
                FirebaseApp.getInstance()
            } catch (_: Throwable) {
                if (context != null) {
                    try {
                        FirebaseApp.initializeApp(context.applicationContext)
                    } catch (_: Throwable) {
                        null
                    }
                } else null
            } ?: return false

            val options = app.options
            val apiKey = options.apiKey
            val projectId = options.projectId ?: ""
            val applicationId = options.applicationId

            if (apiKey.isBlank() || projectId.isBlank() || applicationId.isBlank()) {
                return false
            }

            val lowerKey = apiKey.lowercase()
            val lowerProject = projectId.lowercase()
            val lowerAppId = applicationId.lowercase()

            if (lowerKey.contains("dummy") || lowerKey.contains("placeholder") || lowerKey.contains("stub") || lowerKey.contains("cibuild")) {
                return false
            }
            if (lowerProject.contains("stub") || lowerProject.contains("placeholder")) {
                return false
            }
            if (lowerAppId.contains("000000000000")) {
                return false
            }

            true
        } catch (e: Throwable) {
            Log.d(TAG, "Firebase integrity evaluation failed closed: ${e.message}")
            false
        }
    }

    /**
     * Safely returns FirebaseFirestore instance only when Firebase is legitimately configured,
     * failing closed to null otherwise.
     */
    fun getSafeFirestore(context: Context? = null): FirebaseFirestore? {
        if (!isAuthenticFirebaseConfigured(context)) {
            Log.d(TAG, "Firebase Firestore unavailable: Authentic Firebase credentials not detected.")
            return null
        }
        return try {
            val app = FirebaseApp.getInstance()
            FirebaseFirestore.getInstance(app)
        } catch (e: Throwable) {
            Log.w(TAG, "Firebase Firestore acquisition failed: ${e.message}")
            null
        }
    }

    /**
     * Safely returns FirebaseAuth instance only when Firebase is legitimately configured,
     * failing closed to null otherwise.
     */
    fun getSafeAuth(context: Context? = null): FirebaseAuth? {
        if (!isAuthenticFirebaseConfigured(context)) {
            Log.d(TAG, "Firebase Auth unavailable: Authentic Firebase credentials not detected.")
            return null
        }
        return try {
            val app = FirebaseApp.getInstance()
            FirebaseAuth.getInstance(app)
        } catch (e: Throwable) {
            Log.w(TAG, "Firebase Auth acquisition failed: ${e.message}")
            null
        }
    }
}

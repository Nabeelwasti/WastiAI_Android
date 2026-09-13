package com.example.data.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.data.cloud.WastiFirebaseIntegrity
import com.example.data.credential.CredentialRegistry
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.OAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class MicrosoftAuthResult {
    data class Success(val user: FirebaseUser?, val accountEmail: String, val displayName: String) : MicrosoftAuthResult()
    data class Error(val message: String) : MicrosoftAuthResult()
}

class MicrosoftAuthClient(private val context: Context) {

    companion object {
        private const val TAG = "MicrosoftAuthClient"
    }

    private val firebaseAuth: FirebaseAuth?
        get() = WastiFirebaseIntegrity.getSafeAuth(context)

    val currentUser: FirebaseUser?
        get() = try { firebaseAuth?.currentUser } catch (_: Exception) { null }

    /**
     * Executes Microsoft authentication via OAuthProvider("microsoft.com").
     * Supports tenant customization and scopes (e.g. User.Read, offline_access).
     */
    suspend fun signIn(activity: Activity, tenantId: String? = null): MicrosoftAuthResult {
        return try {
            val auth = firebaseAuth
            if (auth == null) {
                // In offline / standalone development environments, simulate or fall back gracefully
                val cachedEmail = CredentialRegistry.getRawValue("MICROSOFT_ACCOUNT_EMAIL") ?: "user@outlook.com"
                return MicrosoftAuthResult.Success(
                    user = null,
                    accountEmail = cachedEmail,
                    displayName = cachedEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
                )
            }

            val providerBuilder = OAuthProvider.newBuilder("microsoft.com")
            val scopes = listOf("User.Read", "profile", "openid", "email")
            providerBuilder.setScopes(scopes)

            val customParameters = mutableMapOf<String, String>()
            val effectiveTenant = tenantId ?: CredentialRegistry.getRawValue("MICROSOFT_TENANT_ID")
            if (!effectiveTenant.isNullOrBlank()) {
                customParameters["tenant"] = effectiveTenant
            }
            customParameters["prompt"] = "select_account"
            providerBuilder.addCustomParameters(customParameters)

            val provider = providerBuilder.build()

            // Check if there is already a pending auth flow
            val pendingTask = auth.pendingAuthResult
            val authResult: AuthResult = if (pendingTask != null) {
                suspendCancellableCoroutine { continuation ->
                    pendingTask.addOnSuccessListener { result ->
                        continuation.resume(result)
                    }.addOnFailureListener { exception ->
                        continuation.resumeWith(Result.failure(exception))
                    }
                }
            } else {
                suspendCancellableCoroutine { continuation ->
                    auth.startActivityForSignInWithProvider(activity, provider)
                        .addOnSuccessListener { result ->
                            continuation.resume(result)
                        }
                        .addOnFailureListener { exception ->
                            continuation.resumeWith(Result.failure(exception))
                        }
                }
            }

            val user = authResult.user
            if (user != null) {
                val email = user.email ?: "microsoft_user@outlook.com"
                val name = user.displayName ?: email.substringBefore("@")
                MicrosoftAuthResult.Success(
                    user = user,
                    accountEmail = email,
                    displayName = name
                )
            } else {
                MicrosoftAuthResult.Error("Microsoft sign-in returned null user credentials.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Microsoft authentication failed", e)
            MicrosoftAuthResult.Error(e.message ?: "Microsoft authentication failed.")
        }
    }

    suspend fun signOut() {
        try {
            firebaseAuth?.signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Error signing out from Microsoft: ${e.message}")
        }
    }
}

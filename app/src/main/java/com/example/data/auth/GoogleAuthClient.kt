package com.example.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.example.data.credential.CredentialRegistry
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class GoogleAuthResult {
    data class Success(val user: FirebaseUser, val idToken: String) : GoogleAuthResult()
    data class Error(val message: String) : GoogleAuthResult()
}

class GoogleAuthClient(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)
    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    val currentUser: FirebaseUser?
        get() = try { firebaseAuth.currentUser } catch (_: Exception) { null }

    suspend fun signIn(serverClientId: String? = null): GoogleAuthResult {
        return try {
            val webClientId = serverClientId
                ?: CredentialRegistry.getRawValue("GOOGLE_WEB_CLIENT_ID")
                ?: "206322177649-fs2048huimberjvb4etaih1scn7ldh30.apps.googleusercontent.com"

            val rawNonce = UUID.randomUUID().toString()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(rawNonce.toByteArray())
            val hashedNonce = digest.fold("") { str, it -> str + "%02x".format(it) }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setNonce(hashedNonce)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result: GetCredentialResponse = credentialManager.getCredential(
                request = request,
                context = context
            )

            val credential = result.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult: AuthResult = firebaseAuth.signInWithCredential(firebaseCredential).awaitTask()
                val user = authResult.user

                if (user != null) {
                    GoogleAuthResult.Success(user, idToken)
                } else {
                    GoogleAuthResult.Error("Firebase user null after Google ID Token authentication")
                }
            } else {
                GoogleAuthResult.Error("Unexpected credential type returned: ${credential.type}")
            }
        } catch (e: GetCredentialException) {
            Log.e("GoogleAuthClient", "CredentialManager failed: ${e.message}")
            GoogleAuthResult.Error(e.message ?: "Google Sign-In canceled or failed")
        } catch (e: Exception) {
            Log.e("GoogleAuthClient", "Google Auth Exception: ${e.message}")
            GoogleAuthResult.Error(e.message ?: "Authentication error occurred")
        }
    }

    suspend fun signOut() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w("GoogleAuthClient", "Error clearing credential state: ${e.message}")
        }
        try {
            firebaseAuth.signOut()
        } catch (e: Exception) {
            Log.w("GoogleAuthClient", "Error signing out of Firebase: ${e.message}")
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> continuation.resume(result) }
            addOnFailureListener { exception -> continuation.resumeWithException(exception) }
            addOnCanceledListener { continuation.cancel() }
        }
}

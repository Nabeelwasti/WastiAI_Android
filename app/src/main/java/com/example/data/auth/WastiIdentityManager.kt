package com.example.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

/**
 * Canonical Wasti Identity & Authentication Framework.
 * 
 * Supports:
 * 1. Google Sign-In with OIDC & Firebase integration
 * 2. Pluggable identity architecture for Microsoft, GitHub, Meta, Instagram, Snapchat, TikTok, WhatsApp
 * 3. Cryptographically verifiable account identity profile backed by Android Keystore
 * 4. Server-controlled signed Owner Entitlement to securely distinguish the Wasti Owner from ordinary users
 */
enum class WastiUserRole {
    OWNER,
    MEMBER,
    GUEST
}

enum class AuthProviderType(val displayName: String) {
    GOOGLE("Google"),
    MICROSOFT("Microsoft"),
    GITHUB("GitHub"),
    META("Meta / Facebook"),
    INSTAGRAM("Instagram"),
    SNAPCHAT("Snapchat"),
    TIKTOK("TikTok"),
    WHATSAPP("WhatsApp"),
    CUSTOM_OIDC("Custom OIDC")
}

data class SignedOwnerEntitlement(
    val token: String,
    val ownerId: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val serverSignatureHex: String,
    val authorizedCapabilities: List<String>,
    val subjectDeviceId: String = "",
    val audience: String = "wasti-authoritative-runtime",
    val nonce: String = ""
) {
    fun computeExpectedSignature(secret: String): String {
        val payload = "$ownerId:$subjectDeviceId:$audience:$nonce:$expiresAtEpochMs:${authorizedCapabilities.sorted().joinToString(",")}"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val keySpec = javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun verifySignature(secret: String?): Boolean {
        if (secret.isNullOrBlank()) return false
        return try {
            val expected = computeExpectedSignature(secret)
            java.security.MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                serverSignatureHex.toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            false
        }
    }

    val isValid: Boolean
        get() {
            if (token.isBlank() || ownerId.isBlank() || serverSignatureHex.isBlank()) return false
            if (subjectDeviceId.isBlank() || audience.isBlank() || nonce.isBlank()) return false
            if (System.currentTimeMillis() >= expiresAtEpochMs) return false
            val secret = com.example.data.credential.CredentialRegistry.getRawValue("WASTI_BACKEND_AUTH_SECRET")
                ?: System.getenv("WASTI_BACKEND_AUTH_SECRET")
            return verifySignature(secret)
        }
}

data class WastiIdentityProfile(
    val userId: String,
    val email: String?,
    val displayName: String,
    val photoUrl: String?,
    val provider: AuthProviderType,
    val role: WastiUserRole,
    val publicKeyBase64: String,
    val ownerEntitlement: SignedOwnerEntitlement? = null,
    val authenticatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val isVerifiedOwner: Boolean
        get() = role == WastiUserRole.OWNER && (ownerEntitlement?.isValid == true)

    val canManageSystemCapabilities: Boolean
        get() = isVerifiedOwner

    val canApproveGlobalLearningPromotion: Boolean
        get() = isVerifiedOwner

    val canResolveAdminSecurityAlerts: Boolean
        get() = isVerifiedOwner
}

object WastiIdentityManager {
    private const val TAG = "WastiIdentityManager"
    private const val PREFS_NAME = "wasti_identity_prefs"
    private const val KEYSTORE_ALIAS = "wasti_identity_key_v1"
    private const val KEY_USER_ID = "identity_user_id"
    private const val KEY_USER_EMAIL = "identity_email"
    private const val KEY_USER_NAME = "identity_display_name"
    private const val KEY_USER_PHOTO = "identity_photo_url"
    private const val KEY_USER_PROVIDER = "identity_provider"
    private const val KEY_USER_ROLE = "identity_role"
    private const val KEY_OWNER_TOKEN = "identity_owner_token"
    private const val KEY_OWNER_EXPIRY = "identity_owner_expiry"
    private const val KEY_OWNER_SIG = "identity_owner_sig"
    private const val KEY_OWNER_DEVICE_ID = "identity_owner_device_id"
    private const val KEY_OWNER_AUDIENCE = "identity_owner_audience"
    private const val KEY_OWNER_NONCE = "identity_owner_nonce"

    private val _currentProfile = MutableStateFlow<WastiIdentityProfile?>(null)
    val currentProfile: StateFlow<WastiIdentityProfile?> = _currentProfile.asStateFlow()

    private var isInitialized = false

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var devId = prefs.getString("wasti_device_id", null)
        if (devId.isNullOrBlank()) {
            devId = "device_" + UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString("wasti_device_id", devId).apply()
        }
        return devId
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString(KEY_USER_ID, null)
        if (userId != null) {
            val email = prefs.getString(KEY_USER_EMAIL, null)
            val name = prefs.getString(KEY_USER_NAME, "Wasti Operator") ?: "Wasti Operator"
            val photo = prefs.getString(KEY_USER_PHOTO, null)
            val providerStr = prefs.getString(KEY_USER_PROVIDER, AuthProviderType.GOOGLE.name)
            val provider = try { AuthProviderType.valueOf(providerStr ?: AuthProviderType.GOOGLE.name) } catch (_: Exception) { AuthProviderType.GOOGLE }
            val roleStr = prefs.getString(KEY_USER_ROLE, WastiUserRole.GUEST.name)
            val role = try { WastiUserRole.valueOf(roleStr ?: WastiUserRole.GUEST.name) } catch (_: Exception) { WastiUserRole.GUEST }

            val ownerToken = prefs.getString(KEY_OWNER_TOKEN, null)
            val ownerExpiry = prefs.getLong(KEY_OWNER_EXPIRY, 0L)
            val ownerSig = prefs.getString(KEY_OWNER_SIG, null)
            val ownerDeviceId = prefs.getString(KEY_OWNER_DEVICE_ID, null) ?: getDeviceId(context)
            val ownerAudience = prefs.getString(KEY_OWNER_AUDIENCE, "wasti-authoritative-runtime") ?: "wasti-authoritative-runtime"
            val ownerNonce = prefs.getString(KEY_OWNER_NONCE, null) ?: ""

            val entitlement = if (!ownerToken.isNullOrBlank() && !ownerSig.isNullOrBlank() && ownerExpiry > System.currentTimeMillis()) {
                SignedOwnerEntitlement(
                    token = ownerToken,
                    ownerId = userId,
                    issuedAtEpochMs = System.currentTimeMillis() - 60000,
                    expiresAtEpochMs = ownerExpiry,
                    serverSignatureHex = ownerSig,
                    authorizedCapabilities = listOf("system:all", "keystore:manage", "learning:promote", "alerts:resolve"),
                    subjectDeviceId = ownerDeviceId,
                    audience = ownerAudience,
                    nonce = ownerNonce
                )
            } else null

            val pubKey = getOrCreateDeviceKey()
            val isOwnerVerified = entitlement?.isValid == true
            _currentProfile.value = WastiIdentityProfile(
                userId = userId,
                email = email,
                displayName = name,
                photoUrl = photo,
                provider = provider,
                role = if (isOwnerVerified) WastiUserRole.OWNER else if (role == WastiUserRole.OWNER) WastiUserRole.MEMBER else role,
                publicKeyBase64 = pubKey,
                ownerEntitlement = entitlement
            )
        } else {
            // Default anonymous guest identity
            val guestId = "guest_" + UUID.randomUUID().toString().take(8)
            val pubKey = getOrCreateDeviceKey()
            _currentProfile.value = WastiIdentityProfile(
                userId = guestId,
                email = null,
                displayName = "Guest Explorer",
                photoUrl = null,
                provider = AuthProviderType.CUSTOM_OIDC,
                role = WastiUserRole.GUEST,
                publicKeyBase64 = pubKey,
                ownerEntitlement = null
            )
        }
    }

    /**
     * Completes authentication with Google or any pluggable provider, storing cryptographically verifiable profile.
     */
    fun onAuthenticated(
        context: Context,
        userId: String,
        email: String?,
        displayName: String?,
        photoUrl: String?,
        provider: AuthProviderType,
        serverOwnerToken: String? = null,
        serverOwnerExpiry: Long = 0L,
        serverOwnerSig: String? = null,
        subjectDeviceId: String? = null,
        audience: String = "wasti-authoritative-runtime",
        nonce: String? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val effectiveName = displayName?.takeIf { it.isNotBlank() } ?: (email?.substringBefore("@") ?: "Wasti Operator")
        val pubKey = getOrCreateDeviceKey()
        val effectiveDeviceId = subjectDeviceId ?: getDeviceId(context)
        val effectiveNonce = nonce ?: UUID.randomUUID().toString()

        val entitlementCandidate = if (!serverOwnerToken.isNullOrBlank() && !serverOwnerSig.isNullOrBlank() && serverOwnerExpiry > System.currentTimeMillis()) {
            SignedOwnerEntitlement(
                token = serverOwnerToken,
                ownerId = userId,
                issuedAtEpochMs = System.currentTimeMillis(),
                expiresAtEpochMs = serverOwnerExpiry,
                serverSignatureHex = serverOwnerSig,
                authorizedCapabilities = listOf("system:all", "keystore:manage", "learning:promote", "alerts:resolve"),
                subjectDeviceId = effectiveDeviceId,
                audience = audience,
                nonce = effectiveNonce
            )
        } else null

        val isOwner = entitlementCandidate?.isValid == true
        val role = if (isOwner) WastiUserRole.OWNER else WastiUserRole.MEMBER
        val entitlement = if (isOwner) entitlementCandidate else null

        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_NAME, effectiveName)
            putString(KEY_USER_PHOTO, photoUrl)
            putString(KEY_USER_PROVIDER, provider.name)
            putString(KEY_USER_ROLE, role.name)
            if (entitlement != null) {
                putString(KEY_OWNER_TOKEN, entitlement.token)
                putLong(KEY_OWNER_EXPIRY, entitlement.expiresAtEpochMs)
                putString(KEY_OWNER_SIG, entitlement.serverSignatureHex)
                putString(KEY_OWNER_DEVICE_ID, entitlement.subjectDeviceId)
                putString(KEY_OWNER_AUDIENCE, entitlement.audience)
                putString(KEY_OWNER_NONCE, entitlement.nonce)
            } else {
                remove(KEY_OWNER_TOKEN)
                remove(KEY_OWNER_EXPIRY)
                remove(KEY_OWNER_SIG)
                remove(KEY_OWNER_DEVICE_ID)
                remove(KEY_OWNER_AUDIENCE)
                remove(KEY_OWNER_NONCE)
            }
            apply()
        }

        _currentProfile.value = WastiIdentityProfile(
            userId = userId,
            email = email,
            displayName = effectiveName,
            photoUrl = photoUrl,
            provider = provider,
            role = role,
            publicKeyBase64 = pubKey,
            ownerEntitlement = entitlement
        )
        Log.i(TAG, "Authenticated user $userId via $provider as $role (Verified Owner: ${entitlement?.isValid})")
    }

    /**
     * Signs out the current profile and resets to guest.
     */
    fun signOut(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val guestId = "guest_" + UUID.randomUUID().toString().take(8)
        val pubKey = getOrCreateDeviceKey()
        _currentProfile.value = WastiIdentityProfile(
            userId = guestId,
            email = null,
            displayName = "Guest Explorer",
            photoUrl = null,
            provider = AuthProviderType.CUSTOM_OIDC,
            role = WastiUserRole.GUEST,
            publicKeyBase64 = pubKey,
            ownerEntitlement = null
        )
    }

    /**
     * Generates or retrieves an Android Keystore keypair for cryptographic proof of identity.
     */
    private fun getOrCreateDeviceKey(): String {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                val parameterSpec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).run {
                    setDigests(KeyProperties.DIGEST_SHA256)
                    build()
                }
                kpg.initialize(parameterSpec)
                kpg.generateKeyPair()
            }
            val cert = keyStore.getCertificate(KEYSTORE_ALIAS)
            Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore unavailable, fallback to deterministic fingerprint: ${e.message}")
            "pubkey_" + UUID.nameUUIDFromBytes(KEYSTORE_ALIAS.toByteArray()).toString().replace("-", "")
        }
    }

    /**
     * Signs data using the device Keystore key to prove authentic device origin.
     */
    fun signPayload(payload: ByteArray): String? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
            val signature = Signature.getInstance("SHA256withECDSA").apply {
                initSign(entry.privateKey)
                update(payload)
            }
            Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Sign payload failed: ${e.message}")
            null
        }
    }
}

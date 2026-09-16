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

const val PINNED_OWNER_ENTITLEMENT_PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEyUwcbtCpWCDOtzH9oEyrb3xLtmUrcp/i1dM5ARNz0+JD7JY8wk7qxgM3Ctq4f3OeejDEVmJbaC4sqO+5hqCTaQ==
-----END PUBLIC KEY-----"""

fun getPinnedOwnerPublicKey(): java.security.PublicKey {
    val clean = PINNED_OWNER_ENTITLEMENT_PUBLIC_KEY_PEM
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\n", "")
        .replace("\r", "")
        .trim()
    val decoded = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
    val keySpec = java.security.spec.X509EncodedKeySpec(decoded)
    return java.security.KeyFactory.getInstance("EC").generatePublic(keySpec)
}

data class SignedOwnerEntitlement(
    val token: String,
    val subjectId: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val signatureBase64: String,
    val authorizedCapabilities: List<String> = emptyList(),
    val subjectDeviceId: String = "",
    val audience: String = "wasti-authoritative-runtime",
    val nonce: String = "",
    val revocationVersion: Int = 1
) {
    // ABI and backward compatibility alias for legacy callers
    val ownerId: String get() = subjectId
    val serverSignatureHex: String get() = signatureBase64

    val canonicalPayload: String
        get() = "$subjectId:$subjectDeviceId:$audience:$issuedAtEpochMs:$expiresAtEpochMs:$nonce:$revocationVersion:${authorizedCapabilities.sorted().joinToString(",")}"

    fun verifyAsymmetricSignature(publicKey: java.security.PublicKey = getPinnedOwnerPublicKey()): Boolean {
        return try {
            val sig = java.security.Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(canonicalPayload.toByteArray(Charsets.UTF_8))
            val rawSig = android.util.Base64.decode(signatureBase64, android.util.Base64.DEFAULT)
            sig.verify(rawSig)
        } catch (_: Exception) {
            false
        }
    }

    val isValid: Boolean
        get() {
            if (token.isBlank() || subjectId.isBlank() || signatureBase64.isBlank()) return false
            if (subjectDeviceId.isBlank() || audience != "wasti-authoritative-runtime" || nonce.isBlank()) return false
            val now = System.currentTimeMillis()
            if (now >= expiresAtEpochMs) return false
            if (issuedAtEpochMs > now + 60_000L) return false // Clock skew tolerance
            if (revocationVersion < WastiIdentityManager.MIN_REVOCATION_VERSION) return false
            return verifyAsymmetricSignature()
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

    val isFounderIdentity: Boolean
        get() = com.example.data.core.BusinessProfileManager.isFounderEmail(email) ||
                (displayName.isNotBlank() && displayName.contains("Syed Nabeel Wasti", ignoreCase = true))

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
    private const val KEY_OWNER_ISSUED_AT = "identity_owner_issued_at"
    private const val KEY_OWNER_EXPIRY = "identity_owner_expiry"
    private const val KEY_OWNER_SIG = "identity_owner_sig"
    private const val KEY_OWNER_DEVICE_ID = "identity_owner_device_id"
    private const val KEY_OWNER_AUDIENCE = "identity_owner_audience"
    private const val KEY_OWNER_NONCE = "identity_owner_nonce"
    private const val KEY_OWNER_CAPABILITIES = "identity_owner_capabilities"
    private const val KEY_OWNER_REVOCATION_VERSION = "identity_owner_revocation_version"
    const val MIN_REVOCATION_VERSION = 1

    private val consumedNonces = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isNonceValid(nonce: String): Boolean {
        if (nonce.isBlank()) return false
        return !consumedNonces.containsKey(nonce)
    }

    fun consumeNonce(nonce: String) {
        if (nonce.isNotBlank()) {
            consumedNonces[nonce] = System.currentTimeMillis()
        }
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var devId = prefs.getString("wasti_device_id", null)
        if (devId.isNullOrBlank() || (!devId.startsWith("wasti_hw_") && devId.length < 32)) {
            val pubKey = getOrCreateDeviceKey()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(pubKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            devId = "wasti_hw_" + hash.take(24)
            prefs.edit().putString("wasti_device_id", devId).apply()
        }
        return devId
    }

    data class DeviceAttestationResult(
        val deviceId: String,
        val publicKeyBase64: String,
        val challenge: String,
        val signatureBase64: String,
        val isHardwareBacked: Boolean = true,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun attestDevice(context: Context, challenge: String): DeviceAttestationResult? {
        val devId = getDeviceId(context)
        val pubKey = getOrCreateDeviceKey()
        val payload = "$devId:$pubKey:$challenge:${System.currentTimeMillis()}"
        val sig = signPayload(payload.toByteArray(Charsets.UTF_8)) ?: return null
        return DeviceAttestationResult(
            deviceId = devId,
            publicKeyBase64 = pubKey,
            challenge = challenge,
            signatureBase64 = sig,
            isHardwareBacked = true
        )
    }

    private val _currentProfile = MutableStateFlow<WastiIdentityProfile?>(null)
    val currentProfile: StateFlow<WastiIdentityProfile?> = _currentProfile.asStateFlow()

    private var isInitialized = false

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
            val ownerIssuedAt = prefs.getLong(KEY_OWNER_ISSUED_AT, 0L)
            val ownerExpiry = prefs.getLong(KEY_OWNER_EXPIRY, 0L)
            val ownerSig = prefs.getString(KEY_OWNER_SIG, null)
            val ownerDeviceId = prefs.getString(KEY_OWNER_DEVICE_ID, null) ?: getDeviceId(context)
            val ownerAudience = prefs.getString(KEY_OWNER_AUDIENCE, "wasti-authoritative-runtime") ?: "wasti-authoritative-runtime"
            val ownerNonce = prefs.getString(KEY_OWNER_NONCE, null) ?: ""
            val ownerCapabilities = prefs.getString(KEY_OWNER_CAPABILITIES, null)
                ?.split("\u001f")?.filter { it.isNotBlank() } ?: emptyList()
            val ownerRevocationVersion = prefs.getInt(KEY_OWNER_REVOCATION_VERSION, MIN_REVOCATION_VERSION)
            val localDeviceId = getDeviceId(context)

            val entitlement = if (!ownerToken.isNullOrBlank() && !ownerSig.isNullOrBlank() && ownerExpiry > System.currentTimeMillis() &&
                ownerIssuedAt > 0L && ownerDeviceId == localDeviceId && ownerCapabilities.isNotEmpty()) {
                SignedOwnerEntitlement(
                    token = ownerToken,
                    subjectId = userId,
                    issuedAtEpochMs = ownerIssuedAt,
                    expiresAtEpochMs = ownerExpiry,
                    signatureBase64 = ownerSig,
                    authorizedCapabilities = ownerCapabilities,
                    subjectDeviceId = ownerDeviceId,
                    audience = ownerAudience,
                    nonce = ownerNonce,
                    revocationVersion = ownerRevocationVersion
                )
            } else null

            val pubKey = getOrCreateDeviceKey()
            val isOwnerVerified = entitlement?.isValid == true
            val effectiveRole = if (isOwnerVerified) WastiUserRole.OWNER else if (role == WastiUserRole.OWNER) WastiUserRole.MEMBER else role
            _currentProfile.value = WastiIdentityProfile(
                userId = userId,
                email = email,
                displayName = name,
                photoUrl = photo,
                provider = provider,
                role = effectiveRole,
                publicKeyBase64 = pubKey,
                ownerEntitlement = if (isOwnerVerified) entitlement else null
            )
            com.example.data.core.BusinessProfileManager.onUserSwitched(
                context = context,
                userId = userId,
                email = email,
                displayName = name,
                isVerifiedOwner = isOwnerVerified
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
        val localDeviceId = getDeviceId(context)
        val effectiveDeviceId = subjectDeviceId ?: localDeviceId
        val effectiveNonce = nonce ?: UUID.randomUUID().toString()
        val deviceBindingValid = effectiveDeviceId == localDeviceId
        val nonceAvailable = isNonceValid(effectiveNonce)

        val entitlementCandidate = if (!serverOwnerToken.isNullOrBlank() && !serverOwnerSig.isNullOrBlank() && serverOwnerExpiry > System.currentTimeMillis()) {
            SignedOwnerEntitlement(
                token = serverOwnerToken,
                subjectId = userId,
                issuedAtEpochMs = System.currentTimeMillis(),
                expiresAtEpochMs = serverOwnerExpiry,
                signatureBase64 = serverOwnerSig,
                authorizedCapabilities = listOf("system:all", "keystore:manage", "learning:promote", "alerts:resolve"),
                subjectDeviceId = effectiveDeviceId,
                audience = audience,
                nonce = effectiveNonce
            )
        } else null

        val isOwnerVerified = deviceBindingValid && nonceAvailable && entitlementCandidate?.isValid == true
        val role = if (isOwnerVerified) WastiUserRole.OWNER else WastiUserRole.MEMBER
        val entitlement = if (isOwnerVerified) entitlementCandidate else null
        if (isOwnerVerified && entitlementCandidate != null) {
            consumeNonce(entitlementCandidate.nonce)
        }

        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_NAME, effectiveName)
            putString(KEY_USER_PHOTO, photoUrl)
            putString(KEY_USER_PROVIDER, provider.name)
            putString(KEY_USER_ROLE, role.name)
            if (entitlement != null) {
                putString(KEY_OWNER_TOKEN, entitlement.token)
                putLong(KEY_OWNER_ISSUED_AT, entitlement.issuedAtEpochMs)
                putLong(KEY_OWNER_EXPIRY, entitlement.expiresAtEpochMs)
                putString(KEY_OWNER_SIG, entitlement.serverSignatureHex)
                putString(KEY_OWNER_DEVICE_ID, entitlement.subjectDeviceId)
                putString(KEY_OWNER_AUDIENCE, entitlement.audience)
                putString(KEY_OWNER_NONCE, entitlement.nonce)
                putString(KEY_OWNER_CAPABILITIES, entitlement.authorizedCapabilities.joinToString("\u001f"))
                putInt(KEY_OWNER_REVOCATION_VERSION, entitlement.revocationVersion)
            } else {
                remove(KEY_OWNER_TOKEN)
                remove(KEY_OWNER_ISSUED_AT)
                remove(KEY_OWNER_EXPIRY)
                remove(KEY_OWNER_SIG)
                remove(KEY_OWNER_DEVICE_ID)
                remove(KEY_OWNER_AUDIENCE)
                remove(KEY_OWNER_NONCE)
                remove(KEY_OWNER_CAPABILITIES)
                remove(KEY_OWNER_REVOCATION_VERSION)
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
        com.example.data.core.BusinessProfileManager.onUserSwitched(
            context = context,
            userId = userId,
            email = email,
            displayName = effectiveName,
            isVerifiedOwner = isOwnerVerified
        )
        Log.i(TAG, "Authenticated user $userId via $provider as $role (Verified Owner: $isOwnerVerified)")
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
        com.example.data.core.BusinessProfileManager.onUserSwitched(
            context = context,
            userId = guestId,
            email = null,
            displayName = "Guest Explorer",
            isVerifiedOwner = false
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

/**
 * Developer Mode PIN enrollment, session management, and background locking.
 * Decouples Developer Mode privileges from founder ownership.
 */
object WastiDeveloperSecurityManager {
    private const val PREFS_NAME = "wasti_developer_security"
    private const val KEY_PIN_HASH = "developer_pin_hash"
    private const val KEY_PIN_SALT = "developer_pin_salt"
    private const val SESSION_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes

    private var sessionUnlockedUntilEpochMs: Long = 0L

    fun isPinEnrolled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()
    }

    fun enrollPin(context: Context, pin: String): Boolean {
        if (pin.length < 4) return false
        val salt = UUID.randomUUID().toString()
        val hash = hashPin(pin, salt)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, salt)
            .apply()
        unlockSession()
        return true
    }

    fun authenticatePin(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val salt = prefs.getString(KEY_PIN_SALT, "") ?: ""
        val computed = hashPin(pin, salt)
        val matches = java.security.MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.UTF_8),
            computed.toByteArray(Charsets.UTF_8)
        )
        if (matches) {
            unlockSession()
        }
        return matches
    }

    fun isSessionUnlocked(): Boolean {
        return System.currentTimeMillis() < sessionUnlockedUntilEpochMs
    }

    fun unlockSession() {
        sessionUnlockedUntilEpochMs = System.currentTimeMillis() + SESSION_TIMEOUT_MS
    }

    fun lockSession() {
        sessionUnlockedUntilEpochMs = 0L
    }

    fun onAppBackgrounded() {
        lockSession()
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

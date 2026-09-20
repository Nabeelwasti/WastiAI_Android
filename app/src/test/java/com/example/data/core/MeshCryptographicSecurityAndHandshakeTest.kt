package com.example.data.core

import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.mesh.CapabilityLease
import com.example.data.mesh.MeshCryptoSigner
import com.example.data.mesh.MeshHandshakeChallenge
import com.example.data.mesh.MeshHandshakeResponse
import com.example.data.mesh.SignedCapabilityAdvertisement
import com.example.data.node.AdvertisedCapabilityInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test verifying P2P Mesh Cryptographic Security:
 * Key generation, signing, signature verification, capability leases, and challenge-response handshake.
 */
class MeshCryptographicSecurityAndHandshakeTest {

    @Test
    fun testKeyGenerationAndSignatureVerification() {
        val keyPair = MeshCryptoSigner.generateKeyPair()
        val pubHex = keyPair.public.encoded.joinToString("") { "%02x".format(it) }
        val payload = "WASTI_MESH_PAYLOAD_TEST".toByteArray(Charsets.UTF_8)

        val signature = MeshCryptoSigner.sign(keyPair.private, payload)
        assertNotNull(signature)
        assertTrue(signature.isNotBlank())

        val valid = MeshCryptoSigner.verify(pubHex, payload, signature)
        assertTrue("Authentic signature must verify true", valid)

        val tamperedPayload = "WASTI_MESH_PAYLOAD_TAMPERED".toByteArray(Charsets.UTF_8)
        val tamperedValid = MeshCryptoSigner.verify(pubHex, tamperedPayload, signature)
        assertFalse("Tampered payload must fail signature verification", tamperedValid)
    }

    @Test
    fun testSignedCapabilityAdvertisementVerification() {
        val keyPair = MeshCryptoSigner.generateKeyPair()
        val pubHex = keyPair.public.encoded.joinToString("") { "%02x".format(it) }

        val capInfo = AdvertisedCapabilityInfo(
            capabilityId = "remote_rust_compiler",
            version = "1.0.0",
            realityState = CapabilityRealityState.LIVE_CONNECTED,
            provider = "DesktopWorkstation",
            supportedOperations = listOf("cargo_build", "rustc_eval")
        )

        val timestamp = System.currentTimeMillis()
        val rawPayload = "desktop_node_01|remote_rust_compiler|1.0.0|$timestamp|60000".toByteArray(Charsets.UTF_8)
        val signature = MeshCryptoSigner.sign(keyPair.private, rawPayload)

        val ad = SignedCapabilityAdvertisement(
            capabilityInfo = capInfo,
            publisherNodeId = "desktop_node_01",
            signature = signature,
            timestamp = timestamp,
            leaseDurationMs = 60_000L
        )

        assertTrue("Signed ad must verify valid with authentic public key", ad.verifySignature(pubHex))
        assertTrue("isValid helper must pass with public key", ad.isValid(pubHex))
        assertFalse("isValid must fail-closed if public key is null", ad.isValid(null))
        assertFalse("isValid must fail-closed if public key is blank", ad.isValid(""))

        // Check rejection of bad signature
        val badAd = ad.copy(signature = "deadbeef1234")
        assertFalse("Bad signature must fail verification", badAd.verifySignature(pubHex))
    }

    @Test
    fun testCapabilityLeaseAuthorizationAndExpiry() {
        val keyPair = MeshCryptoSigner.generateKeyPair()
        val pubHex = keyPair.public.encoded.joinToString("") { "%02x".format(it) }

        val leaseId = "lease_12345"
        val grantedAt = System.currentTimeMillis()
        val expiresAt = grantedAt + 300_000L

        val rawPayload = "$leaseId|remote_gpu_inference|granter_desktop|grantee_phone|$grantedAt|$expiresAt|50".toByteArray(Charsets.UTF_8)
        val signature = MeshCryptoSigner.sign(keyPair.private, rawPayload)

        val lease = CapabilityLease(
            leaseId = leaseId,
            capabilityId = "remote_gpu_inference",
            granterNodeId = "granter_desktop",
            granteeNodeId = "grantee_phone",
            grantedAtMs = grantedAt,
            expiresAtMs = expiresAt,
            maxOperationsAllowed = 50,
            signature = signature
        )

        assertTrue("Authentic lease must verify authorized", lease.verifySignature(pubHex))
        assertTrue(lease.isAuthorized(pubHex))
        assertFalse("isAuthorized must fail-closed if public key is null", lease.isAuthorized(null))
        assertFalse("isAuthorized must fail-closed if public key is blank", lease.isAuthorized(""))

        // Check expired lease
        val expiredLease = lease.copy(expiresAtMs = System.currentTimeMillis() - 1000L)
        assertFalse("Expired lease must not be authorized", expiredLease.isAuthorized(pubHex))

        // Check revoked lease
        val revokedLease = lease.copy(isRevoked = true)
        assertFalse("Revoked lease must not be authorized", revokedLease.isAuthorized(pubHex))
    }

    @Test
    fun testChallengeResponseHandshakeVerification() {
        val responderKeyPair = MeshCryptoSigner.generateKeyPair()
        val responderPubHex = responderKeyPair.public.encoded.joinToString("") { "%02x".format(it) }

        val challenge = MeshHandshakeChallenge(
            challengerNodeId = "local_android_node",
            nonce = "secure_random_nonce_9876543210"
        )

        val nonceBytes = challenge.nonce.toByteArray(Charsets.UTF_8)
        val signedNonce = MeshCryptoSigner.sign(responderKeyPair.private, nonceBytes)

        val response = MeshHandshakeResponse(
            challengeId = challenge.challengeId,
            responderNodeId = "remote_desktop_node",
            signedNonce = signedNonce,
            responderPublicKey = responderPubHex
        )

        assertTrue("Challenge-response must verify true with matching signed nonce", response.verify(challenge))

        // Mismatched nonce
        val fakeChallenge = challenge.copy(nonce = "forged_nonce_12345")
        assertFalse("Mismatched challenge nonce must fail verification", response.verify(fakeChallenge))
    }
}

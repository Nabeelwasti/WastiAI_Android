package com.example.data.mesh

import android.util.Log
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.core.CommandSubmissionResult
import com.example.data.node.AdvertisedCapabilityInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Stage 18: Capability Synchronization Delta Model.
 */
data class CapabilityDelta(
    val added: List<AdvertisedCapabilityInfo> = emptyList(),
    val modified: List<AdvertisedCapabilityInfo> = emptyList(),
    val removedCapabilityIds: List<String> = emptyList(),
    val targetFingerprint: String = ""
) {
    fun isEmpty(): Boolean = added.isEmpty() && modified.isEmpty() && removedCapabilityIds.isEmpty()

    fun toJson(): String {
        val root = JSONObject()
        root.put("targetFingerprint", targetFingerprint)

        val addArr = JSONArray()
        added.forEach { addArr.put(serializeCap(it)) }
        root.put("added", addArr)

        val modArr = JSONArray()
        modified.forEach { modArr.put(serializeCap(it)) }
        root.put("modified", modArr)

        val remArr = JSONArray()
        removedCapabilityIds.forEach { remArr.put(it) }
        root.put("removed", remArr)

        return root.toString()
    }

    companion object {
        private fun serializeCap(cap: AdvertisedCapabilityInfo): JSONObject {
            return JSONObject().apply {
                put("capabilityId", cap.capabilityId)
                put("version", cap.version)
                put("realityState", cap.realityState.name)
                put("provider", cap.provider)
                put("resourceRequirements", cap.resourceRequirements)
                put("isLocallyExecutable", cap.isLocallyExecutable)
                val opsArr = JSONArray()
                cap.supportedOperations.forEach { opsArr.put(it) }
                put("supportedOperations", opsArr)
            }
        }

        fun fromJson(jsonStr: String): CapabilityDelta {
            val root = JSONObject(jsonStr)
            val targetFingerprint = root.optString("targetFingerprint", "")

            val addedList = mutableListOf<AdvertisedCapabilityInfo>()
            val addArr = root.optJSONArray("added")
            if (addArr != null) {
                for (i in 0 until addArr.length()) {
                    deserializeCap(addArr.getJSONObject(i))?.let { addedList.add(it) }
                }
            }

            val modList = mutableListOf<AdvertisedCapabilityInfo>()
            val modArr = root.optJSONArray("modified")
            if (modArr != null) {
                for (i in 0 until modArr.length()) {
                    deserializeCap(modArr.getJSONObject(i))?.let { modList.add(it) }
                }
            }

            val remList = mutableListOf<String>()
            val remArr = root.optJSONArray("removed")
            if (remArr != null) {
                for (i in 0 until remArr.length()) {
                    remList.add(remArr.getString(i))
                }
            }

            return CapabilityDelta(
                added = addedList,
                modified = modList,
                removedCapabilityIds = remList,
                targetFingerprint = targetFingerprint
            )
        }

        private fun deserializeCap(obj: JSONObject): AdvertisedCapabilityInfo? {
            val capId = obj.optString("capabilityId", "")
            if (capId.isBlank()) return null
            val version = obj.optString("version", "1.0.0")
            val stateStr = obj.optString("realityState", "IMPLEMENTED_NOT_LIVE_VERIFIED")
            val realityState = try {
                CapabilityRealityState.valueOf(stateStr)
            } catch (_: Exception) {
                CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED
            }
            val provider = obj.optString("provider", "RemoteNode")
            val reqs = obj.optString("resourceRequirements", "LOW")
            val isLocallyExecutable = obj.optBoolean("isLocallyExecutable", true)

            val opsList = mutableListOf<String>()
            val opsArr = obj.optJSONArray("supportedOperations")
            if (opsArr != null) {
                for (i in 0 until opsArr.length()) {
                    opsList.add(opsArr.getString(i))
                }
            }

            return AdvertisedCapabilityInfo(
                capabilityId = capId,
                version = version,
                realityState = realityState,
                provider = provider,
                supportedOperations = opsList,
                resourceRequirements = reqs,
                isLocallyExecutable = isLocallyExecutable,
                lastVerifiedTimestamp = System.currentTimeMillis()
            )
        }

        fun computeDelta(
            currentMap: Map<String, AdvertisedCapabilityInfo>,
            targetMap: Map<String, AdvertisedCapabilityInfo>,
            targetFingerprint: String
        ): CapabilityDelta {
            val added = mutableListOf<AdvertisedCapabilityInfo>()
            val modified = mutableListOf<AdvertisedCapabilityInfo>()
            val removed = mutableListOf<String>()

            for ((capId, targetCap) in targetMap) {
                val currentCap = currentMap[capId]
                if (currentCap == null) {
                    added.add(targetCap)
                } else if (currentCap.version != targetCap.version || currentCap.realityState != targetCap.realityState || currentCap.resourceRequirements != targetCap.resourceRequirements) {
                    modified.add(targetCap)
                }
            }

            for (capId in currentMap.keys) {
                if (!targetMap.containsKey(capId)) {
                    removed.add(capId)
                }
            }

            return CapabilityDelta(
                added = added,
                modified = modified,
                removedCapabilityIds = removed,
                targetFingerprint = targetFingerprint
            )
        }
    }
}

/**
 * Stage 18: Replay & Idempotency Guard for Mesh Communications.
 * Protects against replay attacks, duplicate task execution, and stale messages.
 */
class MeshReplayAndIdempotencyGuard(
    private val maxTimeDriftMs: Long = 5 * 60 * 1000L // 5 minute validity window
) {
    private val seenMessageIds = ConcurrentHashMap<String, Long>()
    private val nodeSequences = ConcurrentHashMap<String, Long>()
    private val idempotencyResults = ConcurrentHashMap<String, CommandSubmissionResult>()
    private val activeNodeIdentifiers = ConcurrentSkipListSet<String>()

    fun recordActiveNode(nodeId: String) {
        if (activeNodeIdentifiers.add(nodeId)) {
            Log.d("MeshReplayGuard", "Registered active mesh node sequence: $nodeId")
        }
    }

    fun validateAndRecordMessage(envelope: WastiMeshEnvelope): Result<Boolean> {
        val now = System.currentTimeMillis()
        recordActiveNode(envelope.senderNodeId)

        // 1. Time Drift Check (Reject stale or absurdly futuristic messages)
        val drift = Math.abs(now - envelope.timestamp)
        if (drift > maxTimeDriftMs) {
            return Result.failure(
                SecurityException("Message timestamp drift ($drift ms) exceeds allowable window ($maxTimeDriftMs ms). Replay or clock desynchronization suspected.")
            )
        }

        // 2. Duplicate Message ID Check
        if (envelope.messageId.isNotBlank()) {
            val prev = seenMessageIds.putIfAbsent(envelope.messageId, envelope.timestamp)
            if (prev != null) {
                return Result.failure(
                    SecurityException("Duplicate messageId detected: ${envelope.messageId}. Message rejected as replay attempt.")
                )
            }
        }

        // 3. Monotonic Sequence Number Check for Streamed Frames (if sequence > 0)
        if (envelope.sequenceNumber > 0) {
            val key = "${envelope.senderNodeId}_${envelope.messageType.name}"
            val lastSeq = nodeSequences.getOrDefault(key, 0L)
            if (envelope.sequenceNumber <= lastSeq) {
                return Result.failure(
                    SecurityException("Non-monotonic sequence number ${envelope.sequenceNumber} <= last seen $lastSeq for node ${envelope.senderNodeId}")
                )
            }
            nodeSequences[key] = envelope.sequenceNumber
        }

        // Prune old seen IDs periodically
        if (seenMessageIds.size > 5000) {
            pruneStaleRecords(now)
        }

        return Result.success(true)
    }

    fun getIdempotentResult(requestId: String): CommandSubmissionResult? {
        if (requestId.isBlank()) return null
        return idempotencyResults[requestId]
    }

    fun recordIdempotentResult(requestId: String, result: CommandSubmissionResult) {
        if (requestId.isNotBlank()) {
            idempotencyResults[requestId] = result
        }
    }

    private fun pruneStaleRecords(now: Long) {
        val cutoff = now - maxTimeDriftMs
        seenMessageIds.entries.removeIf { it.value < cutoff }
    }

    fun clear() {
        seenMessageIds.clear()
        nodeSequences.clear()
        idempotencyResults.clear()
    }
}

/**
 * Stage 18: Protocol Version Negotiator.
 * Negotiates mutually supported binary protocol versions without breaking backward compatibility.
 */
object MeshProtocolNegotiator {
    val SUPPORTED_VERSIONS = setOf(1, 2)
    const val PREFERRED_VERSION = 2

    fun negotiate(proposedVersion: Int): NegotiationResult {
        if (proposedVersion <= 0) {
            return NegotiationResult.Incompatible("Invalid protocol version: $proposedVersion")
        }

        return if (SUPPORTED_VERSIONS.contains(proposedVersion)) {
            NegotiationResult.Compatible(
                negotiatedVersion = proposedVersion,
                isPreferred = proposedVersion == PREFERRED_VERSION
            )
        } else if (proposedVersion > PREFERRED_VERSION) {
            // Backward compatibility fallback to our highest supported version
            NegotiationResult.Compatible(
                negotiatedVersion = PREFERRED_VERSION,
                isPreferred = true
            )
        } else {
            NegotiationResult.Incompatible(
                "Unsupported legacy protocol version $proposedVersion. Minimum supported is 1."
            )
        }
    }

    sealed class NegotiationResult {
        data class Compatible(val negotiatedVersion: Int, val isPreferred: Boolean) : NegotiationResult()
        data class Incompatible(val reason: String) : NegotiationResult()
    }
}

/**
 * Stage 16: Cryptographic Node Identity & Signature Infrastructure.
 */
data class NodeCryptoIdentity(
    val nodeId: String,
    val publicKeyHex: String,
    val signatureAlgorithm: String = "SHA256withECDSA",
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Stage 16: Signed Capability Advertisement with lease terms and verifiable signature.
 */
data class SignedCapabilityAdvertisement(
    val capabilityInfo: AdvertisedCapabilityInfo,
    val publisherNodeId: String,
    val signature: String,
    val timestamp: Long = System.currentTimeMillis(),
    val leaseDurationMs: Long = 60_000L,
    val isRevoked: Boolean = false
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > (timestamp + leaseDurationMs)
    fun isValid(): Boolean = !isRevoked && !isExpired() && signature.isNotBlank()
}

/**
 * Stage 16: Time-bounded Capability Lease with explicit revocation.
 */
data class CapabilityLease(
    val leaseId: String = java.util.UUID.randomUUID().toString(),
    val capabilityId: String,
    val granterNodeId: String,
    val granteeNodeId: String,
    val grantedAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = System.currentTimeMillis() + 300_000L,
    val maxOperationsAllowed: Int = 100,
    val signature: String = "",
    val isRevoked: Boolean = false
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAtMs
    fun isAuthorized(): Boolean = !isRevoked && !isExpired()
}

/**
 * Stage 16: Cryptographic Challenge-Response Handshake for Authenticated Node Pairing.
 */
data class MeshHandshakeChallenge(
    val challengeId: String = java.util.UUID.randomUUID().toString(),
    val challengerNodeId: String,
    val nonce: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class MeshHandshakeResponse(
    val challengeId: String,
    val responderNodeId: String,
    val signedNonce: String,
    val responderPublicKey: String,
    val timestamp: Long = System.currentTimeMillis()
)


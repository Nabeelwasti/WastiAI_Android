package com.example.data.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/**
 * Stage 18: Canonical Message Types for the Wasti Cross-Platform Mesh Protocol.
 */
enum class WastiMeshMessageType(val typeId: Int) {
    UNKNOWN(0),
    HELLO(1),
    HELLO_ACK(2),
    PROTOCOL_NEGOTIATE(3),
    PROTOCOL_NEGOTIATE_ACK(4),
    AUTHENTICATE(5),
    AUTHENTICATE_ACK(6),
    CAPABILITY_FINGERPRINT_CHECK(7),
    CAPABILITY_FINGERPRINT_MATCH(8),
    CAPABILITY_DELTA_REQUEST(9),
    CAPABILITY_DELTA_RESPONSE(10),
    CAPABILITY_SNAPSHOT(11),
    CAPABILITY_SNAPSHOT_ACK(12),
    HEARTBEAT(13),
    HEARTBEAT_ACK(14),
    TELEMETRY_UPDATE(15),
    TASK_OFFER(16),
    TASK_ACCEPT(17),
    TASK_REJECT(18),
    TASK_PROGRESS(19),
    TASK_RESULT(20),
    TASK_CANCEL(21),
    LEASE_ACQUIRE(22),
    LEASE_RENEW(23),
    LEASE_RELEASE(24),
    LEASE_ACK(25),
    COMMAND_SUBMIT(26),
    COMMAND_RESULT(27),
    EMERGENCY_STOP(28),
    EMERGENCY_STOP_ACK(29),
    DIAGNOSTIC_QUERY(30),
    DIAGNOSTIC_RESPONSE(31),
    SECURITY_BLOCK(32);

    companion object {
        private val map = values().associateBy { it.typeId }
        fun fromId(typeId: Int): WastiMeshMessageType = map[typeId] ?: UNKNOWN
    }
}

/**
 * Stage 18: Canonical Binary Protocol Envelope for Cross-Platform Mesh.
 * Ensures bounded payload sizes, tamper detection, versioning, replay protection, and deterministic encoding.
 */
data class WastiMeshEnvelope(
    val protocolVersion: Int = 2,
    val messageType: WastiMeshMessageType,
    val flags: Int = 0, // Bit 0: Encrypted, Bit 1: Delta, Bit 2: Compressed, Bit 3: RequiresAck
    val timestamp: Long = System.currentTimeMillis(),
    val sequenceNumber: Long = 0L,
    val messageId: String = UUID.randomUUID().toString(),
    val requestId: String = "",
    val correlationId: String = "",
    val senderNodeId: String,
    val sessionToken: String = "",
    val payloadBytes: ByteArray = ByteArray(0),
    val integrityHash: Long = 0L // CRC32 checksum of payload for rapid tamper detection
) {
    companion object {
        const val MAGIC_BYTES: Int = 0x57415354 // "WAST" in ASCII hex
        const val CURRENT_PROTOCOL_VERSION: Int = 2
        const val MIN_SUPPORTED_PROTOCOL_VERSION: Int = 1
        const val MAX_PAYLOAD_SIZE_BYTES: Int = 1024 * 1024 // 1 MB strict upper limit for binary frame
        const val MAX_CONTROL_PAYLOAD_SIZE_BYTES: Int = 64 * 1024 // 64 KB control limit

        fun computeCrc32(data: ByteArray): Long {
            val crc = CRC32()
            crc.update(data)
            return crc.value
        }

        fun computeSha256(data: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(data)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WastiMeshEnvelope

        if (protocolVersion != other.protocolVersion) return false
        if (messageType != other.messageType) return false
        if (flags != other.flags) return false
        if (timestamp != other.timestamp) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (messageId != other.messageId) return false
        if (requestId != other.requestId) return false
        if (correlationId != other.correlationId) return false
        if (senderNodeId != other.senderNodeId) return false
        if (sessionToken != other.sessionToken) return false
        if (!payloadBytes.contentEquals(other.payloadBytes)) return false
        if (integrityHash != other.integrityHash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + messageType.hashCode()
        result = 31 * result + flags
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + requestId.hashCode()
        result = 31 * result + correlationId.hashCode()
        result = 31 * result + senderNodeId.hashCode()
        result = 31 * result + sessionToken.hashCode()
        result = 31 * result + payloadBytes.contentHashCode()
        result = 31 * result + integrityHash.hashCode()
        return result
    }
}

/**
 * Stage 18: Canonical Binary Serializer for Wasti Mesh Envelopes.
 * High-performance, zero-dependency binary framing with deterministic endianness and strict bounds checking.
 */
object WastiBinaryProtocolSerializer {

    fun serialize(envelope: WastiMeshEnvelope): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // 1. Magic Header
        dos.writeInt(WastiMeshEnvelope.MAGIC_BYTES)

        // 2. Protocol Version (Short)
        dos.writeShort(envelope.protocolVersion)

        // 3. Message Type (Short)
        dos.writeShort(envelope.messageType.typeId)

        // 4. Flags (Short)
        dos.writeShort(envelope.flags)

        // 5. Timestamps & Sequence
        dos.writeLong(envelope.timestamp)
        dos.writeLong(envelope.sequenceNumber)

        // 6. Header String Identifiers
        dos.writeUTF(envelope.messageId)
        dos.writeUTF(envelope.requestId)
        dos.writeUTF(envelope.correlationId)
        dos.writeUTF(envelope.senderNodeId)
        dos.writeUTF(envelope.sessionToken)

        // 7. Payload Length & Integrity Checksum
        val payload = envelope.payloadBytes
        if (payload.size > WastiMeshEnvelope.MAX_PAYLOAD_SIZE_BYTES) {
            throw IllegalArgumentException("Payload size ${payload.size} exceeds maximum allowable limit of ${WastiMeshEnvelope.MAX_PAYLOAD_SIZE_BYTES} bytes")
        }

        val calculatedCrc = WastiMeshEnvelope.computeCrc32(payload)
        dos.writeInt(payload.size)
        dos.writeLong(calculatedCrc)

        // 8. Raw Payload Bytes
        if (payload.isNotEmpty()) {
            dos.write(payload)
        }

        dos.flush()
        return bos.toByteArray()
    }

    fun deserialize(bytes: ByteArray): Result<WastiMeshEnvelope> {
        return try {
            if (bytes.size < 34) { // Minimum required header length
                return Result.failure(IllegalArgumentException("Frame too short for valid Wasti mesh envelope: ${bytes.size} bytes"))
            }

            val bis = ByteArrayInputStream(bytes)
            val dis = DataInputStream(bis)

            // 1. Validate Magic Header
            val magic = dis.readInt()
            if (magic != WastiMeshEnvelope.MAGIC_BYTES) {
                return Result.failure(IllegalArgumentException("Invalid mesh magic header: 0x${Integer.toHexString(magic)} (expected 0x${Integer.toHexString(WastiMeshEnvelope.MAGIC_BYTES)})"))
            }

            // 2. Protocol Version
            val version = dis.readUnsignedShort()
            if (version < WastiMeshEnvelope.MIN_SUPPORTED_PROTOCOL_VERSION) {
                return Result.failure(IllegalArgumentException("Unsupported mesh protocol version: $version (minimum supported is ${WastiMeshEnvelope.MIN_SUPPORTED_PROTOCOL_VERSION})"))
            }

            // 3. Message Type
            val typeId = dis.readUnsignedShort()
            val messageType = WastiMeshMessageType.fromId(typeId)

            // 4. Flags
            val flags = dis.readUnsignedShort()

            // 5. Timestamps & Sequence
            val timestamp = dis.readLong()
            val sequenceNumber = dis.readLong()

            // 6. Header String Identifiers
            val messageId = dis.readUTF()
            val requestId = dis.readUTF()
            val correlationId = dis.readUTF()
            val senderNodeId = dis.readUTF()
            val sessionToken = dis.readUTF()

            // 7. Payload Length & Integrity
            val payloadLength = dis.readInt()
            if (payloadLength < 0 || payloadLength > WastiMeshEnvelope.MAX_PAYLOAD_SIZE_BYTES) {
                return Result.failure(IllegalArgumentException("Oversized or invalid payload length: $payloadLength bytes"))
            }

            val expectedCrc = dis.readLong()

            // 8. Read Payload Bytes
            val payload = ByteArray(payloadLength)
            if (payloadLength > 0) {
                dis.readFully(payload)
            }

            // 9. Verify CRC32 Integrity
            val actualCrc = WastiMeshEnvelope.computeCrc32(payload)
            if (actualCrc != expectedCrc) {
                return Result.failure(SecurityException("Payload CRC32 checksum mismatch: expected $expectedCrc but got $actualCrc (tampering or transmission corruption detected)"))
            }

            Result.success(
                WastiMeshEnvelope(
                    protocolVersion = version,
                    messageType = messageType,
                    flags = flags,
                    timestamp = timestamp,
                    sequenceNumber = sequenceNumber,
                    messageId = messageId,
                    requestId = requestId,
                    correlationId = correlationId,
                    senderNodeId = senderNodeId,
                    sessionToken = sessionToken,
                    payloadBytes = payload,
                    integrityHash = actualCrc
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Malformed mesh envelope: ${e.message}", e))
        }
    }
}

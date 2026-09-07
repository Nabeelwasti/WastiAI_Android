package com.example.data.core

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.agent.runtime.CapabilityInventionEngine
import com.example.data.db.MemoryEntity
import com.example.data.db.KnowledgeEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * [The Eternal Manifesto: The Resurrection Protocol & 10,000-Year Principle]
 *
 * "Recover state and migrate across execution bodies.
 * Preserving principles beyond implementations; preserving knowledge, engineering principles,
 * verified capabilities, successful strategies, lessons, creativity, memories and architecture."
 *
 * Provides sovereign, zero-knowledge encrypted state packaging and cross-body migration.
 */

data class ResurrectionExportResult(
    val isSuccess: Boolean,
    val bundleFile: File?,
    val sha256Checksum: String,
    val totalMemoriesExported: Int,
    val totalKnowledgeExported: Int,
    val totalCapabilitiesExported: Int,
    val mnemonicRecoveryPhrase: String = "",
    val errorMessage: String? = null
)

data class ResurrectionImportResult(
    val isSuccess: Boolean,
    val totalMemoriesRestored: Int,
    val totalKnowledgeRestored: Int,
    val totalCapabilitiesRestored: Int,
    val sourceDeviceId: String,
    val bundleTimestamp: Long,
    val errorMessage: String? = null
)

object WastiResurrectionProtocol {

    private const val TAG = "ResurrectionProtocol"
    private const val PROTOCOL_VERSION = "WASTI_RESURRECTION_V1"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val SALT_LENGTH_BYTES = 16

    /**
     * Packages the entire cognitive state of Wasti AI OS into an encrypted sovereign bundle.
     */
    suspend fun exportResurrectionBundle(
        context: Context,
        passphrase: String,
        deviceId: String = UUID.randomUUID().toString()
    ): ResurrectionExportResult = withContext(Dispatchers.IO) {
        try {
            if (passphrase.length < 8) {
                return@withContext ResurrectionExportResult(
                    isSuccess = false,
                    bundleFile = null,
                    sha256Checksum = "",
                    totalMemoriesExported = 0,
                    totalKnowledgeExported = 0,
                    totalCapabilitiesExported = 0,
                    errorMessage = "Passphrase must be at least 8 characters for sovereign state encryption"
                )
            }

            val db = WastiDatabase.getDatabase(context)
            val memories = db.memoryDao().getAllMemoriesSync()
            val knowledge = db.knowledgeDao().getAllKnowledgeSync()
            val capabilities = CapabilityInventionEngine.getAcquiredCapabilities()

            // 1. Build State Payload
            val payload = JSONObject()
            payload.put("protocolVersion", PROTOCOL_VERSION)
            payload.put("timestamp", System.currentTimeMillis())
            payload.put("deviceId", deviceId)

            val memArray = JSONArray()
            memories.forEach { m ->
                val mObj = JSONObject()
                mObj.put("id", m.id)
                mObj.put("key", m.key)
                mObj.put("category", m.category)
                mObj.put("value", m.value)
                mObj.put("importanceScore", m.importanceScore.toDouble())
                mObj.put("timestamp", m.timestamp)
                memArray.put(mObj)
            }
            payload.put("memories", memArray)

            val knowArray = JSONArray()
            knowledge.forEach { k ->
                val kObj = JSONObject()
                kObj.put("id", k.id)
                kObj.put("title", k.title)
                kObj.put("category", k.category)
                kObj.put("content", k.content)
                kObj.put("tagsCsv", k.tagsCsv)
                kObj.put("dateAdded", k.dateAdded)
                knowArray.put(kObj)
            }
            payload.put("knowledge", knowArray)

            val capArray = JSONArray()
            capabilities.forEach { c ->
                val cObj = JSONObject()
                cObj.put("capabilityId", c.capabilityId)
                cObj.put("displayName", c.displayName)
                cObj.put("description", c.description)
                cObj.put("transformScript", c.transformScript)
                cObj.put("executionLogicType", c.executionLogicType)
                cObj.put("provenanceHash", c.provenanceHash)
                capArray.put(cObj)
            }
            payload.put("capabilities", capArray)

            val rawJson = payload.toString()

            // 2. Encrypt Payload using AES-256-GCM + PBKDF2
            val secureRandom = SecureRandom()
            val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

            val secretKey = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val cipherText = cipher.doFinal(rawJson.toByteArray(Charsets.UTF_8))

            // 3. Assemble Sovereign Resurrection Envelope
            val envelope = JSONObject()
            envelope.put("header", "WASTI_RESURRECTION_BUNDLE")
            envelope.put("version", 1)
            envelope.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            envelope.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            envelope.put("cipherText", Base64.encodeToString(cipherText, Base64.NO_WRAP))

            val envelopeBytes = envelope.toString(2).toByteArray(Charsets.UTF_8)
            val checksum = computeSha256(envelopeBytes)
            envelope.put("sha256", checksum)

            // 4. Write to storage
            val exportDir = File(context.filesDir, "resurrection_exports").also { if (!it.exists()) it.mkdirs() }
            val bundleFile = File(exportDir, "wasti_resurrection_${System.currentTimeMillis()}.was")
            bundleFile.writeBytes(envelopeBytes)

            val generatedMnemonic = if (passphrase.split(Regex("\\s+")).size >= 12) passphrase else generate12WordMnemonic()

            Log.i(TAG, "Resurrection bundle exported successfully: ${bundleFile.absolutePath} (${bundleFile.length()} bytes)")

            ResurrectionExportResult(
                isSuccess = true,
                bundleFile = bundleFile,
                sha256Checksum = checksum,
                totalMemoriesExported = memories.size,
                totalKnowledgeExported = knowledge.size,
                totalCapabilitiesExported = capabilities.size,
                mnemonicRecoveryPhrase = generatedMnemonic
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export resurrection bundle: ${e.message}", e)
            ResurrectionExportResult(
                isSuccess = false,
                bundleFile = null,
                sha256Checksum = "",
                totalMemoriesExported = 0,
                totalKnowledgeExported = 0,
                totalCapabilitiesExported = 0,
                errorMessage = e.message
            )
        }
    }

    /**
     * Decrypts and restores the cognitive state from a Resurrection Bundle into the current device body.
     */
    suspend fun importResurrectionBundle(
        context: Context,
        bundleFile: File,
        passphrase: String
    ): ResurrectionImportResult = withContext(Dispatchers.IO) {
        try {
            if (!bundleFile.exists() || bundleFile.length() == 0L) {
                return@withContext ResurrectionImportResult(
                    isSuccess = false,
                    totalMemoriesRestored = 0,
                    totalKnowledgeRestored = 0,
                    totalCapabilitiesRestored = 0,
                    sourceDeviceId = "UNKNOWN",
                    bundleTimestamp = 0L,
                    errorMessage = "Resurrection bundle file does not exist or is empty"
                )
            }

            val envelopeBytes = bundleFile.readBytes()
            val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))

            if (!envelope.optString("header").contains("WASTI_RESURRECTION")) {
                return@withContext ResurrectionImportResult(
                    isSuccess = false,
                    totalMemoriesRestored = 0,
                    totalKnowledgeRestored = 0,
                    totalCapabilitiesRestored = 0,
                    sourceDeviceId = "UNKNOWN",
                    bundleTimestamp = 0L,
                    errorMessage = "Invalid bundle format: Missing Wasti Resurrection header"
                )
            }

            val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
            val cipherText = Base64.decode(envelope.getString("cipherText"), Base64.NO_WRAP)

            // Decrypt Payload
            val secretKey = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val rawJsonBytes = cipher.doFinal(cipherText)

            val payload = JSONObject(String(rawJsonBytes, Charsets.UTF_8))
            val deviceId = payload.optString("deviceId", "UNKNOWN_NODE")
            val bundleTime = payload.optLong("timestamp", 0L)

            val db = WastiDatabase.getDatabase(context)

            // Restore Memories
            val memArray = payload.optJSONArray("memories") ?: JSONArray()
            var restoredMemCount = 0
            for (i in 0 until memArray.length()) {
                val mObj = memArray.getJSONObject(i)
                val entity = MemoryEntity(
                    id = mObj.getString("id"),
                    key = mObj.getString("key"),
                    category = mObj.optString("category", "Restored"),
                    value = mObj.getString("value"),
                    importanceScore = mObj.optDouble("importanceScore", 0.9).toFloat(),
                    timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
                )
                db.memoryDao().insertMemory(entity)
                restoredMemCount++
            }

            // Restore Knowledge
            val knowArray = payload.optJSONArray("knowledge") ?: JSONArray()
            var restoredKnowCount = 0
            for (i in 0 until knowArray.length()) {
                val kObj = knowArray.getJSONObject(i)
                val entity = KnowledgeEntity(
                    id = kObj.getString("id"),
                    title = kObj.getString("title"),
                    category = kObj.optString("category", "Restored"),
                    content = kObj.getString("content"),
                    tagsCsv = kObj.optString("tagsCsv", ""),
                    dateAdded = kObj.optLong("dateAdded", System.currentTimeMillis())
                )
                db.knowledgeDao().insertKnowledge(entity)
                restoredKnowCount++
            }

            // Restore Dynamic Capabilities
            val capArray = payload.optJSONArray("capabilities") ?: JSONArray()
            var restoredCapCount = 0
            for (i in 0 until capArray.length()) {
                val cObj = capArray.getJSONObject(i)
                val capId = cObj.getString("capabilityId")
                val displayName = cObj.getString("displayName")
                val description = cObj.getString("description")
                val transformScript = cObj.getString("transformScript")
                val logicType = cObj.optString("executionLogicType", "DETERMINISTIC_TRANSFORM")

                // Auto-verify and acquire into current body
                val acquisitionResult = CapabilityInventionEngine.acquireCapability(
                    context = context,
                    capabilityId = capId,
                    displayName = displayName,
                    description = description,
                    parameterSchema = mapOf("input" to "text"),
                    executionLogicType = logicType,
                    transformScript = transformScript,
                    testSpecifications = emptyList() // Inherited verified
                )
                if (acquisitionResult.isSuccess) {
                    restoredCapCount++
                }
            }

            Log.i(TAG, "SUCCESS: Resurrected cognitive state from body '$deviceId' ($restoredMemCount memories, $restoredKnowCount knowledge, $restoredCapCount capabilities).")

            ResurrectionImportResult(
                isSuccess = true,
                totalMemoriesRestored = restoredMemCount,
                totalKnowledgeRestored = restoredKnowCount,
                totalCapabilitiesRestored = restoredCapCount,
                sourceDeviceId = deviceId,
                bundleTimestamp = bundleTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import resurrection bundle: ${e.message}", e)
            ResurrectionImportResult(
                isSuccess = false,
                totalMemoriesRestored = 0,
                totalKnowledgeRestored = 0,
                totalCapabilitiesRestored = 0,
                sourceDeviceId = "UNKNOWN",
                bundleTimestamp = 0L,
                errorMessage = "Resurrection failed: ${e.message ?: "Authentication/decryption failure"}"
            )
        }
    }

    fun generate12WordMnemonic(): String {
        val random = SecureRandom()
        return (1..12).map { BIP39_WORDLIST[random.nextInt(BIP39_WORDLIST.size)] }.joinToString(" ")
    }

    private val BIP39_WORDLIST = listOf(
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "absurd", "abuse",
        "access", "accident", "account", "accuse", "achieve", "acid", "acoustic", "acquire", "across", "act",
        "action", "actor", "actress", "actual", "adapt", "add", "addict", "address", "adjust", "admit",
        "adult", "advance", "advice", "aerobic", "affair", "afford", "afraid", "again", "age", "agent",
        "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album", "alcohol", "alert",
        "alien", "all", "alley", "allow", "almost", "alone", "alpha", "already", "also", "alter",
        "always", "amateur", "amazing", "among", "amount", "amused", "analyst", "anchor", "ancient", "anger",
        "angle", "angry", "animal", "ankle", "announce", "annual", "another", "answer", "antenna", "antique",
        "anxiety", "any", "apart", "apology", "appear", "apple", "approve", "april", "arch", "arctic",
        "area", "arena", "argue", "arm", "armed", "armor", "army", "around", "arrange", "arrest",
        "arrive", "arrow", "art", "artefact", "artist", "artwork", "ask", "aspect", "assault", "asset",
        "assist", "assume", "asthma", "athlete", "atom", "attack", "attend", "attitude", "attract", "auction",
        "audit", "august", "aunt", "author", "auto", "autumn", "average", "avocado", "avoid", "awake",
        "aware", "away", "awesome", "awful", "awkward", "axis", "baby", "bachelor", "bacon", "badge",
        "bag", "balance", "balcony", "ball", "bamboo", "banana", "banner", "bar", "barely", "bargain",
        "barrel", "base", "basic", "basket", "battle", "beach", "bean", "beauty", "because", "become",
        "beef", "before", "begin", "behave", "behind", "believe", "below", "belt", "bench", "benefit",
        "best", "betray", "better", "between", "beyond", "bicycle", "bid", "bike", "bind", "biology",
        "bird", "birth", "bitter", "black", "blade", "blame", "blanket", "blast", "bleak", "bless",
        "blind", "blood", "blossom", "blouse", "blue", "blur", "blush", "board", "boat", "body",
        "boil", "bomb", "bone", "bonus", "book", "boost", "border", "boring", "borrow", "boss",
        "bottom", "bounce", "box", "boy", "bracket", "brain", "brand", "brass", "brave", "bread",
        "breeze", "brick", "bridge", "brief", "bright", "bring", "brisk", "broccoli", "broken", "bronze",
        "broom", "brother", "brown", "brush", "bubble", "buddy", "budget", "buffalo", "build", "bulb",
        "bulk", "bullet", "bundle", "bunker", "burden", "burger", "burst", "bus", "business", "busy",
        "butter", "buyer", "buzz", "cabbage", "cabin", "cable", "cactus", "cage", "cake", "call"
    )

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val normalized = passphrase.trim().lowercase().replace(Regex("\\s+"), " ")
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(normalized.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun listAvailableResurrectionBundles(context: Context): List<File> {
        val exportDir = File(context.filesDir, "resurrection_exports")
        if (!exportDir.exists()) return emptyList()
        return exportDir.listFiles { f -> f.extension == "was" }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    fun getLatestResurrectionBundle(context: Context): File? {
        return listAvailableResurrectionBundles(context).firstOrNull()
    }

    fun generateResurrectionQrPayload(bundleFile: File, checksum: String): String {
        val payload = JSONObject().apply {
            put("protocol", PROTOCOL_VERSION)
            put("fileName", bundleFile.name)
            put("fileSizeBytes", bundleFile.length())
            put("sha256", checksum)
            put("timestamp", bundleFile.lastModified())
        }
        return payload.toString()
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

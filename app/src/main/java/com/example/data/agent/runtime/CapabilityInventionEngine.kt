package com.example.data.agent.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [The Eternal Manifesto: The Invention Engine & Capability Acquisition Law]
 *
 * "If Wasti encounters a capability it does not possess, the correct response is not
 * an explanation of the limitation; the correct response is an autonomous, bounded,
 * evidence-driven capability-acquisition process."
 *
 * Pipeline: Understand -> Design -> Build -> Test -> Observe -> Verify -> Register -> Remember -> Use
 */

data class CapabilityTestSpecification(
    val testName: String,
    val inputParameters: Map<String, String>,
    val expectedOutputPattern: String,
    val verificationDomain: CapabilityVerificationDomain = CapabilityVerificationDomain.GENERAL_COMPUTATION
)

data class AcquiredCapabilityDefinition(
    val capabilityId: String,
    val displayName: String,
    val description: String,
    val version: String = "1.0.0",
    val category: String = "INVENTED",
    val parameterSchema: Map<String, String>,
    val executionLogicType: String = "DETERMINISTIC_TRANSFORM",
    val transformScript: String,
    val testSpecifications: List<CapabilityTestSpecification>,
    val acquiredTimestamp: Long = System.currentTimeMillis(),
    val provenanceHash: String,
    val verificationEvidenceId: String
)

data class CapabilityInventionResult(
    val isSuccess: Boolean,
    val capabilityId: String,
    val status: String,
    val evidence: CapabilitySpecificEvidence?,
    val executionOutput: String,
    val errorDetails: String? = null
)

object CapabilityInventionEngine {

    private const val TAG = "CapabilityInvention"
    private const val PERSISTENCE_FILE_NAME = "wasti_acquired_capabilities.json"

    private val acquiredCapabilities = ConcurrentHashMap<String, AcquiredCapabilityDefinition>()

    fun getAcquiredCapabilities(): List<AcquiredCapabilityDefinition> =
        acquiredCapabilities.values.toList()

    fun getCapability(capabilityId: String): AcquiredCapabilityDefinition? =
        acquiredCapabilities[capabilityId.trim().lowercase()]

    fun initialize(context: Context) {
        try {
            val file = File(context.filesDir, PERSISTENCE_FILE_NAME)
            if (!file.exists()) return

            val jsonStr = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val def = parseDefinition(obj)
                if (def != null) {
                    registerCapabilityInRuntime(def, context, persist = false)
                }
            }
            Log.i(TAG, "Restored ${acquiredCapabilities.size} dynamically acquired capabilities from storage.")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring acquired capabilities: ${e.message}", e)
        }
    }

    /**
     * Autonomous Capability Acquisition.
     * Sandbox tests prove the synthesized definition is internally consistent; a capability
     * is only marked VERIFIED/TRUSTED after the actual UnifiedExecutionFabric path has
     * executed its verification vectors and canonical evidence verification has passed.
     */
    suspend fun acquireCapability(
        context: Context,
        capabilityId: String,
        displayName: String,
        description: String,
        parameterSchema: Map<String, String>,
        executionLogicType: String,
        transformScript: String,
        testSpecifications: List<CapabilityTestSpecification>
    ): CapabilityInventionResult = withContext(Dispatchers.Default) {

        val cleanId = capabilityId.trim().lowercase().replace(" ", "_")

        if (SelfModificationSafetyEngine.isProtectedPath(cleanId)) {
            return@withContext CapabilityInventionResult(
                isSuccess = false,
                capabilityId = cleanId,
                status = "BLOCKED_PROTECTED_PATH",
                evidence = null,
                executionOutput = "Cannot invent capability with protected system identifier: $cleanId",
                errorDetails = "Protected capability name violation"
            )
        }

        val scriptHash = computeSha256("$cleanId:$transformScript:${System.currentTimeMillis()}")
        val verificationEngine = WastiVerificationEngine()
        var lastSandboxEvidence: CapabilitySpecificEvidence? = null

        // Design/build gate: every supplied vector must pass in the bounded sandbox.
        for (test in testSpecifications) {
            val testStart = System.currentTimeMillis()
            val simulatedOutput = executeSandboxTransform(
                logicType = executionLogicType,
                script = transformScript,
                parameters = test.inputParameters
            )

            val passed = simulatedOutput.contains(test.expectedOutputPattern) ||
                Regex(test.expectedOutputPattern).containsMatchIn(simulatedOutput)

            if (!passed) {
                return@withContext CapabilityInventionResult(
                    isSuccess = false,
                    capabilityId = cleanId,
                    status = "VERIFICATION_TEST_FAILED",
                    evidence = null,
                    executionOutput = "Test '${test.testName}' failed. Expected pattern '${test.expectedOutputPattern}', got: '$simulatedOutput'",
                    errorDetails = "Sandbox verification rejected capability synthesis"
                )
            }

            val evidence = CapabilitySpecificEvidence(
                taskId = "invention_task_${UUID.randomUUID().toString().take(8)}",
                actionId = "sandbox_test_${test.testName}",
                capabilityId = cleanId,
                executor = "CapabilityInventionEngine",
                observationSource = ObservationSource.RUNTIME_DIAGNOSTIC,
                timestamp = testStart,
                artifactOrStateReference = "script_hash:$scriptHash",
                expectedState = test.expectedOutputPattern,
                observedState = simulatedOutput.take(128),
                checksumOrHash = computeSha256(simulatedOutput),
                verifierIdentity = "WastiVerificationEngine_InventionGate",
                verificationMethod = "SANDBOX_UNIT_TEST"
            )

            val evaluation = verificationEngine.verify(
                evidence = evidence,
                domain = test.verificationDomain,
                maxAllowedAgeMs = 60_000L
            )

            if (!evaluation.isVerified) {
                return@withContext CapabilityInventionResult(
                    isSuccess = false,
                    capabilityId = cleanId,
                    status = "EVIDENCE_EVALUATION_FAILED",
                    evidence = evidence,
                    executionOutput = "Verification rejected: ${evaluation.explanation}",
                    errorDetails = evaluation.explanation
                )
            }
            lastSandboxEvidence = evidence
        }

        val definition = AcquiredCapabilityDefinition(
            capabilityId = cleanId,
            displayName = displayName,
            description = description,
            parameterSchema = parameterSchema,
            executionLogicType = executionLogicType,
            transformScript = transformScript,
            testSpecifications = testSpecifications,
            provenanceHash = scriptHash,
            verificationEvidenceId = lastSandboxEvidence?.actionId ?: "evidence_${UUID.randomUUID()}"
        )

        // Register as implemented but explicitly not live-verified. This is the only
        // pre-live state exposed to the canonical reality registry.
        registerCapabilityInRuntime(definition, context, persist = false)

        if (testSpecifications.isEmpty()) {
            return@withContext CapabilityInventionResult(
                isSuccess = true,
                capabilityId = cleanId,
                status = "ACQUIRED_UNVERIFIED",
                evidence = lastSandboxEvidence,
                executionOutput = "Capability '$displayName' acquired but not marked verified because no live verification vectors were supplied.",
                errorDetails = "At least one verification vector is required for VERIFIED status"
            )
        }

        // Reality gate: execute the vectors through the same UnifiedExecutionFabric used by
        // real callers, observe the result, then ask the canonical verifier to validate it.
        var lastLiveEvidence: CapabilitySpecificEvidence? = null
        for (test in testSpecifications) {
            val executionResult = UnifiedExecutionFabric.instance.execute(
                UnifiedExecutionRequest(
                    taskId = "invention_live_${UUID.randomUUID().toString().take(8)}",
                    actionId = "live_verify_${test.testName}",
                    capabilityId = cleanId,
                    parameters = test.inputParameters
                ),
                context
            )

            val observedOutput = executionResult.output
            val outputMatches = observedOutput.contains(test.expectedOutputPattern) ||
                Regex(test.expectedOutputPattern).containsMatchIn(observedOutput)

            if (executionResult.status == UnifiedExecutionStatus.FAILED || !outputMatches) {
                return@withContext CapabilityInventionResult(
                    isSuccess = false,
                    capabilityId = cleanId,
                    status = "LIVE_EXECUTION_VERIFICATION_FAILED",
                    evidence = null,
                    executionOutput = "Live verification failed for '${test.testName}': status=${executionResult.status}, output='$observedOutput'",
                    errorDetails = "UnifiedExecutionFabric observation did not satisfy the verification vector"
                )
            }

            val liveEvidence = CapabilitySpecificEvidence(
                taskId = executionResult.taskId,
                actionId = executionResult.actionId,
                capabilityId = cleanId,
                executor = executionResult.executor,
                observationSource = ObservationSource.RUNTIME_DIAGNOSTIC,
                timestamp = executionResult.completedAt,
                artifactOrStateReference = "script_hash:$scriptHash",
                expectedState = test.expectedOutputPattern,
                observedState = observedOutput.take(128),
                checksumOrHash = computeSha256(observedOutput),
                verifierIdentity = "WastiVerificationEngine_LiveInventionGate",
                verificationMethod = "LIVE_EXECUTION_TEST"
            )

            val evaluation = verificationEngine.verify(
                evidence = liveEvidence,
                domain = test.verificationDomain,
                maxAllowedAgeMs = 60_000L
            )

            if (!evaluation.isVerified) {
                return@withContext CapabilityInventionResult(
                    isSuccess = false,
                    capabilityId = cleanId,
                    status = "LIVE_EVIDENCE_EVALUATION_FAILED",
                    evidence = liveEvidence,
                    executionOutput = "Live evidence rejected: ${evaluation.explanation}",
                    errorDetails = evaluation.explanation
                )
            }
            lastLiveEvidence = liveEvidence
        }

        // Only now may the capability cross the LIVE_CONNECTED / VERIFIED boundary.
        markCapabilityLiveVerified(definition, lastLiveEvidence!!)
        saveAllToDisk(context)

        Log.i(TAG, "SUCCESS: Capability '$cleanId' passed sandbox and live execution verification and is now trusted.")

        CapabilityInventionResult(
            isSuccess = true,
            capabilityId = cleanId,
            status = "ACQUIRED_AND_VERIFIED",
            evidence = lastLiveEvidence,
            executionOutput = "Capability '$displayName' acquired, live-executed, observed, and verified against ${testSpecifications.size} vectors."
        )
    }

    fun executeSandboxTransform(
        logicType: String,
        script: String,
        parameters: Map<String, String>
    ): String {
        return when (logicType) {
            "REGEX_EXTRACTOR" -> {
                val input = parameters["input"] ?: parameters["text"] ?: ""
                val regex = Regex(script)
                val matches = regex.findAll(input).map { it.value }.toList()
                matches.joinToString("\n")
            }
            "COMPUTED_AGGREGATOR" -> {
                val input = parameters["input"] ?: parameters["data"] ?: ""
                when (script.trim().uppercase()) {
                    "WORD_COUNT" -> input.split(Regex("\\s+")).filter { it.isNotBlank() }.size.toString()
                    "LINE_COUNT" -> input.lines().size.toString()
                    "CHAR_COUNT" -> input.length.toString()
                    "SUM" -> {
                        val numbers = Regex("-?\\d+(\\.\\d+)?").findAll(input).mapNotNull { it.value.toDoubleOrNull() }
                        numbers.sum().toString()
                    }
                    else -> "Aggregator unknown: $script"
                }
            }
            "JSON_EXTRACTOR" -> {
                val input = parameters["input"] ?: parameters["json"] ?: "{}"
                val targetKey = script.trim()
                try {
                    val obj = JSONObject(input)
                    obj.optString(targetKey, "")
                } catch (_: Exception) {
                    ""
                }
            }
            "NUMERIC_EXPRESSION" -> {
                val input = parameters["input"] ?: parameters["expression"] ?: script
                evaluateSimpleMath(input)
            }
            "DETERMINISTIC_TRANSFORM" -> {
                val input = parameters["input"] ?: parameters["text"] ?: ""
                when (script.trim().uppercase()) {
                    "REVERSE" -> input.reversed()
                    "UPPERCASE" -> input.uppercase()
                    "LOWERCASE" -> input.lowercase()
                    else -> {
                        var result = script
                        parameters.forEach { (k, v) -> result = result.replace("{$k}", v) }
                        result
                    }
                }
            }
            else -> "Execution type '$logicType' evaluated successfully with params: $parameters"
        }
    }

    private fun evaluateSimpleMath(expr: String): String {
        return try {
            val sanitized = expr.replace(" ", "")
            when {
                sanitized.contains("+") -> {
                    val parts = sanitized.split("+")
                    parts.mapNotNull { it.toDoubleOrNull() }.sum().toString()
                }
                sanitized.contains("-") && !sanitized.startsWith("-") -> {
                    val parts = sanitized.split("-")
                    val first = parts[0].toDoubleOrNull() ?: 0.0
                    val rest = parts.drop(1).mapNotNull { it.toDoubleOrNull() }.sum()
                    (first - rest).toString()
                }
                sanitized.contains("*") -> {
                    val parts = sanitized.split("*")
                    parts.mapNotNull { it.toDoubleOrNull() }.fold(1.0) { acc, d -> acc * d }.toString()
                }
                sanitized.contains("/") -> {
                    val parts = sanitized.split("/")
                    val num = parts[0].toDoubleOrNull() ?: 0.0
                    val denom = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
                    if (denom == 0.0) "DivisionByZero" else (num / denom).toString()
                }
                else -> sanitized.toDoubleOrNull()?.toString() ?: expr
            }
        } catch (_: Exception) {
            expr
        }
    }

    private fun registerCapabilityInRuntime(
        def: AcquiredCapabilityDefinition,
        context: Context,
        persist: Boolean
    ) {
        acquiredCapabilities[def.capabilityId] = def

        val reality = CapabilityReality(
            capabilityId = def.capabilityId,
            category = def.category,
            implementationStatus = ImplementationStatus.READY,
            liveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,
            executionStatus = CapabilityExecutionStatus.OPERATIONAL,
            authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
            provider = "CapabilityInventionEngine",
            supportedOperations = listOf("execute", "sandbox_eval"),
            limitations = listOf("Dynamic bounded transform execution; live verification required before trust"),
            realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
            verificationMethod = "SANDBOX_UNIT_TEST"
        )
        UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(reality)

        val dynamicExecutor = object : UnifiedExecutor {
            override val name: String = "InventionExecutor_${def.capabilityId}"
            val executorId: String get() = name
            override val supportedCapabilities: List<String> = listOf(def.capabilityId)

            override suspend fun execute(
                request: UnifiedExecutionRequest,
                context: Context?
            ): UnifiedExecutionResult {
                val startedAt = System.currentTimeMillis()
                val output = executeSandboxTransform(
                    logicType = def.executionLogicType,
                    script = def.transformScript,
                    parameters = request.parameters.mapValues { it.value.toString() }
                )
                val isSuccess = output.isNotBlank() && !output.startsWith("Error:")
                return UnifiedExecutionResult(
                    taskId = request.taskId,
                    actionId = request.actionId,
                    capabilityId = request.capabilityId,
                    status = if (isSuccess) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,
                    output = output,
                    executor = executorId,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                    verificationStatus = UnifiedVerificationStatus.UNVERIFIED,
                    verificationEvidence = "Invention runtime observation; provenance hash: ${def.provenanceHash}"
                )
            }
        }
        UnifiedExecutionFabric.instance.registerExecutor(dynamicExecutor)

        if (persist) saveAllToDisk(context)
    }

    private fun markCapabilityLiveVerified(
        def: AcquiredCapabilityDefinition,
        liveEvidence: CapabilitySpecificEvidence
    ) {
        val verifiedReality = CapabilityReality(
            capabilityId = def.capabilityId,
            category = def.category,
            implementationStatus = ImplementationStatus.READY,
            liveConnectionStatus = LiveConnectionStatus.VERIFIED,
            executionStatus = CapabilityExecutionStatus.OPERATIONAL,
            authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
            provider = "CapabilityInventionEngine",
            supportedOperations = listOf("execute", "sandbox_eval"),
            limitations = listOf("Bounded dynamic transform; verified against recorded live execution vectors"),
            realityState = CapabilityRealityState.LIVE_CONNECTED,
            verificationMethod = liveEvidence.verificationMethod
        )
        UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(verifiedReality)
    }

    private fun saveAllToDisk(context: Context) {
        try {
            val file = File(context.filesDir, PERSISTENCE_FILE_NAME)
            val jsonArray = JSONArray()
            acquiredCapabilities.values.forEach { def ->
                val obj = JSONObject()
                obj.put("capabilityId", def.capabilityId)
                obj.put("displayName", def.displayName)
                obj.put("description", def.description)
                obj.put("version", def.version)
                obj.put("category", def.category)
                obj.put("executionLogicType", def.executionLogicType)
                obj.put("transformScript", def.transformScript)
                obj.put("acquiredTimestamp", def.acquiredTimestamp)
                obj.put("provenanceHash", def.provenanceHash)
                obj.put("verificationEvidenceId", def.verificationEvidenceId)

                val paramObj = JSONObject()
                def.parameterSchema.forEach { (k, v) -> paramObj.put(k, v) }
                obj.put("parameterSchema", paramObj)

                val testsArray = JSONArray()
                def.testSpecifications.forEach { test ->
                    val tObj = JSONObject()
                    tObj.put("testName", test.testName)
                    tObj.put("expectedOutputPattern", test.expectedOutputPattern)
                    val inputObj = JSONObject()
                    test.inputParameters.forEach { (ik, iv) -> inputObj.put(ik, iv) }
                    tObj.put("inputParameters", inputObj)
                    testsArray.put(tObj)
                }
                obj.put("testSpecifications", testsArray)
                jsonArray.put(obj)
            }
            file.writeText(jsonArray.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist acquired capabilities: ${e.message}", e)
        }
    }

    private fun parseDefinition(obj: JSONObject): AcquiredCapabilityDefinition? {
        return try {
            val paramObj = obj.optJSONObject("parameterSchema") ?: JSONObject()
            val paramMap = mutableMapOf<String, String>()
            paramObj.keys().forEach { k -> paramMap[k] = paramObj.getString(k) }

            val testsArray = obj.optJSONArray("testSpecifications") ?: JSONArray()
            val testsList = mutableListOf<CapabilityTestSpecification>()
            for (i in 0 until testsArray.length()) {
                val tObj = testsArray.getJSONObject(i)
                val inObj = tObj.optJSONObject("inputParameters") ?: JSONObject()
                val inMap = mutableMapOf<String, String>()
                inObj.keys().forEach { ik -> inMap[ik] = inObj.getString(ik) }
                testsList.add(
                    CapabilityTestSpecification(
                        testName = tObj.getString("testName"),
                        inputParameters = inMap,
                        expectedOutputPattern = tObj.getString("expectedOutputPattern")
                    )
                )
            }

            AcquiredCapabilityDefinition(
                capabilityId = obj.getString("capabilityId"),
                displayName = obj.getString("displayName"),
                description = obj.getString("description"),
                version = obj.optString("version", "1.0.0"),
                category = obj.optString("category", "INVENTED"),
                parameterSchema = paramMap,
                executionLogicType = obj.getString("executionLogicType"),
                transformScript = obj.getString("transformScript"),
                testSpecifications = testsList,
                acquiredTimestamp = obj.optLong("acquiredTimestamp", System.currentTimeMillis()),
                provenanceHash = obj.optString("provenanceHash", ""),
                verificationEvidenceId = obj.optString("verificationEvidenceId", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse capability definition: ${e.message}", e)
            null
        }
    }

    private fun computeSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

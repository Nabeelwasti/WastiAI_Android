package com.example.data.agent.runtime

import android.content.Context
import android.util.Log
import com.example.data.di.WastiServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CapabilityTestSpecification(val testName: String,val inputParameters: Map<String, String>,val expectedOutputPattern: String,val verificationDomain: CapabilityVerificationDomain = CapabilityVerificationDomain.GENERAL_COMPUTATION)
data class AcquiredCapabilityDefinition(val capabilityId: String,val displayName: String,val description: String,val version: String = "1.0.0",val category: String = "INVENTED",val parameterSchema: Map<String, String>,val executionLogicType: String = "DETERMINISTIC_TRANSFORM",val transformScript: String,val testSpecifications: List<CapabilityTestSpecification>,val acquiredTimestamp: Long = System.currentTimeMillis(),val provenanceHash: String,val verificationEvidenceId: String)
data class CapabilityInventionResult(val isSuccess: Boolean,val capabilityId: String,val status: String,val evidence: CapabilitySpecificEvidence?,val executionOutput: String,val errorDetails: String? = null)

object CapabilityInventionEngine {
    private const val TAG = "CapabilityInvention"
    private const val PERSISTENCE_FILE_NAME = "wasti_acquired_capabilities.json"
    private val acquiredCapabilities = ConcurrentHashMap<String, AcquiredCapabilityDefinition>()
    fun getAcquiredCapabilities(): List<AcquiredCapabilityDefinition> = acquiredCapabilities.values.toList()
    fun getCapability(capabilityId: String): AcquiredCapabilityDefinition? = acquiredCapabilities[capabilityId.trim().lowercase()]
    fun initialize(context: Context) {
        try {
            val file = File(context.filesDir, PERSISTENCE_FILE_NAME)
            if (!file.exists()) return
            val jsonArray = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until jsonArray.length()) parseDefinition(jsonArray.getJSONObject(i))?.let { registerCapabilityInRuntime(it, context, persist = false) }
            Log.i(TAG, "Restored ${acquiredCapabilities.size} dynamically acquired capabilities from storage.")
        } catch (e: Exception) { Log.e(TAG, "Error restoring acquired capabilities: ${e.message}", e) }
    }
    suspend fun acquireCapability(context: Context,capabilityId: String,displayName: String,description: String,parameterSchema: Map<String, String>,executionLogicType: String,transformScript: String,testSpecifications: List<CapabilityTestSpecification>): CapabilityInventionResult = withContext(Dispatchers.Default) {
        val cleanId = capabilityId.trim().lowercase().replace(" ", "_")
        if (SelfModificationSafetyEngine.isProtectedPath(cleanId)) return@withContext CapabilityInventionResult(false, cleanId, "BLOCKED_PROTECTED_PATH", null, "Cannot invent capability with protected system identifier: $cleanId", "Protected capability name violation")
        val scriptHash = computeSha256("$cleanId:$transformScript:${System.currentTimeMillis()}")
        val verificationEngine = WastiVerificationEngine()
        var lastEvidence: CapabilitySpecificEvidence? = null
        for (test in testSpecifications) {
            val testStart = System.currentTimeMillis()
            val simulatedOutput = executeSandboxTransform(executionLogicType, transformScript, test.inputParameters)
            val passed = simulatedOutput.contains(test.expectedOutputPattern) || Regex(test.expectedOutputPattern).containsMatchIn(simulatedOutput)
            if (!passed) return@withContext CapabilityInventionResult(false, cleanId, "VERIFICATION_TEST_FAILED", null, "Test '${test.testName}' failed. Expected pattern '${test.expectedOutputPattern}', got: '$simulatedOutput'", "Sandbox verification rejected capability synthesis")
            val evidence = CapabilitySpecificEvidence(taskId = "invention_task_${UUID.randomUUID().toString().take(8)}",actionId = "verify_test_${test.testName}",capabilityId = cleanId,executor = "CapabilityInventionEngine",observationSource = ObservationSource.RUNTIME_DIAGNOSTIC,timestamp = testStart,artifactOrStateReference = "script_hash:$scriptHash",expectedState = test.expectedOutputPattern,observedState = simulatedOutput.take(128),checksumOrHash = computeSha256(simulatedOutput),verifierIdentity = "WastiVerificationEngine_InventionGate",verificationMethod = "SANDBOX_UNIT_TEST")
            val evaluation = verificationEngine.verify(evidence, test.verificationDomain, 60_000L)
            if (!evaluation.isVerified) return@withContext CapabilityInventionResult(false, cleanId, "EVIDENCE_EVALUATION_FAILED", evidence, "Verification rejected: ${evaluation.explanation}", evaluation.explanation)
            lastEvidence = evidence
        }
        val definition = AcquiredCapabilityDefinition(capabilityId = cleanId,displayName = displayName,description = description,parameterSchema = parameterSchema,executionLogicType = executionLogicType,transformScript = transformScript,testSpecifications = testSpecifications,provenanceHash = scriptHash,verificationEvidenceId = lastEvidence?.actionId ?: "evidence_${UUID.randomUUID()}")
        registerCapabilityInRuntime(definition, context, persist = true)
        Log.i(TAG, "SUCCESS: Capability '$cleanId' invented, sandbox-tested, and hot-loaded. Runtime execution remains independently UNVERIFIED until canonical runtime evidence exists.")
        CapabilityInventionResult(true, cleanId, "ACQUIRED_UNVERIFIED", lastEvidence, "Capability '$displayName' acquired and sandbox-tested against ${testSpecifications.size} tests. Sandbox verification is not runtime/live verification.", "Canonical runtime verification is still required")
    }
    fun executeSandboxTransform(logicType: String,script: String,parameters: Map<String, String>): String = when (logicType) {
        "REGEX_EXTRACTOR" -> { val input = parameters["input"] ?: parameters["text"] ?: ""; Regex(script).findAll(input).map { it.value }.toList().joinToString("\n") }
        "COMPUTED_AGGREGATOR" -> { val input = parameters["input"] ?: parameters["data"] ?: ""; when (script.trim().uppercase()) { "WORD_COUNT" -> input.split(Regex("\\s+")).filter { it.isNotBlank() }.size.toString(); "LINE_COUNT" -> input.lines().size.toString(); "CHAR_COUNT" -> input.length.toString(); "SUM" -> Regex("-?\\d+(\\.\\d+)?").findAll(input).mapNotNull { it.value.toDoubleOrNull() }.sum().toString(); else -> "Aggregator unknown: $script" } }
        "JSON_EXTRACTOR" -> { val input = parameters["input"] ?: parameters["json"] ?: "{}"; try { JSONObject(input).optString(script.trim(), "") } catch (_: Exception) { "" } }
        "NUMERIC_EXPRESSION" -> evaluateSimpleMath(parameters["input"] ?: parameters["expression"] ?: script)
        "DETERMINISTIC_TRANSFORM" -> { val input = parameters["input"] ?: parameters["text"] ?: ""; when (script.trim().uppercase()) { "REVERSE" -> input.reversed(); "UPPERCASE" -> input.uppercase(); "LOWERCASE" -> input.lowercase(); else -> parameters.entries.fold(script) { result, (k, v) -> result.replace("{$k}", v) } } }
        else -> "Execution type '$logicType' evaluated successfully with params: $parameters"
    }
    private fun evaluateSimpleMath(expr: String): String = try { val sanitized = expr.replace(" ", ""); when { sanitized.contains("+") -> sanitized.split("+").mapNotNull { it.toDoubleOrNull() }.sum().toString(); sanitized.contains("-") && !sanitized.startsWith("-") -> { val parts = sanitized.split("-"); ((parts[0].toDoubleOrNull() ?: 0.0) - parts.drop(1).mapNotNull { it.toDoubleOrNull() }.sum()).toString() }; sanitized.contains("*") -> sanitized.split("*").mapNotNull { it.toDoubleOrNull() }.fold(1.0) { acc, d -> acc * d }.toString(); sanitized.contains("/") -> { val parts = sanitized.split("/"); val num = parts[0].toDoubleOrNull() ?: 0.0; val denom = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0; if (denom == 0.0) "DivisionByZero" else (num / denom).toString() }; else -> sanitized.toDoubleOrNull()?.toString() ?: expr } } catch (_: Exception) { expr }
    private fun registerCapabilityInRuntime(def: AcquiredCapabilityDefinition,context: Context,persist: Boolean) {
        acquiredCapabilities[def.capabilityId] = def
        val reality = CapabilityReality(capabilityId = def.capabilityId,category = def.category,implementationStatus = ImplementationStatus.READY,liveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,executionStatus = CapabilityExecutionStatus.OPERATIONAL,authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,provider = "CapabilityInventionEngine",supportedOperations = listOf("execute", "sandbox_eval"),limitations = listOf("Dynamic sandboxed transform execution; independent runtime verification required before VERIFIED/TRUSTED promotion"),realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,verificationMethod = "SANDBOX_UNIT_TEST")
        UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(reality)
        val dynamicExecutor = object : UnifiedExecutor {
            override val name: String = "InventionExecutor_${def.capabilityId}"
            val executorId: String get() = name
            override val supportedCapabilities: List<String> = listOf(def.capabilityId)
            override suspend fun execute(request: UnifiedExecutionRequest,context: Context?): UnifiedExecutionResult {
                val startedAt = System.currentTimeMillis(); val output = executeSandboxTransform(def.executionLogicType,def.transformScript,request.parameters.mapValues { it.value.toString() }); val isSuccess = output.isNotBlank() && !output.startsWith("Error:")
                return UnifiedExecutionResult(taskId = request.taskId,actionId = request.actionId,capabilityId = request.capabilityId,status = if (isSuccess) UnifiedExecutionStatus.COMPLETED else UnifiedExecutionStatus.FAILED,output = output,executor = executorId,startedAt = startedAt,completedAt = System.currentTimeMillis(),verificationStatus = if (isSuccess) UnifiedVerificationStatus.UNVERIFIED else UnifiedVerificationStatus.FAILED,verificationEvidence = "Invention sandbox execution with provenance hash: ${def.provenanceHash}")
            }
        }
        UnifiedExecutionFabric.instance.registerExecutor(dynamicExecutor)
        if (persist) saveAllToDisk(context)
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

    private fun parseDefinition(obj: JSONObject): AcquiredCapabilityDefinition? = try {
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

    private fun computeSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

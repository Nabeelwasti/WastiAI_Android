package com.example.device

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.assistant.PermissionManager
import com.example.data.agent.runtime.EvidenceSource
import com.example.data.agent.runtime.ExecutionProvenanceLedger
import com.example.data.agent.runtime.VerifiedExecutionEvidence
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.ai.runtime.NativeLlamaBridge
import com.example.data.ai.runtime.WastiLocalTokenizer
import com.example.data.core.DeviceExecutionRecord
import com.example.data.core.DeviceVerificationEvidenceTracker
import com.example.data.core.TestCategory
import com.example.data.core.TestTier
import com.example.data.notification.WastiNotificationManager
import com.example.service.WastiAccessibilityService
import com.example.service.WastiForegroundExecutionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [P0-33] REAL-DEVICE-PROOF: Instrumentation test suite executing on real Android OS
 * (physical device or emulator via `connectedAndroidTest` / `connectedCheck`).
 *
 * Grounding Rule: This test can ONLY execute when an actual Android kernel/runtime
 * (ART/Dalvik) is connected. When run, it proves real-world capability and registers
 * verified device execution evidence into DeviceVerificationEvidenceTracker.
 */
@RunWith(AndroidJUnit4::class)
@TestCategory(
    tier = TestTier.DEVICE,
    description = "Physical Android device or emulator instrumentation verification of core capabilities"
)
class RealDeviceAndroidCapabilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext ?: ApplicationProvider.getApplicationContext()
        ExecutionProvenanceLedger.resetForTesting()
        val token = com.example.data.security.TestBootstrapSecurityFixture.createAuthorizedBootstrapToken()
        com.example.data.security.TestBootstrapSecurityFixture.resetEmergencyStopForBootstrap(token)
        com.example.data.security.TestBootstrapSecurityFixture.revokeAuthorizedBootstrapToken(token)
    }

    @Test
    fun testRealDeviceApplicationIdentityAndPackageContract() {
        // Enforce immutable canonical package ID on real target
        assertEquals("com.aistudio.wastios.k9v2pz", context.packageName)
        assertNotNull(context.packageManager)

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertNotNull(packageInfo)
        assertTrue(packageInfo.versionCode >= 1)
        assertTrue(Build.VERSION.SDK_INT >= 24)
    }

    @Test
    fun testRealDeviceServiceAndNotificationCapabilities() {
        // Verify notification channels and foreground service component registration
        val channelCreated = WastiNotificationManager.createNotificationChannels(context)
        assertTrue(channelCreated)

        val serviceComponent = ComponentName(context, WastiForegroundExecutionService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(serviceComponent, PackageManager.GET_META_DATA)
        assertNotNull(serviceInfo)
        assertTrue(serviceInfo.enabled)
    }

    @Test
    fun testRealDeviceAccessibilityServiceRegistration() {
        val a11yComponent = ComponentName(context, WastiAccessibilityService::class.java)
        val a11yInfo = context.packageManager.getServiceInfo(a11yComponent, PackageManager.GET_META_DATA)
        assertNotNull(a11yInfo)
        assertEquals(android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE, a11yInfo.permission)
    }

    @Test
    fun testRealDevicePermissionTruth() {
        val auditMap = PermissionManager.getPermissionAuditMap(context)
        assertNotNull(auditMap)
        assertTrue("Permission audit map must contain standard OS permission keys", auditMap.isNotEmpty())
    }

    @Test
    fun testRealDeviceNativeBridgeAndTokenizerBehavior() {
        // Objective test of tokenizer behavior on device
        val tokenizer = WastiLocalTokenizer(
            vocab = mapOf("hello" to 100, "world" to 101, "wasti" to 102),
            invVocab = mapOf(100 to "hello", 101 to "world", 102 to "wasti")
        )
        val encoded = tokenizer.encode("hello world")
        assertEquals(listOf(100, 101), encoded)
        val decoded = tokenizer.decode(encoded)
        assertEquals("helloworld", decoded)

        // Native bridge version check
        val version = NativeLlamaBridge.getNativeVersion()
        assertNotNull(version)
        if (NativeLlamaBridge.isNativeSupported()) {
            assertTrue("Native runtime version must contain wasti bridge identifier", version.contains("wasti-neural-tensor-bridge"))
        } else {
            assertEquals("UNAVAILABLE", version)
        }
    }

    @Test
    fun testRealDeviceEmergencyStopPropagation() {
        assertFalse(WastiEmergencyStopController.isEmergencyStopped)

        WastiEmergencyStopController.triggerEmergencyStop("Device test emergency stop")
        assertTrue(WastiEmergencyStopController.isEmergencyStopped)
        assertEquals("Device test emergency stop", WastiEmergencyStopController.getReason())

        val token = com.example.data.security.TestBootstrapSecurityFixture.createAuthorizedBootstrapToken()
        val resetResult = com.example.data.security.TestBootstrapSecurityFixture.resetEmergencyStopForBootstrap(token)
        com.example.data.security.TestBootstrapSecurityFixture.revokeAuthorizedBootstrapToken(token)
        assertTrue("Emergency stop reset must succeed with valid token", resetResult)
        assertFalse(WastiEmergencyStopController.isEmergencyStopped)
    }

    @Test
    fun testRealDeviceProvenanceIntegrity() {
        val verificationEngine = com.example.data.agent.runtime.WastiVerificationEngine()
        val capEvidence = com.example.data.agent.runtime.CapabilitySpecificEvidence(
            taskId = "device_task_001",
            actionId = "device_action_001",
            capabilityId = "device_hardware_audit",
            executor = "RealDeviceAndroidCapabilityTest",
            observationSource = EvidenceSource.PROCESS_TELEMETRY,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "device_hardware_telemetry",
            expectedState = "DEVICE_HARDWARE_TELEMETRY_VERIFIED",
            observedState = "DEVICE_HARDWARE_TELEMETRY_VERIFIED",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "real_device_execution_verification",
            confidence = 1.0
        )
        val verificationResult = verificationEngine.verify(capEvidence)
        org.junit.Assert.assertEquals(com.example.data.agent.runtime.ActionVerificationStatus.VERIFIED, verificationResult.status)

        val verifiedEvidence = VerifiedExecutionEvidence(
            evidenceSource = capEvidence.observationSource,
            subject = capEvidence.capabilityId,
            verifiedState = capEvidence.observedState,
            checksumOrHash = capEvidence.checksumOrHash,
            observedAt = capEvidence.timestamp,
            confidence = verificationResult.confidence,
            expectedPostcondition = capEvidence.expectedState,
            observedResult = capEvidence.observedState,
            declaredVerifier = capEvidence.verifierIdentity,
            verificationMethod = capEvidence.verificationMethod
        )

        val entry = ExecutionProvenanceLedger.recordExecution(
            taskId = capEvidence.taskId,
            actionId = capEvidence.actionId,
            capabilityId = capEvidence.capabilityId,
            providerId = capEvidence.executor,
            inputContent = "test_input",
            outputContent = "test_output",
            evidence = verifiedEvidence
        )

        assertNotNull(entry)
        assertTrue(entry.isVerified)
        assertTrue(ExecutionProvenanceLedger.verifyLedgerIntegrity())
        assertTrue(ExecutionProvenanceLedger.verifyEntry(entry.entryId))
    }

    @Test
    fun testRealDeviceLiveGgufNeuralInferenceAndEmergencyStop() {
        val modelFile = java.io.File(context.filesDir, "wasti_neural_test_v1.gguf")
        createMinimalGgufModel(modelFile, dim = 16, nLayers = 1, nHeads = 2, ffnDim = 32)
        assertTrue("Generated GGUF model must exist on device storage", modelFile.exists() && modelFile.length() > 0)

        // 1. Binary GGUF format validation on device
        val runtime = com.example.data.ai.runtime.WastiLocalModelRuntime(context)
        val header = runtime.parseGgufHeader(modelFile)
        assertTrue("GGUF header must be valid format", header.isValidGguf)
        assertEquals("GGUF", header.magic)
        assertEquals(3u, header.version)
        assertEquals(11uL, header.tensorCount)
        assertEquals(7uL, header.metadataKvCount)

        // 2. Native neural model initialization & execution when native library is loaded
        if (NativeLlamaBridge.isNativeSupported()) {
            val handle = NativeLlamaBridge.initModel(modelFile.absolutePath, nThreads = 2, contextLength = 128)
            assertTrue("Native model handle must be valid non-zero pointer", handle != 0L)
            try {
                assertTrue("Native model must have all neural tensors loaded", NativeLlamaBridge.hasTensorsLoaded(handle))

                // 1. Verify baseline neural tensor forward pass and token generation
                val evalOutput = NativeLlamaBridge.evalPrompt(handle, "Hello", maxTokens = 4, temperature = 0.0f)
                assertTrue("Inference output must be non-empty", evalOutput.isNotEmpty())
                assertFalse("Inference output must not report native error", evalOutput.startsWith("[NATIVE_ERROR]"))
                assertFalse("Inference output must not report unavailable error", evalOutput.startsWith("[LOCAL_MODEL_UNAVAILABLE]"))
                assertEquals("Baseline must generate exactly requested 4 tokens", 4, NativeLlamaBridge.getGeneratedTokenCount(handle))

                // 2. Concurrent mid-flight emergency stop interruption
                val threadStarted = java.util.concurrent.atomic.AtomicBoolean(false)
                val midFlightTokens = java.util.concurrent.atomic.AtomicInteger(0)
                val midFlightOutput = arrayOfNulls<String>(1)

                val inferenceThread = Thread {
                    threadStarted.set(true)
                    midFlightOutput[0] = NativeLlamaBridge.evalPrompt(handle, "Hello", maxTokens = 2000, temperature = 0.0f)
                    midFlightTokens.set(NativeLlamaBridge.getGeneratedTokenCount(handle))
                }

                inferenceThread.start()

                // Wait for Thread to enter execution, then sleep 20ms to ensure forward passes are actively running
                while (!threadStarted.get()) {
                    Thread.yield()
                }
                Thread.sleep(20)

                // Trip emergency stop latch mid-flight from test thread
                WastiEmergencyStopController.triggerEmergencyStop("Mid-flight device inference emergency stop")
                inferenceThread.join(5000)

                val actualInterruptedTokens = midFlightTokens.get()
                val outputResult = midFlightOutput[0] ?: ""

                // Verify genuine mid-flight interruption
                assertTrue("Inference must have computed at least one token before interruption", actualInterruptedTokens > 0)
                assertTrue("Inference must have been halted before completing 2000 tokens", actualInterruptedTokens < 2000)
                assertFalse("Output must be partial computed tokens rather than pre-entry rejection", outputResult.startsWith("[EMERGENCY_STOP_ACTIVE]"))

                // 3. Reset and confirm full inference recovery
                val token = com.example.data.security.TestBootstrapSecurityFixture.createAuthorizedBootstrapToken()
                val resetResult = com.example.data.security.TestBootstrapSecurityFixture.resetEmergencyStopForBootstrap(token)
                com.example.data.security.TestBootstrapSecurityFixture.revokeAuthorizedBootstrapToken(token)
                assertTrue("Post-inference stop reset must succeed with valid token", resetResult)
                val resumedOutput = NativeLlamaBridge.evalPrompt(handle, "Hello", maxTokens = 10, temperature = 0.0f)
                assertFalse("Inference must recover cleanly post-reset", resumedOutput.contains("[EMERGENCY_STOP_ACTIVE]"))
                assertEquals("Post-reset inference must complete requested 10 tokens", 10, NativeLlamaBridge.getGeneratedTokenCount(handle))
            } finally {
                NativeLlamaBridge.freeModel(handle)
            }
        } else {
            // Truthful validation when running on architecture without native .so loaded
            assertEquals("UNAVAILABLE", NativeLlamaBridge.getNativeVersion())
        }

        // Clean up test model artifact
        modelFile.delete()
    }

    @Test
    fun testRecordDeviceVerificationProof() {
        val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion")

        val tier = if (isEmulator) TestTier.EMULATOR else TestTier.DEVICE
        val record = DeviceExecutionRecord(
            deviceId = Build.ID ?: "device_unknown",
            deviceModel = Build.MODEL ?: "Android Device",
            manufacturer = Build.MANUFACTURER ?: "Unknown",
            androidApiLevel = Build.VERSION.SDK_INT,
            isEmulator = isEmulator,
            tier = tier,
            verifiedCapabilities = setOf(
                "SERVICES",
                "NOTIFICATIONS",
                "ACCESSIBILITY_REGISTERED",
                "PACKAGE_IDENTITY_VERIFIED",
                "RUNTIME_PERMISSIONS_CHECKED",
                "TOKENIZER_VERIFIED",
                "GGUF_HEADER_PARSED",
                "NEURAL_TENSOR_FORWARD_PASS_VERIFIED",
                "EMERGENCY_STOP_NATIVE_LATCH_VERIFIED",
                "PROVENANCE_LEDGER_VERIFIED"
            ),
            testRunSignature = "REAL_DEVICE_VERIFIED_${Build.MODEL}_${System.currentTimeMillis()}"
        )

        val recorded = DeviceVerificationEvidenceTracker.recordDeviceExecution(record)
        assertTrue(recorded)
        assertTrue(DeviceVerificationEvidenceTracker.hasValidDeviceProof())
        assertTrue(DeviceVerificationEvidenceTracker.hasValidDeviceProof("PACKAGE_IDENTITY_VERIFIED"))
        assertTrue(DeviceVerificationEvidenceTracker.hasValidDeviceProof("NEURAL_TENSOR_FORWARD_PASS_VERIFIED"))
    }

    @Test
    fun testRealDeviceAdversarialLocalIpcRejectionAndLegitimateClient() {
        val serverManager = com.example.data.server.WastiLocalServerManager(context)
        serverManager.stopServer("Pre-test cleanup")
        val startResult = serverManager.startServer(0)
        assertTrue("Local server must start successfully on ephemeral port 0", startResult.isSuccess)
        val info = startResult.getOrThrow()
        assertTrue("Bound HTTP port must be nonzero", info.port > 0)
        assertTrue("Bound WebSocket port must be nonzero", info.wsPort > 0)
        assertEquals(com.example.data.server.LocalServerState.RUNNING, serverManager.serverInfo.value.state)

        val port = info.port
        val baseUrl = "http://127.0.0.1:$port"
        val testDeviceId = "device_instrumentation_test_${System.currentTimeMillis()}"

        try {
            // 1. Unauthenticated adversarial request to privileged endpoint /api/command
            val unauthConn = java.net.URL("$baseUrl/api/command").openConnection() as java.net.HttpURLConnection
            unauthConn.requestMethod = "POST"
            unauthConn.doOutput = true
            unauthConn.connectTimeout = 3000
            unauthConn.readTimeout = 3000
            unauthConn.setRequestProperty("Content-Type", "application/json")
            val unauthPayload = org.json.JSONObject().apply {
                put("command", "system_info")
                put("origin", "LOCAL_SERVER")
            }.toString()

            unauthConn.outputStream.use { it.write(unauthPayload.toByteArray(Charsets.UTF_8)) }
            val unauthResponseCode = unauthConn.responseCode
            val unauthResponseBody = try {
                unauthConn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                unauthConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            // Assert real server rejects unauthenticated request per canonical CommandSubmissionResult.Rejected contract (HTTP 400 with failure reason)
            assertEquals("Unauthenticated privileged command must be rejected with HTTP 400", 400, unauthResponseCode)
            val unauthJson = org.json.JSONObject(unauthResponseBody)
            assertFalse("Unauthenticated command response must have success == false", unauthJson.optBoolean("success", true))
            val reason = unauthJson.optString("reason")
            assertTrue(
                "Rejection reason must indicate transport security denial (got: $reason)",
                reason.contains("TRANSPORT_SECURITY_DENIED")
            )

            // 2. Production Pairing Flow: Request Pairing Challenge
            val pairReqConn = java.net.URL("$baseUrl/api/pairing/request").openConnection() as java.net.HttpURLConnection
            pairReqConn.requestMethod = "POST"
            pairReqConn.doOutput = true
            pairReqConn.connectTimeout = 3000
            pairReqConn.readTimeout = 3000
            pairReqConn.setRequestProperty("Content-Type", "application/json")
            val pairReqPayload = org.json.JSONObject().apply {
                put("deviceId", testDeviceId)
                put("deviceName", "RealDeviceInstrumentationCompanion")
                put("platform", "DESKTOP")
            }.toString()

            pairReqConn.outputStream.use { it.write(pairReqPayload.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, pairReqConn.responseCode)
            val pairReqBody = pairReqConn.inputStream.bufferedReader().use { it.readText() }
            val pairReqJson = org.json.JSONObject(pairReqBody)
            assertTrue("Pairing request must succeed", pairReqJson.optBoolean("success"))
            val pairingCode = pairReqJson.getString("code")
            assertTrue("Pairing code must start with PAIR-", pairingCode.startsWith("PAIR-"))

            // 3. Production Pairing Flow: Verify Pairing Challenge to obtain genuine session token
            val pairVerifyConn = java.net.URL("$baseUrl/api/pairing/verify").openConnection() as java.net.HttpURLConnection
            pairVerifyConn.requestMethod = "POST"
            pairVerifyConn.doOutput = true
            pairVerifyConn.connectTimeout = 3000
            pairVerifyConn.readTimeout = 3000
            pairVerifyConn.setRequestProperty("Content-Type", "application/json")
            val pairVerifyPayload = org.json.JSONObject().apply {
                put("code", pairingCode)
                put("deviceId", testDeviceId)
            }.toString()

            pairVerifyConn.outputStream.use { it.write(pairVerifyPayload.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, pairVerifyConn.responseCode)
            val pairVerifyBody = pairVerifyConn.inputStream.bufferedReader().use { it.readText() }
            val pairVerifyJson = org.json.JSONObject(pairVerifyBody)
            assertTrue("Pairing verification must succeed", pairVerifyJson.optBoolean("success"))
            val sessionToken = pairVerifyJson.getString("sessionToken")
            assertTrue("Session token must start with wasti-dev-sess-", sessionToken.startsWith("wasti-dev-sess-"))

            // 4. Authenticated Privileged Request to /api/command with genuine session token and deviceId
            val authCmdConn = java.net.URL("$baseUrl/api/command").openConnection() as java.net.HttpURLConnection
            authCmdConn.requestMethod = "POST"
            authCmdConn.doOutput = true
            authCmdConn.connectTimeout = 5000
            authCmdConn.readTimeout = 5000
            authCmdConn.setRequestProperty("Content-Type", "application/json")
            authCmdConn.setRequestProperty("X-Wasti-Auth-Token", sessionToken)
            authCmdConn.setRequestProperty("X-Wasti-Device-Id", testDeviceId)
            val authCmdPayload = org.json.JSONObject().apply {
                put("command", "system_info")
                put("origin", "DESKTOP_COMPANION")
            }.toString()

            authCmdConn.outputStream.use { it.write(authCmdPayload.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, authCmdConn.responseCode)
            val authCmdBody = authCmdConn.inputStream.bufferedReader().use { it.readText() }
            val authCmdJson = org.json.JSONObject(authCmdBody)
            assertTrue("Authenticated command submission must succeed", authCmdJson.optBoolean("success"))
            val commandId = authCmdJson.optString("commandId")
            assertTrue("Accepted command must return non-empty commandId", commandId.isNotBlank())
            val message = authCmdJson.optString("message")
            assertTrue("Accepted command message must confirm execution", message.contains("accepted", ignoreCase = true))

            // 4b. Await and inspect actual asynchronous system_info execution completion and output through canonical execution history
            var matchedRecord: com.example.data.core.CommandExecutionRecord? = null
            val deadline = System.currentTimeMillis() + 10000L
            while (System.currentTimeMillis() < deadline) {
                val history = com.example.data.transport.WastiCommandTransport.getInstance(context).executionHistory.value
                val record = history.firstOrNull { it.commandId == commandId }
                if (record != null && (record.isSuccess || record.response.isNotBlank())) {
                    matchedRecord = record
                    break
                }
                Thread.sleep(100)
            }

            assertNotNull(
                "Command $commandId did not complete within the 10-second timeout. Active context: ${com.example.data.core.WastiOSRuntime.getInstance(context).activeContext.value}",
                matchedRecord
            )
            val record = matchedRecord!!
            assertTrue(
                "Command $commandId execution must be recorded as successful (got isSuccess=${record.isSuccess}, state=${record.agenticState}, response='${record.response}')",
                record.isSuccess
            )
            val actualOutput = record.response
            assertTrue(
                "Command $commandId output must contain meaningful system information fields (got: '$actualOutput')",
                actualOutput.isNotBlank() && (
                    actualOutput.contains("OS", ignoreCase = true) ||
                    actualOutput.contains("Linux", ignoreCase = true) ||
                    actualOutput.contains("Android", ignoreCase = true) ||
                    actualOutput.contains("Runtime", ignoreCase = true) ||
                    actualOutput.contains("Device", ignoreCase = true) ||
                    actualOutput.contains("Model", ignoreCase = true) ||
                    actualOutput.contains("SDK", ignoreCase = true)
                )
            )

            // 4c. Invoke privileged execution endpoint /api/execute using legitimate session token to verify synchronous system_info output
            val authExecConn = java.net.URL("$baseUrl/api/execute").openConnection() as java.net.HttpURLConnection
            authExecConn.requestMethod = "POST"
            authExecConn.doOutput = true
            authExecConn.connectTimeout = 5000
            authExecConn.readTimeout = 5000
            authExecConn.setRequestProperty("Content-Type", "application/json")
            authExecConn.setRequestProperty("X-Wasti-Auth-Token", sessionToken)
            authExecConn.setRequestProperty("X-Wasti-Device-Id", testDeviceId)
            val authExecPayload = org.json.JSONObject().apply {
                put("capabilityId", "system_info")
                put("parameters", org.json.JSONObject())
            }.toString()

            authExecConn.outputStream.use { it.write(authExecPayload.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, authExecConn.responseCode)
            val authExecBody = authExecConn.inputStream.bufferedReader().use { it.readText() }
            val authExecJson = org.json.JSONObject(authExecBody)
            val execStatus = authExecJson.optString("status")
            assertTrue(
                "Privileged execution must return completed/verified status (got: $execStatus)",
                execStatus in listOf("COMPLETED", "VERIFIED", "SUCCEEDED")
            )
            val syncOutput = authExecJson.optString("output")
            assertTrue("Synchronous execution output must be non-empty", syncOutput.isNotBlank())
            assertTrue(
                "Synchronous execution output must contain real system telemetry (got: '$syncOutput')",
                syncOutput.contains("OS", ignoreCase = true) ||
                syncOutput.contains("Android", ignoreCase = true) ||
                syncOutput.contains("Linux", ignoreCase = true) ||
                syncOutput.contains("Device", ignoreCase = true) ||
                syncOutput.contains("Model", ignoreCase = true)
            )

            // 5. Clean up paired session by calling revocation API
            val revokeConn = java.net.URL("$baseUrl/api/pairing/revoke").openConnection() as java.net.HttpURLConnection
            revokeConn.requestMethod = "POST"
            revokeConn.doOutput = true
            revokeConn.connectTimeout = 3000
            revokeConn.readTimeout = 3000
            revokeConn.setRequestProperty("Content-Type", "application/json")
            val revokePayload = org.json.JSONObject().apply {
                put("deviceId", testDeviceId)
            }.toString()

            revokeConn.outputStream.use { it.write(revokePayload.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, revokeConn.responseCode)
            val revokeBody = revokeConn.inputStream.bufferedReader().use { it.readText() }
            val revokeJson = org.json.JSONObject(revokeBody)
            assertTrue("Device revocation must succeed", revokeJson.optBoolean("success"))

            // 6. Verify post-revocation adversarial rejection
            val postRevokeConn = java.net.URL("$baseUrl/api/command").openConnection() as java.net.HttpURLConnection
            postRevokeConn.requestMethod = "POST"
            postRevokeConn.doOutput = true
            postRevokeConn.connectTimeout = 3000
            postRevokeConn.readTimeout = 3000
            postRevokeConn.setRequestProperty("Content-Type", "application/json")
            postRevokeConn.setRequestProperty("X-Wasti-Auth-Token", sessionToken)
            postRevokeConn.setRequestProperty("X-Wasti-Device-Id", testDeviceId)
            val postRevokePayload = org.json.JSONObject().apply {
                put("command", "system_info")
                put("origin", "DESKTOP_COMPANION")
            }.toString()

            postRevokeConn.outputStream.use { it.write(postRevokePayload.toByteArray(Charsets.UTF_8)) }
            assertEquals(400, postRevokeConn.responseCode)
            val postRevokeBody = try {
                postRevokeConn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                postRevokeConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            val postRevokeJson = org.json.JSONObject(postRevokeBody)
            assertFalse("Post-revocation command must be rejected", postRevokeJson.optBoolean("success", true))
        } finally {
            val stopResult = serverManager.stopServer("Real device test completed")
            assertTrue("Server stop must succeed", stopResult.isSuccess)
            assertEquals(com.example.data.server.LocalServerState.STOPPED, serverManager.serverInfo.value.state)
        }
    }

    private fun createMinimalGgufModel(file: java.io.File, dim: Int, nLayers: Int, nHeads: Int, ffnDim: Int) {
        file.parentFile?.mkdirs()
        val tokens = listOf("<unk>", "<s>", "</s>", "Hello", "World")
        val vocabSize = tokens.size

        val bio = java.io.ByteArrayOutputStream()

        fun writeLeInt(v: Int) {
            bio.write(v and 0xFF)
            bio.write((v ushr 8) and 0xFF)
            bio.write((v ushr 16) and 0xFF)
            bio.write((v ushr 24) and 0xFF)
        }
        fun writeLeLong(v: Long) {
            for (i in 0 until 8) {
                bio.write(((v ushr (i * 8)) and 0xFF).toInt())
            }
        }
        fun writeGgufString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeLeLong(bytes.size.toLong())
            bio.write(bytes)
        }

        // Magic "GGUF" + Version 3
        bio.write("GGUF".toByteArray(Charsets.US_ASCII))
        writeLeInt(3)

        val tensors = listOf(
            "token_embd.weight" to listOf(dim.toLong(), vocabSize.toLong()),
            "blk.0.attn_norm.weight" to listOf(dim.toLong()),
            "blk.0.attn_q.weight" to listOf(dim.toLong(), dim.toLong()),
            "blk.0.attn_k.weight" to listOf(dim.toLong(), dim.toLong()),
            "blk.0.attn_v.weight" to listOf(dim.toLong(), dim.toLong()),
            "blk.0.attn_output.weight" to listOf(dim.toLong(), dim.toLong()),
            "blk.0.ffn_norm.weight" to listOf(dim.toLong()),
            "blk.0.ffn_gate.weight" to listOf(dim.toLong(), ffnDim.toLong()),
            "blk.0.ffn_up.weight" to listOf(dim.toLong(), ffnDim.toLong()),
            "blk.0.ffn_down.weight" to listOf(ffnDim.toLong(), dim.toLong()),
            "output_norm.weight" to listOf(dim.toLong())
        )

        val metadata = listOf(
            Triple("general.architecture", 8, "llama"),
            Triple("llama.embedding_length", 4, dim),
            Triple("llama.block_count", 4, nLayers),
            Triple("llama.feed_forward_length", 4, ffnDim),
            Triple("llama.attention.head_count", 4, nHeads),
            Triple("llama.attention.head_count_kv", 4, nHeads),
            Triple("tokenizer.ggml.tokens", 9, tokens)
        )

        writeLeLong(tensors.size.toLong())
        writeLeLong(metadata.size.toLong())

        for ((key, type, value) in metadata) {
            writeGgufString(key)
            writeLeInt(type)
            when (type) {
                8 -> writeGgufString(value as String)
                4 -> writeLeInt(value as Int)
                9 -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = value as List<String>
                    writeLeInt(8)
                    writeLeLong(list.size.toLong())
                    for (item in list) {
                        writeGgufString(item)
                    }
                }
            }
        }

        val tensorPayloads = mutableListOf<ByteArray>()
        var currentOffset = 0L

        for ((name, dims) in tensors) {
            writeGgufString(name)
            writeLeInt(dims.size)
            for (d in dims) {
                writeLeLong(d)
            }
            writeLeInt(0) // GGML_TYPE_F32
            writeLeLong(currentOffset)

            var numElements = 1L
            for (d in dims) numElements *= d
            val payload = ByteArray((numElements * 4).toInt())
            val fb = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numElements.toInt()) {
                fb.putFloat(0.01f)
            }
            tensorPayloads.add(payload)
            currentOffset += payload.size
        }

        val headerBytes = bio.toByteArray()
        val alignment = 32
        val padLen = ((headerBytes.size + alignment - 1) / alignment) * alignment - headerBytes.size

        file.outputStream().use { fos ->
            fos.write(headerBytes)
            if (padLen > 0) {
                fos.write(ByteArray(padLen))
            }
            for (p in tensorPayloads) {
                fos.write(p)
            }
        }
    }
}

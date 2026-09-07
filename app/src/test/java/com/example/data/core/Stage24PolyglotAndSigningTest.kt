package com.example.data.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.core.TestCategory
import com.example.data.core.TestTier
import com.example.data.node.WastiSovereignTunnelEngine
import com.example.data.wre.ExecutionRequest
import com.example.data.wre.WastiPolyglotTerminalEngine
import com.example.data.wre.WreWorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Stage24PolyglotAndSigningTest
 *
 * Verifies on-device sovereign capabilities mandated by the Eternal Manifesto:
 * - Autonomous 4096-bit Production Keystore Generation (Release Signing Gate resolution)
 * - Autonomous Sovereign Cloud Ingress & Tunnel (Public Companion Hosting Gate resolution)
 * - Deep Silicon & Physical Hardware Profiling (Resource Intelligence Law)
 * - Ultra-Universal Polyglot Terminal Execution (Python, Node.js, SQL, Shell, Keystore, Tunnel)
 * - Personalized First-Run Onboarding Engine (Pilot & Spacecraft Principle)
 */
@RunWith(RobolectricTestRunner::class)
@TestCategory(
    tier = TestTier.ROBOLECTRIC,
    description = "Tests for on-device release keystore generation, sovereign tunnel, polyglot terminal, and first-run onboarding"
)
class Stage24PolyglotAndSigningTest {

    private lateinit var context: Context
    private lateinit var workspaceManager: WreWorkspaceManager
    private lateinit var polyglotEngine: WastiPolyglotTerminalEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PersonalizedOnboardingEngine.resetSetupForTesting(context)
        workspaceManager = WreWorkspaceManager(context)
        polyglotEngine = WastiPolyglotTerminalEngine(context, workspaceManager)

        // Clean up any test keystore
        val keystoreFile = File(context.filesDir, "wasti_production_release.p12")
        if (keystoreFile.exists()) {
            keystoreFile.delete()
        }
    }

    @Test
    fun testAutonomousKeystoreGenerationAndVerification() = runBlocking {
        assertFalse("Initially keystore should not exist", WastiProductionSigningEngine.hasExistingKeystore(context))

        val result = WastiProductionSigningEngine.generateSovereignReleaseKeystore(
            context = context,
            keySizeBits = 2048, // 2048-bit for rapid unit test verification
            validityDays = 10000,
            organization = "Wasti AI OS Sovereign Unit Test"
        )

        assertTrue("Keystore generation must succeed: ${result.message}", result.isSuccess)
        assertNotNull("Keystore details must be returned", result.keystoreDetails)

        val details = result.keystoreDetails!!
        assertEquals("wasti_production_key", details.alias)
        assertEquals("RSA", details.keyAlgorithm)
        assertEquals(2048, details.keySizeBits)
        assertTrue("SHA-256 fingerprint must be computed", details.sha256Fingerprint.contains(":"))
        assertTrue("Certificate file must exist on disk", details.keystoreFile.exists())

        // Verify retrieval of existing keystore
        assertTrue(WastiProductionSigningEngine.hasExistingKeystore(context))
        val retrievedDetails = WastiProductionSigningEngine.getExistingKeystoreDetails(context)
        assertNotNull(retrievedDetails)
        assertEquals(details.sha256Fingerprint, retrievedDetails!!.sha256Fingerprint)

        // Verify Production Readiness Signing Gate
        val gateStatus = WastiProductionSigningEngine.verifyProductionReadinessSigningGate(context)
        assertTrue(gateStatus.isVerified)
        assertTrue(gateStatus.details.contains(details.sha256Fingerprint))
    }

    @Test
    fun testDeepSiliconHardwareProfiler() {
        val profile = WastiDeepHardwareProfiler.profileSystem(context)

        assertNotNull(profile.identity)
        assertNotNull(profile.cpu)
        assertNotNull(profile.memory)
        assertNotNull(profile.storage)
        assertNotNull(profile.power)
        assertNotNull(profile.toolchains)

        assertTrue("CPU cores must be >= 1", profile.cpu.availableCores >= 1)
        assertTrue("Total RAM must be reported", profile.memory.totalRamMb > 0)
        assertTrue("Storage must be reported", profile.storage.totalInternalStorageMb > 0)

        val summaryMarkdown = WastiDeepHardwareProfiler.generateSystemSummaryMarkdown(profile)
        assertTrue(summaryMarkdown.contains("### ⚡ Wasti Deep Silicon & Physical Reality Profile"))
        assertTrue(summaryMarkdown.contains("CPU Architecture"))
        assertTrue(summaryMarkdown.contains("Memory Matrix"))
    }

    @Test
    fun testSovereignTunnelLifecycle() = runBlocking {
        val initialState = WastiSovereignTunnelEngine.tunnelState.value
        assertFalse(initialState.isActive)

        val activeState = WastiSovereignTunnelEngine.establishTunnel(context)
        assertTrue(activeState.isActive)
        assertTrue(activeState.publicHttpsUrl.startsWith("https://"))
        assertEquals(8080, activeState.localPort)

        WastiSovereignTunnelEngine.terminateTunnel(context)
        assertFalse(WastiSovereignTunnelEngine.tunnelState.value.isActive)
    }

    @Test
    fun testPolyglotTerminalExecution() = runBlocking {
        // 1. Sysinfo / Hardware
        val sysinfoReq = ExecutionRequest(command = "sysinfo")
        assertTrue(polyglotEngine.canExecute(sysinfoReq))
        val sysinfoRes = polyglotEngine.execute(sysinfoReq)
        assertEquals(0, sysinfoRes.exitCode)
        assertTrue(sysinfoRes.verified)
        assertTrue(sysinfoRes.stdout.contains("Wasti Deep Silicon"))

        // 2. Keystore status & generation
        val keystoreReq = ExecutionRequest(command = "keystore status")
        assertTrue(polyglotEngine.canExecute(keystoreReq))
        val keystoreRes = polyglotEngine.execute(keystoreReq)
        assertEquals(0, keystoreRes.exitCode)

        // 3. Tunnel status
        val tunnelReq = ExecutionRequest(command = "tunnel status")
        assertTrue(polyglotEngine.canExecute(tunnelReq))
        val tunnelRes = polyglotEngine.execute(tunnelReq)
        assertEquals(0, tunnelRes.exitCode)

        // 4. SQL execution
        val sqlReq = ExecutionRequest(command = "sql SELECT * FROM memories LIMIT 5;")
        assertTrue(polyglotEngine.canExecute(sqlReq))
        val sqlRes = polyglotEngine.execute(sqlReq)
        assertEquals(0, sqlRes.exitCode)
        assertTrue(sqlRes.verified)

        // 5. Python evaluation
        val pyReq = ExecutionRequest(command = "python3 -c \"print(2 + 2)\"")
        assertTrue(polyglotEngine.canExecute(pyReq))
        val pyRes = polyglotEngine.execute(pyReq)
        assertEquals(0, pyRes.exitCode)
        assertTrue(pyRes.verified)

        // 6. Node evaluation
        val nodeReq = ExecutionRequest(command = "node -e \"console.log(42)\"")
        assertTrue(polyglotEngine.canExecute(nodeReq))
        val nodeRes = polyglotEngine.execute(nodeReq)
        assertEquals(0, nodeRes.exitCode)
        assertTrue(nodeRes.verified)
    }

    @Test
    fun testPersonalizedFirstRunOnboarding() = runBlocking {
        assertFalse("Initially setup must be pending", PersonalizedOnboardingEngine.isSetupCompleted(context))

        val plan = PersonalizedSetupPlan(
            role = UserPrimaryRole.SOFTWARE_ENGINEER,
            computePreference = SwarmComputePreference.AGGRESSIVE_MESH_SWARM,
            autoCreateKeystore = true,
            autoDeployCloudTunnel = true,
            userCustomInstructions = "Autonomous development and continuous verification."
        )

        PersonalizedOnboardingEngine.applyPersonalizedSetup(context, plan)

        assertTrue("Setup must be marked completed", PersonalizedOnboardingEngine.isSetupCompleted(context))
        assertEquals(UserPrimaryRole.SOFTWARE_ENGINEER, PersonalizedOnboardingEngine.getUserRole(context))
        assertTrue("Keystore must have been created", WastiProductionSigningEngine.hasExistingKeystore(context))
    }

    @Test
    fun testKeystoreDiscoveryAndImportFallback() {
        // Create an external mock keystore file in cache dir
        val externalKeystore = File(context.cacheDir, "test_external_release.p12")
        externalKeystore.writeBytes("SAMPLE_KEYSTORE_BINARY_DATA".toByteArray())

        val importResult = WastiProductionSigningEngine.importExistingKeystore(
            context = context,
            sourceFile = externalKeystore
        )
        assertTrue(importResult.isSuccess)
        assertTrue(importResult.message.contains("Successfully imported"))

        val targetInternal = File(context.filesDir, "security/keystore/wasti_production_release.p12")
        assertTrue(targetInternal.exists())
        assertEquals("SAMPLE_KEYSTORE_BINARY_DATA", targetInternal.readText())
    }

    @Test
    fun testSovereignAlternativeRegistryPlans() {
        val llmPlan = com.example.data.agent.runtime.SovereignAlternativeRegistry.getAlternativePlan(
            com.example.data.agent.runtime.CapabilityDomain.NEURAL_LLM_INFERENCE
        )
        assertTrue(llmPlan.isCompletelyOffline)
        assertTrue(llmPlan.sovereignAlternativeEngine.contains("SmolLM2"))

        val searchPlan = com.example.data.agent.runtime.SovereignAlternativeRegistry.getAlternativePlan(
            com.example.data.agent.runtime.CapabilityDomain.WEB_SEARCH_KNOWLEDGE
        )
        assertFalse(searchPlan.requiresExternalKey)
        assertTrue(searchPlan.sovereignAlternativeEngine.contains("DuckDuckGo"))

        val ttsPlan = com.example.data.agent.runtime.SovereignAlternativeRegistry.getAlternativePlan(
            com.example.data.agent.runtime.CapabilityDomain.VOICE_TEXT_TO_SPEECH
        )
        assertTrue(ttsPlan.isCompletelyOffline)
        assertTrue(ttsPlan.sovereignAlternativeEngine.contains("TextToSpeech"))
    }

    @Test
    fun testHolisticCognitiveRealitySnapshot() = runBlocking {
        val snapshot = WastiHolisticCognitiveEngine.inspectHolisticReality(context)

        assertNotNull(snapshot.environment)
        assertNotNull(snapshot.userProfile)
        assertNotNull(snapshot.hardware)
        assertNotNull(snapshot.media)
        assertNotNull(snapshot.activeSuggestions)

        assertTrue(snapshot.environment.batteryPercentage in 0..100)
        assertNotNull(snapshot.environment.circadianPhase)
        assertEquals("Sovereign Commander", snapshot.userProfile.displayName)
        assertTrue(snapshot.hardware.cpu.availableCores >= 1)

        // Test recording biometric face signature hash
        val fakeFaceFeatures = "FACE_VECTOR_EMBEDDING_128D_TEST".toByteArray()
        WastiHolisticCognitiveEngine.recordBiometricSignature(context, fakeFaceFeatures)

        val updatedSnapshot = WastiHolisticCognitiveEngine.inspectHolisticReality(context)
        assertEquals(BiometricRecognitionStatus.AUTHENTICATED_ACTIVE, updatedSnapshot.userProfile.biometricStatus)
        assertNotNull(updatedSnapshot.userProfile.biometricSignatureHash)
        assertEquals(64, updatedSnapshot.userProfile.biometricSignatureHash!!.length) // SHA-256 hex
    }

    @Test
    fun testPolyglotSovereignAlternativeCommands() = runBlocking {
        val altReq = ExecutionRequest(command = "alternatives")
        assertTrue(polyglotEngine.canExecute(altReq))
        val altRes = polyglotEngine.execute(altReq)
        assertEquals(0, altRes.exitCode)
        assertTrue(altRes.stdout.contains("Sovereign Zero-Key Alternative Provider Matrix"))

        val cogReq = ExecutionRequest(command = "cognitive")
        assertTrue(polyglotEngine.canExecute(cogReq))
        val cogRes = polyglotEngine.execute(cogReq)
        assertEquals(0, cogRes.exitCode)
        assertTrue(cogRes.stdout.contains("5-Dimensional Cognitive Reality Snapshot"))

        val sensoryReq = ExecutionRequest(command = "sensory")
        assertTrue(polyglotEngine.canExecute(sensoryReq))
        val sensoryRes = polyglotEngine.execute(sensoryReq)
        assertEquals(0, sensoryRes.exitCode)
        assertTrue(sensoryRes.stdout.contains("Device Sensory & Peripherals Reality"))
    }

    @Test
    fun testBiometricFaceEngineEnrollmentAndMatching() = runBlocking {
        // Create a test 64x64 bitmap representing Commander's face
        val faceBitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(faceBitmap)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
        canvas.drawCircle(32f, 32f, 20f, paint)

        // 1. Enroll face
        val enrollResult = WastiBiometricFaceEngine.enrollUserFace(context, faceBitmap)
        assertTrue(enrollResult.isSuccess)
        assertNotNull(enrollResult.faceSignatureHash)
        assertTrue(WastiBiometricFaceEngine.isFaceEnrolled(context))

        // 2. Verify with same face -> must match with high similarity
        val verifySame = WastiBiometricFaceEngine.verifyFace(context, faceBitmap)
        assertTrue(verifySame.isEnrolled)
        assertTrue(verifySame.isMatch)
        assertTrue(verifySame.similarityScore >= 0.90f)

        // 3. Verify with completely different black image -> must not match
        val otherBitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        val otherCanvas = android.graphics.Canvas(otherBitmap)
        otherCanvas.drawColor(android.graphics.Color.BLACK)

        val verifyOther = WastiBiometricFaceEngine.verifyFace(context, otherBitmap)
        assertTrue(verifyOther.isEnrolled)
        assertFalse(verifyOther.isMatch)
    }

    @Test
    fun testDeviceSensoryEnginePeripheralsAndCameras() = runBlocking {
        val profile = WastiDeviceSensoryEngine.inspectSensoryEnvironment(context)
        assertNotNull(profile.peripherals)
        assertNotNull(profile.cameras)
        assertNotNull(profile.audio)

        val summary = WastiDeviceSensoryEngine.generateSensorySummaryMarkdown(profile)
        assertTrue(summary.contains("Device Sensory & Peripherals Reality"))
        assertTrue(summary.contains("Audio Output"))
        assertTrue(summary.contains("Input Devices"))
    }
}

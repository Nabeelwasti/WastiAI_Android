package com.example.data.core

import com.example.data.agent.runtime.ActionVerificationStatus
import com.example.data.agent.runtime.CapabilitySpecificEvidence
import com.example.data.agent.runtime.EvidenceSource
import com.example.data.agent.runtime.ExecutionProvenanceLedger
import com.example.data.agent.runtime.VerificationResult
import com.example.data.agent.runtime.WastiVerificationReceipt
import com.example.data.agent.runtime.WastiTruthAuthority
import com.example.data.agent.runtime.WastiTruthGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Truth Authority Architecture & Unforgeable Receipt Verification Suite.
 *
 * Enforces the core architectural mandate:
 * "One Truth Authority, Many Observers, Zero Independent Truth Authorities."
 */
class TruthAuthorityArchitectureTest {

    @Test
    fun testTruthAuthorityIssuesValidSignedReceipt() {
        val now = System.currentTimeMillis()
        val evidence = CapabilitySpecificEvidence(
            taskId = "task_test_101",
            actionId = "action_test_101",
            capabilityId = "capability_test",
            executor = "TestExecutor",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            timestamp = now,
            artifactOrStateReference = "ref_101",
            checksumOrHash = "abcd1234efgh5678",
            expectedState = "state_ok",
            observedState = "state_ok",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "runtime_diagnostic_probe",
            confidence = 0.95
        )

        val (verResult, receipt) = WastiTruthGate.verifyCapability(evidence)
        assertEquals(ActionVerificationStatus.VERIFIED, verResult.status)
        assertNotNull("Valid verification must produce a receipt", receipt)
        assertTrue("Receipt must be structurally valid", WastiTruthGate.validateReceipt(receipt!!))
    }

    @Test
    fun testFabricatedOrTamperedReceiptIsRejectedByTruthGate() {
        val fakeReceipt = WastiVerificationReceipt(
            receiptId = UUID.randomUUID().toString(),
            taskId = "task_test_999",
            actionId = "action_test_999",
            capabilityId = "capability_test",
            verifierIdentity = "FakeVerifier",
            timestamp = System.currentTimeMillis(),
            postconditionHash = "fake_hash_12345",
            signatureToken = "fake_hmac_signature_12345"
        )

        assertFalse("Fake receipt must be rejected by Truth Gate", WastiTruthGate.validateReceipt(fakeReceipt))
    }

    @Test
    fun testTamperedReceiptSignatureIsRejected() {
        val now = System.currentTimeMillis()
        val evidence = CapabilitySpecificEvidence(
            taskId = "task_test_102",
            actionId = "action_test_102",
            capabilityId = "capability_test",
            executor = "TestExecutor",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            timestamp = now,
            artifactOrStateReference = "ref_102",
            checksumOrHash = "1234abcd5678efgh",
            expectedState = "state_ok",
            observedState = "state_ok",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "runtime_diagnostic_probe",
            confidence = 0.95
        )

        val (_, genuineReceipt) = WastiTruthGate.verifyCapability(evidence)
        assertNotNull(genuineReceipt)

        val tamperedReceipt = genuineReceipt!!.copy(
            verifierIdentity = "ForgedVerifierIdentity"
        )

        assertFalse("Tampered receipt with altered identity must be rejected", WastiTruthGate.validateReceipt(tamperedReceipt))
    }

    @Test
    fun testPostconditionMismatchFailsVerificationAndReturnsNoReceipt() {
        val evidence = CapabilitySpecificEvidence(
            taskId = "task_mismatch",
            actionId = "action_mismatch",
            capabilityId = "cap_mismatch",
            executor = "TestExecutor",
            observationSource = EvidenceSource.FILESYSTEM,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "ref_mismatch",
            checksumOrHash = "hash_mismatch",
            expectedState = "file_exists",
            observedState = "file_missing",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "filesystem_probe",
            confidence = 0.95
        )

        val (verResult, receipt) = WastiTruthGate.verifyCapability(evidence)
        assertEquals(ActionVerificationStatus.FAILED, verResult.status)
        assertNull("Postcondition mismatch must not produce a receipt", receipt)
    }

    @Test
    fun testUnprobedProcessTelemetryReturnsNotVerifiableAndNoReceipt() {
        val evidence = CapabilitySpecificEvidence(
            taskId = "task_telemetry",
            actionId = "action_telemetry",
            capabilityId = "cap_telemetry",
            executor = "TestExecutor",
            observationSource = EvidenceSource.PROCESS_TELEMETRY,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "ref_telemetry",
            checksumOrHash = "hash_telemetry",
            expectedState = "ok",
            observedState = "ok",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "process_telemetry_probe",
            confidence = 0.95
        )

        val (verResult, receipt) = WastiTruthGate.verifyCapability(evidence)
        assertEquals(ActionVerificationStatus.NOT_VERIFIABLE, verResult.status)
        assertNull("Unprobed telemetry must not produce a receipt", receipt)
    }

    @Test
    fun testExecutionProvenanceLedgerRejectsVerifiedStatusWithoutValidReceipt() {
        val taskId = "task_ledger_bypass_test"
        val actionId = "action_ledger_bypass_test"

        val unverifiedVerResult = VerificationResult(
            taskId = taskId,
            actionId = actionId,
            capabilityId = "bypass_cap",
            status = ActionVerificationStatus.VERIFIED,
            confidence = 0.99,
            evidence = "Fake evidence claiming verified without receipt"
        )

        try {
            ExecutionProvenanceLedger.recordExecution(
                taskId = taskId,
                actionId = actionId,
                capabilityId = "bypass_cap",
                providerId = "TestProvider",
                modelId = "TestModel",
                inputContent = "input",
                outputContent = "output",
                verificationResult = unverifiedVerResult,
                verificationReceipt = null // Zero receipt supplied!
            )
            fail("Ledger must throw IllegalStateException when VERIFIED claim lacks a valid receipt")
        } catch (e: IllegalStateException) {
            assertTrue("Exception message must indicate receipt requirement", e.message?.contains("Receipt") == true || e.message?.contains("receipt") == true)
        }
    }

    @Test
    fun testExecutionProvenanceLedgerAcceptsVerifiedStatusWithGenuineReceipt() {
        val taskId = "task_ledger_valid_test"
        val actionId = "action_ledger_valid_test"

        val evidence = CapabilitySpecificEvidence(
            taskId = taskId,
            actionId = actionId,
            capabilityId = "valid_cap",
            executor = "TestExecutor",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "ref_valid",
            checksumOrHash = "valid_hash_12345678",
            expectedState = "state_ok",
            observedState = "state_ok",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "runtime_diagnostic_probe",
            confidence = 0.95
        )

        val (verResult, receipt) = WastiTruthGate.verifyCapability(evidence)
        assertEquals(ActionVerificationStatus.VERIFIED, verResult.status)
        assertNotNull(receipt)

        val entry = ExecutionProvenanceLedger.recordExecution(
            taskId = taskId,
            actionId = actionId,
            capabilityId = "valid_cap",
            providerId = "TestProvider",
            modelId = "TestModel",
            inputContent = "input",
            outputContent = "output",
            verificationResult = verResult,
            verificationReceipt = receipt
        )

        assertTrue("Provenance entry must be marked verified when valid receipt is provided", entry.isVerified)
        assertEquals("VERIFIED", entry.verificationStatus)
    }

    @Test
    fun testCompileConfigurationPreservation() {
        val gradleFile = java.io.File("app/build.gradle.kts")
        assertTrue("app/build.gradle.kts must exist", gradleFile.exists())
        val content = gradleFile.readText()
        assertTrue("compileSdk must be strictly set to canonical 37", content.contains("compileSdk = 37"))
        assertTrue("targetSdk must be strictly set to canonical 37", content.contains("targetSdk = 37"))
    }

    @Test
    fun testReceiptReplayAcrossExecutionsIsRejected() {
        val taskIdA = "task_execution_A"
        val taskIdB = "task_execution_B"
        val actionId = "action_shared"
        val capabilityId = "cap_shared"

        val inputA = WastiTruthAuthority.hashString("input_payload_A")
        val outputA = WastiTruthAuthority.hashString("output_payload_A")

        val evidenceA = CapabilitySpecificEvidence(
            taskId = taskIdA,
            actionId = actionId,
            capabilityId = capabilityId,
            executor = "TestExecutor",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "ref_A",
            checksumOrHash = "hash_A",
            expectedState = "state_A",
            observedState = "state_A",
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "runtime_diagnostic_probe",
            confidence = 0.95
        )

        val (verResult, receiptA) = WastiTruthAuthority.evaluate(
            taskId = taskIdA,
            actionId = actionId,
            capabilityId = capabilityId,
            expectedState = "state_A",
            observedState = "state_A",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "runtime_diagnostic_probe",
            confidence = 0.95,
            inputHash = inputA,
            outputHash = outputA
        )

        assertEquals(ActionVerificationStatus.VERIFIED, verResult.status)
        assertNotNull(receiptA)

        // 1. Replay receiptA on task B -> must fail
        val validForTaskB = WastiTruthGate.validateReceiptApplicability(
            receipt = receiptA,
            taskId = taskIdB,
            actionId = actionId,
            capabilityId = capabilityId,
            inputHash = inputA,
            outputHash = outputA
        )
        assertFalse("Receipt from Task A must be rejected when replayed on Task B", validForTaskB)

        // 2. Replay receiptA on Task A with altered input payload -> must fail
        val inputB = WastiTruthAuthority.hashString("input_payload_B_altered")
        val validForAlteredInput = WastiTruthGate.validateReceiptApplicability(
            receipt = receiptA,
            taskId = taskIdA,
            actionId = actionId,
            capabilityId = capabilityId,
            inputHash = inputB,
            outputHash = outputA
        )
        assertFalse("Receipt must be rejected when execution binding inputHash differs", validForAlteredInput)
    }

    @Test
    fun testAlteredInputOutputArtifactBindingIsRejected() {
        val taskId = "task_binding_test"
        val actionId = "action_binding_test"
        val capabilityId = "cap_binding"
        val inHash = WastiTruthAuthority.hashString("input_1")
        val outHash = WastiTruthAuthority.hashString("output_1")

        val (_, receipt) = WastiTruthAuthority.evaluate(
            taskId = taskId,
            actionId = actionId,
            capabilityId = capabilityId,
            expectedState = "state_ok",
            observedState = "state_ok",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "runtime_probe",
            confidence = 0.95,
            inputHash = inHash,
            outputHash = outHash
        )
        assertNotNull(receipt)

        val alteredOutHash = WastiTruthAuthority.hashString("output_altered")
        val isApplicable = WastiTruthGate.validateReceiptApplicability(
            receipt = receipt,
            taskId = taskId,
            actionId = actionId,
            capabilityId = capabilityId,
            inputHash = inHash,
            outputHash = alteredOutHash
        )
        assertFalse("Receipt applicability check must fail when output hash is altered", isApplicable)
    }

    @Test
    fun testForgedVerifierIdentityIsRejected() {
        val (verResult, receipt) = WastiTruthAuthority.evaluate(
            taskId = "task_forged",
            actionId = "action_forged",
            capabilityId = "cap_forged",
            expectedState = "state_ok",
            observedState = "state_ok",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            verifierIdentity = "WastiTruthAuthority", // External caller pretending to be WastiTruthAuthority
            verificationMethod = "external_probe",
            confidence = 0.95,
            executorIdentity = "ExternalUntrustedExecutor"
        )

        assertEquals(ActionVerificationStatus.NOT_VERIFIABLE, verResult.status)
        assertEquals("FORGED_VERIFIER_IDENTITY", verResult.failureReason)
        assertNull("Forged verifier identity must not produce a receipt", receipt)
    }

    @Test
    fun testCircularExpectedEqualsObservedEvidenceIsRejected() {
        val (verResult, receipt) = WastiTruthAuthority.evaluate(
            taskId = "task_circular",
            actionId = "action_circular",
            capabilityId = "cap_circular",
            expectedState = "ok",
            observedState = "ok",
            observationSource = EvidenceSource.PROCESS_TELEMETRY,
            verifierIdentity = "TestVerifier",
            verificationMethod = "telemetry_probe",
            confidence = 0.95
        )

        assertEquals(ActionVerificationStatus.NOT_VERIFIABLE, verResult.status)
        assertNull("Circular telemetry assertion must not produce a receipt", receipt)
    }

    @Test
    fun testProducerSuppliedFilesystemHashesDoNotBypassTrustedProbe() {
        // Create a temporary file with actual content
        val tempFile = java.io.File.createTempFile("wasti_probe_test_", ".tmp")
        tempFile.writeText("actual_disk_content_123")
        tempFile.deleteOnExit()

        val fakeClaimedHash = WastiTruthAuthority.hashString("fake_content_claim")

        val evidence = CapabilitySpecificEvidence(
            taskId = "task_producer_fake",
            actionId = "action_producer_fake",
            capabilityId = "cap_self_mod",
            executor = "SelfModificationSafetyEngine",
            observationSource = EvidenceSource.FILESYSTEM,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = tempFile.absolutePath,
            checksumOrHash = fakeClaimedHash,
            expectedState = fakeClaimedHash, // Producer claims expected state is fake hash
            observedState = fakeClaimedHash, // Producer claims observed state is fake hash
            verifierIdentity = "FilesystemObservationProbe",
            verificationMethod = "post_apply_filesystem_hash_probe",
            confidence = 0.95
        )

        val (verResult, receipt) = WastiTruthGate.verifyCapability(evidence)

        assertEquals(ActionVerificationStatus.FAILED, verResult.status)
        assertEquals("DISK_HASH_MISMATCH", verResult.failureReason)
        assertNull("Fake producer claim bypassing actual disk hash must not produce a receipt", receipt)
    }

    @Test
    fun testMutationEngineSelfCertificationIsRejected() {
        val (verResult, receipt) = WastiTruthAuthority.evaluate(
            taskId = "task_self_cert",
            actionId = "action_self_cert",
            capabilityId = "cap_self_cert",
            expectedState = "state_ok",
            observedState = "state_ok",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            verifierIdentity = "SelfModificationSafetyEngine", // Executor attempting self-certification
            verificationMethod = "self_audit",
            confidence = 0.95,
            executorIdentity = "SelfModificationSafetyEngine"
        )

        assertEquals(ActionVerificationStatus.NOT_VERIFIABLE, verResult.status)
        assertEquals("EXECUTOR_SELF_CERTIFICATION", verResult.failureReason)
        assertNull("Self-certification by mutation engine must not produce a receipt", receipt)
    }

    @Test
    fun testSandboxToRuntimeEscalationIsRejected() {
        val (verResult, receipt) = WastiTruthAuthority.evaluate(
            taskId = "task_sandbox",
            actionId = "action_sandbox",
            capabilityId = "cap_sandbox",
            expectedState = "SANDBOX_PASSED",
            observedState = "SANDBOX_PASSED",
            observationSource = EvidenceSource.RUNTIME_DIAGNOSTIC,
            verifierIdentity = "WastiVerificationEngine",
            verificationMethod = "SANDBOX_SUITE_RUN",
            confidence = 0.95
        )

        assertEquals(ActionVerificationStatus.NOT_VERIFIABLE, verResult.status)
        assertEquals("SANDBOX_EVIDENCE_NOT_CANONICAL", verResult.failureReason)
        assertNull("Sandbox verification cannot issue runtime receipt", receipt)
    }

    @Test
    fun testUnavailableProbeFailClosedBehavior() {
        val evidence = CapabilitySpecificEvidence(
            taskId = "task_missing_file",
            actionId = "action_missing_file",
            capabilityId = "cap_missing_file",
            executor = "TestExecutor",
            observationSource = EvidenceSource.FILESYSTEM,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = "/nonexistent/path/wasti_missing_file_9999.txt",
            checksumOrHash = "some_hash",
            expectedState = "some_hash",
            observedState = "",
            verifierIdentity = "FilesystemObservationProbe",
            verificationMethod = "post_apply_probe",
            confidence = 0.95
        )

        val (verResult, receipt) = WastiTruthGate.verifyCapability(evidence)

        assertEquals(ActionVerificationStatus.FAILED, verResult.status)
        assertEquals("FILE_NOT_FOUND", verResult.failureReason)
        assertNull("Unavailable or missing file probe must fail closed and return no receipt", receipt)
    }
}

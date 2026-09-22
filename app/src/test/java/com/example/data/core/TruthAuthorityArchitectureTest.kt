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
}

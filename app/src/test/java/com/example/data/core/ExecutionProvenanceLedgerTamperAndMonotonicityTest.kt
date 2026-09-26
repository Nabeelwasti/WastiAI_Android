package com.example.data.core

import com.example.data.agent.runtime.ActionVerificationStatus
import com.example.data.agent.runtime.CapabilitySpecificEvidence
import com.example.data.agent.runtime.EvidenceLadder
import com.example.data.agent.runtime.EvidenceSource
import com.example.data.agent.runtime.ExecutionProvenanceLedger
import com.example.data.agent.runtime.ProvenanceEntry
import com.example.data.agent.runtime.VerifiedExecutionEvidence
import com.example.data.agent.runtime.WastiTruthAuthority
import com.example.data.agent.runtime.WastiTruthGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit test verifying ExecutionProvenanceLedger:
 * Monotonic sequence enforcement, hash chain integrity, tamper detection, and fail-closed persistence.
 */
class ExecutionProvenanceLedgerTamperAndMonotonicityTest {

    @Before
    fun setUp() {
        WastiTruthAuthority.setTestAuthorityKeyForTesting()
        ExecutionProvenanceLedger.resetForTesting()
    }

    @Test
    fun testMonotonicSequenceAndHashChaining() {
        val testArtifact = File.createTempFile("wasti_provenance_probe_", ".dat")
        testArtifact.writeText("genuine_neural_forward_pass_execution_output")
        testArtifact.deleteOnExit()

        val artifactHash = WastiTruthAuthority.hashString("genuine_neural_forward_pass_execution_output")
        val inContent = "Hello Wasti"
        val outContent = "Hello from sovereign neural runtime"

        val evidence1 = CapabilitySpecificEvidence(
            taskId = "task_001",
            actionId = "action_001",
            capabilityId = "native_neural_inference",
            executor = "NativeLlamaBridge",
            observationSource = EvidenceSource.FILESYSTEM_AUDIT,
            timestamp = System.currentTimeMillis(),
            artifactOrStateReference = testArtifact.absolutePath,
            checksumOrHash = artifactHash,
            expectedState = artifactHash,
            observedState = artifactHash,
            verifierIdentity = "FilesystemObservationProbe",
            verificationMethod = "objective_postcondition_disk_hash_probe",
            confidence = 1.0
        )

        val (verResult, receipt) = WastiTruthGate.verifyCapability(evidence1)
        assertEquals(ActionVerificationStatus.VERIFIED, verResult.status)
        assertNotNull(receipt)
        assertTrue(WastiTruthGate.validateReceipt(receipt))

        val entry1 = ExecutionProvenanceLedger.recordExecution(
            taskId = "task_001",
            actionId = "action_001",
            capabilityId = "native_neural_inference",
            providerId = "NativeLlamaBridge",
            inputContent = inContent,
            outputContent = outContent,
            evidence = VerifiedExecutionEvidence(
                evidenceSource = EvidenceSource.FILESYSTEM_AUDIT,
                subject = testArtifact.absolutePath,
                verifiedState = artifactHash,
                confidence = 1.0,
                expectedPostcondition = artifactHash,
                observedResult = artifactHash,
                declaredVerifier = "FilesystemObservationProbe",
                verificationMethod = "objective_postcondition_disk_hash_probe",
                checksumOrHash = artifactHash
            ),
            verifier = "FilesystemObservationProbe",
            evidenceLevel = EvidenceLadder.RUNTIME_VERIFIED,
            verificationResult = verResult,
            verificationReceipt = receipt
        )

        assertEquals(1L, entry1.sequenceNumber)
        assertEquals(ExecutionProvenanceLedger.GENESIS_HASH, entry1.previousEntryHash)
        assertTrue(entry1.isVerified)
        assertEquals(EvidenceLadder.RUNTIME_VERIFIED, entry1.evidenceLevel)
        assertEquals("FilesystemObservationProbe", entry1.verifier)

        val entry2 = ExecutionProvenanceLedger.recordExecution(
            taskId = "task_002",
            actionId = "action_002",
            capabilityId = "shell_tool",
            providerId = "WastiPolyglotTerminalEngine",
            inputContent = "ls -la",
            outputContent = "total 12",
            evidence = VerifiedExecutionEvidence(
                evidenceSource = EvidenceSource.PROCESS_TELEMETRY,
                subject = "shell_exec",
                verifiedState = "PROCESS_COMPLETED_SUCCESS",
                confidence = 0.80
            ),
            evidenceLevel = EvidenceLadder.INTEGRATION_TESTED
        )

        assertEquals(2L, entry2.sequenceNumber)
        assertEquals(entry1.entryHash, entry2.previousEntryHash)
        assertTrue("Ledger must verify intact", ExecutionProvenanceLedger.verifyLedgerIntegrity())
    }

    @Test
    fun testTamperDetectionOnEntryHashMismatch() {
        ExecutionProvenanceLedger.recordExecution(
            taskId = "task_A",
            actionId = "action_A",
            capabilityId = "core_eval",
            providerId = "Evaluator",
            inputContent = "input_A",
            outputContent = "output_A",
            evidence = null
        )

        assertTrue(ExecutionProvenanceLedger.verifyLedgerIntegrity())

        // Inject tampered entry
        ExecutionProvenanceLedger.injectTamperedEntryForTesting("bad_tampered_hash_0000000000000000000000")
        assertFalse("Tampered entry must cause ledger verification failure", ExecutionProvenanceLedger.verifyLedgerIntegrity())
    }

    @Test
    fun testMonotonicSequenceViolationDetection() {
        val entry1 = ExecutionProvenanceLedger.recordExecution(
            taskId = "task_1",
            actionId = "action_1",
            capabilityId = "cap_1",
            providerId = "prov_1",
            inputContent = "in_1",
            outputContent = "out_1",
            evidence = null
        )

        // Inject out-of-order sequence entry (sequenceNumber 1 <= last seen 1)
        val testTimestamp = System.currentTimeMillis()
        val illegalSequenceEntry = ProvenanceEntry(
            entryId = "illegal_seq_entry",
            taskId = "task_2",
            actionId = "action_2",
            capabilityId = "cap_2",
            providerId = "prov_2",
            modelId = null,
            inputHash = ExecutionProvenanceLedger.hashString("in_2"),
            outputHash = ExecutionProvenanceLedger.hashString("out_2"),
            evidenceSource = EvidenceSource.PROCESS_TELEMETRY,
            evidenceSummary = "Illegal sequence test",
            verificationStatus = "EXECUTOR_COMPLETED",
            isVerified = false,
            timestamp = testTimestamp,
            previousEntryHash = entry1.entryHash,
            entryHash = ExecutionProvenanceLedger.hashString("${entry1.entryHash}|task_2|action_2|cap_2|prov_2|${ExecutionProvenanceLedger.hashString("in_2")}|${ExecutionProvenanceLedger.hashString("out_2")}|EXECUTOR_COMPLETED|$testTimestamp|1"),
            sequenceNumber = 1L // Non-monotonic sequence
        )

        ExecutionProvenanceLedger.injectTamperedEntryForTesting(illegalSequenceEntry)
        assertFalse("Non-monotonic sequence must fail ledger integrity check", ExecutionProvenanceLedger.verifyLedgerIntegrity())
    }

    @Test
    fun testHmacIntegrityAnchorDeterminism() {
        val payload = "wasti_constitutional_state_snapshot_test"
        val hmac1 = ExecutionProvenanceLedger.computeHmacIntegrityAnchor(payload)
        val hmac2 = ExecutionProvenanceLedger.computeHmacIntegrityAnchor(payload)
        assertNotNull(hmac1)
        assertEquals(hmac1, hmac2)
        assertEquals(64, hmac1.length) // SHA-256 HMAC produces 64 hex characters
    }

    @Test
    fun testAdversarialFieldMutationFailsValidation() {
        val entry = ExecutionProvenanceLedger.recordExecution(
            taskId = "task_adv_1",
            actionId = "action_adv_1",
            capabilityId = "cap_adv_1",
            providerId = "prov_adv_1",
            modelId = "model_adv_1",
            inputContent = "input_adv",
            outputContent = "output_adv",
            evidence = VerifiedExecutionEvidence(
                evidenceSource = EvidenceSource.PROCESS_TELEMETRY,
                subject = "adv_test",
                verifiedState = "STATE_OK",
                confidence = 0.90
            ),
            expectedState = "EXPECTED_VAL",
            observedState = "OBSERVED_VAL",
            evidenceLevel = EvidenceLadder.INTEGRATION_TESTED
        )

        assertTrue("Original recorded entry must verify valid", ExecutionProvenanceLedger.verifyEntry(entry.entryId))

        fun assertMutationRejected(mutator: (ProvenanceEntry) -> ProvenanceEntry, fieldName: String) {
            val mutated = mutator(entry)
            ExecutionProvenanceLedger.resetForTesting()
            ExecutionProvenanceLedger.injectTamperedEntryForTesting(mutated)
            assertFalse(
                "Mutating $fieldName must cause entry or ledger integrity verification to fail",
                ExecutionProvenanceLedger.verifyEntry(mutated.entryId) && ExecutionProvenanceLedger.verifyLedgerIntegrity()
            )
        }

        assertMutationRejected({ it.copy(previousEntryHash = "bad_prev_hash") }, "previousEntryHash")
        assertMutationRejected({ it.copy(sequenceNumber = 999L) }, "sequenceNumber")
        assertMutationRejected({ it.copy(taskId = "tampered_task") }, "taskId")
        assertMutationRejected({ it.copy(actionId = "tampered_action") }, "actionId")
        assertMutationRejected({ it.copy(capabilityId = "tampered_capability") }, "capabilityId")
        assertMutationRejected({ it.copy(providerId = "tampered_provider") }, "providerId")
        assertMutationRejected({ it.copy(modelId = "tampered_model") }, "modelId")
        assertMutationRejected({ it.copy(inputHash = "bad_input_hash") }, "inputHash")
        assertMutationRejected({ it.copy(outputHash = "bad_output_hash") }, "outputHash")
        assertMutationRejected({ it.copy(evidenceSource = EvidenceSource.LOCAL_MODEL_INFERENCE) }, "evidenceSource")
        assertMutationRejected({ it.copy(evidenceLevel = EvidenceLadder.RUNTIME_VERIFIED) }, "evidenceLevel")
        assertMutationRejected({ it.copy(expectedState = "tampered_expected") }, "expectedState")
        assertMutationRejected({ it.copy(observedState = "tampered_observed") }, "observedState")
        assertMutationRejected({ it.copy(receiptId = "receipt_fake_123") }, "receiptId")
        assertMutationRejected({ it.copy(verificationStatus = "FAILED") }, "verificationStatus")
        assertMutationRejected({ it.copy(timestamp = 1000000L) }, "timestamp")
    }
}

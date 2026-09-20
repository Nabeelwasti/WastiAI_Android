package com.example.data.core

import com.example.data.agent.runtime.EvidenceLadder
import com.example.data.agent.runtime.EvidenceSource
import com.example.data.agent.runtime.ExecutionProvenanceLedger
import com.example.data.agent.runtime.ProvenanceEntry
import com.example.data.agent.runtime.VerifiedExecutionEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test verifying ExecutionProvenanceLedger:
 * Monotonic sequence enforcement, hash chain integrity, tamper detection, and fail-closed persistence.
 */
class ExecutionProvenanceLedgerTamperAndMonotonicityTest {

    @Before
    fun setUp() {
        ExecutionProvenanceLedger.resetForTesting()
    }

    @Test
    fun testMonotonicSequenceAndHashChaining() {
        val entry1 = ExecutionProvenanceLedger.recordExecution(
            taskId = "task_001",
            actionId = "action_001",
            capabilityId = "native_neural_inference",
            providerId = "NativeLlamaBridge",
            inputContent = "Hello Wasti",
            outputContent = "Hello from sovereign neural runtime",
            evidence = VerifiedExecutionEvidence(
                evidenceSource = EvidenceSource.LOCAL_MODEL_INFERENCE,
                subject = "local_native_inference:wasti-smollm",
                verifiedState = "GENUINE_NEURAL_TENSOR_FORWARD_PASS",
                confidence = 0.95,
                expectedPostcondition = "GENUINE_NEURAL_TENSOR_FORWARD_PASS",
                observedResult = "GENUINE_NEURAL_TENSOR_FORWARD_PASS",
                declaredVerifier = "WastiVerificationEngine",
                verificationMethod = "native_tensor_forward_pass_verification"
            ),
            verifier = "WastiVerificationEngine",
            evidenceLevel = EvidenceLadder.RUNTIME_VERIFIED
        )

        assertEquals(1L, entry1.sequenceNumber)
        assertEquals(ExecutionProvenanceLedger.GENESIS_HASH, entry1.previousEntryHash)
        assertTrue(entry1.isVerified)
        assertEquals(EvidenceLadder.RUNTIME_VERIFIED, entry1.evidenceLevel)
        assertEquals("WastiVerificationEngine", entry1.verifier)

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
}

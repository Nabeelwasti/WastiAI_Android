package com.example.data.core

import com.example.data.agent.runtime.ModificationDecision
import com.example.data.agent.runtime.ModificationOutcomeStatus
import com.example.data.agent.runtime.SelfModificationSafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit test verifying SelfModificationSafetyEngine:
 * Proposal audit chaining, diff computation, multi-generation rollback snapshots, post-restore validation.
 */
class SelfModificationSafetyAndRollbackTest {

    @Test
    fun testProtectedPathEnforcement() {
        val protectedPath = "app/src/main/java/com/example/data/security/ZeroTrustSentinelEngine.kt"
        assertTrue(SelfModificationSafetyEngine.isProtectedPath(protectedPath))

        // Autonomous modification on protected path without admin token must be blocked
        val decision = SelfModificationSafetyEngine.evaluateModification(
            filePath = protectedPath,
            newContent = "package com.example.data.security",
            isAutonomous = true,
            adminAuthToken = null
        )
        assertEquals(ModificationDecision.BLOCKED_PROTECTED_PATH, decision)
    }

    @Test
    fun testProposalAndDiffGeneration() {
        val orig = "fun calculate(): Int = 10"
        val mod = "fun calculate(): Int = 20"
        val diff = SelfModificationSafetyEngine.computeDiff(orig, mod)
        assertNotNull(diff)
        assertTrue("Diff must show added/deleted lines", diff.isNotEmpty())
    }

    @Test
    fun testSnapshotCreationAndRollbackIntegrity() {
        val tmpFile = File.createTempFile("wasti_self_mod_test", ".kt")
        try {
            tmpFile.writeText("initial_content_v1")
            val snap = SelfModificationSafetyEngine.createRollbackSnapshot(tmpFile)
            assertNotNull(snap)
            assertEquals(1, snap.generation)

            // Overwrite file
            tmpFile.writeText("mutated_content_v2")
            assertEquals("mutated_content_v2", tmpFile.readText())

            // Execute rollback
            val rollbackOk = SelfModificationSafetyEngine.rollback(snap.snapshotId)
            assertTrue("Rollback must succeed", rollbackOk)
            assertEquals("initial_content_v1", tmpFile.readText())
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun testStagedModificationWithAutomaticRollbackOnValidationFailure() {
        val tmpFile = File.createTempFile("wasti_staged_test", ".txt")
        try {
            tmpFile.writeText("good_state")

            val outcome = SelfModificationSafetyEngine.executeModificationWithStagedRollback(
                targetFile = tmpFile,
                newContent = "corrupted_state_invalid_syntax",
                isAutonomous = false,
                stagedValidator = { file ->
                    // Validator rejects invalid syntax
                    !file.readText().contains("corrupted")
                }
            )

            assertEquals(ModificationOutcomeStatus.ROLLED_BACK, outcome.status)
            assertTrue("File must be restored to good state after validation failure", outcome.rollbackPerformed)
            assertEquals("good_state", tmpFile.readText())
        } finally {
            tmpFile.delete()
        }
    }
}

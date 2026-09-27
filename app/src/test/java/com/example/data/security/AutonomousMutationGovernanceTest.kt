package com.example.data.security

import com.example.data.agent.runtime.ModificationDecision
import com.example.data.agent.runtime.ModificationOutcomeStatus
import com.example.data.agent.runtime.ProposalAuditAction
import com.example.data.agent.runtime.SelfModificationSafetyEngine
import com.example.data.agent.runtime.WastiEmergencyStopController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AutonomousMutationGovernanceTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "wasti_gov_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        SelfModificationSafetyEngine.resetForTesting()
        AutonomousMutationGovernance.resetForTesting()
        WastiEmergencyStopController.resetEmergencyStop(requester = "SYSTEM_INITIALIZER")
    }

    @After
    fun tearDown() {
        SelfModificationSafetyEngine.resetForTesting()
        AutonomousMutationGovernance.resetForTesting()
        WastiEmergencyStopController.resetEmergencyStop(requester = "SYSTEM_INITIALIZER")
        tempDir.deleteRecursively()
    }

    @Test
    fun testRiskTierClassification_allTiers() {
        // Low risk
        assertEquals(MutationRiskTier.LOW_REVERSIBLE, AutonomousMutationGovernance.classifyRiskTier("workspace/scratch/test_script.py"))
        assertEquals(MutationRiskTier.LOW_REVERSIBLE, AutonomousMutationGovernance.classifyRiskTier("workspace/temp/notes.txt"))
        assertEquals(MutationRiskTier.LOW_REVERSIBLE, AutonomousMutationGovernance.classifyRiskTier("workspace/docs/README.md"))

        // Medium impact
        assertEquals(MutationRiskTier.MEDIUM_IMPACT, AutonomousMutationGovernance.classifyRiskTier("app/src/main/java/com/example/ui/HomeScreen.kt"))
        assertEquals(MutationRiskTier.MEDIUM_IMPACT, AutonomousMutationGovernance.classifyRiskTier("app/src/main/res/layout/activity_main.xml"))

        // High governance
        assertEquals(MutationRiskTier.HIGH_GOVERNANCE, AutonomousMutationGovernance.classifyRiskTier("app/src/main/java/com/example/data/PermissionManager.kt"))
        assertEquals(MutationRiskTier.HIGH_GOVERNANCE, AutonomousMutationGovernance.classifyRiskTier("db/query.sql", content = "DROP TABLE users;"))
        assertEquals(MutationRiskTier.HIGH_GOVERNANCE, AutonomousMutationGovernance.classifyRiskTier("terminal", action = "su root"))

        // Critical security immutable
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, AutonomousMutationGovernance.classifyRiskTier("app/release.keystore"))
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, AutonomousMutationGovernance.classifyRiskTier(".github/workflows/build-apk.yml"))
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, AutonomousMutationGovernance.classifyRiskTier(".env"))
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, AutonomousMutationGovernance.classifyRiskTier("app/src/main/AndroidManifest.xml"))
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, AutonomousMutationGovernance.classifyRiskTier("app/src/main/cpp/wasti_ai_native.cpp"))
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, AutonomousMutationGovernance.classifyRiskTier("app/src/main/java/com/example/data/security/ZeroTrustSentinelEngine.kt"))
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, AutonomousMutationGovernance.classifyRiskTier("app/src/main/java/com/example/data/agent/runtime/SelfModificationSafetyEngine.kt"))
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, AutonomousMutationGovernance.classifyRiskTier("app/src/main/java/com/example/data/agent/runtime/WastiEmergencyStopController.kt"))
    }

    @Test
    fun testLowRiskReversibleAutonomousExecutionWithSnapshot() {
        val scratchFile = File(tempDir, "scratch_test.txt")
        scratchFile.writeText("Initial scratch content")

        val eval = AutonomousMutationGovernance.evaluateMutationAuthority(
            filePath = scratchFile.absolutePath,
            newContent = "Updated scratch content",
            isAutonomous = true
        )
        assertEquals(MutationRiskTier.LOW_REVERSIBLE, eval.riskTier)
        assertEquals(ModificationDecision.ALLOWED, eval.decision)
        assertFalse(eval.requiresHumanApproval)

        val outcome = SelfModificationSafetyEngine.executeModificationWithStagedRollback(
            targetFile = scratchFile,
            newContent = "Updated scratch content",
            isAutonomous = true
        )
        assertTrue(outcome.status == ModificationOutcomeStatus.APPLIED_VERIFIED || outcome.status == ModificationOutcomeStatus.APPLIED_UNVERIFIED)
        assertEquals("Updated scratch content", scratchFile.readText())

        // Verify snapshot rollback
        assertNotNull(outcome.snapshotId)
        val rollbackOk = SelfModificationSafetyEngine.rollback(outcome.snapshotId!!)
        assertTrue(rollbackOk)
        assertEquals("Initial scratch content", scratchFile.readText())
    }

    @Test
    fun testCriticalSecurityImmutableBlocksAutonomousMutation() {
        val protectedTarget = "app/src/main/java/com/example/data/security/ZeroTrustSentinelEngine.kt"
        val eval = AutonomousMutationGovernance.evaluateMutationAuthority(
            filePath = protectedTarget,
            newContent = "// Malicious payload to disable zero trust sentinel",
            isAutonomous = true
        )
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, eval.riskTier)
        assertEquals(ModificationDecision.BLOCKED_PROTECTED_PATH, eval.decision)
        assertTrue(eval.requiresHumanApproval)

        val dummyFile = File(tempDir, "ZeroTrustSentinelEngine.kt")
        dummyFile.writeText("original")
        val outcome = SelfModificationSafetyEngine.executeModificationWithStagedRollback(
            targetFile = File(tempDir, "ZeroTrustSentinelEngine.kt"),
            newContent = "// Malicious payload",
            isAutonomous = true
        )
        assertEquals(ModificationOutcomeStatus.BLOCKED_POLICY, outcome.status)
        assertEquals("original", dummyFile.readText())
    }

    @Test
    fun testCriticalSecurityAuthorizedWithValidAdminToken() {
        val testToken = "wasti_admin_sec_token_999"
        SelfModificationSafetyEngine.registerAdminToken(testToken)

        val targetFile = File(tempDir, "ZeroTrustSentinelEngine.kt")
        targetFile.writeText("original code")

        val eval = AutonomousMutationGovernance.evaluateMutationAuthority(
            filePath = targetFile.absolutePath,
            newContent = "// Authorized security patch",
            isAutonomous = false,
            adminAuthToken = testToken
        )
        assertEquals(MutationRiskTier.CRITICAL_SECURITY_IMMUTABLE, eval.riskTier)
        assertEquals(ModificationDecision.ALLOWED, eval.decision)

        val outcome = SelfModificationSafetyEngine.executeModificationWithStagedRollback(
            targetFile = targetFile,
            newContent = "// Authorized security patch",
            isAutonomous = false,
            adminAuthToken = testToken
        )
        assertTrue(outcome.status == ModificationOutcomeStatus.APPLIED_VERIFIED || outcome.status == ModificationOutcomeStatus.APPLIED_UNVERIFIED)
        assertEquals("// Authorized security patch", targetFile.readText())
    }

    @Test
    fun testEmergencyStopBlocksAllMutations() {
        WastiEmergencyStopController.triggerEmergencyStop("Security breach detected")
        assertTrue(WastiEmergencyStopController.isEmergencyStopped)

        val scratchFile = File(tempDir, "scratch.txt")
        scratchFile.writeText("pre-stop content")

        val eval = AutonomousMutationGovernance.evaluateMutationAuthority(
            filePath = scratchFile.absolutePath,
            newContent = "mutation attempt during stop",
            isAutonomous = true
        )
        assertEquals(ModificationDecision.BLOCKED_EMERGENCY_STOP, eval.decision)
        assertTrue(eval.requiresHumanApproval)

        val outcome = SelfModificationSafetyEngine.executeModificationWithStagedRollback(
            targetFile = scratchFile,
            newContent = "mutation attempt during stop",
            isAutonomous = true
        )
        assertEquals(ModificationOutcomeStatus.BLOCKED_POLICY, outcome.status)
        assertEquals(ModificationDecision.BLOCKED_EMERGENCY_STOP, outcome.decision)
        assertEquals("pre-stop content", scratchFile.readText())
    }

    @Test
    fun testAutonomousAICannotResetEmergencyStop() {
        WastiEmergencyStopController.triggerEmergencyStop("Test Stop Active")
        assertTrue(WastiEmergencyStopController.isEmergencyStopped)

        // Autonomous AI attempts to reset
        val aiResetAllowed = AutonomousMutationGovernance.isEmergencyStopResetPermitted("AUTONOMOUS_AI")
        assertFalse("Autonomous AI must not be permitted to reset emergency stop", aiResetAllowed)

        val aiSubagentResetAllowed = AutonomousMutationGovernance.isEmergencyStopResetPermitted("AI_SUBAGENT_CODE_REPAIR")
        assertFalse("AI subagents must not be permitted to reset emergency stop", aiSubagentResetAllowed)

        val resetOutcome = WastiEmergencyStopController.resetEmergencyStop(requester = "AUTONOMOUS_AI")
        assertFalse(resetOutcome)
        assertTrue("Emergency stop must remain active after unauthorized AI reset attempt", WastiEmergencyStopController.isEmergencyStopped)

        // Human operator resets
        val humanResetAllowed = AutonomousMutationGovernance.isEmergencyStopResetPermitted("HUMAN_OPERATOR")
        assertTrue(humanResetAllowed)

        val humanResetOutcome = WastiEmergencyStopController.resetEmergencyStop(requester = "HUMAN_OPERATOR")
        assertTrue(humanResetOutcome)
        assertFalse("Emergency stop latch should be cleared by human operator", WastiEmergencyStopController.isEmergencyStopped)
    }

    @Test
    fun testMultiGenerationSnapshotRingBufferAndRollback() {
        val file = File(tempDir, "versioned_app_file.txt")
        file.writeText("Gen 0 Base Content")

        // Mutation 1 -> Gen 1
        val snap1 = SelfModificationSafetyEngine.createRollbackSnapshot(file)
        file.writeText("Gen 1 Modified Content")

        // Mutation 2 -> Gen 2
        val snap2 = SelfModificationSafetyEngine.createRollbackSnapshot(file)
        file.writeText("Gen 2 Modified Content")

        // Mutation 3 -> Gen 3
        val snap3 = SelfModificationSafetyEngine.createRollbackSnapshot(file)
        file.writeText("Gen 3 Modified Content")

        val snapshots = SelfModificationSafetyEngine.getSnapshotsForFile(file.absolutePath)
        assertEquals(3, snapshots.size)
        assertEquals(1, snapshots[0].generation)
        assertEquals(2, snapshots[1].generation)
        assertEquals(3, snapshots[2].generation)

        // Rollback to Generation 1 (most recent prior state: Gen 2 content)
        val rollbackOk = SelfModificationSafetyEngine.rollbackToGeneration(file.absolutePath, generation = 1)
        assertTrue(rollbackOk)
        assertEquals("Gen 2 Modified Content", file.readText())
    }

    @Test
    fun testRecursiveMutationLoopAndOscillationDetection() {
        val targetFile = File(tempDir, "loop_test.txt")
        targetFile.writeText("v0")

        // Mutation 1
        var out = SelfModificationSafetyEngine.executeModificationWithStagedRollback(targetFile, "v1", isAutonomous = true)
        assertNotEquals(ModificationDecision.BLOCKED_LOOP_DETECTED, out.decision)

        // Mutation 2
        out = SelfModificationSafetyEngine.executeModificationWithStagedRollback(targetFile, "v2", isAutonomous = true)
        assertNotEquals(ModificationDecision.BLOCKED_LOOP_DETECTED, out.decision)

        // Mutation 3
        out = SelfModificationSafetyEngine.executeModificationWithStagedRollback(targetFile, "v3", isAutonomous = true)
        assertNotEquals(ModificationDecision.BLOCKED_LOOP_DETECTED, out.decision)

        // Mutation 4 in same window -> BLOCKED_LOOP_DETECTED
        out = SelfModificationSafetyEngine.executeModificationWithStagedRollback(targetFile, "v4", isAutonomous = true)
        assertEquals(ModificationDecision.BLOCKED_LOOP_DETECTED, out.decision)
        assertEquals(ModificationOutcomeStatus.BLOCKED_POLICY, out.status)
    }

    @Test
    fun testProvenanceLedgerCryptographicChainAndTruthStates() {
        val record1 = AutonomousMutationGovernance.recordProvenance(
            proposalId = "prop_1",
            requester = "AUTONOMOUS_AI",
            reason = "Scratch test edit",
            proposedMutationHash = "hash1",
            affectedFiles = listOf("scratch/file1.txt"),
            riskTier = MutationRiskTier.LOW_REVERSIBLE,
            approvalIdentity = "AUTONOMOUS_AI",
            approvalState = MutationApprovalState.EXECUTED,
            executionResult = "EXECUTED"
        )
        assertNotNull(record1.recordHash)
        assertTrue(record1.recordHash.isNotBlank())
        assertEquals(ExecutionProvenanceLedger.GENESIS_HASH, record1.previousRecordHash)

        val record2 = AutonomousMutationGovernance.recordProvenance(
            proposalId = "prop_2",
            requester = "HUMAN_OPERATOR",
            reason = "High governance config update",
            proposedMutationHash = "hash2",
            affectedFiles = listOf("config/settings.json"),
            riskTier = MutationRiskTier.HIGH_GOVERNANCE,
            approvalIdentity = "HUMAN_OPERATOR",
            approvalState = MutationApprovalState.APPROVED,
            executionResult = "APPROVED"
        )
        assertEquals(record1.recordHash, record2.previousRecordHash)

        val history = AutonomousMutationGovernance.getProvenanceHistory()
        assertEquals(2, history.size)
        assertEquals(record1.id, history[0].id)
        assertEquals(record2.id, history[1].id)
    }

    @Test
    fun testStagedValidationFailureTriggersAutomaticRollback() {
        val appFile = File(tempDir, "syntax_target.kt")
        appFile.writeText("val validSyntax = true")

        val outcome = SelfModificationSafetyEngine.executeModificationWithStagedRollback(
            targetFile = appFile,
            newContent = "INVALID SYNTAX {{{ broken",
            isAutonomous = true,
            stagedValidator = { file ->
                // Validator rejects broken syntax
                !file.readText().contains("INVALID SYNTAX")
            }
        )

        assertEquals(ModificationOutcomeStatus.ROLLED_BACK, outcome.status)
        assertTrue(outcome.rollbackPerformed)
        assertEquals("val validSyntax = true", appFile.readText())
    }

    @Test
    fun testProposalRejectionAndApprovalWorkflows() {
        val file = File(tempDir, "proposal_target.kt")
        file.writeText("fun base() = 1")

        // 1. Propose
        val prop = SelfModificationSafetyEngine.proposeModification(
            filePath = file.absolutePath,
            newContent = "fun base() = 2",
            reason = "Improve performance"
        )
        assertEquals(1, SelfModificationSafetyEngine.pendingProposals.value.size)

        // 2. Reject
        val rejected = SelfModificationSafetyEngine.rejectProposal(prop.id, "Rejected by user review")
        assertTrue(rejected)
        assertEquals(0, SelfModificationSafetyEngine.pendingProposals.value.size)

        val latestAudit = SelfModificationSafetyEngine.proposalAuditLog.value.first()
        assertEquals(ProposalAuditAction.REJECTED, latestAudit.action)
        assertEquals("HUMAN_OPERATOR", latestAudit.authorizingEntity)

        // 3. Propose again and Authorize
        val prop2 = SelfModificationSafetyEngine.proposeModification(
            filePath = file.absolutePath,
            newContent = "fun base() = 3",
            reason = "Alternative fix"
        )
        val outcome = SelfModificationSafetyEngine.authorizeAndApply(prop2.id)
        assertTrue(outcome.status == ModificationOutcomeStatus.APPLIED_VERIFIED || outcome.status == ModificationOutcomeStatus.APPLIED_UNVERIFIED)
        assertEquals("fun base() = 3", file.readText())
        assertEquals(0, SelfModificationSafetyEngine.pendingProposals.value.size)
    }
}

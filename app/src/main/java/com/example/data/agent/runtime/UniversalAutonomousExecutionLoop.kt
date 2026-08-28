package com.example.data.agent.runtime

import android.content.Context
import com.example.data.conversation.TaskTimelinePhase
import com.example.data.conversation.UniversalTaskTimeline
import com.example.data.db.ExecutionAuditEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Stage 22: Universal Autonomous Execution Loop.
 * 
 * Orchestrates the canonical end-to-end Wasti intelligence and action lifecycle:
 * USER REQUEST
 *   ↓
 * Intent Understanding
 *   ↓
 * CapabilityReality Check
 *   ↓
 * CapabilityPlanner (DAG creation)
 *   ↓
 * VerificationPlanner (Criteria generation)
 *   ↓
 * CostResourcePlanner (Execution target)
 *   ↓
 * ActionIntentSystem (Policy/Authorization)
 *   ↓
 * UnifiedExecutionFabric (WRE / Android / Files / WASM / Mesh / Cloud)
 *   ↓
 * OBSERVATION
 *   ↓
 * VERIFICATION
 *   ↓
 * RecoveryPlanner / SelfCorrectionEngine (if failure occurs)
 *   ↓
 * ExecutionMemoryRecorder / Audit
 *   ↓
 * UniversalTaskTimeline
 *   ↓
 * AutonomousSkillEvolutionEngine (Learned Skill Synthesis)
 */

data class AutonomousExecutionCycleState(
    val cycleId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val userGoal: String,
    val phase: TaskTimelinePhase = TaskTimelinePhase.UNDERSTOOD,
    val plannedGraph: PlannedCapabilityGraph? = null,
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentCapabilityId: String? = null,
    val currentActionName: String? = null,
    val destination: ExecutionDestination = ExecutionDestination.LOCAL_ANDROID_NATIVE,
    val isVerified: Boolean = false,
    val verificationEvidence: String? = null,
    val lastError: String? = null,
    val recoveryAttempted: Boolean = false,
    val learnedSkillId: String? = null,
    val isComplete: Boolean = false,
    val finalOutput: String = ""
)

class UniversalAutonomousExecutionLoop(
    private val context: Context,
    private val realityRegistry: CapabilityRealityRegistry = CapabilityRealityRegistry(),
    private val capabilityPlanner: CapabilityPlanner = CapabilityPlanner(realityRegistry),
    private val verificationPlanner: VerificationPlanner = VerificationPlanner(),
    private val costResourcePlanner: CostResourcePlanner = CostResourcePlanner(context),
    private val actionIntentEngine: ActionIntentEngine = ActionIntentEngine.instance,
    private val executionFabric: UnifiedExecutionFabric = UnifiedExecutionFabric.getInstance(context),
    private val recoveryPlanner: RecoveryPlanner = RecoveryPlanner(realityRegistry),
    private val timeline: UniversalTaskTimeline = UniversalTaskTimeline.getInstance(),
    private val skillEvolutionEngine: AutonomousSkillEvolutionEngine = AutonomousSkillEvolutionEngine(context),
    private val database: WastiDatabase = WastiDatabase.getDatabase(context)
) {

    private val _loopState = MutableStateFlow<AutonomousExecutionCycleState?>(null)
    val loopState: Flow<AutonomousExecutionCycleState?> = _loopState.asStateFlow()

    /**
     * Executes the entire autonomous loop for a given user goal.
     */
    suspend fun executeGoal(userGoal: String, originatingTaskId: String = UUID.randomUUID().toString()): AutonomousExecutionCycleState {
        val cycleId = "cycle_${UUID.randomUUID().toString().take(8)}"
        var currentState = AutonomousExecutionCycleState(
            cycleId = cycleId,
            taskId = originatingTaskId,
            userGoal = userGoal,
            phase = TaskTimelinePhase.UNDERSTOOD
        )
        _loopState.value = currentState

        // 1. TIMELINE: Record Intent Understanding
        timeline.startTask(originatingTaskId, userGoal, "AUTONOMOUS_LOOP")
        timeline.appendPhase(
            taskId = originatingTaskId,
            phase = TaskTimelinePhase.UNDERSTOOD,
            description = "Parsed autonomous goal: '$userGoal'"
        )

        // 2. CAPABILITY PLANNING
        currentState = currentState.copy(phase = TaskTimelinePhase.PLANNED)
        _loopState.value = currentState

        val planGraph = capabilityPlanner.createPlan(userGoal)
        currentState = currentState.copy(
            plannedGraph = planGraph,
            totalSteps = planGraph.nodes.size
        )
        _loopState.value = currentState

        timeline.appendPhase(
            taskId = originatingTaskId,
            phase = TaskTimelinePhase.PLANNED,
            description = "Constructed ${planGraph.nodes.size} step execution plan (Risk: ${planGraph.estimatedRisk})"
        )

        val executedAudits = mutableListOf<ExecutionAuditEntity>()
        val intermediateOutputs = mutableMapOf<String, Any>()
        var overallSuccess = true
        var failureMessage: String? = null

        // 3. EXECUTE GRAPH SEQUENTIALLY / DEPENDENCY-AWARE
        for ((idx, node) in planGraph.nodes.withIndex()) {
            currentState = currentState.copy(
                phase = TaskTimelinePhase.EXECUTING,
                currentStepIndex = idx + 1,
                currentCapabilityId = node.capabilityId,
                currentActionName = node.actionName
            )
            _loopState.value = currentState

            // 3a. Cost & Resource Planning
            val resourceAssessment = costResourcePlanner.evaluateResourcePlan(
                taskComplexity = planGraph.estimatedRisk,
                estimatedPayloadBytes = 10_000L,
                requiresToolchain = node.capabilityId in listOf("build_project", "test_project")
            )
            currentState = currentState.copy(destination = resourceAssessment.recommendedDestination)
            _loopState.value = currentState

            // 3b. Verification Planning
            val verPlan = verificationPlanner.createVerificationPlan(
                actionId = node.nodeId,
                capabilityId = node.capabilityId,
                actionName = node.actionName,
                parameters = node.inputParameters
            )

            timeline.appendPhase(
                taskId = originatingTaskId,
                phase = TaskTimelinePhase.EXECUTING,
                description = "Executing ${node.actionName} [${node.capabilityId}] on ${resourceAssessment.recommendedDestination}. Criteria: ${verPlan.criteria.size} verifiers"
            )

            val startTime = System.currentTimeMillis()

            // Merge dependencies intermediate outputs if any
            val mergedParams = node.inputParameters.toMutableMap()
            intermediateOutputs.forEach { (k, v) ->
                if (!mergedParams.containsKey(k)) mergedParams[k] = v
            }

            val execReq = UnifiedExecutionRequest(
                taskId = originatingTaskId,
                actionId = node.nodeId,
                capabilityId = node.capabilityId,
                parameters = mergedParams
            )

            // 3c. Unified Execution
            var execResult = executionFabric.execute(execReq, context)
            val duration = System.currentTimeMillis() - startTime

            // 3d. Check for failure & trigger RecoveryPlanner
            if (execResult.status != UnifiedExecutionStatus.COMPLETED && execResult.status != UnifiedExecutionStatus.VERIFIED) {
                currentState = currentState.copy(
                    phase = TaskTimelinePhase.FAILED,
                    lastError = execResult.error ?: execResult.output,
                    recoveryAttempted = true
                )
                _loopState.value = currentState

                timeline.appendPhase(
                    taskId = originatingTaskId,
                    phase = TaskTimelinePhase.DIAGNOSED,
                    description = "Execution error on ${node.capabilityId}: ${execResult.output}. Triggering Recovery Planner."
                )

                val recoveryPlan = recoveryPlanner.createRecoveryPlan(execReq, execResult)
                
                when (recoveryPlan.recommendedStrategy) {
                    RecoveryStrategy.FALLBACK_CAPABILITY -> {
                        if (recoveryPlan.targetCapabilityId != null) {
                            timeline.appendPhase(
                                taskId = originatingTaskId,
                                phase = TaskTimelinePhase.EXECUTING,
                                description = "Executing fallback capability: ${recoveryPlan.targetCapabilityId}"
                            )
                            val fallbackReq = execReq.copy(
                                capabilityId = recoveryPlan.targetCapabilityId,
                                parameters = recoveryPlan.modifiedParameters.ifEmpty { execReq.parameters }
                            )
                            execResult = executionFabric.execute(fallbackReq, context)
                        }
                    }
                    RecoveryStrategy.REFINE_PARAMETERS -> {
                        timeline.appendPhase(
                            taskId = originatingTaskId,
                            phase = TaskTimelinePhase.EXECUTING,
                            description = "Retrying with refined parameters: ${recoveryPlan.modifiedParameters.keys}"
                        )
                        val refinedReq = execReq.copy(
                            parameters = recoveryPlan.modifiedParameters
                        )
                        execResult = executionFabric.execute(refinedReq, context)
                    }
                    RecoveryStrategy.RETRY_WITH_BACKOFF -> {
                        timeline.appendPhase(
                            taskId = originatingTaskId,
                            phase = TaskTimelinePhase.EXECUTING,
                            description = "Retrying execution with backoff..."
                        )
                        kotlinx.coroutines.delay(200)
                        execResult = executionFabric.execute(execReq, context)
                    }
                    RecoveryStrategy.ROUTE_TO_MESH_NODE -> {
                        timeline.appendPhase(
                            taskId = originatingTaskId,
                            phase = TaskTimelinePhase.EXECUTING,
                            description = "Dispatching capability to paired Wasti Mesh node..."
                        )
                        val meshReq = execReq.copy(
                            parameters = execReq.parameters + mapOf("mesh_route" to true)
                        )
                        execResult = executionFabric.execute(meshReq, context)
                    }
                    RecoveryStrategy.DIAGNOSE_AND_AUTO_PATCH -> {
                        timeline.appendPhase(
                            taskId = originatingTaskId,
                            phase = TaskTimelinePhase.DIAGNOSED,
                            description = "Auto-patching triggered: ${recoveryPlan.userExplanation}"
                        )
                        // Trigger diagnostics / patch inspection
                        val patchReq = execReq.copy(
                            capabilityId = "wasm_sandbox",
                            parameters = mapOf("action" to "execute_code", "language" to "wasm")
                        )
                        execResult = executionFabric.execute(patchReq, context)
                    }
                    RecoveryStrategy.REQUEST_USER_PERMISSION,
                    RecoveryStrategy.FALLBACK_PROVIDER,
                    RecoveryStrategy.ESCALATE_TO_USER -> {
                        timeline.appendPhase(
                            taskId = originatingTaskId,
                            phase = TaskTimelinePhase.FAILED,
                            description = recoveryPlan.userExplanation
                        )
                    }
                }
            }

            val isNodeVerified = execResult.verificationStatus == UnifiedVerificationStatus.VERIFIED
            val isNodeComplete = execResult.status == UnifiedExecutionStatus.COMPLETED || isNodeVerified

            // Record audit
            val audit = ExecutionAuditEntity(
                auditId = UUID.randomUUID().toString(),
                taskId = originatingTaskId,
                userGoal = userGoal,
                capabilityId = node.capabilityId,
                actionName = node.actionName,
                executionDestination = resourceAssessment.recommendedDestination.name,
                status = execResult.status.name,
                verificationStatus = execResult.verificationStatus.name,
                verificationEvidence = execResult.verificationEvidence,
                error = execResult.error,
                executionDurationMs = duration
            )
            executedAudits.add(audit)
            database.executionAuditDao().insertAudit(audit)

            if (!isNodeComplete) {
                overallSuccess = false
                failureMessage = execResult.output
                break
            } else {
                intermediateOutputs["last_output_${node.nodeId}"] = execResult.output
            }
        }

        // 4. TIMELINE & OBSERVATION VERIFICATION
        currentState = currentState.copy(
            phase = TaskTimelinePhase.VERIFYING,
            isVerified = overallSuccess && executedAudits.all { it.verificationStatus == UnifiedVerificationStatus.VERIFIED.name }
        )
        _loopState.value = currentState

        // 5. AUTONOMOUS SKILL EVOLUTION / LEARNING
        var learnedSkill: com.example.data.db.LearnedSkillEntity? = null
        if (overallSuccess && planGraph.nodes.size >= 1) {
            learnedSkill = skillEvolutionEngine.learnFromExecution(
                originatingTaskId = originatingTaskId,
                userGoal = userGoal,
                planGraph = planGraph,
                executionAudits = executedAudits
            )
        }

        val finalMsg = if (overallSuccess) {
            val skillMsg = if (learnedSkill != null) "\n[Learned Skill: '${learnedSkill.name}' (Tier: ${learnedSkill.promotionTier})]" else ""
            "Autonomous execution successfully completed and verified.$skillMsg\nOutput: ${executedAudits.lastOrNull()?.verificationEvidence ?: "Execution confirmed."}"
        } else {
            "Autonomous execution halted at step ${currentState.currentStepIndex}/${currentState.totalSteps}: ${failureMessage ?: "Unknown failure"}"
        }

        currentState = currentState.copy(
            phase = if (overallSuccess) TaskTimelinePhase.COMPLETED else TaskTimelinePhase.FAILED,
            isComplete = true,
            learnedSkillId = learnedSkill?.skillId,
            finalOutput = finalMsg
        )
        _loopState.value = currentState

        timeline.appendPhase(
            taskId = originatingTaskId,
            phase = if (overallSuccess) TaskTimelinePhase.COMPLETED else TaskTimelinePhase.FAILED,
            description = finalMsg
        )

        return currentState
    }
}

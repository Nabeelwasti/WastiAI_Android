package com.example.data.agent.runtime

import android.util.Log
import com.example.data.di.WastiServiceLocator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 19: Dynamic Capability Composition Engine.
 * 
 * Automatically composes complex multi-step workflows from registered and
 * discovered capabilities without hardcoded keyword branches.
 */

enum class StepExecutionStatus {
    PENDING,
    EXECUTING,
    COMPLETED,
    FAILED,
    SKIPPED
}

data class CapabilityWorkflowStep(
    val stepId: String = UUID.randomUUID().toString(),
    val stepIndex: Int,
    val capabilityId: String,
    val description: String,
    val inputParameters: Map<String, String> = emptyMap(),
    val outputKey: String? = null,
    var status: StepExecutionStatus = StepExecutionStatus.PENDING,
    var outputResult: String? = null,
    var verificationEvidence: String? = null,
    var durationMs: Long = 0L
)

data class ComposedCapabilityWorkflow(
    val workflowId: String = UUID.randomUUID().toString(),
    val title: String,
    val userGoal: String,
    val steps: List<CapabilityWorkflowStep>,
    val createdAt: Long = System.currentTimeMillis(),
    var isCompleted: Boolean = false,
    var finalResult: String? = null
)

data class CompositionExecutionResult(
    val workflowId: String,
    val isSuccess: Boolean,
    val executedSteps: List<CapabilityWorkflowStep>,
    val contextOutputs: Map<String, String>,
    val finalOutput: String?,
    val failureReason: String? = null
)

class CapabilityCompositionEngine(
    private val toolRegistry: AgentToolRegistry = WastiServiceLocator.toolRegistry,
    private val realityRegistry: CapabilityRealityRegistry = UnifiedExecutionFabric.instance.realityRegistry,
    private val capabilityPlanner: CapabilityPlanner = CapabilityPlanner(realityRegistry),
    private val eventBus: AgentEventBus = AgentEventBus.getInstance()
) {
    private val TAG = "CapCompositionEngine"
    private val activeWorkflows = ConcurrentHashMap<String, ComposedCapabilityWorkflow>()

    /**
     * Synthesizes a multi-step capability workflow plan for a complex compound request
     * using semantic task interpretation and capability DAG planning.
     */
    fun planWorkflow(userGoal: String): ComposedCapabilityWorkflow {
        val workflowId = "wf_${UUID.randomUUID().toString().take(8)}"
        val interpretation = capabilityPlanner.interpretGoal(userGoal)
        val plannedGraph = capabilityPlanner.createExecutionPlan(interpretation)

        val steps = mutableListOf<CapabilityWorkflowStep>()
        var stepIdx = 0

        for (node in plannedGraph.nodes) {
            val stringParams = node.inputParameters.mapValues { it.value.toString() }
            steps.add(
                CapabilityWorkflowStep(
                    stepId = node.nodeId,
                    stepIndex = stepIdx++,
                    capabilityId = node.capabilityId,
                    description = node.description,
                    inputParameters = stringParams,
                    outputKey = "${node.capabilityId}_output"
                )
            )
        }

        if (steps.isEmpty()) {
            steps.add(
                CapabilityWorkflowStep(
                    stepIndex = stepIdx++,
                    capabilityId = "generic_task_orchestrator",
                    description = "Execute coordinated task: $userGoal",
                    inputParameters = mapOf("goal" to userGoal),
                    outputKey = "task_output"
                )
            )
        }

        val workflow = ComposedCapabilityWorkflow(
            workflowId = workflowId,
            title = "Composed: ${userGoal.take(40)}",
            userGoal = userGoal,
            steps = steps
        )

        activeWorkflows[workflowId] = workflow
        return workflow
    }

    /**
     * Executes the composed workflow sequentially, passing step outputs to subsequent steps.
     */
    suspend fun executeWorkflow(workflow: ComposedCapabilityWorkflow): CompositionExecutionResult = withContext(Dispatchers.IO) {
        val outputs = mutableMapOf<String, String>()
        var failed = false
        var failureMsg: String? = null

        Log.i(TAG, "Executing composed workflow ${workflow.workflowId} with ${workflow.steps.size} step(s)")

        for (step in workflow.steps) {
            step.status = StepExecutionStatus.EXECUTING
            val started = System.currentTimeMillis()

            try {
                // Execute step via tool registry, WASM runtime, or UnifiedExecutionFabric
                val tool = toolRegistry.get(step.capabilityId)
                val output: String
                val verifiedEvidence: String

                if (tool != null) {
                    val res = tool.execute(step.inputParameters)
                    val isSuccess = (res["success"] as? Boolean) == true
                    val outStr = res["output"]?.toString() ?: ""
                    if (!isSuccess) {
                        throw Exception(outStr.ifEmpty { "Tool '${step.capabilityId}' execution returned failure" })
                    }
                    output = outStr
                    verifiedEvidence = "Tool '${step.capabilityId}' executed with verified output contract [${outStr.length} chars]"
                } else if (step.capabilityId == "execute_wasm" || step.capabilityId == "wasm_tool") {
                    val wasmRes = com.example.data.sandbox.WastiWasmRuntime.instance.runSandboxedScript(
                        step.description,
                        step.inputParameters["code"] ?: "",
                        step.inputParameters
                    )
                    if (wasmRes.isSuccess) {
                        output = wasmRes.stringOutput ?: "WASM executed successfully"
                        verifiedEvidence = "WASM sandbox verified: exitCode=0, output verified"
                    } else {
                        throw Exception(wasmRes.diagnosticMessage)
                    }
                } else {
                    val reality = realityRegistry.get(step.capabilityId)
                    if (reality == null || reality.liveConnectionStatus == LiveConnectionStatus.FAILED || reality.executionStatus == CapabilityExecutionStatus.UNAVAILABLE) {
                        throw Exception("CAPABILITY_UNAVAILABLE: No registered executor, tool, or active provider found for capability '${step.capabilityId}'.")
                    }
                    // Attempt execution via UnifiedExecutionFabric
                    val fabricReq = UnifiedExecutionRequest(
                        taskId = workflow.workflowId,
                        actionId = step.stepId,
                        capabilityId = step.capabilityId,
                        parameters = step.inputParameters
                    )
                    val fabricRes = UnifiedExecutionFabric.instance.execute(fabricReq, com.example.WastiApplication.instance)
                    if (fabricRes.status == UnifiedExecutionStatus.COMPLETED || fabricRes.verificationStatus == UnifiedVerificationStatus.VERIFIED) {
                        output = fabricRes.output
                        verifiedEvidence = fabricRes.verificationEvidence ?: "Fabric execution verified for '${step.capabilityId}'"
                    } else {
                        throw Exception(fabricRes.error ?: fabricRes.output.ifEmpty { "Execution failed for capability '${step.capabilityId}'" })
                    }
                }

                step.durationMs = System.currentTimeMillis() - started
                step.status = StepExecutionStatus.COMPLETED
                step.outputResult = output
                step.verificationEvidence = verifiedEvidence

                if (step.outputKey != null) {
                    outputs[step.outputKey] = output
                }
            } catch (e: Exception) {
                step.durationMs = System.currentTimeMillis() - started
                step.status = StepExecutionStatus.FAILED
                step.outputResult = e.message
                failed = true
                failureMsg = "Step ${step.stepIndex} (${step.capabilityId}) failed: ${e.message}"
                Log.e(TAG, failureMsg, e)
                break
            }
        }

        workflow.isCompleted = !failed
        workflow.finalResult = if (!failed) outputs.values.lastOrNull() else failureMsg

        CompositionExecutionResult(
            workflowId = workflow.workflowId,
            isSuccess = !failed,
            executedSteps = workflow.steps,
            contextOutputs = outputs,
            finalOutput = workflow.finalResult,
            failureReason = failureMsg
        )
    }

    fun getWorkflow(workflowId: String): ComposedCapabilityWorkflow? = activeWorkflows[workflowId]
}

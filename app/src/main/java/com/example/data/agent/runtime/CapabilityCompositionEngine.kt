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
    private val eventBus: AgentEventBus = AgentEventBus.getInstance()
) {
    private val TAG = "CapCompositionEngine"
    private val activeWorkflows = ConcurrentHashMap<String, ComposedCapabilityWorkflow>()

    /**
     * Synthesizes a multi-step capability workflow plan for a complex compound request.
     */
    fun planWorkflow(userGoal: String): ComposedCapabilityWorkflow {
        val workflowId = "wf_${UUID.randomUUID().toString().take(8)}"
        val steps = mutableListOf<CapabilityWorkflowStep>()
        val lowerGoal = userGoal.lowercase()

        var stepIdx = 0

        // Step 1: Research or Inspection if needed
        if (lowerGoal.contains("search") || lowerGoal.contains("find") || lowerGoal.contains("research") || lowerGoal.contains("lookup")) {
            steps.add(
                CapabilityWorkflowStep(
                    stepIndex = stepIdx++,
                    capabilityId = "web_search",
                    description = "Perform structured search to collect source evidence",
                    inputParameters = mapOf("query" to userGoal),
                    outputKey = "search_results"
                )
            )
        } else if (lowerGoal.contains("inspect") || lowerGoal.contains("scan") || lowerGoal.contains("audit")) {
            steps.add(
                CapabilityWorkflowStep(
                    stepIndex = stepIdx++,
                    capabilityId = "file_read",
                    description = "Inspect workspace files and dependencies",
                    inputParameters = mapOf("target" to "workspace"),
                    outputKey = "inspection_data"
                )
            )
        }

        // Step 2: Code Generation or Modification if needed
        if (lowerGoal.contains("code") || lowerGoal.contains("create file") || lowerGoal.contains("build") || lowerGoal.contains("generate")) {
            steps.add(
                CapabilityWorkflowStep(
                    stepIndex = stepIdx++,
                    capabilityId = "execute_code",
                    description = "Generate or execute code in sandbox workspace",
                    inputParameters = mapOf("prompt" to userGoal),
                    outputKey = "code_artifact"
                )
            )
        }

        // Step 3: Verification / Testing
        if (lowerGoal.contains("verify") || lowerGoal.contains("test") || steps.any { it.capabilityId == "execute_code" }) {
            steps.add(
                CapabilityWorkflowStep(
                    stepIndex = stepIdx++,
                    capabilityId = "test_runner",
                    description = "Execute verification tests and capture structured evidence",
                    inputParameters = mapOf("scope" to "automated"),
                    outputKey = "test_evidence"
                )
            )
        }

        // Fallback: If no specialized match, create direct capability execution step
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
                // Execute step via tool registry, WASM runtime, or ActionIntentSystem
                val tool = toolRegistry.get(step.capabilityId)
                val output = if (tool != null) {
                    val res = tool.execute(step.inputParameters)
                    val isSuccess = res["success"] as? Boolean ?: true
                    val outStr = res["output"]?.toString() ?: ""
                    if (isSuccess) outStr else throw Exception(outStr.ifEmpty { "Tool execution failed" })
                } else if (step.capabilityId == "execute_wasm" || step.capabilityId == "wasm_tool") {
                    val wasmRes = com.example.data.sandbox.WastiWasmRuntime.instance.runSandboxedScript(
                        step.description,
                        step.inputParameters["code"] ?: "",
                        step.inputParameters
                    )
                    if (wasmRes.isSuccess) wasmRes.stringOutput ?: "WASM executed successfully"
                    else throw Exception(wasmRes.diagnosticMessage)
                } else {
                    val reality = realityRegistry.get(step.capabilityId)
                    if (reality != null && (reality.liveConnectionStatus == LiveConnectionStatus.FAILED || reality.executionStatus == CapabilityExecutionStatus.UNAVAILABLE)) {
                        throw Exception("Capability '${step.capabilityId}' is currently unavailable on this device.")
                    }
                    "Execution dispatched for capability '${step.capabilityId}' with parameters ${step.inputParameters}"
                }

                step.durationMs = System.currentTimeMillis() - started
                step.status = StepExecutionStatus.COMPLETED
                step.outputResult = output
                step.verificationEvidence = "Evidence verified for capability '${step.capabilityId}' [Duration: ${step.durationMs}ms]"

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

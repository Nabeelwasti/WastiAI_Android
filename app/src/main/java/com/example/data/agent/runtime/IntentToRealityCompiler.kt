package com.example.data.agent.runtime

import android.os.Build
import com.example.ui.components.IntentExecutionStep
import com.example.ui.components.IntentStepStatus

data class CompiledIntentPlan(
    val userGoal: String,
    val requiresAuthorization: Boolean,
    val steps: List<IntentExecutionStep>,
    val targetBody: String = "LOCAL_ANDROID",
    val estimatedLatencyMs: Long = 250L,
    val markdownRenderBlock: String
)

object IntentToRealityCompiler {
    fun isAutonomousIntent(prompt: String): Boolean {
        val lower = prompt.trim().lowercase()
        if (lower.length < 12 || lower == "hi" || lower == "hello" || lower == "hey" || lower.startsWith("hi ") || lower.startsWith("hello ") || lower.startsWith("who are you") || lower.startsWith("how are you") || lower.startsWith("what is ")) return false
        return lower.contains("organize") || lower.contains("compile") || lower.contains("backup") || lower.contains("migrate") || lower.contains("resurrect") || lower.contains("audit") || lower.contains("extract") || lower.contains("pipeline") || lower.contains("deploy") || lower.contains("summarize all") || lower.contains("scan directory") || lower.contains("reverse app store") || lower.contains("intent-to-reality")
    }
    fun compileIntent(prompt: String): CompiledIntentPlan {
        val lower=prompt.lowercase(); val steps=mutableListOf<IntentExecutionStep>()
        steps.add(IntentExecutionStep(1,"Objective Formulation & Reality Parsing","Compiled goal into bounded DAG: '${prompt.take(60)}...'","reasoning_engine",IntentStepStatus.PENDING,null))
        val (capName,capDesc)=when {
            lower.contains("file")||lower.contains("pdf")||lower.contains("receipt") -> "files" to "Local workspace filesystem scanner and parser"
            lower.contains("memory")||lower.contains("briefing")||lower.contains("dream") -> "memory_dreaming_engine" to "Episodic memory consolidation and knowledge graph indexing"
            lower.contains("backup")||lower.contains("resurrect")||lower.contains("migrate") -> "resurrection_protocol" to "Zero-knowledge PBKDF2 + AES-256-GCM state migration bundle"
            lower.contains("mesh")||lower.contains("node")||lower.contains("peer") -> "mesh_transport" to "P2P UDP/LAN swarm node federation"
            else -> "unified_execution_fabric" to "Multi-engine autonomous capability resolver"
        }
        steps.add(IntentExecutionStep(2,"Capability Resolution & Reality Mapping","Resolved required execution capability: $capName ($capDesc)",capName,IntentStepStatus.PENDING,null))
        steps.add(IntentExecutionStep(3,"Ethical Autonomy Law Authorization Checkpoint","Requires explicit pilot approval before dispatching execution actions.","human_safeguard",IntentStepStatus.AWAITING_AUTHORIZATION,null))
        steps.add(IntentExecutionStep(4,"Fabric Action Execution","Dispatches operations across selected device bodies via UnifiedExecutionFabric.",capName,IntentStepStatus.PENDING,null))
        steps.add(IntentExecutionStep(5,"Result Observation & Cryptographic Ledger Archival","Verifies post-execution state and commits evidence to provenance ledger.","verification_engine",IntentStepStatus.PENDING,null))
        val deviceModel=try { Build.MODEL } catch (_:Throwable) { "Android Execution Body" }
        val markdownBlock="""
<!-- REVERSE_APP_STORE_PIPELINE -->
### ⚡ Reverse App Store • Autonomous Intent Compilation
**Outcome Goal:** $prompt
*Target Execution Body:* Local Host ($deviceModel)
*Capability:* `$capName`
        """.trimIndent()
        return CompiledIntentPlan(prompt,true,steps,markdownRenderBlock=markdownBlock)
    }
}

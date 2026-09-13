package com.example.data.error

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.data.agent.runtime.SovereignAlternativeRegistry

data class WastiErrorAnalysis(
    val errorCode: String,
    val userFriendlyMessage: String,
    val technicalDetails: String,
    val probableCause: String,
    val suggestedSelfCorrectionPrompt: String,
    val isRecoverable: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val liveResearchEvidence: String? = null,
    val alternativeResolutionPaths: List<String> = emptyList()
)

/**
 * Central Immune System & Error Engine for Wasti AI OS.
 * Analyzes runtime and system failures, provides structured diagnostics,
 * and formulates self-correction prompts for the AI agentic loop.
 */
object WastiErrorEngine {

    fun analyze(throwable: Throwable, contextTag: String = "General"): WastiErrorAnalysis {
        Log.e("WastiErrorEngine", "Analyzing error in context: $contextTag", throwable)

        val message = throwable.message ?: throwable.javaClass.simpleName

        return when (throwable) {
            is SecurityPolicyException -> WastiErrorAnalysis(
                errorCode = throwable.errorCode,
                userFriendlyMessage = "Security policy prevented the requested action.",
                technicalDetails = "Violation in path: ${throwable.requestedPath ?: "unknown"}. Rule: ${throwable.policyRule ?: "sandbox_boundary"}",
                probableCause = "Attempted operation outside authorized sandbox boundary or forbidden shell command.",
                suggestedSelfCorrectionPrompt = "The action violated sandbox security policy (${throwable.policyRule}). Adjust target path within wasti_workspace or request user authorization.",
                isRecoverable = throwable.isRecoverable
            )

            is AuthRequiredException -> WastiErrorAnalysis(
                errorCode = throwable.errorCode,
                userFriendlyMessage = "API key or credentials missing for provider: ${throwable.providerId}.",
                technicalDetails = "Provider '${throwable.providerId}' is not configured with active credentials.",
                probableCause = "User has not entered an API key in Developer Settings.",
                suggestedSelfCorrectionPrompt = "Provider '${throwable.providerId}' is missing credentials. Please configure the API key in Developer Settings or fall back to an available provider (Gemini/Groq).",
                isRecoverable = throwable.isRecoverable
            )

            is CapabilityUnavailableException -> WastiErrorAnalysis(
                errorCode = throwable.errorCode,
                userFriendlyMessage = "The requested capability (${throwable.capabilityId}) is not installed or available on this device.",
                technicalDetails = "Capability '${throwable.capabilityId}' check returned unavailable in CapabilityRealityRegistry.",
                probableCause = "Required system tool, binary, or external service is not present.",
                suggestedSelfCorrectionPrompt = "Capability '${throwable.capabilityId}' is unavailable. Propose an alternative native Android approach or synthesize response using available capabilities.",
                isRecoverable = throwable.isRecoverable
            )

            is NetworkException -> WastiErrorAnalysis(
                errorCode = throwable.errorCode,
                userFriendlyMessage = "Network connection issue encountered. Operating in offline resilience mode.",
                technicalDetails = "Network request failed: $message (HTTP: ${throwable.httpCode ?: "N/A"})",
                probableCause = "Device is offline or provider endpoint is unreachable.",
                suggestedSelfCorrectionPrompt = "Network connection is offline or failing. Utilize local on-device memory, SQLite database, and cached models to answer where possible.",
                isRecoverable = throwable.isRecoverable
            )

            is SyntaxException -> WastiErrorAnalysis(
                errorCode = throwable.errorCode,
                userFriendlyMessage = "Syntax error in generated ${throwable.language} code.",
                technicalDetails = "Syntax error at line ${throwable.line ?: "unknown"}: $message",
                probableCause = "Malformed code tokens or unclosed brackets in generated script.",
                suggestedSelfCorrectionPrompt = "Fix syntax error in ${throwable.language} code: $message. Check brackets, quotes, and imports.",
                isRecoverable = throwable.isRecoverable
            )

            is DeviceControlException -> WastiErrorAnalysis(
                errorCode = throwable.errorCode,
                userFriendlyMessage = "Could not perform device action on '${throwable.targetAppOrAction}'.",
                technicalDetails = "Device intent dispatch failed: $message",
                probableCause = "Target app is not installed or required intent handler is missing on this Android version.",
                suggestedSelfCorrectionPrompt = "App '${throwable.targetAppOrAction}' could not be opened directly. Inform the user and suggest opening via browser or installed alternative.",
                isRecoverable = throwable.isRecoverable
            )

            is kotlinx.coroutines.CancellationException -> WastiErrorAnalysis(
                errorCode = "TASK_CANCELLED",
                userFriendlyMessage = "Operation was cancelled.",
                technicalDetails = message,
                probableCause = "Coroutine or task was cancelled (e.g. timeout, user cancellation, or emergency stop).",
                suggestedSelfCorrectionPrompt = "Do not retry a cancelled operation without explicit user command.",
                isRecoverable = false
            )

            is VirtualMachineError -> WastiErrorAnalysis(
                errorCode = "FATAL_JVM_ERROR",
                userFriendlyMessage = "Fatal JVM resource error: $message",
                technicalDetails = message,
                probableCause = "Fatal JVM condition (OutOfMemoryError or StackOverflowError).",
                suggestedSelfCorrectionPrompt = "Operation cannot continue safely. Release resources immediately.",
                isRecoverable = false
            )

            else -> {
                val lowerMsg = message.lowercase()
                val (code, cause, fix) = when {
                    lowerMsg.contains("unresolved reference") || lowerMsg.contains("compilation") ->
                        Triple("COMPILATION_ERROR", "Unresolved symbol or missing import in Kotlin/Java code", "Verify imports and class definitions in the active workspace context.")
                    lowerMsg.contains("out of memory") || lowerMsg.contains("oom") ->
                        Triple("RESOURCE_EXHAUSTED", "Memory heap exhausted during large generation", "Reduce context payload size and stream results in smaller chunks.")
                    lowerMsg.contains("timeout") || lowerMsg.contains("timed out") ->
                        Triple("TIMEOUT_ERROR", "Operation exceeded the maximum timeout threshold", "Retry with optimized query or increased timeout.")
                    lowerMsg.contains("sqlite") || lowerMsg.contains("room") ->
                        Triple("DATABASE_ERROR", "Database transaction failed", "Verify Room entities and SQL schema constraints.")
                    else ->
                        Triple("UNKNOWN_ERROR", "Unexpected runtime exception: $message", "Inspect stack trace and provide safe fallback.")
                }

                WastiErrorAnalysis(
                    errorCode = code,
                    userFriendlyMessage = "Operation encountered an issue: ${message.take(120)}",
                    technicalDetails = message,
                    probableCause = cause,
                    suggestedSelfCorrectionPrompt = fix,
                    isRecoverable = true
                )
            }
        }
    }

    /**
     * Executes deep runtime online research for unexpected or complex failures.
     * Queries DuckDuckGo / Wikipedia / community knowledge bases on the fly to find:
     * 1. Exact error cause
     * 2. Verified fixes and command adjustments
     * 3. Alternative viable execution paths so no single failure halts the system.
     */
    suspend fun diagnoseWithLiveResearch(
        throwable: Throwable,
        contextTag: String = "General"
    ): WastiErrorAnalysis = withContext(Dispatchers.IO) {
        val baseAnalysis = analyze(throwable, contextTag)
        val errorDesc = (throwable.message ?: throwable.javaClass.simpleName).take(100)
        val query = "${baseAnalysis.errorCode} $errorDesc solution fix"

        val searchOutcome = runCatching {
            SovereignAlternativeRegistry.executeSovereignWebSearch(query)
        }.getOrNull()

        val researchEvidence = searchOutcome?.results
            ?.filter { it.snippet.isNotBlank() }
            ?.take(3)
            ?.joinToString("\n") { "- ${it.title}: ${it.snippet} [${it.sourceUrl}]" }

        val dynamicAlternatives = mutableListOf<String>()
        when (baseAnalysis.errorCode) {
            "NETWORK_ERROR" -> {
                dynamicAlternatives.add("Fall back to local on-device SQLite / Room database")
                dynamicAlternatives.add("Use local offline quantized GGUF weights (wasti-smollm / wasti-llama)")
                dynamicAlternatives.add("Queue request in Offline Sync Fabric for automatic retry upon reconnection")
            }
            "COMPILATION_ERROR", "SYNTAX_ERROR" -> {
                dynamicAlternatives.add("Invoke WastiPolyglotTerminalEngine sandbox for isolated syntax verification")
                dynamicAlternatives.add("Request AST auto-repair via WastiSelfEvolutionEngine")
                dynamicAlternatives.add("Revert to last cryptographically verified code checkpoint in WreWorkspaceManager")
            }
            "CAPABILITY_UNAVAILABLE" -> {
                dynamicAlternatives.add("Synthesize capability from basic primitives via WastiSelfEvolutionEngine")
                dynamicAlternatives.add("Discover alternative tool in CapabilityRealityRegistry")
                dynamicAlternatives.add("Offload computation to Swarm / PC node via AutonomousHardwareOffloader")
            }
            "AUTH_REQUIRED" -> {
                dynamicAlternatives.add("Fall back to zero-key SovereignAlternativeRegistry engines")
                dynamicAlternatives.add("Switch to local HuggingFace / Ollama daemon (127.0.0.1:11434)")
            }
            else -> {
                dynamicAlternatives.add("Run isolated diagnostics via WastiPolyglotTerminalEngine")
                dynamicAlternatives.add("Query online knowledge base for verified patch")
            }
        }

        val enrichedPrompt = if (!researchEvidence.isNullOrBlank()) {
            "${baseAnalysis.suggestedSelfCorrectionPrompt}\n\n[RUNTIME WEB RESEARCH INTELLIGENCE]:\n$researchEvidence"
        } else {
            baseAnalysis.suggestedSelfCorrectionPrompt
        }

        baseAnalysis.copy(
            suggestedSelfCorrectionPrompt = enrichedPrompt,
            liveResearchEvidence = researchEvidence,
            alternativeResolutionPaths = dynamicAlternatives
        )
    }

    suspend fun diagnoseStringWithLiveResearch(
        errorMessage: String,
        contextTag: String = "General"
    ): WastiErrorAnalysis = withContext(Dispatchers.IO) {
        diagnoseWithLiveResearch(RuntimeException(errorMessage), contextTag)
    }
}

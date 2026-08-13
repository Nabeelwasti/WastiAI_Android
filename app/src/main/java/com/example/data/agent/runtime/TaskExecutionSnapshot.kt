package com.example.data.agent.runtime

data class TaskExecutionSnapshot(
    val taskId: String,
    val currentPhase: String,
    val currentAction: String,
    val activeProvider: String,
    val activeModel: String?,
    val activeCapability: String,
    val elapsedTimeMs: Long,
    val completedSteps: List<String>,
    val failedSteps: List<String>,
    val retryCount: Int,
    val currentFallback: String?,
    val waitingReason: String?,
    val estimatedRemainingWork: String?,
    val finalOutcome: String?
)

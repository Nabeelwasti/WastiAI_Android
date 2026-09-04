package com.example.data.ops

import com.example.data.ai.AIManager
import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemoryObservabilityStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProviderHealthSummary(
    val providerId: String,
    val name: String,
    val healthStatus: String,
    val averageLatencyMs: Double,
    val successRate: Double,
    val totalRequests: Long
)

data class OperationsDashboardStats(
    val providerSummaries: List<ProviderHealthSummary>,
    val totalTokensConsumed: Long,
    val dailyCostEstimateUsd: Double,
    val memoryStats: MemoryObservabilityStats,
    val activeVoiceProvider: String,
    val activeBackgroundJobsCount: Int = 0,
    val registeredToolsCount: Int = 0,
    val activeWorkflowRulesCount: Int = 0,
    val evaluationQualityScoresCount: Int = 0,
    val uptimeMs: Long = System.currentTimeMillis()
)

object OperationsManager {

    private val _dashboardStatsFlow = MutableStateFlow(getDashboardStatsSnapshot())
    val dashboardStatsFlow: StateFlow<OperationsDashboardStats> = _dashboardStatsFlow.asStateFlow()

    fun getDashboardStatsSnapshot(): OperationsDashboardStats {
        return try {
            val providers = try { AIManager.capabilityRegistry.getAllProviders() } catch (e: Throwable) { emptyList() }
            val healthMonitor = try { AIManager.healthMonitor } catch (e: Throwable) { null }
            val costTracker = try { AIManager.costTracker } catch (e: Throwable) { null }
            val tokenTracker = try { AIManager.tokenUsageTracker } catch (e: Throwable) { null }

            val summaries = providers.map { provider ->
                val health = healthMonitor?.getHealth(provider.id)
                val statusName = health?.status?.name ?: "UNKNOWN"
                val latency = health?.latencyMs?.toDouble() ?: 0.0
                val successPct = health?.successRatePercentage?.toDouble() ?: if (health != null && health.totalRequests > 0) 0.0 else 0.0
                ProviderHealthSummary(
                    providerId = provider.id,
                    name = provider.name,
                    healthStatus = statusName,
                    averageLatencyMs = latency,
                    successRate = successPct,
                    totalRequests = health?.totalRequests ?: 0L
                )
            }

            val usageTotal = try { tokenTracker?.getTotalUsage()?.totalTokens ?: 0L } catch (e: Throwable) { 0L }
            val todayCost = try { costTracker?.getTodayCostUsd() ?: 0.0 } catch (e: Throwable) { 0.0 }
            val memStats = try { MemoryManager.getObservabilityStats() } catch (e: Throwable) { MemoryObservabilityStats() }
            val voiceProvider = try { com.example.data.voice.VoiceManager.activeProviderId.value } catch (e: Throwable) { "LOCAL_OFFLINE" }
            val jobsCount = try { com.example.data.worker.BackgroundTaskManager.jobsStateFlow.value.size } catch (e: Throwable) { 0 }
            val toolsCount = try { com.example.data.tool.ToolRegistry.getAllTools().size } catch (e: Throwable) { 0 }
            val rulesCount = try { com.example.data.workflow.WorkflowEngine.rulesStateFlow.value.size } catch (e: Throwable) { 0 }
            val scoresCount = try { com.example.data.evaluation.AIEvaluationEngine.qualityScoresFlow.value.size } catch (e: Throwable) { 0 }

            OperationsDashboardStats(
                providerSummaries = summaries,
                totalTokensConsumed = usageTotal,
                dailyCostEstimateUsd = todayCost,
                memoryStats = memStats,
                activeVoiceProvider = voiceProvider,
                activeBackgroundJobsCount = jobsCount,
                registeredToolsCount = toolsCount,
                activeWorkflowRulesCount = rulesCount,
                evaluationQualityScoresCount = scoresCount
            )
        } catch (e: Throwable) {
            OperationsDashboardStats(
                providerSummaries = emptyList(),
                totalTokensConsumed = 0L,
                dailyCostEstimateUsd = 0.0,
                memoryStats = MemoryObservabilityStats(),
                activeVoiceProvider = "gemini"
            )
        }
    }

    fun refreshStats() {
        _dashboardStatsFlow.value = getDashboardStatsSnapshot()
    }
}

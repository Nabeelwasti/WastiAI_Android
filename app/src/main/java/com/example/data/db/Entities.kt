package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val updatedTimestamp: Long = System.currentTimeMillis(),
    val activeAgentId: String = "ceo_agent",
    val modelName: String = "gemini-3.5-flash",
    val systemPrompt: String = "You are Wasti OS Executive Brain.",
    val isPinned: Boolean = false
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "user", "assistant", "system", "agent"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val agentId: String = "executive_brain",
    val modelUsed: String = "gemini-3.5-flash",
    val tokensUsed: Int = 0,
    val toolCallsJson: String? = null,
    val thinkingContent: String? = null,
    val attachedMediaUris: String = ""
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val key: String,
    val category: String, // "Preference", "Fact", "Rule", "Goal", "ProjectContext", "Personal"
    val value: String,
    val importanceScore: Float = 0.9f,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceMessageId: String? = null
)

@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val content: String,
    val tagsCsv: String = "",
    val dateAdded: Long = System.currentTimeMillis(),
    val sourceUrl: String? = null
)

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val roleTitle: String,
    val iconName: String,
    val systemInstruction: String,
    val temperature: Float = 0.7f,
    val capabilitiesCsv: String,
    val status: String = "Active", // "Active", "Standby", "Busy"
    val agentType: String = "CEO"
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val status: String = "In Progress", // "In Progress", "Planning", "Completed", "Archived"
    val priority: String = "High", // "High", "Medium", "Low"
    val deadline: String = "2026-12-31",
    val createdDate: Long = System.currentTimeMillis(),
    val tagsCsv: String = "AI,OS"
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val description: String,
    val isCompleted: Boolean = false,
    val priority: String = "Medium",
    val assignedAgentId: String = "ceo_agent",
    val dueDate: String = "2026-08-15"
)

@Entity(tableName = "integrations")
data class IntegrationEntity(
    @PrimaryKey val id: String,
    val serviceName: String,
    val provider: String,
    val isConnected: Boolean = false,
    val authType: String = "OAuth2",
    val lastSyncedTimestamp: Long = System.currentTimeMillis(),
    val statusText: String = "Configured"
)

@Entity(tableName = "system_logs")
data class SystemLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: String = "INFO", // "INFO", "WARN", "ERROR", "AGENT"
    val source: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val details: String? = null
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "vector_embeddings")
data class VectorEmbeddingEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val modelName: String,
    val vectorLength: Int,
    val vectorCsv: String,
    val metadataJson: String = "{}"
)

@Entity(tableName = "knowledge_graph_nodes")
data class KnowledgeGraphNodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val nodeType: String,
    val attributesJson: String = "{}"
)

@Entity(tableName = "knowledge_graph_edges")
data class KnowledgeGraphEdgeEntity(
    @PrimaryKey val id: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val relationType: String,
    val weight: Float = 1.0f
)

@Entity(tableName = "developer_logs")
data class DeveloperLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: String,
    val errorMessage: String,
    val errorType: String = "API_FAILURE",
    val timestamp: Long = System.currentTimeMillis(),
    val details: String? = null
)

@Entity(tableName = "leads")
data class LeadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val link: String,
    val description: String,
    val pubDate: String = "",
    val category: String = "",
    val matchScore: Int = 85,
    val matchedSkillsCsv: String = "",
    val draftedPitch: String = "",
    val status: String = "DISCOVERED",
    val clientEmail: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey val id: String,
    val clientName: String,
    val projectMilestone: String,
    val amountUsd: Double,
    val currency: String = "USD",
    val status: String = "DRAFT",
    val issueDate: String = "",
    val dueDate: String = "",
    val clientFeedback: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "prospects",
    indices = [Index(value = ["status"])]
)
data class ProspectEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val clientName: String = "",
    val companyName: String = "",
    val country: String = "",
    val region: String = "",
    val email: String = "",
    val phone: String = "",
    val whatsappNumber: String = "",
    val websiteUrl: String = "",
    val paymentInfo: String = "",
    val leadSource: String = "Google X-Ray", // "Google X-Ray", "Upwork RSS", "Web Scraper"
    val opportunityNature: String = "Video Editing", // "Video Editing", "Graphic Design", "AI Automation"
    val status: String = "NEW", // "NEW", "CONTACTED", "PITCHED", "REPLIED", "CLOSED"
    val aiDraftedMessage: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val title: String = "",
    val link: String = "",
    val description: String = "",
    val pubDate: String = "",
    val category: String = "",
    val matchScore: Int = 85,
    val matchedSkillsCsv: String = "",
    val draftedPitch: String = "",
    val clientEmail: String = ""
)

@Entity(
    tableName = "terminal_sessions",
    indices = [Index(value = ["sessionId"]), Index(value = ["timestamp"])]
)
data class TerminalSessionEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String = "default",
    val command: String,
    val output: String = "",
    val stderr: String = "",
    val workingDirectory: String = "home/wasti",
    val status: String = "SUCCESS", // "SUCCESS", "FAILED", "RUNNING", "SYSTEM"
    val exitCode: Int = 0,
    val durationMs: Long = 0L,
    val verified: Boolean = false,
    val verificationEvidence: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "proactive_tasks",
    indices = [
        Index(value = ["idempotencyKey"]),
        Index(value = ["state"]),
        Index(value = ["scheduledAt"])
    ]
)
data class ProactiveTaskEntity(
    @PrimaryKey val taskId: String = java.util.UUID.randomUUID().toString(),
    val correlationId: String = "corr_${java.util.UUID.randomUUID()}",
    val title: String,
    val prompt: String,
    val origin: String = "BACKGROUND_WORKER",
    val priority: String = "MEDIUM",
    val state: String = "SCHEDULED",
    val triggerType: String = "ONE_TIME_DELAYED",
    val createdAt: Long = System.currentTimeMillis(),
    val scheduledAt: Long = System.currentTimeMillis(),
    val intervalMs: Long = 0L,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val nextRetryAt: Long = 0L,
    val requiredCapabilitiesCsv: String = "",
    val preferredNode: String? = null,
    val selectedNode: String? = null,
    val leaseOwnerNode: String? = null,
    val leaseExpiresAt: Long = 0L,
    val idempotencyKey: String? = null,
    val verificationEvidence: String? = null,
    val lastError: String? = null,
    val isIdempotent: Boolean = true,
    val executionMode: String = "AUTONOMOUS",
    val completedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)




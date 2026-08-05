package com.example.data.memory.model

enum class SearchType {
    HYBRID,
    VECTOR,
    KEYWORD_TEXT,
    GRAPH_RELATIONAL
}

data class EmbeddingVector(
    val providerId: String,
    val modelName: String,
    val vectorLength: Int,
    val values: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingVector
        if (providerId != other.providerId) return false
        if (modelName != other.modelName) return false
        if (vectorLength != other.vectorLength) return false
        if (!values.contentEquals(other.values)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + modelName.hashCode()
        result = 31 * result + vectorLength
        result = 31 * result + values.contentHashCode()
        return result
    }
}

data class MemoryItem(
    val id: String,
    val key: String,
    val category: String,
    val value: String,
    val importanceScore: Float = 0.9f,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceMessageId: String? = null,
    val embedding: EmbeddingVector? = null,
    val isArchived: Boolean = false,
    val accessCount: Int = 0,
    val lastAccessedTimestamp: Long = System.currentTimeMillis()
)

data class MemorySearchQuery(
    val queryText: String,
    val category: String? = null,
    val topK: Int = 10,
    val minImportance: Float = 0.5f,
    val searchType: SearchType = SearchType.HYBRID
)

data class MemorySearchResult(
    val memory: MemoryItem,
    val relevanceScore: Float,
    val vectorSimilarity: Float = 0.0f,
    val textMatchScore: Float = 0.0f,
    val matchType: SearchType
)

enum class NodeType {
    USER,
    PROJECT,
    TASK,
    PERSON,
    COMPANY,
    DOCUMENT,
    CONVERSATION,
    GOAL
}

enum class RelationType {
    CREATED,
    BELONGS_TO,
    REFERENCES,
    DEPENDS_ON,
    ASSIGNED_TO,
    ASSOCIATED_WITH
}

data class GraphNode(
    val id: String,
    val label: String,
    val type: NodeType,
    val attributesJson: String = "{}"
)

data class GraphEdge(
    val id: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val relation: RelationType,
    val weight: Float = 1.0f
)

data class KnowledgeGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>
)

data class MemoryRetentionPolicy(
    val autoArchivalDays: Int = 30,
    val minImportanceToRetain: Float = 0.4f,
    val maxActiveMemoriesCount: Int = 1000,
    val autoSummarizeThresholdMessages: Int = 20,
    val deduplicationThresholdSimilarity: Float = 0.92f
)

data class MemoryObservabilityStats(
    val totalActiveMemories: Int = 0,
    val totalArchivedMemories: Int = 0,
    val totalVectorsIndexed: Int = 0,
    val totalGraphNodes: Int = 0,
    val totalGraphEdges: Int = 0,
    val averageVectorLength: Int = 1536,
    val storageUsageBytes: Long = 0L,
    val lastCleanupTimestamp: Long = System.currentTimeMillis()
)

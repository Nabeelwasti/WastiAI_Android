package com.example.data.memory.policy

import com.example.data.memory.model.MemoryItem
import com.example.data.memory.model.MemoryRetentionPolicy
import java.util.concurrent.TimeUnit

class MemoryPolicyEngine(
    private var policy: MemoryRetentionPolicy = MemoryRetentionPolicy()
) {
    fun updatePolicy(newPolicy: MemoryRetentionPolicy) {
        policy = newPolicy
    }

    fun getPolicy(): MemoryRetentionPolicy = policy

    fun shouldArchive(memory: MemoryItem): Boolean {
        if (memory.isArchived) return false
        val now = System.currentTimeMillis()
        val ageDays = TimeUnit.MILLISECONDS.toDays(now - memory.lastAccessedTimestamp)
        
        return (ageDays > policy.autoArchivalDays && memory.importanceScore < policy.minImportanceToRetain)
    }

    fun isDuplicate(existingValue: String, newValue: String): Boolean {
        if (existingValue.equals(newValue, ignoreCase = true)) return true
        val words1 = existingValue.lowercase().split(" ").toSet()
        val words2 = newValue.lowercase().split(" ").toSet()
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        val jaccard = if (union > 0) intersection.toFloat() / union.toFloat() else 0.0f
        return jaccard >= policy.deduplicationThresholdSimilarity
    }

    fun calculateUpdatedImportance(
        currentScore: Float,
        accessCount: Int,
        isExplicitlyMarked: Boolean
    ): Float {
        if (isExplicitlyMarked) return 1.0f
        val boost = accessCount * 0.02f
        return (currentScore + boost).coerceIn(0.1f, 1.0f)
    }
}

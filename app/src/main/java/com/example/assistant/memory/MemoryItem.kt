package com.example.assistant.memory

import com.example.data.memory.model.MemoryItem as ActiveMemoryItem
import com.example.data.memory.model.MemoryProvenanceCategory
import com.example.data.memory.model.MemoryTier

/**
 * Compatibility and transfer data model for the assistant layer memory representations.
 * Provides seamless bidirectional conversion to and from the active Room/MemoryManager model.
 */
data class MemoryItem(
    val id: Long = 0L,
    val content: String = "",
    val type: String = "general",
    val timestamp: Long = System.currentTimeMillis(),
    val tier: MemoryTier = MemoryTier.USER_MEMORY,
    val provenanceCategory: MemoryProvenanceCategory = MemoryProvenanceCategory.IMPORTED
) {
    /**
     * Converts this assistant MemoryItem into the active enterprise MemoryItem.
     */
    fun toActiveMemoryItem(): ActiveMemoryItem {
        val stringId = if (id != 0L) "mem_compat_$id" else "mem_compat_${System.currentTimeMillis()}"
        return ActiveMemoryItem(
            id = stringId,
            key = type.ifBlank { "Assistant Memory" },
            category = type.ifBlank { "General" },
            value = content,
            importanceScore = 0.85f,
            timestamp = timestamp,
            tier = tier,
            provenanceCategory = provenanceCategory
        )
    }

    companion object {
        /**
         * Maps an active enterprise MemoryItem into this assistant MemoryItem representation.
         */
        fun fromActiveMemoryItem(item: ActiveMemoryItem): MemoryItem {
            val parsedId = item.id.removePrefix("mem_compat_").toLongOrNull() ?: item.timestamp
            return MemoryItem(
                id = parsedId,
                content = item.value,
                type = item.category,
                timestamp = item.timestamp,
                tier = item.tier,
                provenanceCategory = item.provenanceCategory
            )
        }
    }
}

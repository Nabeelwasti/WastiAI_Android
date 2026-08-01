package com.example.assistant.memory

/**
 * Minimal MemoryItem data class used by the migrator and other components.
 */
data class MemoryItem(
    val id: Long = 0L,
    val content: String = "",
)

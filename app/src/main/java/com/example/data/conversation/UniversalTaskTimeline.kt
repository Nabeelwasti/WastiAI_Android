package com.example.data.conversation

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 19: Universal Task Timeline Engine.
 * 
 * Exposes a single, canonical, immutable chronological execution timeline for every
 * Wasti task across all rooms (Chat, Terminal, Floating Bubble, Web, Desktop, Background, Notification).
 */

enum class TaskTimelinePhase {
    RECEIVED,
    UNDERSTOOD,
    PLANNED,
    CAPABILITY_CHECKED,
    AUTHORIZED,
    EXECUTING,
    OBSERVING,
    VERIFYING,
    COMPLETED,
    FAILED,
    DIAGNOSED,
    CORRECTED,
    RETESTED,
    VERIFIED_POST_CORRECTION,
    CANCELLED,
    EMERGENCY_STOPPED
}

data class TaskTimelineEntry(
    val entryId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val phase: TaskTimelinePhase,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
    val durationSinceStartMs: Long = 0L
)

data class TaskTimelineRecord(
    val taskId: String,
    val originRoom: String,
    val prompt: String,
    val startedAt: Long = System.currentTimeMillis(),
    var currentPhase: TaskTimelinePhase = TaskTimelinePhase.RECEIVED,
    val entries: MutableList<TaskTimelineEntry> = mutableListOf(),
    var completedAt: Long? = null,
    var isSuccessful: Boolean = false,
    var finalEvidence: String? = null
)

class UniversalTaskTimeline private constructor() {
    private val TAG = "TaskTimeline"
    private val records = ConcurrentHashMap<String, TaskTimelineRecord>()

    private val _timelineEvents = MutableSharedFlow<TaskTimelineEntry>(replay = 50, extraBufferCapacity = 100)
    val timelineEvents: SharedFlow<TaskTimelineEntry> = _timelineEvents.asSharedFlow()

    private val _activeTasksCount = MutableStateFlow(0)
    val activeTasksCount: StateFlow<Int> = _activeTasksCount.asStateFlow()

    companion object {
        @Volatile
        private var instance: UniversalTaskTimeline? = null

        fun getInstance(): UniversalTaskTimeline {
            return instance ?: synchronized(this) {
                instance ?: UniversalTaskTimeline().also { instance = it }
            }
        }
    }

    @Synchronized
    fun startTask(taskId: String, prompt: String, originRoom: String = "CHAT"): TaskTimelineRecord {
        val record = TaskTimelineRecord(
            taskId = taskId,
            originRoom = originRoom,
            prompt = prompt,
            startedAt = System.currentTimeMillis()
        )
        records[taskId] = record
        appendPhase(taskId, TaskTimelinePhase.RECEIVED, "Task received from room '$originRoom'")
        updateActiveTasksCount()
        return record
    }

    @Synchronized
    fun appendPhase(
        taskId: String,
        phase: TaskTimelinePhase,
        description: String,
        metadata: Map<String, String> = emptyMap()
    ): TaskTimelineEntry? {
        val record = records[taskId] ?: return null
        val now = System.currentTimeMillis()
        val duration = now - record.startedAt

        val entry = TaskTimelineEntry(
            taskId = taskId,
            phase = phase,
            description = description,
            timestamp = now,
            metadata = metadata,
            durationSinceStartMs = duration
        )

        record.entries.add(entry)
        record.currentPhase = phase

        if (phase == TaskTimelinePhase.COMPLETED || phase == TaskTimelinePhase.VERIFIED_POST_CORRECTION) {
            record.completedAt = now
            record.isSuccessful = true
        } else if (phase == TaskTimelinePhase.FAILED || phase == TaskTimelinePhase.CANCELLED || phase == TaskTimelinePhase.EMERGENCY_STOPPED) {
            record.completedAt = now
            record.isSuccessful = false
        }

        _timelineEvents.tryEmit(entry)
        updateActiveTasksCount()
        Log.d(TAG, "Task [$taskId] phase advanced to $phase: $description")
        return entry
    }

    fun getRecord(taskId: String): TaskTimelineRecord? = records[taskId]

    fun getAllRecords(): List<TaskTimelineRecord> = records.values.toList()

    fun getRecentEntries(limit: Int = 50): List<TaskTimelineEntry> {
        return records.values.flatMap { it.entries }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    private fun updateActiveTasksCount() {
        _activeTasksCount.value = records.values.count { it.completedAt == null }
    }
}

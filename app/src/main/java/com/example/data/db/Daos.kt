package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedTimestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesListForConversation(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("UPDATE messages SET content = :newContent WHERE id = :id")
    suspend fun updateMessageContent(id: String, newContent: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND timestamp > :timestamp")
    suspend fun deleteMessagesAfterTimestamp(conversationId: String, timestamp: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY importanceScore DESC, timestamp DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC")
    fun getMemoriesByCategory(category: String): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge ORDER BY dateAdded DESC")
    fun getAllKnowledge(): Flow<List<KnowledgeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledge(knowledge: KnowledgeEntity)

    @Query("DELETE FROM knowledge WHERE id = :id")
    suspend fun deleteKnowledgeById(id: String)
}

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY name ASC")
    fun getAllAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getAgentById(id: String): AgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentEntity)

    @Query("UPDATE agents SET status = :status WHERE id = :id")
    suspend fun updateAgentStatus(id: String, status: String)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdDate DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY isCompleted ASC, priority DESC")
    fun getTasksForProject(projectId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, priority DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Query("UPDATE tasks SET isCompleted = :isCompleted WHERE id = :id")
    suspend fun updateTaskStatus(id: String, isCompleted: Boolean)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)
}

@Dao
interface IntegrationDao {
    @Query("SELECT * FROM integrations ORDER BY serviceName ASC")
    fun getAllIntegrations(): Flow<List<IntegrationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIntegration(integration: IntegrationEntity)
}

@Dao
interface SystemLogDao {
    @Query("SELECT * FROM system_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): Flow<List<SystemLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SystemLogEntity)

    @Query("DELETE FROM system_logs")
    suspend fun clearAllLogs()
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun getAllSettings(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getSettingValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: SettingEntity)
}

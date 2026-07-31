package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        KnowledgeEntity::class,
        AgentEntity::class,
        ProjectEntity::class,
        TaskEntity::class,
        IntegrationEntity::class,
        SystemLogEntity::class,
        SettingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class WastiDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun agentDao(): AgentDao
    abstract fun projectDao(): ProjectDao
    abstract fun taskDao(): TaskDao
    abstract fun integrationDao(): IntegrationDao
    abstract fun systemLogDao(): SystemLogDao
    abstract fun settingDao(): SettingDao

    companion object {
        @Volatile
        private var INSTANCE: WastiDatabase? = null

        fun getDatabase(context: Context): WastiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WastiDatabase::class.java,
                    "wasti_os_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

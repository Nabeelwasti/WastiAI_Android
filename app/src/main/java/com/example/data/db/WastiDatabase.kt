package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vector_embeddings` (
                `id` TEXT NOT NULL,
                `providerId` TEXT NOT NULL,
                `modelName` TEXT NOT NULL,
                `vectorLength` INTEGER NOT NULL,
                `vectorCsv` TEXT NOT NULL,
                `metadataJson` TEXT NOT NULL DEFAULT '{}',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_graph_nodes` (
                `id` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `nodeType` TEXT NOT NULL,
                `attributesJson` TEXT NOT NULL DEFAULT '{}',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `knowledge_graph_edges` (
                `id` TEXT NOT NULL,
                `sourceNodeId` TEXT NOT NULL,
                `targetNodeId` TEXT NOT NULL,
                `relationType` TEXT NOT NULL,
                `weight` REAL NOT NULL DEFAULT 1.0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

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
        SettingEntity::class,
        VectorEmbeddingEntity::class,
        KnowledgeGraphNodeEntity::class,
        KnowledgeGraphEdgeEntity::class
    ],
    version = 2,
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
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}


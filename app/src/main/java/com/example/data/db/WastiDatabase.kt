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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `developer_logs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `providerId` TEXT NOT NULL,
                `errorMessage` TEXT NOT NULL,
                `errorType` TEXT NOT NULL DEFAULT 'API_FAILURE',
                `timestamp` INTEGER NOT NULL,
                `details` TEXT
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `leads` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `link` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `pubDate` TEXT NOT NULL DEFAULT '',
                `category` TEXT NOT NULL DEFAULT '',
                `matchScore` INTEGER NOT NULL DEFAULT 85,
                `matchedSkillsCsv` TEXT NOT NULL DEFAULT '',
                `draftedPitch` TEXT NOT NULL DEFAULT '',
                `status` TEXT NOT NULL DEFAULT 'DISCOVERED',
                `clientEmail` TEXT NOT NULL DEFAULT '',
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `invoices` (
                `id` TEXT NOT NULL,
                `clientName` TEXT NOT NULL,
                `projectMilestone` TEXT NOT NULL,
                `amountUsd` REAL NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'DRAFT',
                `issueDate` TEXT NOT NULL DEFAULT '',
                `dueDate` TEXT NOT NULL DEFAULT '',
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `invoices` ADD COLUMN `clientFeedback` TEXT DEFAULT NULL")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `invoices` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'USD'")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `prospects` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `link` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `pubDate` TEXT NOT NULL DEFAULT '',
                `category` TEXT NOT NULL DEFAULT '',
                `matchScore` INTEGER NOT NULL DEFAULT 85,
                `matchedSkillsCsv` TEXT NOT NULL DEFAULT '',
                `draftedPitch` TEXT NOT NULL DEFAULT '',
                `status` TEXT NOT NULL DEFAULT 'Contacted',
                `clientEmail` TEXT NOT NULL DEFAULT '',
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_prospects_status` ON `prospects` (`status`)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `clientName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `companyName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `country` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `region` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `email` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `phone` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `whatsappNumber` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `websiteUrl` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `paymentInfo` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `leadSource` TEXT NOT NULL DEFAULT 'Google X-Ray'")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `opportunityNature` TEXT NOT NULL DEFAULT 'Video Editing'")
        db.execSQL("ALTER TABLE `prospects` ADD COLUMN `aiDraftedMessage` TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachedMediaUris` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `media_vault` (
                `id` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL DEFAULT '',
                `uri` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL DEFAULT 'image/jpeg',
                `timestamp` INTEGER NOT NULL,
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
        KnowledgeGraphEdgeEntity::class,
        DeveloperLogEntity::class,
        LeadEntity::class,
        InvoiceEntity::class,
        ProspectEntity::class,
        MediaVaultEntity::class
    ],
    version = 10,
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
    abstract fun developerLogDao(): DeveloperLogDao
    abstract fun leadDao(): LeadDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun prospectDao(): ProspectDao
    abstract fun mediaVaultDao(): MediaVaultDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration(true)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}


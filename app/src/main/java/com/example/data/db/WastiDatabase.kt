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

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `terminal_sessions` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `command` TEXT NOT NULL,
                `output` TEXT NOT NULL DEFAULT '',
                `stderr` TEXT NOT NULL DEFAULT '',
                `workingDirectory` TEXT NOT NULL DEFAULT 'home/wasti',
                `status` TEXT NOT NULL DEFAULT 'SUCCESS',
                `exitCode` INTEGER NOT NULL DEFAULT 0,
                `durationMs` INTEGER NOT NULL DEFAULT 0,
                `verified` INTEGER NOT NULL DEFAULT 0,
                `verificationEvidence` TEXT DEFAULT NULL,
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_terminal_sessions_sessionId` ON `terminal_sessions` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_terminal_sessions_timestamp` ON `terminal_sessions` (`timestamp`)")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `proactive_tasks` (
                `taskId` TEXT NOT NULL,
                `correlationId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `prompt` TEXT NOT NULL,
                `origin` TEXT NOT NULL DEFAULT 'BACKGROUND_WORKER',
                `priority` TEXT NOT NULL DEFAULT 'MEDIUM',
                `state` TEXT NOT NULL DEFAULT 'SCHEDULED',
                `triggerType` TEXT NOT NULL DEFAULT 'ONE_TIME_DELAYED',
                `createdAt` INTEGER NOT NULL,
                `scheduledAt` INTEGER NOT NULL,
                `intervalMs` INTEGER NOT NULL DEFAULT 0,
                `retryCount` INTEGER NOT NULL DEFAULT 0,
                `maxRetries` INTEGER NOT NULL DEFAULT 3,
                `nextRetryAt` INTEGER NOT NULL DEFAULT 0,
                `requiredCapabilitiesCsv` TEXT NOT NULL DEFAULT '',
                `preferredNode` TEXT,
                `selectedNode` TEXT,
                `leaseOwnerNode` TEXT,
                `leaseExpiresAt` INTEGER NOT NULL DEFAULT 0,
                `idempotencyKey` TEXT,
                `verificationEvidence` TEXT,
                `lastError` TEXT,
                `isIdempotent` INTEGER NOT NULL DEFAULT 1,
                `executionMode` TEXT NOT NULL DEFAULT 'AUTONOMOUS',
                `completedAt` INTEGER,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`taskId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_tasks_idempotencyKey` ON `proactive_tasks` (`idempotencyKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_tasks_state` ON `proactive_tasks` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_tasks_scheduledAt` ON `proactive_tasks` (`scheduledAt`)")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `node_metadata` (
                `nodeId` TEXT NOT NULL,
                `nodeName` TEXT NOT NULL,
                `platform` TEXT NOT NULL,
                `trustState` TEXT NOT NULL,
                `isLocal` INTEGER NOT NULL,
                `networkAddress` TEXT,
                `protocolVersion` INTEGER NOT NULL DEFAULT 2,
                `capabilityFingerprint` TEXT NOT NULL DEFAULT '',
                `capabilitiesCsv` TEXT NOT NULL DEFAULT '',
                `dataLocality` TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                `lastSyncTimestamp` INTEGER NOT NULL DEFAULT 0,
                `lastPingTimestamp` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`nodeId`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learned_skills` (
                `skillId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `originatingTaskId` TEXT NOT NULL,
                `executionGraphJson` TEXT NOT NULL,
                `requiredCapabilitiesJson` TEXT NOT NULL,
                `requiredPermissionsJson` TEXT NOT NULL,
                `inputParametersJson` TEXT NOT NULL,
                `expectedOutputsJson` TEXT NOT NULL,
                `verificationCriteriaJson` TEXT NOT NULL,
                `actualEvidenceSummary` TEXT,
                `successCount` INTEGER NOT NULL DEFAULT 1,
                `failureCount` INTEGER NOT NULL DEFAULT 0,
                `recoveryCount` INTEGER NOT NULL DEFAULT 0,
                `regressionScore` REAL NOT NULL DEFAULT 1.0,
                `promotionTier` TEXT NOT NULL DEFAULT 'SANDBOX_EXPERIMENTAL',
                `operationalStatus` TEXT NOT NULL DEFAULT 'ACTIVE',
                `version` TEXT NOT NULL DEFAULT '1.0.0',
                `createdAt` INTEGER NOT NULL,
                `lastVerifiedAt` INTEGER NOT NULL,
                PRIMARY KEY(`skillId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reusable_workflows` (
                `workflowId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `triggerPattern` TEXT NOT NULL,
                `stepsJson` TEXT NOT NULL,
                `parameterSchemaJson` TEXT NOT NULL,
                `requiredPermissionsJson` TEXT NOT NULL,
                `isAutoExecutable` INTEGER NOT NULL DEFAULT 0,
                `usageCount` INTEGER NOT NULL DEFAULT 0,
                `successRate` REAL NOT NULL DEFAULT 1.0,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`workflowId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `execution_audits` (
                `auditId` TEXT NOT NULL,
                `taskId` TEXT NOT NULL,
                `userGoal` TEXT NOT NULL,
                `capabilityId` TEXT NOT NULL,
                `actionName` TEXT NOT NULL,
                `executionDestination` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `verificationStatus` TEXT NOT NULL,
                `verificationEvidence` TEXT,
                `error` TEXT,
                `executionDurationMs` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`auditId`)
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
        MediaVaultEntity::class,
        TerminalSessionEntity::class,
        ProactiveTaskEntity::class,
        NodeMetadataEntity::class,
        LearnedSkillEntity::class,
        ReusableWorkflowEntity::class,
        ExecutionAuditEntity::class
    ],
    version = 14,
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
    abstract fun terminalSessionDao(): TerminalSessionDao
    abstract fun proactiveTaskDao(): ProactiveTaskDao
    abstract fun nodeMetadataDao(): NodeMetadataDao
    abstract fun learnedSkillDao(): LearnedSkillDao
    abstract fun reusableWorkflowDao(): ReusableWorkflowDao
    abstract fun executionAuditDao(): ExecutionAuditDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .fallbackToDestructiveMigration(true)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}



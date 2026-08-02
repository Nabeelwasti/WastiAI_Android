package com.example.assistant.memory

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryMigrator {
    /**
     * Placeholder migrator that does nothing in CI builds. Real migration logic should
     * inspect existing storage and migrate into Room/MemoryDatabase as needed.
     */
    suspend fun migrateIfNeeded(context: Context, db: MemoryDatabase) {
        withContext(Dispatchers.IO) {
            // no-op for CI
        }
    }
}

package com.example.assistant.memory

import android.content.Context

/**
 * Minimal in-memory placeholder for Room Database used in CI to satisfy compilation.
 * The real implementation should use Room with DAOs and proper migrations.
 */
class MemoryDatabase private constructor(val context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        fun getInstance(ctx: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryDatabase(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Placeholder methods for compile-time compatibility
}

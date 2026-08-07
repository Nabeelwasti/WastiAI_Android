package com.example.data.log

import android.content.Context
import android.util.Log
import com.example.data.db.DeveloperLogEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeveloperLogger {
    @Volatile
    private var contextRef: Context? = null

    fun initialize(context: Context) {
        contextRef = context.applicationContext
    }

    suspend fun logError(
        providerId: String,
        errorMessage: String,
        errorType: String = "API_FAILURE",
        details: String? = null
    ) {
        withContext(Dispatchers.IO) {
            Log.e("DeveloperLogger", "[$providerId] $errorType: $errorMessage")
            val ctx = contextRef ?: return@withContext
            try {
                val db = WastiDatabase.getDatabase(ctx)
                db.developerLogDao().insertLog(
                    DeveloperLogEntity(
                        providerId = providerId,
                        errorMessage = errorMessage,
                        errorType = errorType,
                        details = details ?: errorMessage
                    )
                )
            } catch (e: Throwable) {
                Log.e("DeveloperLogger", "Failed to insert developer log into Room", e)
            }
        }
    }
}

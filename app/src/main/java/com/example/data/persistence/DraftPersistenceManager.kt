package com.example.data.persistence

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object DraftPersistenceManager {

    private const val TAG = "DraftPersistenceManager"
    private const val PREFS_FILE = "wasti_chat_drafts_secure"
    private const val KEY_PROMPT_DRAFT = "active_prompt_bar_draft"
    private const val KEY_TIMESTAMP = "active_prompt_draft_timestamp"

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Falling back to standard SharedPreferences for draft storage", e)
            context.getSharedPreferences("wasti_chat_drafts_fallback", Context.MODE_PRIVATE)
        }
    }

    /**
     * Real-time auto-save for unsubmitted prompt input.
     */
    fun saveDraftPrompt(context: Context, text: String) {
        try {
            val prefs = getSecurePrefs(context)
            prefs.edit()
                .putString(KEY_PROMPT_DRAFT, text)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error auto-saving draft prompt", e)
        }
    }

    /**
     * Restore unsent text when reopening ChatWorkspaceScreen or recovering from restart/crash.
     */
    fun getDraftPrompt(context: Context): String {
        return try {
            val prefs = getSecurePrefs(context)
            prefs.getString(KEY_PROMPT_DRAFT, "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error loading draft prompt", e)
            ""
        }
    }

    /**
     * Clear draft once prompt is sent or cleared explicitly.
     */
    fun clearDraftPrompt(context: Context) {
        try {
            val prefs = getSecurePrefs(context)
            prefs.edit()
                .remove(KEY_PROMPT_DRAFT)
                .remove(KEY_TIMESTAMP)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing draft prompt", e)
        }
    }
}

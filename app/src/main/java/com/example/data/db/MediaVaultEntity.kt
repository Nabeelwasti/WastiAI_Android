package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_vault")
data class MediaVaultEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val conversationId: String,
    val messageId: String = "",
    val uri: String,
    val mimeType: String = "image/jpeg",
    val timestamp: Long = System.currentTimeMillis()
)

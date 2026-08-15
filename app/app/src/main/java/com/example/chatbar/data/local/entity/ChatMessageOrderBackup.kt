package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageOrderBackupEntry(
    val messageId: String,
    val orderKey: Long,
    val updatedAt: Long
)

@Serializable
data class ChatMessageOrderBackup(
    val sessionId: String,
    val createdAt: Long,
    val entries: List<ChatMessageOrderBackupEntry>
)

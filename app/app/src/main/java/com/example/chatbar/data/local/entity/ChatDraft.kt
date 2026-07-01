package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class ChatDraft(
    val sessionId: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
)

package com.example.chatbar.domain.deletion

import com.example.chatbar.data.local.entity.MessageRole
import kotlinx.serialization.Serializable

/** Local recovery state. This entity is deliberately excluded from portable backups. */
@Serializable
internal data class ReplyRemoval(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val versionId: String?,
    val role: MessageRole,
    val sourceTurnId: String?,
    val sourceTurnOrder: Long?,
    val timelineTurn: Long?,
    val images: List<String>,
    val audioPaths: List<String>,
    val invalidateAutomaticMemory: Boolean = versionId != null
)

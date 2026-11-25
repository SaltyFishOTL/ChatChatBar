package com.example.chatbar.domain.deletion

import com.example.chatbar.data.local.entity.ChatMessage
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
    val invalidateAutomaticMemory: Boolean = versionId != null,
    val legacyVoiceAnchorWitnessId: String? = null,
    val legacyVoiceContentHashes: Map<String, String> = emptyMap(),
    val retainsReply: Boolean = false,
    val previousContentProof: String? = null,
    val expectedMessageProof: String? = null,
    val replacement: ChatMessage? = null,
    val onlyIfUnvoiced: Boolean = false,
    val timelineSettled: Boolean = false,
    val voiceRecordsSettled: Boolean = false
) {
    companion object {
        const val STORE = "pending_reply_removals"
    }
}

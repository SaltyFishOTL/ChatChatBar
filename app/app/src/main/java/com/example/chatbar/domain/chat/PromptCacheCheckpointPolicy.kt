package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.PromptCacheCheckpoint
import java.security.MessageDigest

private const val CHECKPOINT_ADVANCE_MESSAGE_COUNT = 6

data class PromptCacheCheckpointPlan(
    val checkpoint: PromptCacheCheckpoint?,
    val hotMessages: List<ChatMessage>,
    val checkpointChanged: Boolean
)

/**
 * 将长期记忆固定为短期内不变的前缀，把 checkpoint 之后的原始消息保留在热区。
 * 绝不因 checkpoint 丢弃消息；长期记忆尚未追上时，热区会暂时扩大。
 */
object PromptCacheCheckpointPolicy {
    fun plan(
        allMessages: List<ChatMessage>,
        current: PromptCacheCheckpoint?,
        stablePromptFingerprint: String,
        longTermMemoryEnabled: Boolean,
        currentMemory: String,
        memoryUpdatedThroughMessageId: String?
    ): PromptCacheCheckpointPlan {
        if (!longTermMemoryEnabled || currentMemory.isBlank() || memoryUpdatedThroughMessageId.isNullOrBlank()) {
            return PromptCacheCheckpointPlan(
                checkpoint = null,
                hotMessages = allMessages,
                checkpointChanged = current != null
            )
        }

        val latestCoveredIndex = allMessages.indexOfFirst { it.id == memoryUpdatedThroughMessageId }
        if (latestCoveredIndex < 0) {
            return PromptCacheCheckpointPlan(
                checkpoint = null,
                hotMessages = allMessages,
                checkpointChanged = current != null
            )
        }

        val currentCoveredIndex = current?.coveredThroughMessageId
            ?.let { id -> allMessages.indexOfFirst { it.id == id } }
            ?: -1
        val currentIsUsable = current != null &&
            current.stablePromptFingerprint == stablePromptFingerprint &&
            current.memorySnapshot.isNotBlank() &&
            currentCoveredIndex >= 0

        val shouldAdvance = !currentIsUsable ||
            latestCoveredIndex - currentCoveredIndex >= CHECKPOINT_ADVANCE_MESSAGE_COUNT
        val checkpoint = if (shouldAdvance) {
            PromptCacheCheckpoint(
                stablePromptFingerprint = stablePromptFingerprint,
                memorySnapshot = currentMemory.trim(),
                coveredThroughMessageId = memoryUpdatedThroughMessageId,
                createdAt = System.currentTimeMillis()
            )
        } else {
            current
        }
        val coveredIndex = if (shouldAdvance) latestCoveredIndex else currentCoveredIndex
        return PromptCacheCheckpointPlan(
            checkpoint = checkpoint,
            hotMessages = allMessages.drop(coveredIndex + 1),
            checkpointChanged = shouldAdvance
        )
    }
}

object PromptCacheKeyFactory {
    fun fingerprint(value: String): String = sha256(value)

    fun cacheKey(stableSystemPrompt: String, checkpoint: PromptCacheCheckpoint?): String {
        val prefix = buildString {
            append(stableSystemPrompt)
            append('\n')
            append(checkpoint?.memorySnapshot.orEmpty())
        }
        return "chatbar-${sha256(prefix).take(48)}"
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

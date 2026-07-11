package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class MomentPost(
    val id: String,
    val characterCardId: String,
    val sessionId: String,
    val senderCharacterId: String? = null,
    val senderName: String,
    val senderAvatar: String? = null,
    val text: String,
    val imagePath: String? = null,
    val imagePrompt: String = "",
    val imageBrief: String = "",
    val isPrivate: Boolean = false,
    val baseLikeCount: Int = 0,
    val userLiked: Boolean = false,
    val isPlaceholder: Boolean = false,
    val failureReason: String? = null,
    val generationCheckpoint: String = "",
    val generationReason: String = "",
    val scheduledAt: Long,
    val generatedAt: Long,
    val createdAt: Long = generatedAt,
    val updatedAt: Long = generatedAt
) {
    val displayLikeCount: Int
        get() = (if (isPrivate) 0 else baseLikeCount) + if (userLiked) 1 else 0

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            characterCardId: String,
            sessionId: String,
            senderCharacterId: String?,
            senderName: String,
            senderAvatar: String?,
            text: String,
            imagePath: String?,
            imagePrompt: String,
            imageBrief: String,
            isPrivate: Boolean,
            baseLikeCount: Int,
            generationReason: String,
            scheduledAt: Long,
            generatedAt: Long = System.currentTimeMillis()
        ): MomentPost = MomentPost(
            id = Uuid.random().toString(),
            characterCardId = characterCardId,
            sessionId = sessionId,
            senderCharacterId = senderCharacterId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            text = text,
            imagePath = imagePath,
            imagePrompt = imagePrompt,
            imageBrief = imageBrief,
            isPrivate = isPrivate,
            baseLikeCount = if (isPrivate) 0 else baseLikeCount.coerceAtLeast(0),
            generationReason = generationReason,
            scheduledAt = scheduledAt,
            generatedAt = generatedAt
        )

        @OptIn(ExperimentalUuidApi::class)
        fun createPlaceholder(
            characterCardId: String,
            sessionId: String,
            senderCharacterId: String?,
            senderName: String,
            senderAvatar: String?,
            failureReason: String,
            generationCheckpoint: String = "",
            scheduledAt: Long,
            generatedAt: Long = System.currentTimeMillis()
        ): MomentPost = MomentPost(
            id = Uuid.random().toString(),
            characterCardId = characterCardId,
            sessionId = sessionId,
            senderCharacterId = senderCharacterId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            text = "",
            imagePath = null,
            imagePrompt = "",
            imageBrief = "",
            isPrivate = false,
            baseLikeCount = 0,
            isPlaceholder = true,
            failureReason = failureReason,
            generationCheckpoint = generationCheckpoint,
            generationReason = failureReason,
            scheduledAt = scheduledAt,
            generatedAt = generatedAt
        )
    }
}

@Serializable
data class MomentTask(
    val id: String,
    val characterCardId: String,
    val sessionId: String,
    val scheduledAt: Long,
    val status: MomentTaskStatus = MomentTaskStatus.PENDING,
    val postId: String? = null,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            characterCardId: String,
            sessionId: String,
            scheduledAt: Long,
            now: Long = System.currentTimeMillis()
        ): MomentTask = MomentTask(
            id = Uuid.random().toString(),
            characterCardId = characterCardId,
            sessionId = sessionId,
            scheduledAt = scheduledAt,
            createdAt = now,
            updatedAt = now
        )
    }
}

@Serializable
enum class MomentTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED
}

package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class FishAudioVoiceBinding(
    val referenceId: String,
    val title: String,
    val authorId: String? = null,
    val authorName: String? = null,
    val coverImage: String? = null,
    val sampleAudio: String? = null,
    val sampleText: String? = null,
    val visibility: String? = null,
    val languages: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)

@Serializable
data class GeneratedVoiceMessage(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val anchorId: String? = null,
    val sourceOrder: Long,
    val sourceSegmentKind: String,
    val sourceSpeakerName: String,
    val sourceText: String,
    /** 去除 Fish Audio 标签后的实际合成文本；旧记录为空时等同 sourceText。 */
    val synthesisText: String? = null,
    val taggedText: String,
    val characterId: String,
    val characterName: String,
    val voice: FishAudioVoiceBinding,
    val fishModelId: String,
    val audioPath: String,
    val durationMs: Long,
    val byteLength: Long,
    val createdAt: Long,
    val updatedAt: Long
) {
    val effectiveSynthesisText: String
        get() = synthesisText ?: sourceText

    companion object {
        fun create(
            sessionId: String,
            messageId: String,
            anchorId: String?,
            sourceOrder: Long,
            sourceSegmentKind: String,
            sourceSpeakerName: String,
            sourceText: String,
            synthesisText: String? = null,
            taggedText: String,
            characterId: String,
            characterName: String,
            voice: FishAudioVoiceBinding,
            fishModelId: String,
            audioPath: String,
            durationMs: Long,
            byteLength: Long
        ): GeneratedVoiceMessage {
            val now = System.currentTimeMillis()
            return GeneratedVoiceMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                messageId = messageId,
                anchorId = anchorId,
                sourceOrder = sourceOrder,
                sourceSegmentKind = sourceSegmentKind,
                sourceSpeakerName = sourceSpeakerName,
                sourceText = sourceText,
                synthesisText = synthesisText,
                taggedText = taggedText,
                characterId = characterId,
                characterName = characterName,
                voice = voice,
                fishModelId = fishModelId,
                audioPath = audioPath,
                durationMs = durationMs,
                byteLength = byteLength,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}

@Serializable
data class VoiceAnchor(
    val id: String,
    val segmentKind: String,
    val speakerName: String?,
    val sourceText: String,
    val start: Int,
    val endExclusive: Int,
    val sourceOrder: Long
)

@Serializable
data class VoiceAnchorState(
    val messageId: String,
    val sessionId: String = "",
    val displayContentSnapshot: String,
    val anchors: List<VoiceAnchor> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

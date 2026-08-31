package com.example.chatbar.data.local.entity

import com.example.chatbar.domain.image.NovelAiImageModel
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 存档槽位 - 保存会话完整状态快照
 */
@Serializable
data class SaveSlot(
    val schemaVersion: Int = 4,
    val id: String,
    val sessionId: String,
    val name: String,
    val description: String? = null,
    val playerName: String? = null,
    val playerSetting: String? = null,
    val supplementarySetting: String? = null,
    val modelId: String? = null,
    val imageModelId: String? = null,
    val novelAiImageModel: NovelAiImageModel? = null,
    val formatCardId: String? = null,
    @Serializable(with = ReplyLengthSerializer::class)
    val replyLength: Int = DEFAULT_REPLY_LENGTH_CHARS,
    val replyLanguage: String? = null,
    val roleplayStyle: String? = null,
    val chatBackground: String? = null,
    val audiobookModeEnabled: Boolean? = null,
    val voiceLanguage: String? = null,
    val longTermMemoryEnabled: Boolean = true,
    val longTermMemory: String = "",
    val longTermMemoryUpdatedThroughMessageId: String? = null,
    val nextSourceTurnOrder: Long = 1,
    val sourceTurnTombstones: List<SourceTurnTombstone> = emptyList(),
    /** v3草稿兼容字段。 */
    val nextTimelineTurn: Long = 1,
    val timelineTombstones: Set<Long> = emptySet(),
    val memoryLimitChars: Int = 2000,
    val memorySnapshot: MemorySnapshot? = null,
    val contextWindowSize: Int? = null,
    val extraWorldBookIds: List<String> = emptyList(),
    val timedWorldInfo: Map<String, TimedEffectState> = emptyMap(),
    val messages: List<ChatMessage> = emptyList(),
    val imageResources: Map<String, SaveSlotImageResource> = emptyMap(),
    val voiceMessages: List<GeneratedVoiceMessage> = emptyList(),
    val audioResources: Map<String, SaveSlotAudioResource> = emptyMap(),
    val vectorChunks: List<VectorChunk> = emptyList(), // 记忆状态
    /** v8：图片写入策略。旧存档默认视为包含原图。 */
    val imagePolicy: SaveSlotImagePolicy = SaveSlotImagePolicy.ORIGINAL,
    /** v8：是否把本地生成语音写入存档包。 */
    val includeAudio: Boolean = true,
    /** v8：大型正文与媒体所在的流式包。null 表示旧版内联 JSON。 */
    val packageRef: SaveSlotPackageRef? = null,
    val createdAt: Long
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            sessionId: String,
            name: String,
            description: String? = null,
            messages: List<ChatMessage> = emptyList(),
            vectorChunks: List<VectorChunk> = emptyList()
        ): SaveSlot = SaveSlot(
            schemaVersion = 7,
            id = Uuid.random().toString(),
            sessionId = sessionId,
            name = name,
            description = description,
            messages = messages,
            vectorChunks = vectorChunks,
            createdAt = System.currentTimeMillis()
        )
    }
}

data class SaveSlotSummary(
    val id: String,
    val sessionId: String,
    val name: String,
    val description: String?,
    val messageCount: Int,
    val createdAt: Long,
    val schemaVersion: Int = 1,
    val imagePolicy: SaveSlotImagePolicy = SaveSlotImagePolicy.ORIGINAL,
    val imageCount: Int = 0,
    val includeAudio: Boolean = true,
    val audioCount: Int = 0
)

fun SaveSlot.toSummary(): SaveSlotSummary = SaveSlotSummary(
    id = id,
    sessionId = sessionId,
    name = name,
    description = description,
    messageCount = packageRef?.messageCount ?: messages.size,
    createdAt = createdAt,
    schemaVersion = schemaVersion,
    imagePolicy = imagePolicy,
    imageCount = packageRef?.imageCount ?: imageResources.size,
    includeAudio = includeAudio,
    audioCount = packageRef?.audioCount ?: audioResources.size
)

@Serializable
enum class SaveSlotImagePolicy {
    NONE,
    COMPRESSED,
    ORIGINAL
}

@Serializable
data class SaveSlotPackageRef(
    val fileName: String,
    val messageCount: Int = 0,
    val imageCount: Int = 0,
    val audioCount: Int = 0,
    val ragChunkCount: Int = 0,
    val byteLength: Long = 0L
)

@Serializable
data class SaveSlotImageResource(
    val fileName: String,
    val data: String
)

@Serializable
data class SaveSlotAudioResource(
    val fileName: String,
    val data: String
)

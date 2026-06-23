package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 角色卡片 - 包含一个或多个角色的完整设定
 */
@Serializable
data class CharacterCard(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val characters: List<CharacterInfo> = emptyList(),
    val customDocuments: List<DocumentInfo> = emptyList(),
    val greeting: String = "", // 起始台词
    val chatBackground: String? = null,
    val editMode: CharacterEditMode = CharacterEditMode.STRUCTURED,
    val basicSetting: String = "",
    val freeformCharacterText: String = "",
    val defaultImagePrompt: String = "",
    val sourcePresetKey: String? = null,
    val sourcePresetVersion: Int? = null,
    val ragIndexStatus: String = RagIndexStatus.NOT_INDEXED.name,
    val ragIndexDone: Int = 0,
    val ragIndexTotal: Int = 0,
    val ragIndexMessage: String? = null,
    val ragIndexedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            name: String,
            greeting: String = "",
            characters: List<CharacterInfo> = emptyList()
        ): CharacterCard {
            val now = System.currentTimeMillis()
            return CharacterCard(
                id = Uuid.random().toString(),
                name = name,
                characters = characters,
                greeting = greeting,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}

@Serializable
enum class CharacterEditMode {
    STRUCTURED,
    FREEFORM
}

@Serializable
enum class RagIndexStatus {
    NOT_INDEXED,
    INDEXING,
    COMPLETE,
    FAILED
}

/**
 * 单个角色信息
 */
@Serializable
data class CharacterInfo(
    val id: String,
    val name: String,
    val profile: String = "",       // 简介
    val appearance: String = "",    // 形象文字描述
    val appearanceImage: String? = null, // 形象图片路径
    val speakingStyle: String = "", // 语气
    val background: String = "",    // 过往
    val imagePrompt: String = ""
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(name: String): CharacterInfo = CharacterInfo(
            id = Uuid.random().toString(),
            name = name
        )
    }
}

/**
 * 自定义文档信息
 */
@Serializable
data class DocumentInfo(
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileType: String, // txt/md/json
    val addedAt: Long,
    val contentHash: String? = null,
    val indexedHash: String? = null,
    val ragStatus: String = DocumentRagStatus.PENDING.name,
    val ragChunkCount: Int = 0,
    val ragIndexedAt: Long? = null,
    val ragError: String? = null
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(fileName: String, filePath: String, fileType: String): DocumentInfo =
            DocumentInfo(
                id = Uuid.random().toString(),
                fileName = fileName,
                filePath = filePath,
                fileType = fileType,
                addedAt = System.currentTimeMillis()
            )
    }
}

@Serializable
enum class DocumentRagStatus {
    PENDING,
    INDEXING,
    INDEXED,
    FAILED
}

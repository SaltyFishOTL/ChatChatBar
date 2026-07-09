package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class EditorDraft(
    val id: String,
    val entityType: EditorDraftType,
    val mode: EditorDraftMode,
    val targetId: String? = null,
    val draftSessionId: String,
    val baseUpdatedAt: Long? = null,
    val baseHash: String? = null,
    val characterPayload: CharacterCard? = null,
    val formatPayload: FormatCard? = null,
    val worldBookPayload: WorldBook? = null,
    val draftAssetPaths: List<String> = emptyList(),
    val pendingDeletedAssets: List<String> = emptyList(),
    val pendingDeletedDocumentIds: List<String> = emptyList(),
    val openModalState: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    val title: String
        get() = when (entityType) {
            EditorDraftType.CHARACTER_CARD -> characterPayload?.name
            EditorDraftType.FORMAT_CARD -> formatPayload?.name
            EditorDraftType.WORLD_BOOK -> worldBookPayload?.name
        }.orEmpty().ifBlank { "未命名草稿" }

    val isNew: Boolean
        get() = mode == EditorDraftMode.CREATE || targetId.isNullOrBlank()
}

@Serializable
enum class EditorDraftType {
    CHARACTER_CARD,
    FORMAT_CARD,
    WORLD_BOOK
}

@Serializable
enum class EditorDraftMode {
    CREATE,
    EDIT
}

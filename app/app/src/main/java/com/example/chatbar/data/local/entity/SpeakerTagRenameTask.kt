package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class SpeakerTagRename(
    val characterId: String,
    val oldName: String,
    val newName: String
)

@Serializable
data class SpeakerTagRenameTask(
    val id: String,
    val characterCardId: String,
    val expectedCardUpdatedAt: Long,
    val renames: List<SpeakerTagRename>,
    val createdAt: Long,
    val lastError: String? = null
)

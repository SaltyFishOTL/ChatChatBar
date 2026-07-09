package com.example.chatbar.domain.draft

import com.example.chatbar.data.local.entity.CharacterInfo
import kotlinx.serialization.Serializable

@Serializable
data class CharacterOpenModalState(
    val kind: CharacterOpenModalKind,
    val character: CharacterInfo? = null,
    val documentId: String? = null,
    val documentName: String = "",
    val documentContent: String = ""
)

@Serializable
enum class CharacterOpenModalKind {
    CHARACTER,
    NEW_DOCUMENT,
    EDIT_DOCUMENT
}

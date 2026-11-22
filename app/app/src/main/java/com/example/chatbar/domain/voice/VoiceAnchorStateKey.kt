package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.VoiceAnchorState
import java.util.UUID

internal data class VoiceAnchorStateKey(
    val sessionId: String,
    val messageId: String,
    val messageVersionId: String?
) {
    constructor(state: VoiceAnchorState) : this(state.sessionId, state.messageId, state.messageVersionId)

    val storageId: String
        get() = if (sessionId.isBlank()) legacyStorageId else uuid("$sessionId\u0000$messageId\u0000${messageVersionId.orEmpty()}")

    val legacyStorageId: String
        get() = messageVersionId?.let { uuid("$messageId\u0000$it") } ?: messageId

    private fun uuid(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}

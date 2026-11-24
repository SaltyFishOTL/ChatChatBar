package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy

object VoicePlaybackQueuePolicy {
    fun sequenceFrom(
        selected: GeneratedVoiceMessage,
        orderedVisibleVoices: List<GeneratedVoiceMessage>
    ): List<GeneratedVoiceMessage> {
        val selectedIndex = orderedVisibleVoices.indexOfFirst { it.id == selected.id }
        if (selectedIndex < 0) return listOf(selected)
        return listOf(selected) + orderedVisibleVoices.drop(selectedIndex + 1).filter {
            it.sessionId == selected.sessionId && it.messageId == selected.messageId &&
                it.messageVersionId == selected.messageVersionId
        }
    }

    internal fun ownsDisplayedVersion(message: ChatMessage, voice: GeneratedVoiceMessage): Boolean =
        voice.sessionId == message.sessionId && voice.messageId == message.id &&
            voice.messageVersionId == MessageAlternativeVersionPolicy.activeVersionId(message)
}

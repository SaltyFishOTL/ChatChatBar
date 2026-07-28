package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.GeneratedVoiceMessage

object VoicePlaybackQueuePolicy {
    fun sequenceFrom(
        selected: GeneratedVoiceMessage,
        orderedVisibleVoices: List<GeneratedVoiceMessage>
    ): List<GeneratedVoiceMessage> {
        val selectedIndex = orderedVisibleVoices.indexOfFirst { it.id == selected.id }
        return if (selectedIndex >= 0) {
            orderedVisibleVoices.drop(selectedIndex)
        } else {
            listOf(selected)
        }
    }
}

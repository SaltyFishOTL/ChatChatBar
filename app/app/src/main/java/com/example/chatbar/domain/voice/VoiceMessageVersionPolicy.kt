package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.VoiceAnchor
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.MessageVersionSnapshot
import kotlin.math.abs

object VoiceMessageVersionPolicy {
    fun visibleVoices(
        message: ChatMessage,
        voices: List<GeneratedVoiceMessage>,
        version: MessageVersionSnapshot = MessageAlternativeVersionPolicy.activeVersion(message)
    ): List<GeneratedVoiceMessage> = voices.filter { voice ->
        voice.sessionId == message.sessionId && voice.messageId == message.id && voice.messageVersionId == version.id &&
            (!voice.requiresCompatibleVersion || isCompatibleVersion(version, voice))
    }

    fun applyAnchorReplacements(
        voices: List<GeneratedVoiceMessage>,
        messageId: String,
        messageVersionId: String,
        replacements: Map<String, String?>,
        updatedAt: Long = System.currentTimeMillis()
    ): List<GeneratedVoiceMessage> = voices.map { voice ->
        val anchorId = voice.anchorId
        if (
            voice.messageId != messageId ||
            voice.messageVersionId != messageVersionId ||
            anchorId == null ||
            anchorId !in replacements ||
            replacements[anchorId] == anchorId
        ) {
            voice
        } else {
            voice.copy(
                anchorId = replacements[anchorId],
                updatedAt = updatedAt
            )
        }
    }

    fun inferLegacyVersionId(
        message: ChatMessage,
        voice: GeneratedVoiceMessage,
        legacyState: VoiceAnchorState?
    ): String {
        val versions = MessageAlternativeVersionPolicy.versions(message)
        val activeVersionId = MessageAlternativeVersionPolicy.activeVersionId(message)
        val stateVersion = legacyState?.let { state ->
            versions.firstOrNull { it.content == state.displayContentSnapshot }
        }
        if (
            voice.anchorId != null &&
            legacyState?.anchors?.any { it.id == voice.anchorId } == true
        ) {
            return stateVersion?.id ?: activeVersionId
        }
        val matchingVersions = versions.filter { version ->
            versionContainsVoice(version, voice)
        }
        val matchingVersionIds = matchingVersions.mapTo(mutableSetOf(), MessageVersionSnapshot::id)
        return when {
            stateVersion?.id in matchingVersionIds ->
                checkNotNull(stateVersion).id
            activeVersionId in matchingVersionIds ->
                activeVersionId
            matchingVersions.isNotEmpty() -> matchingVersions.first().id
            stateVersion != null -> stateVersion.id
            else -> activeVersionId
        }
    }

    internal fun compatibleVersionId(message: ChatMessage, voice: GeneratedVoiceMessage): String? =
        MessageAlternativeVersionPolicy.versions(message).singleOrNull { isCompatibleVersion(it, voice) }?.id

    internal fun isCompatibleVersion(version: MessageVersionSnapshot, voice: GeneratedVoiceMessage): Boolean =
        versionContainsVoice(version, voice, requireMatchingSpeaker = true)

    fun anchorIdForVoice(
        voice: GeneratedVoiceMessage,
        state: VoiceAnchorState
    ): String? {
        if (voice.sourceSegmentKind == VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND) {
            return null
        }
        voice.anchorId?.let { anchorId ->
            if (state.anchors.any { it.id == anchorId }) return anchorId
        }
        val sameKind = state.anchors.filter { it.segmentKind == voice.sourceSegmentKind }
        if (sameKind.isEmpty()) return null
        val sameText = sameKind.filter {
            normalize(it.sourceText) == normalize(voice.sourceText)
        }
        val sameSpeaker = sameText.filter {
            normalize(it.speakerName.orEmpty()) == normalize(voice.sourceSpeakerName)
        }
        return (sameSpeaker.ifEmpty { sameText }.ifEmpty { sameKind })
            .minByOrNull { abs(it.sourceOrder - voice.sourceOrder) }
            ?.id
    }

    private fun versionContainsVoice(
        version: MessageVersionSnapshot,
        voice: GeneratedVoiceMessage,
        requireMatchingSpeaker: Boolean = false
    ): Boolean {
        if (voice.sourceSegmentKind == VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND) {
            return (
                VoiceAnchorPolicy.wholeMessageSegment(version.content)
                    ?.spokenText
                    ?.let { normalize(it) == normalize(voice.sourceText) }
                ) == true
        }
        return VoiceAnchorPolicy.eligibleSegments(
            version.content,
            includeNarration = true
        ).any { segment ->
            segment.kind.name == voice.sourceSegmentKind &&
                (!requireMatchingSpeaker || normalize(segment.speakerName.orEmpty()) == normalize(voice.sourceSpeakerName)) &&
                normalize(segment.spokenText) == normalize(voice.sourceText)
        }
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()
}

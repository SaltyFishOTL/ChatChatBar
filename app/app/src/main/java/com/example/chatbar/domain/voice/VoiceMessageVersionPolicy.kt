package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.VoiceAnchor
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.MessageVersionSnapshot
import java.security.MessageDigest
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
        sessionId: String,
        messageId: String,
        messageVersionId: String,
        replacements: Map<String, String?>,
        updatedAt: Long = System.currentTimeMillis()
    ): List<GeneratedVoiceMessage> = voices.map { voice ->
        val anchorId = voice.anchorId
        if (
            voice.sessionId != sessionId ||
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

    internal fun recoverCompatibleVersionId(message: ChatMessage, voice: GeneratedVoiceMessage): String? {
        // A shared line cannot establish a new owner for a previously assigned segment recording.
        if (!voice.messageVersionId.isNullOrBlank() && voice.legacyVersionContentHash == null &&
            voice.sourceSegmentKind != VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND) return null
        return compatibleVersionId(message, voice)
    }

    internal fun isCompatibleVersion(version: MessageVersionSnapshot, voice: GeneratedVoiceMessage): Boolean =
        (voice.legacyVersionContentHash == null || voice.legacyVersionContentHash == contentHash(version.content)) &&
        versionContainsVoice(version, voice, requireMatchingSpeaker = true)

    internal fun legacyVersionContentHash(voice: GeneratedVoiceMessage, version: MessageVersionSnapshot?): String? =
        voice.legacyVersionContentHash ?: version?.takeIf {
            voice.messageVersionId.isNullOrBlank() &&
                voice.sourceSegmentKind != VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND &&
                versionContainsVoice(it, voice, requireMatchingSpeaker = true)
        }?.let { contentHash(it.content) }

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

    internal fun archivedAnchorIdForVoice(
        voice: GeneratedVoiceMessage,
        state: VoiceAnchorState,
        exactArchivedLayout: Boolean = false,
        recoverUnversionedAnchor: Boolean = false
    ): String? {
        if (voice.anchorId == null && !recoverUnversionedAnchor) return null
        if (voice.sessionId != state.sessionId || voice.messageId != state.messageId ||
            voice.messageVersionId != state.messageVersionId ||
            voice.sourceSegmentKind == VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND) return null
        fun matchesSource(anchor: VoiceAnchor): Boolean = with(anchor) {
            segmentKind == voice.sourceSegmentKind &&
                normalize(speakerName.orEmpty()) == normalize(voice.sourceSpeakerName) &&
                normalize(sourceText) == normalize(voice.sourceText)
        }
        val savedAnchor = state.anchors.firstOrNull { it.id == voice.anchorId }
        if (exactArchivedLayout) return savedAnchor?.id
        savedAnchor?.let { return it.takeIf(::matchesSource)?.id }
        return state.anchors.singleOrNull(::matchesSource)?.id
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

    private fun contentHash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

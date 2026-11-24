package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.VoiceAnchor
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.MessageVersionSnapshot
import com.example.chatbar.domain.chat.RoleplayActionOptionsPolicy
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

    /** A legacy recording without an owner needs evidence before it can choose between replies. */
    fun inferLegacyVersionId(
        message: ChatMessage,
        voice: GeneratedVoiceMessage,
        legacyState: VoiceAnchorState?
    ): String? {
        val versions = MessageAlternativeVersionPolicy.versions(message)
        return legacyState?.let { anchoredLegacyVersion(versions, voice, it) }?.id
            ?: versions.singleOrNull { isCompatibleVersion(it, voice) }?.id
            ?: versions.singleOrNull()?.id
    }

    internal fun anchoredLegacyVersion(
        versions: List<MessageVersionSnapshot>,
        voice: GeneratedVoiceMessage,
        state: VoiceAnchorState
    ): MessageVersionSnapshot? =
        state.takeIf { it.anchors.any { anchor -> anchor.id == voice.anchorId } }?.let {
            versions.singleOrNull { version -> version.content == it.displayContentSnapshot }
        }

    internal fun bindWitnessedLegacyVersion(
        versions: List<MessageVersionSnapshot>,
        voice: GeneratedVoiceMessage,
        state: VoiceAnchorState
    ): GeneratedVoiceMessage? {
        if (voice.versionOwnershipConfirmed || !voice.messageVersionId.isNullOrBlank() ||
            voice.anchorId.isNullOrBlank() || voice.legacyVersionContentHash != null) return null
        val version = anchoredLegacyVersion(versions, voice, state) ?: return null
        val contentHash = legacyVersionContentHash(voice, version) ?: return null
        return voice.copy(messageVersionId = version.id, legacyVersionContentHash = contentHash)
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
        versionContainsVoice(version, voice)

    internal fun legacyVersionContentHash(voice: GeneratedVoiceMessage, version: MessageVersionSnapshot?): String? =
        voice.legacyVersionContentHash ?: version?.takeIf {
            voice.messageVersionId.isNullOrBlank() &&
                voice.sourceSegmentKind != VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND &&
                versionContainsVoice(it, voice)
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
        voice: GeneratedVoiceMessage
    ): Boolean {
        val excluded = RoleplayActionOptionsPolicy.blocks(version.content, version.actionOptionsFormat)
        // Previously generated recordings can match the unfiltered text.
        if (voice.sourceSegmentKind == VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND) {
            return sequenceOf(
                VoiceAnchorPolicy.wholeMessageSegment(version.content),
                VoiceAnchorPolicy.wholeMessageSegment(version.content, excluded)
            ).filterNotNull().any { normalize(it.spokenText) == normalize(voice.sourceText) }
        }
        return (
            VoiceAnchorPolicy.eligibleSegments(version.content, includeNarration = true) +
                VoiceAnchorPolicy.eligibleSegments(version.content, includeNarration = true, excludedActionBlocks = excluded)
        ).any { segment ->
            segment.kind.name == voice.sourceSegmentKind &&
                normalize(segment.speakerName.orEmpty()) == normalize(voice.sourceSpeakerName) &&
                normalize(segment.spokenText) == normalize(voice.sourceText)
        }
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun contentHash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

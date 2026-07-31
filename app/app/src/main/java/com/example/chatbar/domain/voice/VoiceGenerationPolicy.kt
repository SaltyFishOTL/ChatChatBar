package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.domain.chat.RoleplaySegmentKind

object VoiceGenerationPolicy {
    const val WHOLE_MESSAGE_SOURCE_KIND = "WHOLE_MESSAGE"

    fun audiobookEnabled(session: ChatSession, settings: AppSettings): Boolean =
        session.audiobookModeEnabled ?: settings.audiobookModeEnabled

    fun shouldGenerateAiTags(audiobookEnabled: Boolean): Boolean =
        !audiobookEnabled

    fun generationSegments(
        content: String,
        audiobookEnabled: Boolean,
        segmentedBubblesEnabled: Boolean
    ): List<CurrentVoiceSegment> {
        if (audiobookEnabled && !segmentedBubblesEnabled) {
            return listOfNotNull(VoiceAnchorPolicy.wholeMessageSegment(content))
        }
        return VoiceAnchorPolicy.eligibleSegments(
            content = content,
            includeNarration = audiobookEnabled
        )
    }

    fun availableCharacters(
        card: CharacterCard,
        segment: CurrentVoiceSegment
    ): List<CharacterInfo> {
        if (segment.requiresNarratorVoice()) {
            return card.characters.filter { it.fishAudioVoice != null }
        }
        val speaker = segment.speakerName?.trim()?.takeIf(String::isNotEmpty)
            ?: return emptyList()
        return card.characters
            .filter { it.name.trim().equals(speaker, ignoreCase = true) }
            .singleOrNull()
            ?.takeIf { it.fishAudioVoice != null }
            ?.let(::listOf)
            .orEmpty()
    }

    fun resolveCharacter(
        card: CharacterCard,
        segment: CurrentVoiceSegment,
        narratorCharacterId: String?
    ): CharacterInfo? {
        val available = availableCharacters(card, segment)
        if (!segment.requiresNarratorVoice()) return available.singleOrNull()
        if (card.characters.size == 1) return available.singleOrNull()
        return available.singleOrNull { it.id == narratorCharacterId }
    }

    fun resolveRegenerationCharacter(
        card: CharacterCard,
        characterId: String
    ): CharacterInfo? =
        card.characters
            .singleOrNull { it.id == characterId }
            ?.takeIf { it.fishAudioVoice != null }

    fun resolveFishModelId(configuredModelId: String): String =
        configuredModelId
            .takeIf { it in FishAudioTtsModels.supported }
            ?: FishAudioTtsModels.S2_1_PRO_FREE

    fun requiresNarratorSelection(
        card: CharacterCard,
        segments: List<CurrentVoiceSegment>
    ): Boolean =
        card.characters.size > 1 &&
            segments.any(CurrentVoiceSegment::requiresNarratorVoice) &&
            card.characters.any { it.fishAudioVoice != null }

    fun hasResolvableTarget(
        card: CharacterCard,
        segment: CurrentVoiceSegment
    ): Boolean = availableCharacters(card, segment).isNotEmpty()

    fun sourceKind(segment: CurrentVoiceSegment): String =
        if (segment.sourceScope == VoiceSourceScope.WHOLE_MESSAGE) {
            WHOLE_MESSAGE_SOURCE_KIND
        } else {
            segment.kind.name
        }
}

private fun CurrentVoiceSegment.requiresNarratorVoice(): Boolean =
    sourceScope == VoiceSourceScope.WHOLE_MESSAGE ||
        kind == RoleplaySegmentKind.NARRATION

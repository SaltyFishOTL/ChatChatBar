package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterInfo

internal object CharacterPlaceholderPolicy {
    fun isEmpty(character: CharacterInfo): Boolean = isEmpty(
        name = character.name,
        profile = character.profile,
        appearance = character.appearance,
        appearanceImage = character.appearanceImage,
        clothing = character.clothing,
        abilities = character.abilities,
        habits = character.habits,
        background = character.background,
        relationships = character.relationships,
        speakingStyle = character.speakingStyle,
        imagePrompt = character.imagePrompt,
        hasVoice = character.fishAudioVoice != null
    )

    fun isEmpty(character: PackagedCharacter): Boolean = isEmpty(
        name = character.name,
        profile = character.profile,
        appearance = character.appearance,
        appearanceImage = character.appearanceImageResourceId,
        clothing = character.clothing,
        abilities = character.abilities,
        habits = character.habits,
        background = character.background,
        relationships = character.relationships,
        speakingStyle = character.speakingStyle,
        imagePrompt = character.imagePrompt,
        hasVoice = character.fishAudioVoice != null
    )

    private fun isEmpty(
        name: String,
        profile: String,
        appearance: String,
        appearanceImage: String?,
        clothing: String,
        abilities: String,
        habits: String,
        background: String,
        relationships: String,
        speakingStyle: String,
        imagePrompt: String,
        hasVoice: Boolean
    ): Boolean =
        name.isBlank() &&
            profile.isBlank() &&
            appearance.isBlank() &&
            appearanceImage.isNullOrBlank() &&
            clothing.isBlank() &&
            abilities.isBlank() &&
            habits.isBlank() &&
            background.isBlank() &&
            relationships.isBlank() &&
            speakingStyle.isBlank() &&
            imagePrompt.isBlank() &&
            !hasVoice
}

package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.WorldBookEntry

enum class CharacterTextSection(val label: String) {
    PROFILE("简介"),
    APPEARANCE("外貌"),
    CLOTHING("服装"),
    ABILITIES("能力"),
    HABITS("习惯与爱好"),
    BACKGROUND("背景经历"),
    RELATIONSHIPS("人际关系"),
    SPEAKING_STYLE("语气与口癖"),
    IMAGE_PROMPT("角色 Prompt")
}

data class CharacterSectionSelection(
    val characterId: String,
    val sections: Set<CharacterTextSection>
)

data class WorldBookCharacterImportResult(
    val entries: List<WorldBookEntry>,
    val createdCount: Int,
    val updatedCount: Int
)

data class CharacterCardCharacterImportResult(
    val characters: List<CharacterInfo>,
    val createdCount: Int,
    val updatedCount: Int
)

object CharacterSectionImportPolicy {
    val worldBookSections: Set<CharacterTextSection> = setOf(
        CharacterTextSection.PROFILE,
        CharacterTextSection.APPEARANCE,
        CharacterTextSection.CLOTHING,
        CharacterTextSection.ABILITIES,
        CharacterTextSection.HABITS,
        CharacterTextSection.BACKGROUND,
        CharacterTextSection.RELATIONSHIPS,
        CharacterTextSection.SPEAKING_STYLE
    )

    val transferableSections: Set<CharacterTextSection> = CharacterTextSection.entries.toSet()

    fun sectionValue(character: CharacterInfo, section: CharacterTextSection): String = when (section) {
        CharacterTextSection.PROFILE -> character.profile
        CharacterTextSection.APPEARANCE -> character.appearance
        CharacterTextSection.CLOTHING -> character.clothing
        CharacterTextSection.ABILITIES -> character.abilities
        CharacterTextSection.HABITS -> character.habits
        CharacterTextSection.BACKGROUND -> character.background
        CharacterTextSection.RELATIONSHIPS -> character.relationships
        CharacterTextSection.SPEAKING_STYLE -> character.speakingStyle
        CharacterTextSection.IMAGE_PROMPT -> character.imagePrompt
    }

    fun importIntoWorldBook(
        currentEntries: List<WorldBookEntry>,
        sourceCharacters: List<CharacterInfo>
    ): WorldBookCharacterImportResult {
        val result = currentEntries.toMutableList()
        var created = 0
        var updated = 0
        sourceCharacters
            .filter { it.name.isNotBlank() }
            .distinctBy { NamePolicy.normalize(it.name).lowercase() }
            .forEach { source ->
                val normalizedName = NamePolicy.normalize(source.name)
                val content = StructuredCharacterFreeformConverter.convert(listOf(source))
                val existingIndex = result.indexOfFirst { entry -> NamePolicy.isSame(entry.name, normalizedName) }
                if (existingIndex >= 0) {
                    result[existingIndex] = result[existingIndex].copy(content = content)
                    updated += 1
                } else {
                    result += WorldBookEntry.create(keys = listOf(normalizedName), content = content).copy(
                        name = normalizedName
                    )
                    created += 1
                }
            }
        return WorldBookCharacterImportResult(result, created, updated)
    }

    fun clearWorldBookImportedSections(
        card: CharacterCard,
        characterIds: Set<String>
    ): CharacterCard = card.copy(
        characters = card.characters.map { character ->
            if (character.id !in characterIds) character else character.copy(
                appearance = "",
                clothing = "",
                abilities = "",
                habits = "",
                background = "",
                relationships = "",
                speakingStyle = ""
            )
        }
    )

    fun importIntoCharacterCard(
        currentCharacters: List<CharacterInfo>,
        sourceCharacters: List<CharacterInfo>,
        selections: List<CharacterSectionSelection>
    ): CharacterCardCharacterImportResult {
        val result = currentCharacters.toMutableList()
        val sourceById = sourceCharacters.associateBy(CharacterInfo::id)
        var created = 0
        var updated = 0
        selections.forEach { selection ->
            val source = sourceById[selection.characterId]
                ?.takeIf { it.name.isNotBlank() && selection.sections.isNotEmpty() }
                ?: return@forEach
            val normalizedName = NamePolicy.normalize(source.name)
            val existingIndex = result.indexOfFirst { target -> NamePolicy.isSame(target.name, normalizedName) }
            if (existingIndex >= 0) {
                result[existingIndex] = copySelectedSections(result[existingIndex], source, selection.sections)
                updated += 1
            } else {
                result += copySelectedSections(CharacterInfo.create(normalizedName), source, selection.sections)
                created += 1
            }
        }
        return CharacterCardCharacterImportResult(result, created, updated)
    }

    private fun copySelectedSections(
        target: CharacterInfo,
        source: CharacterInfo,
        sections: Set<CharacterTextSection>
    ): CharacterInfo = target.copy(
        profile = selectedValue(source.profile, target.profile, CharacterTextSection.PROFILE, sections),
        appearance = selectedValue(source.appearance, target.appearance, CharacterTextSection.APPEARANCE, sections),
        clothing = selectedValue(source.clothing, target.clothing, CharacterTextSection.CLOTHING, sections),
        abilities = selectedValue(source.abilities, target.abilities, CharacterTextSection.ABILITIES, sections),
        habits = selectedValue(source.habits, target.habits, CharacterTextSection.HABITS, sections),
        background = selectedValue(source.background, target.background, CharacterTextSection.BACKGROUND, sections),
        relationships = selectedValue(source.relationships, target.relationships, CharacterTextSection.RELATIONSHIPS, sections),
        speakingStyle = selectedValue(source.speakingStyle, target.speakingStyle, CharacterTextSection.SPEAKING_STYLE, sections),
        imagePrompt = selectedValue(source.imagePrompt, target.imagePrompt, CharacterTextSection.IMAGE_PROMPT, sections)
    )

    private fun selectedValue(
        source: String,
        target: String,
        section: CharacterTextSection,
        selections: Set<CharacterTextSection>
    ): String = source.takeIf { section in selections && it.isNotBlank() } ?: target
}

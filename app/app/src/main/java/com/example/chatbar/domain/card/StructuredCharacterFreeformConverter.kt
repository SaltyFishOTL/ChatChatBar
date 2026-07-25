package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo

data class StructuredToFreeformTransition(
    val targetMode: CharacterEditMode,
    val freeformCharacterText: String
)

object StructuredCharacterFreeformConverter {
    fun createTransition(characters: List<CharacterInfo>): StructuredToFreeformTransition? =
        convert(characters).takeIf { it.isNotBlank() }?.let {
            StructuredToFreeformTransition(
                targetMode = CharacterEditMode.FREEFORM,
                freeformCharacterText = it
            )
        }

    fun convert(characters: List<CharacterInfo>): String =
        characters.mapNotNull(::convertCharacter).joinToString("\n\n")

    fun hasConvertibleContent(characters: List<CharacterInfo>): Boolean =
        createTransition(characters) != null

    private fun convertCharacter(character: CharacterInfo): String? {
        val sections = buildList {
            addSection("角色名称", character.name)
            addSection("简介", character.profile)
            addSection("外貌", character.appearance)
            addSection("服装", character.clothing)
            addSection("能力", character.abilities)
            addSection("习惯与爱好", character.habits)
            addSection("背景经历", character.background)
            addSection("人际关系", character.relationships)
            addSection("语气与口癖", character.speakingStyle)
        }
        return sections.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun MutableList<String>.addSection(title: String, value: String) {
        value.trim().takeIf { it.isNotEmpty() }?.let {
            add("【$title】\n$it")
        }
    }
}

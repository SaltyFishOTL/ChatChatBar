package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard

/** Position is 0 for the main greeting, otherwise original alternate array index + 1. */
data class CharacterGreetingChoice(val cardId: String, val position: Int, val text: String)

object CharacterGreetingPolicy {
    fun choices(card: CharacterCard): List<CharacterGreetingChoice> = buildList {
        add(CharacterGreetingChoice(card.id, 0, card.greeting))
        card.alternateGreetings.forEachIndexed { index, value ->
            if (value.isNotBlank()) add(CharacterGreetingChoice(card.id, index + 1, value))
        }
    }

    /** Resolve against the freshly loaded card before any session or message is written. */
    fun resolve(card: CharacterCard, selection: CharacterGreetingChoice?): String {
        if (selection == null) return card.greeting
        require(selection in choices(card)) { "开场内容已变化，请重新选择后创建会话。" }
        return selection.text
    }
}

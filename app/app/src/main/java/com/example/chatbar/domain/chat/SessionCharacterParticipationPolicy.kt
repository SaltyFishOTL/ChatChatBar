package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.SessionCharacterParticipation

/** Request-only structured-person view; no changes to card entities or historical context. */
object SessionCharacterParticipationPolicy {
    fun cardAllows(card: CharacterCard, personId: String): Boolean = card.editMode != CharacterEditMode.STRUCTURED ||
        card.characters.singleOrNull { it.id == personId }?.enabled == true

    fun isEnabled(session: ChatSession?, card: CharacterCard, personId: String): Boolean =
        card.editMode != CharacterEditMode.STRUCTURED || (cardAllows(card, personId) &&
            (session == null || session.characterCardId != card.id ||
                session.characterParticipation.singleOrNull { it.cardId == card.id }?.let {
                    personId !in it.disabledPersonIds && (!session.autoPauseAbsentPeople || personId !in it.automaticallyPausedPersonIds)
                } != false))

    fun effectiveCard(session: ChatSession?, card: CharacterCard): CharacterCard {
        val active = card.characters.filter { isEnabled(session, card, it.id) }
        return if (active.size == card.characters.size) card else card.copy(characters = active)
    }

    fun change(session: ChatSession, card: CharacterCard, personId: String, enabled: Boolean): ChatSession {
        require(session.characterCardId == card.id) { "人物不属于当前会话角色卡" }
        require(card.editMode == CharacterEditMode.STRUCTURED) { "自由文本卡尚不支持逐人物关闭" }
        require(personId.isNotBlank() && card.characters.count { it.id == personId } == 1) { "人物已变化或身份不唯一" }
        require(!enabled || cardAllows(card, personId)) { "人物已在角色卡中关闭，请先到角色卡编辑器启用" }
        val before = session.characterParticipation.singleOrNull { it.cardId == card.id }
            ?: SessionCharacterParticipation(card.id, emptySet())
        return replace(session, before.copy(
            disabledPersonIds = if (enabled) before.disabledPersonIds - personId else before.disabledPersonIds + personId,
            automaticallyPausedPersonIds = before.automaticallyPausedPersonIds - personId))
    }

    private fun replace(session: ChatSession, entry: SessionCharacterParticipation): ChatSession {
        val entries = session.characterParticipation.toMutableList()
        val index = entries.indexOfFirst { it.cardId == entry.cardId }
        if (entry.disabledPersonIds.isEmpty() && entry.automaticallyPausedPersonIds.isEmpty()) {
            if (index >= 0) entries.removeAt(index)
        } else if (index >= 0) entries[index] = entry else entries.add(entry)
        return session.copy(characterParticipation = entries)
    }
}

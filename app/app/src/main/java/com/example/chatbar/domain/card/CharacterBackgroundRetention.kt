package com.example.chatbar.domain.card

import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.SaveSlotSummary

/** A gallery image remains owned while another card, conversation, draft or archive uses it. */
object CharacterBackgroundRetention {
    fun references(
        cards: List<CharacterCard>, sessions: List<ChatSession>, drafts: List<EditorDraft>,
        slots: List<SaveSlotSummary>
    ): List<String> = buildList {
        cards.forEach { card -> addAll(card.chatBackgroundImages); card.chatBackground?.let(::add) }
        sessions.mapNotNullTo(this) { it.chatBackground }
        slots.mapNotNullTo(this) { it.chatBackground }
        drafts.forEach { draft ->
            addAll(draft.draftAssetPaths)
            draft.characterPayload?.let { card -> addAll(card.chatBackgroundImages); card.chatBackground?.let(::add) }
        }
    }.filter(String::isNotBlank).distinct()

    suspend fun currentReferences(app: ChatBarApp, excludedCardId: String? = null): List<String> = references(
        app.characterRepository.getAll().filterNot { it.id == excludedCardId },
        app.chatRepository.getAllSessions(),
        app.editorDraftRepository.getAll().filterNot { excludedCardId != null && it.targetId == excludedCardId },
        app.saveSlotRepository.getAll()
    )
}

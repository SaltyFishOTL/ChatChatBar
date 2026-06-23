package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository

class CharacterSessionService(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository
) {
    suspend fun createSessionForCharacter(cardId: String): String {
        val card = requireNotNull(characterRepository.getById(cardId)) { "角色卡不存在" }
        val session = ChatSession.create(characterCardId = card.id, title = card.name)
        chatRepository.createSession(session)
        chatRepository.addMessage(
            ChatMessage.create(sessionId = session.id, role = MessageRole.ASSISTANT, content = card.greeting)
        )
        return session.id
    }
}

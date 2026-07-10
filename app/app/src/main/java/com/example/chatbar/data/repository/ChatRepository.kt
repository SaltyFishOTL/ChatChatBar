package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatDraft
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.domain.chat.ChatMessageOrdering
import com.example.chatbar.domain.chat.renameRoleplaySpeakerMarkers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * 聊天仓库 - 管理会话和消息
 */
class ChatRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val SESSION_TYPE = "chat_sessions"
        private const val MESSAGE_TYPE = "chat_messages"
        private const val DRAFT_TYPE = "chat_drafts"
    }

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: Flow<List<ChatSession>> = _sessions.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        refreshSessionCache()
        initialized = true
    }

    private suspend fun refreshSessionCache() {
        _sessions.value = storage.loadAll(SESSION_TYPE, ChatSession.serializer())
            .sortedWith(
                compareByDescending<ChatSession> { it.isPinned }
                    .thenByDescending { it.lastMessageTime ?: it.createdAt }
            )
    }

    // ===== 会话操作 =====

    fun observeSessions(): Flow<List<ChatSession>> = _sessions.asStateFlow()

    /** 获取置顶会话 */
    fun observePinnedSessions(): Flow<List<ChatSession>> =
        _sessions.map { list -> list.filter { it.isPinned } }

    suspend fun getAllSessions(): List<ChatSession> {
        initialize()
        return _sessions.value
    }

    suspend fun getSession(id: String): ChatSession? {
        return storage.loadEntity(SESSION_TYPE, id, ChatSession.serializer())
    }

    suspend fun createSession(session: ChatSession): ChatSession {
        storage.saveEntity(SESSION_TYPE, session.id, session, ChatSession.serializer())
        refreshSessionCache()
        return session
    }

    suspend fun updateSession(session: ChatSession) {
        val updated = session.copy(updatedAt = System.currentTimeMillis())
        storage.saveEntity(SESSION_TYPE, updated.id, updated, ChatSession.serializer())
        refreshSessionCache()
    }

    suspend fun getSessionDraft(id: String): String {
        return storage.loadEntity(DRAFT_TYPE, id, ChatDraft.serializer())?.content.orEmpty()
    }

    suspend fun updateSessionDraft(id: String, draft: String) {
        if (draft.isEmpty()) {
            deleteSessionDraft(id)
        } else {
            storage.saveEntity(DRAFT_TYPE, id, ChatDraft(id, draft), ChatDraft.serializer())
        }
    }

    suspend fun deleteSessionDraft(id: String) {
        storage.deleteEntity<ChatDraft>(DRAFT_TYPE, id)
    }

    suspend fun deleteSession(id: String) {
        deleteSessionRecord(id)
        deleteMessagesForSession(id)
    }

    suspend fun deleteSessionRecord(id: String) {
        storage.deleteEntity<ChatSession>(SESSION_TYPE, id)
        deleteSessionDraft(id)
        _sessions.value = _sessions.value.filterNot { it.id == id }
    }

    suspend fun pinSession(id: String) {
        getSession(id)?.let { session ->
            updateSession(session.copy(isPinned = true))
        }
    }

    suspend fun unpinSession(id: String) {
        getSession(id)?.let { session ->
            updateSession(session.copy(isPinned = false))
        }
    }

    // ===== 消息操作 =====

    /**
     * 消息存储为 chat_messages/<sessionId>_<messageId>.json
     * 这样可按sessionId前缀过滤
     */
    private fun messageStorageId(sessionId: String, messageId: String): String {
        return "${sessionId}_${messageId}"
    }

    suspend fun getMessages(sessionId: String): List<ChatMessage> {
        return storage.loadAll(MESSAGE_TYPE, ChatMessage.serializer())
            .filter { it.sessionId == sessionId }
            .sortedWith(ChatMessage.TimelineComparator)
    }

    suspend fun getMessage(messageId: String, sessionId: String): ChatMessage? {
        return storage.loadEntity(
            MESSAGE_TYPE,
            messageStorageId(sessionId, messageId),
            ChatMessage.serializer()
        )
    }

    suspend fun addMessage(message: ChatMessage): ChatMessage {
        saveMessageRecord(message)

        // 更新会话预览
        val latest = getMessages(message.sessionId).lastOrNull()
        if (latest?.id == message.id) {
            getSession(message.sessionId)?.let { session ->
                updateSession(
                    session.copy(
                        lastMessagePreview = message.previewText(),
                        lastMessageTime = message.createdAt,
                        lastMessageRole = message.role
                    )
                )
            }
        }

        return message
    }

    suspend fun addMessageAfter(message: ChatMessage, anchorMessageId: String): ChatMessage {
        val reordered = ChatMessageOrdering.insertGeneratedImageAfter(
            messages = getMessages(message.sessionId),
            imageMessage = message,
            anchorMessageId = anchorMessageId
        )
        val inserted = reordered.first { it.id == message.id }
        reordered.forEach { saveMessageRecord(it) }

        val latest = reordered.lastOrNull()
        getSession(message.sessionId)?.let { session ->
            updateSession(
                session.copy(
                    lastMessagePreview = latest?.previewText(),
                    lastMessageTime = latest?.createdAt,
                    lastMessageRole = latest?.role
                )
            )
        }

        return inserted
    }

    private suspend fun saveMessageRecord(message: ChatMessage) {
        storage.saveEntity(
            MESSAGE_TYPE,
            messageStorageId(message.sessionId, message.id),
            message,
            ChatMessage.serializer()
        )
    }

    suspend fun updateMessage(message: ChatMessage) {
        val updated = message.copy(updatedAt = System.currentTimeMillis())
        storage.saveEntity(
            MESSAGE_TYPE,
            messageStorageId(updated.sessionId, updated.id),
            updated,
            ChatMessage.serializer()
        )

        val latest = getMessages(updated.sessionId).lastOrNull()
        if (latest?.id == updated.id) {
            getSession(updated.sessionId)?.let { session ->
                updateSession(
                    session.copy(
                        lastMessagePreview = updated.previewText(),
                        lastMessageTime = updated.createdAt,
                        lastMessageRole = updated.role
                    )
                )
            }
        }
    }

    suspend fun deleteMessage(messageId: String, sessionId: String) {
        storage.deleteEntity<ChatMessage>(MESSAGE_TYPE, messageStorageId(sessionId, messageId))
        val latest = getMessages(sessionId).lastOrNull()
        getSession(sessionId)?.let { session ->
            updateSession(
                session.copy(
                    lastMessagePreview = latest?.previewText(),
                    lastMessageTime = latest?.createdAt,
                    lastMessageRole = latest?.role
                )
            )
        }
    }

    suspend fun deleteMessagesForSession(sessionId: String): Int =
        storage.deleteByIdPrefix<ChatMessage>(MESSAGE_TYPE, "${sessionId}_")

    /** 获取最近N条消息（用于上下文窗口） */
    suspend fun getRecentMessages(sessionId: String, count: Int): List<ChatMessage> {
        return getMessages(sessionId).takeLast(count)
    }

    suspend fun rewriteSpeakerTagsForCharacterCard(
        characterCardId: String,
        renames: List<SpeakerTagRename>
    ): Int {
        if (renames.isEmpty()) return 0
        initialize()
        var updatedCount = 0
        _sessions.value.filter { it.characterCardId == characterCardId }.forEach { session ->
            val messages = getMessages(session.id)
            var sessionChanged = false
            val updatedMessages = messages.map { message ->
                val content = renameRoleplaySpeakerMarkers(message.content, renames)
                val alternatives = message.alternatives.map { alternative ->
                    renameRoleplaySpeakerMarkers(alternative, renames)
                }
                if (content == message.content && alternatives == message.alternatives) {
                    message
                } else {
                    updatedCount++
                    sessionChanged = true
                    message.copy(
                        content = content,
                        alternatives = alternatives,
                        updatedAt = System.currentTimeMillis()
                    ).also { saveMessageRecord(it) }
                }
            }
            if (sessionChanged) {
                val latest = updatedMessages.lastOrNull()
                updateSession(
                    session.copy(
                        lastMessagePreview = latest?.previewText(),
                        lastMessageTime = latest?.createdAt,
                        lastMessageRole = latest?.role
                    )
                )
            }
        }
        return updatedCount
    }

    /** 搜索会话 */
    suspend fun searchSessions(query: String): List<ChatSession> {
        return getAllSessions().filter { session ->
            session.title.contains(query, ignoreCase = true)
        }
    }
}

private fun ChatMessage.previewText(): String =
    displayContent.takeIf { it.isNotBlank() }?.take(100)
        ?: if (images.isNotEmpty()) "[图片]" else ""

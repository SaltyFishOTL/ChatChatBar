package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatDraft
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.domain.chat.ChatMessageOrdering
import com.example.chatbar.domain.chat.TimelineTurnPolicy
import com.example.chatbar.domain.chat.renameRoleplaySpeakerMarkers
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val messageAppendMutex = Mutex()

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

    suspend fun addMessage(message: ChatMessage): ChatMessage = messageAppendMutex.withLock {
        val assigned = assignSourceTurnForAppend(message)
        saveMessageRecord(assigned)

        // 更新会话预览
        val latest = getMessages(assigned.sessionId).lastOrNull()
        if (latest?.id == assigned.id) {
            getSession(assigned.sessionId)?.let { session ->
                updateSession(
                    session.copy(
                        lastMessagePreview = assigned.previewText(),
                        lastMessageTime = assigned.createdAt,
                        lastMessageRole = assigned.role
                    )
                )
            }
        }

        assigned
    }

    suspend fun addMessageAfter(
        message: ChatMessage,
        anchorMessageId: String
    ): ChatMessage = messageAppendMutex.withLock {
        val anchor = getMessage(anchorMessageId, message.sessionId)
        val assigned = message.copy(
            sourceTurnId = message.sourceTurnId ?: anchor?.sourceTurnId,
            sourceTurnOrder = message.sourceTurnOrder ?: anchor?.sourceTurnOrder,
            timelineTurn = message.timelineTurn ?: anchor?.timelineTurn
        )
        val reordered = ChatMessageOrdering.insertGeneratedImageAfter(
            messages = getMessages(message.sessionId),
            imageMessage = assigned,
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

        inserted
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

    suspend fun deleteMessage(messageId: String, sessionId: String) = messageAppendMutex.withLock {
        val removed = getMessage(messageId, sessionId)
        storage.deleteEntity<ChatMessage>(MESSAGE_TYPE, messageStorageId(sessionId, messageId))
        val remaining = getMessages(sessionId)
        val latest = remaining.lastOrNull()
        getSession(sessionId)?.let { session ->
            val removedSourceId = removed?.sourceTurnId
            val removedSourceOrder = removed?.sourceTurnOrder
            val sourceTombstones = if (
                removedSourceId != null && removedSourceOrder != null &&
                remaining.none { it.sourceTurnId == removedSourceId }
            ) {
                (session.sourceTurnTombstones + com.example.chatbar.data.local.entity.SourceTurnTombstone(
                    sourceTurnId = removedSourceId,
                    sourceOrder = removedSourceOrder
                )).distinctBy { it.sourceTurnId }
            } else {
                session.sourceTurnTombstones
            }
            val removedLegacyTurn = removed?.timelineTurn
            val legacyTombstones = if (
                removedLegacyTurn != null && remaining.none { it.timelineTurn == removedLegacyTurn }
            ) {
                session.timelineTombstones + removedLegacyTurn
            } else {
                session.timelineTombstones
            }
            updateSession(
                session.copy(
                    lastMessagePreview = latest?.previewText(),
                    lastMessageTime = latest?.createdAt,
                    lastMessageRole = latest?.role,
                    sourceTurnTombstones = sourceTombstones,
                    timelineTombstones = legacyTombstones
                )
            )
        }
    }

    suspend fun deleteMessagesForSession(sessionId: String): Int =
        storage.deleteByIdPrefix<ChatMessage>(MESSAGE_TYPE, "${sessionId}_")

    /** 批量替换会话消息，避免逐条更新会话预览与反复全量加载。 */
    suspend fun replaceMessagesForSession(sessionId: String, messages: List<ChatMessage>) {
        val entities = messages.associate { message ->
            val normalized = if (message.sessionId == sessionId) message else message.copy(sessionId = sessionId)
            messageStorageId(sessionId, normalized.id) to normalized
        }
        deleteMessagesForSession(sessionId)
        storage.saveAll(MESSAGE_TYPE, entities, ChatMessage.serializer())
        getSession(sessionId)?.let { session ->
            val nextSource = messages.mapNotNull { it.sourceTurnOrder }.maxOrNull()?.plus(1) ?: 1
            val nextLegacy = messages.mapNotNull { it.timelineTurn }.maxOrNull()?.plus(1) ?: nextSource
            updateSession(
                session.copy(
                    nextSourceTurnOrder = maxOf(session.nextSourceTurnOrder, nextSource),
                    nextTimelineTurn = maxOf(session.nextTimelineTurn, nextLegacy)
                )
            )
        }
    }

    /** 旧消息首次使用时补稳定source turn；不改消息ID、时间、orderKey。 */
    suspend fun ensureSourceTurns(sessionId: String): List<ChatMessage> = messageAppendMutex.withLock {
        val messages = getMessages(sessionId)
        val session = getSession(sessionId) ?: return@withLock messages
        if (messages.none {
                it.role != MessageRole.SYSTEM &&
                    (it.sourceTurnId == null || it.sourceTurnOrder == null)
            }
        ) {
            val next = messages.mapNotNull { it.sourceTurnOrder }.maxOrNull()?.plus(1) ?: 1
            if (session.nextSourceTurnOrder < next) {
                updateSession(session.copy(nextSourceTurnOrder = next))
            }
            return@withLock messages
        }

        val result = TimelineTurnPolicy.migrate(
            messages = messages,
            initialNextTurn = session.nextTimelineTurn,
            initialNextSourceTurnOrder = session.nextSourceTurnOrder
        )
        val migrated = result.messages
        migrated.forEach { saveMessageRecord(it) }
        updateSession(
            session.copy(
                nextTimelineTurn = result.nextTimelineTurn,
                nextSourceTurnOrder = result.nextSourceTurnOrder
            )
        )
        migrated
    }

    /** v2草稿调用兼容。 */
    suspend fun ensureTimelineTurns(sessionId: String): List<ChatMessage> = ensureSourceTurns(sessionId)

    private suspend fun assignSourceTurnForAppend(message: ChatMessage): ChatMessage {
        if (message.role == MessageRole.SYSTEM) return message
        val session = getSession(message.sessionId) ?: return message
        val messages = getMessages(message.sessionId)
        val assignment = TimelineTurnPolicy.nextForAppend(
            message = message,
            existingMessages = messages,
            nextSourceTurnOrder = session.nextSourceTurnOrder,
            tombstones = session.sourceTurnTombstones,
            newSourceTurnId = UUID.randomUUID().toString()
        )
        if (assignment != null && assignment.sourceTurnOrder >= session.nextSourceTurnOrder) {
            updateSession(
                session.copy(
                    nextSourceTurnOrder = maxOf(
                        session.nextSourceTurnOrder,
                        assignment.sourceTurnOrder + 1
                    ),
                    nextTimelineTurn = maxOf(
                        session.nextTimelineTurn,
                        assignment.sourceTurnOrder + 1
                    )
                )
            )
        }
        return message.copy(
            sourceTurnId = assignment?.sourceTurnId,
            sourceTurnOrder = assignment?.sourceTurnOrder,
            timelineTurn = message.timelineTurn ?: assignment?.sourceTurnOrder
        )
    }

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

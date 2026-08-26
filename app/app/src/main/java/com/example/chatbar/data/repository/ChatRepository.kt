package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatDraft
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatMessageOrderBackup
import com.example.chatbar.data.local.entity.ChatMessageOrderBackupEntry
import com.example.chatbar.data.local.entity.ChatScrollPosition
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.domain.chat.ChatMessageOrdering
import com.example.chatbar.domain.chat.ChatMessageOrderRepairPlan
import com.example.chatbar.domain.chat.ChatMessageOrderRepairPolicy
import com.example.chatbar.domain.chat.ChatMessageOrderSnapshot
import com.example.chatbar.domain.chat.SessionDisplayTitlePolicy
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
        private const val MESSAGE_ORDER_BACKUP_TYPE = "chat_message_order_backups"
        private const val DRAFT_TYPE = "chat_drafts"
        private const val SCROLL_POSITION_TYPE = "chat_scroll_positions"
    }

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: Flow<List<ChatSession>> = _sessions.asStateFlow()

    private var initialized = false
    private val messageAppendMutex = Mutex()
    private val scrollPositionMutex = Mutex()

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

    suspend fun getScrollPosition(sessionId: String): ChatScrollPosition? {
        return storage.loadEntity(
            SCROLL_POSITION_TYPE,
            sessionId,
            ChatScrollPosition.serializer()
        )
    }

    suspend fun updateScrollPosition(position: ChatScrollPosition) =
        scrollPositionMutex.withLock {
            if (getSession(position.sessionId) == null) return@withLock
            val current = getScrollPosition(position.sessionId)
            if (current != null && current.capturedAt >= position.capturedAt) return@withLock
            storage.saveEntity(
                SCROLL_POSITION_TYPE,
                position.sessionId,
                position,
                ChatScrollPosition.serializer()
            )
        }

    suspend fun deleteScrollPosition(sessionId: String) =
        scrollPositionMutex.withLock {
            storage.deleteEntity<ChatScrollPosition>(SCROLL_POSITION_TYPE, sessionId)
        }

    suspend fun deleteSession(id: String) {
        deleteSessionRecord(id)
        deleteMessagesForSession(id)
    }

    suspend fun deleteSessionRecord(id: String) {
        storage.deleteEntity<ChatSession>(SESSION_TYPE, id)
        deleteSessionDraft(id)
        deleteScrollPosition(id)
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

    suspend fun updateSessionDisplayTitle(id: String, displayTitle: String?) {
        val normalized = SessionDisplayTitlePolicy.normalize(displayTitle)
        getSession(id)?.let { session ->
            if (session.displayTitleOverride != normalized) {
                updateSession(session.copy(displayTitleOverride = normalized))
            }
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
        val existingMessages = getMessages(message.sessionId)
        val anchor = getMessage(anchorMessageId, message.sessionId)
        val assigned = message.copy(
            sourceTurnId = message.sourceTurnId ?: anchor?.sourceTurnId,
            sourceTurnOrder = message.sourceTurnOrder ?: anchor?.sourceTurnOrder,
            timelineTurn = message.timelineTurn ?: anchor?.timelineTurn
        )
        val reordered = ChatMessageOrdering.insertGeneratedImageAfter(
            messages = existingMessages,
            imageMessage = assigned,
            anchorMessageId = anchorMessageId
        )
        val inserted = reordered.first { it.id == message.id }
        val existingById = existingMessages.associateBy(ChatMessage::id)
        reordered.forEach { reorderedMessage ->
            if (reorderedMessage != existingById[reorderedMessage.id]) {
                saveMessageRecord(reorderedMessage)
            }
        }

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

    suspend fun updateMessage(message: ChatMessage) = messageAppendMutex.withLock {
        val persisted = getMessage(message.id, message.sessionId)
        val updated = message.copy(
            updatedAt = System.currentTimeMillis(),
            orderKey = persisted?.orderKey ?: message.orderKey
        )
        saveMessageRecord(updated)

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

    suspend fun previewMessageOrderRepair(sessionId: String): ChatMessageOrderRepairPlan =
        messageAppendMutex.withLock {
            ChatMessageOrderRepairPolicy.plan(getMessages(sessionId))
        }

    suspend fun repairMessageOrder(
        sessionId: String,
        expectedBaseline: List<ChatMessageOrderSnapshot>
    ): ChatMessageOrderRepairPlan = messageAppendMutex.withLock {
        val current = getMessages(sessionId)
        val plan = ChatMessageOrderRepairPolicy.plan(current)
        check(plan.baseline == expectedBaseline) {
            "预览后聊天内容已变化，请重新生成修复预览"
        }
        if (!plan.requiresRepair) return@withLock plan

        storage.saveEntity(
            MESSAGE_ORDER_BACKUP_TYPE,
            sessionId,
            ChatMessageOrderBackup(
                sessionId = sessionId,
                createdAt = System.currentTimeMillis(),
                entries = current.map { message ->
                    ChatMessageOrderBackupEntry(message.id, message.orderKey, message.updatedAt)
                }
            ),
            ChatMessageOrderBackup.serializer()
        )
        persistMessageOrder(sessionId, current, plan.repairedMessages)
        plan
    }

    suspend fun hasMessageOrderBackup(sessionId: String): Boolean =
        storage.exists(MESSAGE_ORDER_BACKUP_TYPE, sessionId)

    suspend fun restoreMessageOrderBackup(sessionId: String): List<ChatMessage> =
        messageAppendMutex.withLock {
            val backup = storage.loadEntity(
                MESSAGE_ORDER_BACKUP_TYPE,
                sessionId,
                ChatMessageOrderBackup.serializer()
            ) ?: error("当前会话没有可撤销的顺序修复")
            val current = getMessages(sessionId)
            val backupEntries = backup.entries.associateBy(ChatMessageOrderBackupEntry::messageId)
            check(
                backup.entries.size == backupEntries.size &&
                    current.map { it.id }.toSet() == backupEntries.keys &&
                    current.all { message -> backupEntries.getValue(message.id).updatedAt == message.updatedAt }
            ) {
                "修复后聊天内容已变化，无法安全撤销"
            }
            val restored = current.map { message ->
                message.copy(orderKey = backupEntries.getValue(message.id).orderKey)
            }.sortedWith(ChatMessage.TimelineComparator)
            persistMessageOrder(sessionId, current, restored)
            storage.deleteEntity<ChatMessageOrderBackup>(MESSAGE_ORDER_BACKUP_TYPE, sessionId)
            restored
        }

    private suspend fun persistMessageOrder(
        sessionId: String,
        current: List<ChatMessage>,
        reordered: List<ChatMessage>
    ) {
        val currentById = current.associateBy(ChatMessage::id)
        reordered.forEach { message ->
            if (currentById[message.id]?.orderKey != message.orderKey) {
                saveMessageRecord(message)
            }
        }
        val latest = reordered.sortedWith(ChatMessage.TimelineComparator).lastOrNull()
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

    suspend fun deleteMessage(messageId: String, sessionId: String) = messageAppendMutex.withLock {
        val removed = getMessage(messageId, sessionId)
        storage.deleteEntity<ChatMessage>(MESSAGE_TYPE, messageStorageId(sessionId, messageId))
        val remaining = getMessages(sessionId)
        val latest = remaining.lastOrNull()
        getSession(sessionId)?.let { session ->
            val removedSourceId = removed?.sourceTurnId
            val removedSourceOrder = removed?.sourceTurnOrder
            val sourceTombstones = if (
                removed?.role != MessageRole.SYSTEM &&
                removedSourceId != null && removedSourceOrder != null &&
                remaining.none {
                    it.role != MessageRole.SYSTEM && it.sourceTurnId == removedSourceId
                }
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

    suspend fun deleteMessagesForSession(sessionId: String): Int {
        val deleted = storage.deleteByIdPrefix<ChatMessage>(MESSAGE_TYPE, "${sessionId}_")
        storage.deleteEntity<ChatMessageOrderBackup>(MESSAGE_ORDER_BACKUP_TYPE, sessionId)
        return deleted
    }

    /** 批量替换会话消息，避免逐条更新会话预览与反复全量加载。 */
    suspend fun replaceMessagesForSession(sessionId: String, messages: List<ChatMessage>) {
        val entities = messages.associate { message ->
            val normalized = if (message.sessionId == sessionId) message else message.copy(sessionId = sessionId)
            messageStorageId(sessionId, normalized.id) to normalized
        }
        deleteMessagesForSession(sessionId)
        deleteScrollPosition(sessionId)
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

    /**
     * 角色卡改名后同步会话显示名称：把绑定该卡的所有会话标题中出现的旧名改写为新名。
     * 这样首页会话列表与聊天页标题展示的角色卡名字能与角色卡保持一致。
     */
    suspend fun rewriteSessionTitlesForCharacterCard(
        characterCardId: String,
        oldName: String,
        newName: String
    ): Int {
        val from = oldName.trim()
        val to = newName.trim()
        if (from.isEmpty() || to.isEmpty() || from == to) return 0
        initialize()
        var updatedCount = 0
        _sessions.value.filter { it.characterCardId == characterCardId }.forEach { session ->
            val title = session.title.replace(from, to)
            if (title != session.title) {
                updatedCount++
                updateSession(session.copy(title = title))
            }
        }
        return updatedCount
    }

    /** 搜索会话 */
    suspend fun searchSessions(query: String): List<ChatSession> {
        return getAllSessions().filter { session ->
            session.title.contains(query, ignoreCase = true) ||
                session.displayTitleOverride?.contains(query, ignoreCase = true) == true
        }
    }
}

private fun ChatMessage.previewText(): String =
    displayContent.takeIf { it.isNotBlank() }?.take(100)
        ?: if (images.isNotEmpty()) "[图片]" else ""

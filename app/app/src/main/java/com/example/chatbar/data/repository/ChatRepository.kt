package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatDraft
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatMessageIndex
import com.example.chatbar.data.local.entity.ChatMessageIndexEntry
import com.example.chatbar.data.local.entity.ChatMessagePage
import com.example.chatbar.data.local.entity.ChatMessageOrderBackup
import com.example.chatbar.data.local.entity.ChatMessageOrderBackupEntry
import com.example.chatbar.data.local.entity.ChatScrollPosition
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.data.local.entity.toIndexEntry
import com.example.chatbar.domain.chat.ChatMessageOrdering
import com.example.chatbar.domain.chat.ChatMessageOrderRepairPlan
import com.example.chatbar.domain.chat.ChatMessageOrderRepairPolicy
import com.example.chatbar.domain.chat.ChatMessageOrderSnapshot
import com.example.chatbar.domain.chat.SessionDisplayTitlePolicy
import com.example.chatbar.domain.chat.TimelineTurnPolicy
import com.example.chatbar.domain.chat.renameRoleplaySpeakerMarkers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        private const val MESSAGE_INDEX_TYPE = "chat_message_indexes"
        private const val MESSAGE_ORDER_BACKUP_TYPE = "chat_message_order_backups"
        private const val DRAFT_TYPE = "chat_drafts"
        private const val SCROLL_POSITION_TYPE = "chat_scroll_positions"
        private const val INITIAL_WINDOW_TURNS = 80
        private const val PAGE_TURNS = 40
        const val MAX_WINDOW_TURNS = 120
    }

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: Flow<List<ChatSession>> = _sessions.asStateFlow()

    private var initialized = false
    private val messageAppendMutex = Mutex()
    private val messageIndexMutex = Mutex()
    private val messageIndexes = ConcurrentHashMap<String, ChatMessageIndex>()
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
        val index = loadMessageIndex(sessionId)
        return loadIndexedMessages(sessionId, index.entries)
    }

    /** 顺序分批读取会话消息；调用方每次只需持有一个小批次。 */
    suspend fun forEachMessage(
        sessionId: String,
        batchSize: Int = 32,
        action: suspend (ChatMessage) -> Unit
    ) {
        require(batchSize > 0)
        val index = loadMessageIndex(sessionId)
        index.entries.chunked(batchSize).forEach { entries ->
            val messages = loadIndexedMessages(sessionId, entries)
            check(messages.size == entries.size) { "聊天消息文件缺失，无法完整读取会话" }
            messages.forEach { action(it) }
        }
    }

    suspend fun getInitialMessagePage(
        sessionId: String,
        anchorMessageId: String? = null
    ): ChatMessagePage {
        val index = loadMessageIndex(sessionId)
        val groups = index.entries.turnGroups()
        if (groups.isEmpty()) return ChatMessagePage(emptyList(), false, false, 0)
        val anchorGroup = anchorMessageId
            ?.let { id ->
                groups.indexOfFirst { range -> range.any { index.entries[it].messageId == id } }
            }
            ?.takeIf { it >= 0 }
        val startGroup: Int
        val endGroup: Int
        if (anchorGroup == null) {
            startGroup = (groups.size - INITIAL_WINDOW_TURNS).coerceAtLeast(0)
            endGroup = groups.lastIndex
        } else {
            val latestStart = (groups.size - MAX_WINDOW_TURNS).coerceAtLeast(0)
            startGroup = (anchorGroup - (MAX_WINDOW_TURNS / 2 - 1))
                .coerceIn(0, latestStart)
            endGroup = (startGroup + MAX_WINDOW_TURNS - 1).coerceAtMost(groups.lastIndex)
        }
        return pageForGroups(sessionId, index, groups, startGroup, endGroup)
    }

    suspend fun getOlderMessagePage(
        sessionId: String,
        beforeMessageId: String
    ): ChatMessagePage = directionalPage(sessionId, beforeMessageId, older = true)

    suspend fun getNewerMessagePage(
        sessionId: String,
        afterMessageId: String
    ): ChatMessagePage = directionalPage(sessionId, afterMessageId, older = false)

    private suspend fun directionalPage(
        sessionId: String,
        boundaryMessageId: String,
        older: Boolean
    ): ChatMessagePage {
        val index = loadMessageIndex(sessionId)
        val groups = index.entries.turnGroups()
        if (groups.isEmpty()) return ChatMessagePage(emptyList(), false, false, 0)
        val boundaryGroup = groups.indexOfFirst { range ->
            range.any { index.entries[it].messageId == boundaryMessageId }
        }.takeIf { it >= 0 } ?: return getInitialMessagePage(sessionId)
        val startGroup: Int
        val endGroup: Int
        if (older) {
            endGroup = boundaryGroup - 1
            if (endGroup < 0) return ChatMessagePage(emptyList(), false, true, index.entries.size)
            startGroup = (endGroup - PAGE_TURNS + 1).coerceAtLeast(0)
        } else {
            startGroup = boundaryGroup + 1
            if (startGroup > groups.lastIndex) {
                return ChatMessagePage(emptyList(), true, false, index.entries.size)
            }
            endGroup = (startGroup + PAGE_TURNS - 1).coerceAtMost(groups.lastIndex)
        }
        return pageForGroups(sessionId, index, groups, startGroup, endGroup)
    }

    private suspend fun pageForGroups(
        sessionId: String,
        index: ChatMessageIndex,
        groups: List<IntRange>,
        startGroup: Int,
        endGroup: Int
    ): ChatMessagePage {
        val entryStart = groups[startGroup].first
        val entryEnd = groups[endGroup].last
        return ChatMessagePage(
            messages = loadIndexedMessages(sessionId, index.entries.subList(entryStart, entryEnd + 1)),
            hasOlder = startGroup > 0,
            hasNewer = endGroup < groups.lastIndex,
            totalMessageCount = index.entries.size
        )
    }

    private suspend fun loadIndexedMessages(
        sessionId: String,
        entries: List<ChatMessageIndexEntry>
    ): List<ChatMessage> = storage.loadByIdsUncached(
        MESSAGE_TYPE,
        entries.map { messageStorageId(sessionId, it.messageId) },
        ChatMessage.serializer()
    ).sortedWith(ChatMessage.TimelineComparator)

    private suspend fun loadMessageIndex(sessionId: String): ChatMessageIndex {
        messageIndexes[sessionId]?.let { return it }
        return messageIndexMutex.withLock {
            messageIndexes[sessionId]?.let { return@withLock it }
            val prefix = "${sessionId}_"
            val signature = storage.fileSetSignatureByIdPrefix(MESSAGE_TYPE, prefix)
            val stored = storage.loadEntity(
                MESSAGE_INDEX_TYPE,
                sessionId,
                ChatMessageIndex.serializer()
            )
            if (
                stored != null &&
                stored.fileCount == signature.count &&
                (stored.fileFingerprint == 0L || stored.fileFingerprint == signature.fingerprint)
            ) {
                return@withLock stored.copy(entries = stored.entries.sortedWith(indexComparator))
            }
            rebuildMessageIndexLocked(sessionId)
        }
    }

    private suspend fun rebuildMessageIndexLocked(sessionId: String): ChatMessageIndex {
        val prefix = "${sessionId}_"
        val entries = storage.mapByIdPrefixUncached(
            MESSAGE_TYPE,
            prefix,
            ChatMessage.serializer(),
            ChatMessage::toIndexEntry
        ).sortedWith(indexComparator)
        return writeMessageIndexLocked(sessionId, entries)
    }

    private suspend fun writeMessageIndexLocked(
        sessionId: String,
        entries: List<ChatMessageIndexEntry>
    ): ChatMessageIndex {
        val index = ChatMessageIndex(
            sessionId = sessionId,
            entries = entries.sortedWith(indexComparator),
            fileCount = entries.size,
            // App-owned mutations always update this index. 0 表示下次启动只需核对文件数，
            // 避免每次新增消息都 O(n) 扫描整个长会话。
            fileFingerprint = 0L,
            updatedAt = System.currentTimeMillis()
        )
        storage.saveEntityUncached(
            MESSAGE_INDEX_TYPE,
            sessionId,
            index,
            ChatMessageIndex.serializer()
        )
        messageIndexes[sessionId] = index
        return index
    }

    private suspend fun updateMessageIndex(
        sessionId: String,
        base: ChatMessageIndex,
        transform: (List<ChatMessageIndexEntry>) -> List<ChatMessageIndexEntry>
    ) = messageIndexMutex.withLock {
        writeMessageIndexLocked(sessionId, transform(base.entries))
    }

    private val indexComparator: Comparator<ChatMessageIndexEntry>
        get() = compareBy<ChatMessageIndexEntry> { it.orderKey }
            .thenBy { it.createdAt }
            .thenBy { it.messageId }

    private fun List<ChatMessageIndexEntry>.turnGroups(): List<IntRange> {
        if (isEmpty()) return emptyList()
        val groups = mutableListOf<IntRange>()
        var index = 0
        while (index < size) {
            val start = index
            val first = this[index]
            val stableKey = if (first.role == MessageRole.SYSTEM) {
                asSequence().drop(index + 1).firstOrNull { it.role != MessageRole.SYSTEM }
                    ?.stableTurnKey()
            } else {
                first.stableTurnKey()
            }
            if (stableKey != null) {
                index++
                while (index < size) {
                    val next = this[index]
                    if (next.role == MessageRole.SYSTEM || next.stableTurnKey() == stableKey) {
                        index++
                    } else {
                        break
                    }
                }
            } else if (
                first.role == MessageRole.USER &&
                getOrNull(index + 1)?.role == MessageRole.ASSISTANT
            ) {
                index += 2
            } else {
                index++
            }
            groups += start..(index - 1)
        }
        return groups
    }

    private fun ChatMessageIndexEntry.stableTurnKey(): String? = when {
        !sourceTurnId.isNullOrBlank() -> "source:$sourceTurnId"
        sourceTurnOrder != null -> "source-order:$sourceTurnOrder"
        timelineTurn != null -> "timeline:$timelineTurn"
        else -> null
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
        val latest = latestMessage(assigned.sessionId)
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
        val index = loadMessageIndex(message.sessionId)
        val anchorIndex = index.entries.indexOfFirst { it.messageId == anchorMessageId }
        var insertIndex = if (anchorIndex < 0) index.entries.size else anchorIndex + 1
        while (
            insertIndex < index.entries.size &&
            index.entries[insertIndex].generatedFromMessageId == anchorMessageId
        ) {
            insertIndex++
        }
        val previousOrder = index.entries.getOrNull(insertIndex - 1)?.orderKey
        val nextOrder = index.entries.getOrNull(insertIndex)?.orderKey
        val availableOrder = when {
            previousOrder == null -> index.entries.firstOrNull()?.orderKey?.minus(1)
                ?: com.example.chatbar.data.local.entity.MESSAGE_ORDER_STEP
            previousOrder == Long.MAX_VALUE -> null
            nextOrder == null || previousOrder + 1 < nextOrder -> previousOrder + 1
            else -> null
        }
        val inserted = if (availableOrder != null) {
            assigned.copy(
                generatedFromMessageId = anchorMessageId,
                orderKey = availableOrder
            ).also { saveMessageRecord(it) }
        } else {
            val existingMessages = getMessages(message.sessionId)
            val reordered = ChatMessageOrdering.insertGeneratedImageAfter(
                messages = existingMessages,
                imageMessage = assigned,
                anchorMessageId = anchorMessageId
            )
            val existingById = existingMessages.associateBy(ChatMessage::id)
            reordered.forEach { reorderedMessage ->
                if (reorderedMessage != existingById[reorderedMessage.id]) {
                    saveMessageRecord(reorderedMessage)
                }
            }
            reordered.first { it.id == message.id }
        }

        val latest = latestMessage(message.sessionId)
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

    private suspend fun saveMessageRecord(message: ChatMessage, updateIndex: Boolean = true) {
        val index = if (updateIndex) loadMessageIndex(message.sessionId) else null
        storage.saveEntityUncached(
            MESSAGE_TYPE,
            messageStorageId(message.sessionId, message.id),
            message,
            ChatMessage.serializer()
        )
        if (index != null) {
            updateMessageIndex(message.sessionId, index) { entries ->
                (entries.filterNot { it.messageId == message.id } + message.toIndexEntry())
                    .sortedWith(indexComparator)
            }
        }
    }

    suspend fun updateMessage(message: ChatMessage) = messageAppendMutex.withLock {
        val persisted = getMessage(message.id, message.sessionId)
        val updated = message.copy(
            updatedAt = System.currentTimeMillis(),
            orderKey = persisted?.orderKey ?: message.orderKey
        )
        saveMessageRecord(updated)

        val latest = latestMessage(updated.sessionId)
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

    private suspend fun latestMessage(sessionId: String): ChatMessage? {
        val latest = loadMessageIndex(sessionId).entries.lastOrNull() ?: return null
        return getMessage(latest.messageId, sessionId)
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
        val index = loadMessageIndex(sessionId)
        storage.deleteEntityUncached(MESSAGE_TYPE, messageStorageId(sessionId, messageId))
        val remainingEntries = index.entries.filterNot { it.messageId == messageId }
        updateMessageIndex(sessionId, index) { remainingEntries }
        val latest = remainingEntries.lastOrNull()?.let { getMessage(it.messageId, sessionId) }
        getSession(sessionId)?.let { session ->
            val removedSourceId = removed?.sourceTurnId
            val removedSourceOrder = removed?.sourceTurnOrder
            val sourceTombstones = if (
                removed?.role != MessageRole.SYSTEM &&
                removedSourceId != null && removedSourceOrder != null &&
                remainingEntries.none {
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
                removedLegacyTurn != null && remainingEntries.none { it.timelineTurn == removedLegacyTurn }
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
        val deleted = storage.deleteByIdPrefixUncached(MESSAGE_TYPE, "${sessionId}_")
        storage.deleteEntityUncached(MESSAGE_INDEX_TYPE, sessionId)
        messageIndexes.remove(sessionId)
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
        storage.saveAllUncached(MESSAGE_TYPE, entities, ChatMessage.serializer())
        messageIndexMutex.withLock {
            writeMessageIndexLocked(
                sessionId,
                entities.values.map(ChatMessage::toIndexEntry).sortedWith(indexComparator)
            )
        }
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

    /**
     * 流式替换会话消息。新消息先逐条落到暂存目录，完整成功后再切换；
     * 不把旧会话或新会话的完整正文集合放入内存。
     */
    suspend fun replaceMessagesForSessionStreaming(
        sessionId: String,
        producer: suspend (emit: suspend (ChatMessage) -> Unit) -> Unit
    ) = messageAppendMutex.withLock {
        val entries = mutableListOf<ChatMessageIndexEntry>()
        var latest: ChatMessage? = null
        var maxSourceOrder = 0L
        var maxTimelineTurn = 0L
        storage.replaceByIdPrefixStreamingUncached(
            entityType = MESSAGE_TYPE,
            prefix = "${sessionId}_",
            serializer = ChatMessage.serializer()
        ) { emit ->
            producer { message ->
                val normalized = if (message.sessionId == sessionId) {
                    message
                } else {
                    message.copy(sessionId = sessionId)
                }
                emit(messageStorageId(sessionId, normalized.id), normalized)
                entries += normalized.toIndexEntry()
                if (latest == null || ChatMessage.TimelineComparator.compare(latest, normalized) < 0) {
                    latest = normalized
                }
                maxSourceOrder = maxOf(maxSourceOrder, normalized.sourceTurnOrder ?: 0L)
                maxTimelineTurn = maxOf(maxTimelineTurn, normalized.timelineTurn ?: 0L)
            }
        }
        deleteScrollPosition(sessionId)
        storage.deleteEntity<ChatMessageOrderBackup>(MESSAGE_ORDER_BACKUP_TYPE, sessionId)
        messageIndexMutex.withLock {
            writeMessageIndexLocked(sessionId, entries.sortedWith(indexComparator))
        }
        getSession(sessionId)?.let { session ->
            updateSession(
                session.copy(
                    lastMessagePreview = latest?.previewText(),
                    lastMessageTime = latest?.createdAt,
                    lastMessageRole = latest?.role,
                    nextSourceTurnOrder = maxOf(session.nextSourceTurnOrder, maxSourceOrder + 1),
                    nextTimelineTurn = maxOf(session.nextTimelineTurn, maxTimelineTurn + 1)
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
        migrated.forEach { saveMessageRecord(it, updateIndex = false) }
        messageIndexMutex.withLock { rebuildMessageIndexLocked(sessionId) }
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
        val index = loadMessageIndex(message.sessionId)
        val recentEntries = index.entries.takeLast(PAGE_TURNS * 4)
        val messages = loadIndexedMessages(message.sessionId, recentEntries)
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
        if (count <= 0) return emptyList()
        val index = loadMessageIndex(sessionId)
        return loadIndexedMessages(sessionId, index.entries.takeLast(count))
    }

    /** 最近若干完整 source turn，可附带尚未归档的旧 source turn。 */
    suspend fun getContextCandidateMessages(
        sessionId: String,
        recentTurnCount: Int,
        includeSourceTurnIds: Set<String> = emptySet()
    ): List<ChatMessage> {
        val index = loadMessageIndex(sessionId)
        val firstRecentIndex = index.entries.turnGroups()
            .filter { range -> range.any { index.entries[it].role != MessageRole.SYSTEM } }
            .takeLast(recentTurnCount.coerceAtLeast(1))
            .firstOrNull()
            ?.first
            ?: index.entries.size
        val recentEntries = index.entries.drop(firstRecentIndex)
        val requiredEntries = if (includeSourceTurnIds.isEmpty()) {
            recentEntries
        } else {
            (recentEntries + index.entries.filter { it.sourceTurnId in includeSourceTurnIds })
                .distinctBy(ChatMessageIndexEntry::messageId)
                .sortedWith(indexComparator)
        }
        return loadIndexedMessages(sessionId, requiredEntries)
    }

    /** 仅从轻量索引读取活消息 ID，供 RAG 孤儿清理。 */
    suspend fun getMessageIds(sessionId: String): Set<String> =
        loadMessageIndex(sessionId).entries.mapTo(linkedSetOf()) { it.messageId }

    suspend fun getMessagesForSourceTurn(
        sessionId: String,
        sourceTurnId: String
    ): List<ChatMessage> {
        val entries = loadMessageIndex(sessionId).entries
            .filter { it.sourceTurnId == sourceTurnId }
        return loadIndexedMessages(sessionId, entries)
    }

    suspend fun getFirstMessageIdForSourceTurn(
        sessionId: String,
        sourceTurnId: String
    ): String? = loadMessageIndex(sessionId).entries
        .firstOrNull { it.sourceTurnId == sourceTurnId }
        ?.messageId

    /** 轻量估算稳定 source turn 数；只用于决定是否启用历史检索。 */
    suspend fun getMessageTurnCount(sessionId: String): Int =
        loadMessageIndex(sessionId).entries.let { entries ->
            entries.turnGroups().count { range ->
                range.any { entries[it].role != MessageRole.SYSTEM }
            }
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

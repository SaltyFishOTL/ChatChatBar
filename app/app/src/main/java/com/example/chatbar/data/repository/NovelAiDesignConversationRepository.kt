package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.NovelAiDesignConversation
import com.example.chatbar.domain.image.NovelAiDesignCurrentState
import com.example.chatbar.domain.image.NovelAiDesignReply
import com.example.chatbar.domain.image.NovelAiDesignResearchSnapshot
import com.example.chatbar.domain.image.NovelAiDesignTurn
import com.example.chatbar.domain.image.NovelAiDesignTurnStatus
import com.example.chatbar.domain.image.NovelAiImageModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NovelAiDesignConversationRepository(
    private val storage: JsonFileStorage
) {
    private val mutex = Mutex()
    private val _conversations = MutableStateFlow<List<NovelAiDesignConversation>>(emptyList())
    val conversations: StateFlow<List<NovelAiDesignConversation>> = _conversations.asStateFlow()
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()
    private var initialized = false

    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        val loaded = storage.loadAll(CONVERSATION_ENTITY, NovelAiDesignConversation.serializer())
        val repaired = loaded.map { conversation ->
            val turns = conversation.turns.map { turn ->
                if (turn.status == NovelAiDesignTurnStatus.PENDING) {
                    turn.copy(
                        status = NovelAiDesignTurnStatus.FAILED,
                        error = INTERRUPTED_ERROR,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    turn
                }
            }
            if (turns == conversation.turns) conversation else conversation.copy(turns = turns)
        }
        repaired.filter { it !in loaded }.forEach { conversation ->
            storage.saveEntity(
                CONVERSATION_ENTITY,
                conversation.id,
                conversation,
                NovelAiDesignConversation.serializer()
            )
        }
        val savedCurrentId = storage.loadSingleton(
            CURRENT_ENTITY,
            NovelAiDesignCurrentState.serializer()
        )?.currentConversationId
        val currentId = savedCurrentId?.takeIf { id -> repaired.any { it.id == id } }
            ?: repaired.maxByOrNull(NovelAiDesignConversation::updatedAt)?.id
        _conversations.value = repaired.sortedByDescending(NovelAiDesignConversation::updatedAt)
        _currentConversationId.value = currentId
        storage.saveSingleton(
            CURRENT_ENTITY,
            NovelAiDesignCurrentState(currentId),
            NovelAiDesignCurrentState.serializer()
        )
        initialized = true
        pruneHistoryLocked()
    }

    fun currentConversation(): NovelAiDesignConversation? {
        val currentId = _currentConversationId.value ?: return null
        return _conversations.value.firstOrNull { it.id == currentId }
    }

    fun history(): List<NovelAiDesignConversation> {
        val currentId = _currentConversationId.value
        return _conversations.value
            .asSequence()
            .filterNot { it.id == currentId }
            .sortedByDescending(NovelAiDesignConversation::updatedAt)
            .toList()
    }

    suspend fun createCurrentConversation(
        userText: String,
        designModelId: String,
        targetImageModel: NovelAiImageModel
    ): Pair<NovelAiDesignConversation, NovelAiDesignTurn> =
        mutex.withLock {
            require(userText.isNotBlank()) { "请输入画面内容" }
            val now = nextTimestampLocked()
            val turn = NovelAiDesignTurn(
                userText = userText.trim(),
                designModelId = designModelId,
                targetImageModel = targetImageModel,
                createdAt = now,
                updatedAt = now
            )
            val conversation = NovelAiDesignConversation(
                title = NovelAiDesignConversation.titleFrom(userText),
                turns = listOf(turn),
                createdAt = now,
                updatedAt = now
            )
            saveConversationLocked(conversation)
            try {
                saveCurrentLocked(conversation.id)
            } catch (error: Throwable) {
                try {
                    withContext(NonCancellable) {
                        storage.deleteEntity<NovelAiDesignConversation>(CONVERSATION_ENTITY, conversation.id)
                    }
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                _conversations.value = _conversations.value.filterNot { it.id == conversation.id }
                throw error
            }
            pruneHistoryLocked()
            conversation to turn
        }

    suspend fun appendPendingTurn(
        conversationId: String,
        userText: String,
        designModelId: String,
        targetImageModel: NovelAiImageModel
    ): NovelAiDesignTurn = mutex.withLock {
        require(userText.isNotBlank()) { "请输入修改需求" }
        val conversation = requireConversationLocked(conversationId)
        require(!conversation.hasBlockingTurn) { "请先重试或完成上一条修改需求" }
        val now = nextTimestampLocked()
        val turn = NovelAiDesignTurn(
            userText = userText.trim(),
            designModelId = designModelId,
            targetImageModel = targetImageModel,
            createdAt = now,
            updatedAt = now
        )
        saveConversationLocked(
            conversation.copy(turns = conversation.turns + turn, updatedAt = now)
        )
        turn
    }

    suspend fun markTurnPending(
        conversationId: String,
        turnId: String,
        designModelId: String,
        targetImageModel: NovelAiImageModel
    ): NovelAiDesignTurn =
        updateTurn(conversationId, turnId) { turn ->
            turn.copy(
                designModelId = designModelId,
                targetImageModel = targetImageModel,
                status = NovelAiDesignTurnStatus.PENDING,
                error = "",
                updatedAt = nextTimestampLocked()
            )
        }

    suspend fun completeTurn(
        conversationId: String,
        turnId: String,
        reply: NovelAiDesignReply,
        initialResearch: NovelAiDesignResearchSnapshot? = null
    ) = mutex.withLock {
        val conversation = requireConversationLocked(conversationId)
        val now = nextTimestampLocked()
        val turns = conversation.turns.map { turn ->
            if (turn.id == turnId) {
                turn.copy(
                    reply = reply,
                    status = NovelAiDesignTurnStatus.COMPLETED,
                    error = "",
                    updatedAt = now
                )
            } else {
                turn
            }
        }
        require(turns != conversation.turns) { "AI 设计轮次不存在" }
        saveConversationLocked(
            conversation.copy(
                turns = turns,
                initialResearch = conversation.initialResearch ?: initialResearch,
                updatedAt = now
            )
        )
    }

    suspend fun failTurn(
        conversationId: String,
        turnId: String,
        error: String,
        cancelled: Boolean = false
    ) = updateTurn(conversationId, turnId) { turn ->
        turn.copy(
            status = if (cancelled) NovelAiDesignTurnStatus.CANCELLED else NovelAiDesignTurnStatus.FAILED,
            error = error.ifBlank { if (cancelled) "已停止生成" else "AI 设计失败" },
            updatedAt = nextTimestampLocked()
        )
    }

    suspend fun switchCurrent(conversationId: String) = mutex.withLock {
        requireConversationLocked(conversationId)
        saveCurrentLocked(conversationId)
    }

    private suspend fun updateTurn(
        conversationId: String,
        turnId: String,
        transform: (NovelAiDesignTurn) -> NovelAiDesignTurn
    ): NovelAiDesignTurn = mutex.withLock {
        val conversation = requireConversationLocked(conversationId)
        var updated: NovelAiDesignTurn? = null
        val turns = conversation.turns.map { turn ->
            if (turn.id == turnId) transform(turn).also { updated = it } else turn
        }
        val result = requireNotNull(updated) { "AI 设计轮次不存在" }
        saveConversationLocked(conversation.copy(turns = turns, updatedAt = result.updatedAt))
        result
    }

    private fun requireConversationLocked(id: String): NovelAiDesignConversation =
        requireNotNull(_conversations.value.firstOrNull { it.id == id }) { "AI 设计会话不存在" }

    private fun nextTimestampLocked(): Long = maxOf(
        System.currentTimeMillis(),
        (_conversations.value.maxOfOrNull(NovelAiDesignConversation::updatedAt) ?: 0L) + 1L
    )

    private suspend fun saveConversationLocked(conversation: NovelAiDesignConversation) {
        storage.saveEntity(
            CONVERSATION_ENTITY,
            conversation.id,
            conversation,
            NovelAiDesignConversation.serializer()
        )
        _conversations.value = (_conversations.value.filterNot { it.id == conversation.id } + conversation)
            .sortedByDescending(NovelAiDesignConversation::updatedAt)
    }

    private suspend fun saveCurrentLocked(id: String?) {
        storage.saveSingleton(
            CURRENT_ENTITY,
            NovelAiDesignCurrentState(id),
            NovelAiDesignCurrentState.serializer()
        )
        _currentConversationId.value = id
    }

    private suspend fun pruneHistoryLocked() {
        val currentId = _currentConversationId.value
        val stale = _conversations.value
            .filterNot { it.id == currentId }
            .sortedByDescending(NovelAiDesignConversation::updatedAt)
            .drop(MAX_HISTORY_CONVERSATIONS)
        if (stale.isEmpty()) return
        stale.forEach { conversation ->
            storage.deleteEntity<NovelAiDesignConversation>(CONVERSATION_ENTITY, conversation.id)
        }
        val staleIds = stale.mapTo(mutableSetOf(), NovelAiDesignConversation::id)
        _conversations.value = _conversations.value.filterNot { it.id in staleIds }
    }

    companion object {
        const val MAX_HISTORY_CONVERSATIONS = 100
        private const val CONVERSATION_ENTITY = "novelai_design_conversations"
        private const val CURRENT_ENTITY = "novelai_design_current"
        private const val INTERRUPTED_ERROR = "上次生成已中断，可重试"
    }
}

package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.VoiceAnchor
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.voice.CurrentVoiceSegment
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VoiceSegmentAnchor(
    val segment: CurrentVoiceSegment,
    val anchor: VoiceAnchor
)

data class VoiceMessagePlacement(
    val voice: GeneratedVoiceMessage,
    val segmentIndex: Int?
)

class VoiceMessageRepository(
    private val storage: JsonFileStorage
) {
    private val mutex = Mutex()
    private val _voices = MutableStateFlow<List<GeneratedVoiceMessage>>(emptyList())
    val voices = _voices.asStateFlow()
    private var anchorsByMessage = emptyMap<String, VoiceAnchorState>()
    private var initialized = false

    suspend fun initialize() {
        mutex.withLock {
            if (initialized) return@withLock
            _voices.value = storage.loadAll(VOICE_TYPE, GeneratedVoiceMessage.serializer())
            anchorsByMessage = storage.loadAll(ANCHOR_TYPE, VoiceAnchorState.serializer())
                .associateBy(VoiceAnchorState::messageId)
            initialized = true
        }
    }

    fun observeSession(sessionId: String): Flow<List<GeneratedVoiceMessage>> =
        voices.map { all ->
            all.filter { it.sessionId == sessionId }
                .sortedWith(compareBy(GeneratedVoiceMessage::messageId, GeneratedVoiceMessage::sourceOrder, GeneratedVoiceMessage::createdAt))
        }

    suspend fun get(id: String): GeneratedVoiceMessage? {
        initialize()
        return _voices.value.firstOrNull { it.id == id }
    }

    suspend fun listForSession(sessionId: String): List<GeneratedVoiceMessage> {
        initialize()
        return _voices.value.filter { it.sessionId == sessionId }
    }

    suspend fun listForMessage(messageId: String): List<GeneratedVoiceMessage> {
        initialize()
        return _voices.value.filter { it.messageId == messageId }
    }

    suspend fun ensureAnchors(message: ChatMessage): List<VoiceSegmentAnchor> = mutex.withLock {
        ensureInitializedLocked()
        val current = anchorsByMessage[message.id]
        val reconciliation = if (current == null) {
            val state = VoiceAnchorPolicy.initialState(
                messageId = message.id,
                content = message.displayContent,
                sessionId = message.sessionId,
                includeNarration = true
            )
            com.example.chatbar.domain.voice.VoiceAnchorReconciliation(state, emptyMap())
        } else {
            VoiceAnchorPolicy.reconcile(
                old = current,
                newContent = message.displayContent,
                includeNarration = true
            )
        }
        val state = reconciliation.state.copy(sessionId = message.sessionId)
        if (current != state) {
            storage.saveEntity(ANCHOR_TYPE, message.id, state, VoiceAnchorState.serializer())
            anchorsByMessage = anchorsByMessage + (message.id to state)
        }
        if (reconciliation.anchorReplacement.isNotEmpty()) {
            val changed = _voices.value.map { voice ->
                if (voice.messageId != message.id || voice.anchorId == null) {
                    voice
                } else {
                    val replacement = reconciliation.anchorReplacement[voice.anchorId]
                    if (replacement == voice.anchorId || !reconciliation.anchorReplacement.containsKey(voice.anchorId)) {
                        voice
                    } else {
                        voice.copy(anchorId = replacement, updatedAt = System.currentTimeMillis())
                    }
                }
            }
            persistChangedVoicesLocked(_voices.value, changed)
        }
        val segments = VoiceAnchorPolicy.eligibleSegments(
            message.displayContent,
            includeNarration = true
        )
        state.anchors.mapIndexedNotNull { index, anchor ->
            segments.getOrNull(index)?.let { VoiceSegmentAnchor(it, anchor) }
        }
    }

    suspend fun placementsForMessage(message: ChatMessage): List<VoiceMessagePlacement> {
        val anchors = ensureAnchors(message)
        val indexByAnchor = anchors.associate { it.anchor.id to it.segment.segmentIndex }
        return listForMessage(message.id)
            .map { voice -> VoiceMessagePlacement(voice, voice.anchorId?.let(indexByAnchor::get)) }
            .sortedWith(
                compareBy<VoiceMessagePlacement> { it.segmentIndex ?: -1 }
                    .thenBy { it.voice.sourceOrder }
                    .thenBy { it.voice.createdAt }
            )
    }

    suspend fun save(voice: GeneratedVoiceMessage) = mutex.withLock {
        ensureInitializedLocked()
        storage.saveEntity(VOICE_TYPE, voice.id, voice, GeneratedVoiceMessage.serializer())
        _voices.value = _voices.value.filterNot { it.id == voice.id } + voice
    }

    suspend fun replace(voice: GeneratedVoiceMessage) = save(voice)

    suspend fun delete(id: String): GeneratedVoiceMessage? = mutex.withLock {
        ensureInitializedLocked()
        val existing = _voices.value.firstOrNull { it.id == id } ?: return@withLock null
        storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, id)
        _voices.value = _voices.value.filterNot { it.id == id }
        existing
    }

    suspend fun deleteForMessage(messageId: String): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        val removed = _voices.value.filter { it.messageId == messageId }
        removed.forEach { storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, it.id) }
        storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, messageId)
        anchorsByMessage = anchorsByMessage - messageId
        _voices.value = _voices.value.filterNot { it.messageId == messageId }
        removed
    }

    suspend fun deleteForSession(sessionId: String): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        val removed = _voices.value.filter { it.sessionId == sessionId }
        val messageIds = buildSet {
            removed.mapTo(this, GeneratedVoiceMessage::messageId)
            anchorsByMessage.values
                .filter { it.sessionId == sessionId }
                .mapTo(this, VoiceAnchorState::messageId)
        }
        removed.forEach { storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, it.id) }
        messageIds.forEach { storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, it) }
        anchorsByMessage = anchorsByMessage - messageIds
        _voices.value = _voices.value.filterNot { it.sessionId == sessionId }
        removed
    }

    suspend fun restoreForSession(
        sessionId: String,
        voices: List<GeneratedVoiceMessage>
    ) = mutex.withLock {
        ensureInitializedLocked()
        val old = _voices.value.filter { it.sessionId == sessionId }
        old.forEach { storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, it.id) }
        voices.forEach { voice ->
            storage.saveEntity(
                VOICE_TYPE,
                voice.id,
                voice.copy(sessionId = sessionId),
                GeneratedVoiceMessage.serializer()
            )
        }
        _voices.value = _voices.value.filterNot { it.sessionId == sessionId } +
            voices.map { it.copy(sessionId = sessionId) }
    }

    suspend fun restoreForSession(
        sessionId: String,
        voices: List<GeneratedVoiceMessage>,
        messages: List<ChatMessage>
    ) {
        val anchorsByMessage = messages.associate { message ->
            message.id to ensureAnchors(message)
        }
        val remapped = voices.map { voice ->
            val candidates = anchorsByMessage[voice.messageId].orEmpty()
                .filter { anchored ->
                    anchored.segment.kind.name == voice.sourceSegmentKind
                }
            val match = candidates.firstOrNull { anchored ->
                normalize(anchored.segment.spokenText) == normalize(voice.sourceText)
            } ?: candidates.minByOrNull { anchored ->
                kotlin.math.abs(anchored.anchor.sourceOrder - voice.sourceOrder)
            }
            voice.copy(
                sessionId = sessionId,
                anchorId = match?.anchor?.id,
                updatedAt = System.currentTimeMillis()
            )
        }
        restoreForSession(sessionId, remapped)
    }

    private suspend fun ensureInitializedLocked() {
        if (initialized) return
        _voices.value = storage.loadAll(VOICE_TYPE, GeneratedVoiceMessage.serializer())
        anchorsByMessage = storage.loadAll(ANCHOR_TYPE, VoiceAnchorState.serializer())
            .associateBy(VoiceAnchorState::messageId)
        initialized = true
    }

    private suspend fun persistChangedVoicesLocked(
        old: List<GeneratedVoiceMessage>,
        next: List<GeneratedVoiceMessage>
    ) {
        val oldById = old.associateBy(GeneratedVoiceMessage::id)
        next.filter { oldById[it.id] != it }.forEach { voice ->
            storage.saveEntity(VOICE_TYPE, voice.id, voice, GeneratedVoiceMessage.serializer())
        }
        _voices.value = next
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val VOICE_TYPE = "generated_voice_messages"
        const val ANCHOR_TYPE = "voice_anchor_states"
    }
}

package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.VoiceAnchor
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.deletion.ReplyRemoval
import com.example.chatbar.domain.voice.CurrentVoiceSegment
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import com.example.chatbar.domain.voice.VoiceMessageVersionPolicy
import java.nio.charset.StandardCharsets
import java.util.UUID
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

private data class VoiceAnchorStateKey(
    val messageId: String,
    val messageVersionId: String?
)

class VoiceMessageRepository(
    private val storage: JsonFileStorage,
    private val loadCurrentMessage: (suspend (String, String) -> ChatMessage?)? = null
) {
    private val mutex = Mutex()
    private val _voices = MutableStateFlow<List<GeneratedVoiceMessage>>(emptyList())
    val voices = _voices.asStateFlow()
    private var anchorsByVersion = emptyMap<VoiceAnchorStateKey, VoiceAnchorState>()
    private var initialized = false

    suspend fun initialize() {
        mutex.withLock {
            if (initialized) return@withLock
            _voices.value = storage.loadAll(VOICE_TYPE, GeneratedVoiceMessage.serializer())
            anchorsByVersion = storage.loadAll(ANCHOR_TYPE, VoiceAnchorState.serializer())
                .associateBy { VoiceAnchorStateKey(it.messageId, it.messageVersionId) }
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

    suspend fun snapshotAnchorsForMessages(messageIds: Set<String>): List<VoiceAnchorState> = mutex.withLock {
        ensureInitializedLocked()
        anchorsByVersion.values.filter { it.messageId in messageIds }
    }

    /** Caller supplies fresh message identities; preserve exact version/segment placement. */
    suspend fun restoreCopiedAnchors(states: List<VoiceAnchorState>) = mutex.withLock {
        ensureInitializedLocked()
        states.forEach { state ->
            val key = VoiceAnchorStateKey(state.messageId, state.messageVersionId)
            check(key !in anchorsByVersion) { "复制语音锚点身份冲突" }
            storage.saveEntity(ANCHOR_TYPE, anchorStorageId(key), state, VoiceAnchorState.serializer())
            anchorsByVersion = anchorsByVersion + (key to state)
        }
    }

    suspend fun listForMessage(messageId: String): List<GeneratedVoiceMessage> {
        initialize()
        return _voices.value.filter { it.messageId == messageId }
    }

    suspend fun listForCurrentVersion(message: ChatMessage): List<GeneratedVoiceMessage> {
        ensureAnchors(message)
        return VoiceMessageVersionPolicy.visibleVoices(message, _voices.value)
    }

    suspend fun ensureAnchors(message: ChatMessage): List<VoiceSegmentAnchor> = mutex.withLock {
        ensureInitializedLocked()
        val version = MessageAlternativeVersionPolicy.activeVersion(message)
        val current = if (loadCurrentMessage == null) message else
            loadCurrentMessage.invoke(message.id, message.sessionId) ?: return@withLock emptyList()
        val currentVersion = MessageAlternativeVersionPolicy.versions(current).firstOrNull { it.id == version.id }
            ?: return@withLock emptyList()
        migrateLegacyVoiceVersionsLocked(current)
        ensureVersionAnchorsLocked(current, version.id, currentVersion.content)
    }

    suspend fun ensureAnchorsForVersion(
        message: ChatMessage,
        messageVersionId: String,
        content: String,
        loadCurrentMessage: (suspend () -> ChatMessage?)? = null
    ): List<VoiceSegmentAnchor> = mutex.withLock {
        ensureInitializedLocked()
        val loader = loadCurrentMessage ?: this.loadCurrentMessage?.let { provider ->
            suspend { provider(message.id, message.sessionId) }
        }
        val current = if (loader == null) message else {
            val owner = loader()
            check(owner != null && owner.id == message.id && owner.sessionId == message.sessionId &&
                MessageAlternativeVersionPolicy.versions(owner).any { it.id == messageVersionId }) {
                "消息或回复备选已删除"
            }
            owner
        }
        migrateLegacyVoiceVersionsLocked(current)
        val currentContent = if (loader == null) content else
            MessageAlternativeVersionPolicy.versions(current).first { it.id == messageVersionId }.content
        ensureVersionAnchorsLocked(current, messageVersionId, currentContent)
    }

    private suspend fun ensureVersionAnchorsLocked(
        message: ChatMessage,
        messageVersionId: String,
        content: String
    ): List<VoiceSegmentAnchor> {
        val key = VoiceAnchorStateKey(message.id, messageVersionId)
        val current = anchorsByVersion[key]
        val reconciliation = if (current == null) {
            val state = VoiceAnchorPolicy.initialState(
                messageId = message.id,
                content = content,
                messageVersionId = messageVersionId,
                sessionId = message.sessionId,
                includeNarration = true
            )
            com.example.chatbar.domain.voice.VoiceAnchorReconciliation(state, emptyMap())
        } else {
            VoiceAnchorPolicy.reconcile(
                old = current,
                newContent = content,
                includeNarration = true
            )
        }
        val state = reconciliation.state.copy(
            messageVersionId = messageVersionId,
            sessionId = message.sessionId
        )
        if (current != state) {
            storage.saveEntity(
                ANCHOR_TYPE,
                anchorStorageId(key),
                state,
                VoiceAnchorState.serializer()
            )
            anchorsByVersion = anchorsByVersion + (key to state)
        }
        if (reconciliation.anchorReplacement.isNotEmpty()) {
            val changed = VoiceMessageVersionPolicy.applyAnchorReplacements(
                voices = _voices.value,
                messageId = message.id,
                messageVersionId = messageVersionId,
                replacements = reconciliation.anchorReplacement
            )
            persistChangedVoicesLocked(_voices.value, changed)
        }
        val segments = VoiceAnchorPolicy.eligibleSegments(
            content,
            includeNarration = true
        )
        return state.anchors.mapIndexedNotNull { index, anchor ->
            segments.getOrNull(index)?.let { VoiceSegmentAnchor(it, anchor) }
        }
    }

    suspend fun placementsForMessage(message: ChatMessage): List<VoiceMessagePlacement> {
        val anchors = ensureAnchors(message)
        val indexByAnchor = anchors.associate { it.anchor.id to it.segment.segmentIndex }
        return VoiceMessageVersionPolicy.visibleVoices(message, _voices.value)
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

    /** Check ownership under the same lock as version cleanup, including late TTS responses. */
    suspend fun saveForExistingVersion(
        voice: GeneratedVoiceMessage,
        replacing: GeneratedVoiceMessage? = null,
        loadMessage: suspend () -> ChatMessage?
    ) = mutex.withLock {
        ensureInitializedLocked()
        val message = loadMessage()
        check(message != null && message.id == voice.messageId && message.sessionId == voice.sessionId) {
            "消息或回复备选已删除"
        }
        if (replacing != null) {
            check(voice.id == replacing.id && _voices.value.firstOrNull { it.id == replacing.id } == replacing) {
                "语音消息已变化，请重新操作"
            }
        }
        check(MessageAlternativeVersionPolicy.versions(message).any { it.id == voice.messageVersionId }) {
            "回复备选已删除"
        }
        storage.saveEntity(VOICE_TYPE, voice.id, voice, GeneratedVoiceMessage.serializer())
        _voices.value = _voices.value.filterNot { it.id == voice.id } + voice
    }

    /** Keep version deletion and voice operations serialized. */
    suspend fun deleteForVersion(
        message: ChatMessage,
        versionId: String,
        commitMessage: suspend () -> Boolean
    ): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        require(MessageAlternativeVersionPolicy.versions(message).any { it.id == versionId })
        check(commitMessage()) { "消息已变化，请重新操作" }
        val targeted = persistedReplyVoices(message.sessionId, message.id, versionId)
        val removed = targeted.filter(GeneratedVoiceMessage::versionOwnershipConfirmed)
        val key = VoiceAnchorStateKey(message.id, versionId)
        deleteVoiceRecordsLocked(removed)
        detachUnconfirmedReplyVoices(targeted)
        deleteAnchorLocked(key)
        removed
    }

    /** Capture only audio with confirmed ownership before changing a reply. */
    internal suspend fun prepareReplyRemoval(
        message: ChatMessage,
        versionId: String?,
        commit: suspend (suspend () -> List<String>) -> Boolean
    ): Boolean = mutex.withLock {
        ensureInitializedLocked()
        commit {
            persistedReplyVoices(message.sessionId, message.id, versionId)
                .filter { versionId == null || it.versionOwnershipConfirmed }
                .map(GeneratedVoiceMessage::audioPath).distinct()
        }
    }

    /** The owner guard also holds the message write lock until all voice effects finish. */
    internal suspend fun finishReplyRemoval(
        removal: ReplyRemoval,
        whileOwnerRemoved: suspend (suspend () -> Unit) -> Boolean,
        stopPlayback: (Set<String>) -> Unit,
        deleteAudio: (String) -> Boolean
    ): Boolean = mutex.withLock {
        ensureInitializedLocked()
        whileOwnerRemoved {
            val targeted = persistedReplyVoices(removal.sessionId, removal.messageId, removal.versionId)
            val removed = targeted.filter { removal.versionId == null || it.versionOwnershipConfirmed }
            val anchorKeys = mutableSetOf<VoiceAnchorStateKey>()
            storage.forEachUncached(ANCHOR_TYPE, VoiceAnchorState.serializer(), requireCompleteRead = true) { state ->
                if (state.messageId == removal.messageId &&
                    (state.sessionId == removal.sessionId || state.sessionId.isBlank()) &&
                    (removal.versionId == null || state.messageVersionId == removal.versionId)) {
                    anchorKeys += VoiceAnchorStateKey(state.messageId, state.messageVersionId)
                }
            }
            stopPlayback(removed.mapTo(mutableSetOf(), GeneratedVoiceMessage::id))
            deleteVoiceRecordsLocked(removed)
            if (removal.versionId != null) detachUnconfirmedReplyVoices(targeted)
            anchorKeys.forEach { deleteAnchorLocked(it) }
            deleteUnownedReplyAudio(removal.audioPaths, deleteAudio)
        }
    }

    internal suspend fun deleteUnownedReplyAudio(paths: List<String>, deleteAudio: (String) -> Boolean) {
        val candidates = paths.mapNotNullTo(linkedSetOf(), storage::canonicalMediaPath)
        storage.deleteUnreferencedRegisteredMedia(candidates, setOf(VOICE_TYPE, "save_slots")) { path ->
            check(deleteAudio(path)) { "回复已删除，语音文件清理失败，将在重试时继续" }
        }
    }

    private suspend fun persistedReplyVoices(sessionId: String, messageId: String, versionId: String?): List<GeneratedVoiceMessage> {
        val result = mutableListOf<GeneratedVoiceMessage>()
        storage.forEachUncached(VOICE_TYPE, GeneratedVoiceMessage.serializer(), requireCompleteRead = true) { voice ->
            if (voice.sessionId == sessionId && voice.messageId == messageId &&
                (versionId == null || voice.messageVersionId == versionId)) result += voice
        }
        return result
    }

    private suspend fun detachUnconfirmedReplyVoices(voices: List<GeneratedVoiceMessage>) {
        // Older versions stored inferred IDs in the same field as generation-time IDs.
        voices.filterNot(GeneratedVoiceMessage::versionOwnershipConfirmed).forEach { voice ->
            val detached = voice.copy(messageVersionId = null, anchorId = null)
            storage.saveEntity(VOICE_TYPE, detached.id, detached, GeneratedVoiceMessage.serializer())
            _voices.value = _voices.value.map { if (it.id == detached.id) detached else it }
        }
    }

    internal suspend fun withPersistedAudioPaths(action: suspend (Set<String>) -> Unit) = mutex.withLock {
        action(persistedAudioPathsLocked())
    }

    private suspend fun persistedAudioPathsLocked(): Set<String> {
        val paths = mutableSetOf<String>()
        storage.forEachUncached(VOICE_TYPE, GeneratedVoiceMessage.serializer(), requireCompleteRead = true) {
            storage.canonicalMediaPath(it.audioPath)?.let(paths::add)
        }
        return paths
    }

    private suspend fun deleteVoiceRecordsLocked(removed: List<GeneratedVoiceMessage>) {
        removed.forEach { voice ->
            storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, voice.id, requireSuccess = true)
            _voices.value = _voices.value.filterNot { it.id == voice.id }
        }
    }

    private suspend fun deleteAnchorLocked(key: VoiceAnchorStateKey) {
        storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, anchorStorageId(key), requireSuccess = true)
        anchorsByVersion = anchorsByVersion - key
    }

    suspend fun delete(id: String): GeneratedVoiceMessage? = mutex.withLock {
        ensureInitializedLocked()
        val existing = _voices.value.firstOrNull { it.id == id } ?: return@withLock null
        deleteVoiceRecordsLocked(listOf(existing))
        existing
    }

    suspend fun deleteForMessage(messageId: String): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        val removed = _voices.value.filter { it.messageId == messageId }
        deleteVoiceRecordsLocked(removed)
        val anchorKeys = anchorsByVersion.keys.filter { it.messageId == messageId }
        anchorKeys.map(::anchorStorageId).plus(messageId).distinct().forEach { storageId ->
            storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, storageId, requireSuccess = true)
        }
        anchorsByVersion = anchorsByVersion.filterKeys { it.messageId != messageId }
        removed
    }

    suspend fun deleteForSession(sessionId: String): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        val removed = _voices.value.filter { it.sessionId == sessionId }
        val messageIds = buildSet {
            removed.mapTo(this, GeneratedVoiceMessage::messageId)
            anchorsByVersion.values
                .filter { it.sessionId == sessionId }
                .mapTo(this, VoiceAnchorState::messageId)
        }
        deleteVoiceRecordsLocked(removed)
        val anchorKeys = anchorsByVersion.keys.filter { it.messageId in messageIds }
        val anchorStorageIds = anchorKeys.map(::anchorStorageId) + messageIds
        anchorStorageIds.distinct().forEach { storageId ->
            storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, storageId, requireSuccess = true)
        }
        anchorsByVersion = anchorsByVersion.filterKeys { it.messageId !in messageIds }
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
        val messagesById = messages.associateBy(ChatMessage::id)
        val anchorStatesByMessageVersion =
            mutableMapOf<Pair<String, String>, VoiceAnchorState>()
        messages.forEach { message ->
            MessageAlternativeVersionPolicy.versions(message).forEach { version ->
                val anchors = ensureAnchorsForVersion(
                    message = message,
                    messageVersionId = version.id,
                    content = version.content
                )
                anchorStatesByMessageVersion[message.id to version.id] = VoiceAnchorState(
                    messageId = message.id,
                    messageVersionId = version.id,
                    sessionId = message.sessionId,
                    displayContentSnapshot = version.content,
                    anchors = anchors.map(VoiceSegmentAnchor::anchor)
                )
            }
        }
        val remapped = voices.map { voice ->
            val message = messagesById[voice.messageId]
            val versionId = if (message == null) {
                voice.messageVersionId
            } else {
                voice.messageVersionId
                    ?.takeIf { id ->
                        MessageAlternativeVersionPolicy.versions(message).any { it.id == id }
                    }
                    ?: VoiceMessageVersionPolicy.inferLegacyVersionId(
                        message = message,
                        voice = voice,
                        legacyState = null
                    )
            }
            val state = versionId?.let {
                anchorStatesByMessageVersion[voice.messageId to it]
            }
            voice.copy(
                sessionId = sessionId,
                messageVersionId = versionId,
                versionOwnershipConfirmed = voice.versionOwnershipConfirmed && versionId == voice.messageVersionId,
                anchorId = state?.let {
                    VoiceMessageVersionPolicy.anchorIdForVoice(voice, it)
                } ?: voice.anchorId,
                updatedAt = System.currentTimeMillis()
            )
        }
        restoreForSession(sessionId, remapped)
    }

    private suspend fun ensureInitializedLocked() {
        if (initialized) return
        _voices.value = storage.loadAll(VOICE_TYPE, GeneratedVoiceMessage.serializer())
        anchorsByVersion = storage.loadAll(ANCHOR_TYPE, VoiceAnchorState.serializer())
            .associateBy { VoiceAnchorStateKey(it.messageId, it.messageVersionId) }
        initialized = true
    }

    private suspend fun migrateLegacyVoiceVersionsLocked(message: ChatMessage) {
        val legacyKey = VoiceAnchorStateKey(message.id, null)
        val legacyState = anchorsByVersion[legacyKey]?.takeIf { it.sessionId == message.sessionId || it.sessionId.isBlank() }
        val legacyVoices = _voices.value.filter {
            it.sessionId == message.sessionId && it.messageId == message.id && it.messageVersionId.isNullOrBlank()
        }
        if (legacyState == null && legacyVoices.isEmpty()) return

        val versions = MessageAlternativeVersionPolicy.versions(message)
        val activeVersionId = MessageAlternativeVersionPolicy.activeVersionId(message)
        val legacyStateVersionId = legacyState?.let { state ->
            versions.firstOrNull { it.content == state.displayContentSnapshot }?.id
                ?: activeVersionId
        }
        if (legacyState != null && legacyStateVersionId != null) {
            val migratedKey = VoiceAnchorStateKey(message.id, legacyStateVersionId)
            if (anchorsByVersion[migratedKey] == null) {
                val migratedState = legacyState.copy(
                    messageVersionId = legacyStateVersionId,
                    sessionId = message.sessionId
                )
                storage.saveEntity(
                    ANCHOR_TYPE,
                    anchorStorageId(migratedKey),
                    migratedState,
                    VoiceAnchorState.serializer()
                )
                anchorsByVersion = anchorsByVersion + (migratedKey to migratedState)
            }
        }

        val changed = _voices.value.map { voice ->
            if (voice.sessionId != message.sessionId || voice.messageId != message.id || !voice.messageVersionId.isNullOrBlank()) {
                voice
            } else {
                val versionId = VoiceMessageVersionPolicy.inferLegacyVersionId(
                    message = message,
                    voice = voice,
                    legacyState = legacyState
                )
                val version = versions.firstOrNull { it.id == versionId }
                val key = VoiceAnchorStateKey(message.id, versionId)
                var state = anchorsByVersion[key]
                if (state == null && version != null) {
                    state = VoiceAnchorPolicy.initialState(
                        messageId = message.id,
                        content = version.content,
                        messageVersionId = versionId,
                        sessionId = message.sessionId,
                        includeNarration = true
                    )
                    storage.saveEntity(
                        ANCHOR_TYPE,
                        anchorStorageId(key),
                        state,
                        VoiceAnchorState.serializer()
                    )
                    anchorsByVersion = anchorsByVersion + (key to state)
                }
                voice.copy(
                    messageVersionId = versionId,
                    versionOwnershipConfirmed = false,
                    anchorId = state?.let {
                        VoiceMessageVersionPolicy.anchorIdForVoice(voice, it)
                    },
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        persistChangedVoicesLocked(_voices.value, changed)
        if (legacyState != null) {
            deleteAnchorLocked(legacyKey)
        }
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

    private fun anchorStorageId(key: VoiceAnchorStateKey): String {
        val versionId = key.messageVersionId ?: return key.messageId
        return UUID.nameUUIDFromBytes(
            "${key.messageId}\u0000$versionId".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }

    private companion object {
        const val VOICE_TYPE = "generated_voice_messages"
        const val ANCHOR_TYPE = "voice_anchor_states"
    }
}

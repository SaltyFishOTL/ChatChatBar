package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.VoiceAnchor
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.MessageVersionSnapshot
import com.example.chatbar.domain.deletion.ReplyRemoval
import com.example.chatbar.domain.voice.CurrentVoiceSegment
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import com.example.chatbar.domain.voice.VoiceAnchorReconciliation
import com.example.chatbar.domain.voice.VoiceAnchorStateKey
import com.example.chatbar.domain.voice.VoiceMessageVersionPolicy
import com.example.chatbar.domain.voice.VoicePlaybackQueuePolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class VoiceSegmentAnchor(
    val segment: CurrentVoiceSegment,
    val anchor: VoiceAnchor
)

data class VoiceMessagePlacement(
    val voice: GeneratedVoiceMessage,
    val segmentIndex: Int?
)

private data class VoiceVersionAnchors(val version: MessageVersionSnapshot, val anchors: List<VoiceSegmentAnchor>)

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
        mutex.withLock { ensureInitializedLocked() }
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

    suspend fun snapshotAnchorsForMessages(sessionId: String, messageIds: Set<String>): List<VoiceAnchorState> = mutex.withLock {
        ensureInitializedLocked()
        val legacyKeys = anchorsByVersion.keys.filter { it.sessionId.isBlank() && it.messageId in messageIds }
        legacyKeys.forEach { key ->
            val scopedKey = key.copy(sessionId = sessionId)
            if (scopedKey !in anchorsByVersion) adoptUnscopedAnchorLocked(scopedKey)
        }
        anchorsByVersion.values.filter { it.sessionId == sessionId && it.messageId in messageIds }
    }

    /** Caller supplies fresh message identities; preserve exact version/segment placement. */
    suspend fun restoreCopiedAnchors(states: List<VoiceAnchorState>) = mutex.withLock {
        ensureInitializedLocked()
        states.forEach { state ->
            val key = VoiceAnchorStateKey(state)
            check(key !in anchorsByVersion) { "复制语音锚点身份冲突" }
            saveAnchorLocked(key, state)
        }
    }

    internal suspend fun withCurrentPlaybackQueue(
        captured: List<GeneratedVoiceMessage>,
        chats: ChatRepository,
        enqueue: suspend (List<GeneratedVoiceMessage>) -> Unit,
    ) = mutex.withLock {
        ensureInitializedLocked()
        // Use the same voice-to-message lock order as reply removal for validation and handoff.
        chats.withStableMessageTimeline {
            enqueue(captured.mapNotNull { currentVoiceForPlaybackLocked(it, chats) })
        }
    }

    private suspend fun currentVoiceForPlaybackLocked(
        captured: GeneratedVoiceMessage,
        chats: ChatRepository,
    ): GeneratedVoiceMessage? {
        val current = _voices.value.firstOrNull { it.id == captured.id } ?: return null
        if (current.sessionId != captured.sessionId || current.messageId != captured.messageId ||
            current.messageVersionId != captured.messageVersionId || current.audioPath != captured.audioPath) return null
        val requiresCompatibility = current.requiresCompatibleVersion || captured.requiresCompatibleVersion
        val message = chats.getMessage(current.messageId, current.sessionId) ?: return null
        val version = MessageAlternativeVersionPolicy.activeVersion(message)
        return current.takeIf {
            it.messageVersionId == version.id &&
                (!requiresCompatibility || VoiceMessageVersionPolicy.isCompatibleVersion(version, it))
        }
    }

    suspend fun listForMessage(sessionId: String, messageId: String): List<GeneratedVoiceMessage> {
        initialize()
        return _voices.value.filter { it.sessionId == sessionId && it.messageId == messageId }
    }

    suspend fun listForCurrentVersion(message: ChatMessage): List<GeneratedVoiceMessage> = mutex.withLock {
        val current = ensureAnchorsIfVersionExistsLocked(message) ?: return@withLock emptyList()
        VoiceMessageVersionPolicy.visibleVoices(message, _voices.value, current.version)
    }

    suspend fun ensureAnchors(message: ChatMessage): List<VoiceSegmentAnchor> = mutex.withLock {
        ensureAnchorsIfVersionExistsLocked(message)?.anchors.orEmpty()
    }

    private suspend fun ensureAnchorsIfVersionExistsLocked(message: ChatMessage): VoiceVersionAnchors? {
        ensureInitializedLocked()
        val versionId = MessageAlternativeVersionPolicy.activeVersionId(message)
        val current = if (loadCurrentMessage == null) message else
            loadCurrentMessage.invoke(message.id, message.sessionId) ?: return null
        val currentVersion = MessageAlternativeVersionPolicy.versions(current).firstOrNull { it.id == versionId }
            ?: return null
        migrateLegacyVoiceVersionsLocked(current)
        return VoiceVersionAnchors(currentVersion, ensureVersionAnchorsLocked(current, versionId, currentVersion.content))
    }

    suspend fun ensureAnchorsForVersion(
        message: ChatMessage,
        messageVersionId: String,
        content: String,
        requireSameContent: Boolean = false,
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
        val currentContent = if (loader == null) content else
            MessageAlternativeVersionPolicy.versions(current).first { it.id == messageVersionId }.content
        check(!requireSameContent || currentContent == content) { "消息已变化，请重新操作" }
        migrateLegacyVoiceVersionsLocked(current)
        ensureVersionAnchorsLocked(current, messageVersionId, currentContent)
    }

    private suspend fun ensureVersionAnchorsLocked(
        message: ChatMessage,
        messageVersionId: String,
        content: String
    ): List<VoiceSegmentAnchor> {
        val key = VoiceAnchorStateKey(message.sessionId, message.id, messageVersionId)
        val current = anchorsByVersion[key] ?: adoptUnscopedAnchorLocked(key)
        val reconciliation = if (current == null) {
            val state = VoiceAnchorPolicy.initialState(
                messageId = message.id,
                content = content,
                messageVersionId = messageVersionId,
                sessionId = message.sessionId,
                includeNarration = true
            )
            VoiceAnchorReconciliation(state, emptyMap())
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
        if (current != state) saveAnchorLocked(key, state)
        if (reconciliation.anchorReplacement.isNotEmpty()) {
            val changed = VoiceMessageVersionPolicy.applyAnchorReplacements(
                voices = _voices.value,
                sessionId = message.sessionId,
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

    suspend fun placementsForMessage(message: ChatMessage): List<VoiceMessagePlacement> = mutex.withLock {
        val current = ensureAnchorsIfVersionExistsLocked(message) ?: return@withLock emptyList()
        val indexByAnchor = current.anchors.associate { it.anchor.id to it.segment.segmentIndex }
        VoiceMessageVersionPolicy.visibleVoices(message, _voices.value, current.version)
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
        removeFromPlayback: suspend (Set<String>) -> Unit = {},
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
        withContext(NonCancellable) {
            storage.saveEntity(VOICE_TYPE, voice.id, voice, GeneratedVoiceMessage.serializer())
            _voices.value = _voices.value.filterNot { it.id == voice.id } + voice
            if (replacing != null && voice.audioPath != replacing.audioPath) removeFromPlayback(setOf(voice.id))
        }
    }

    internal suspend fun switchDisplayedVersion(
        expected: ChatMessage,
        replacement: ChatMessage,
        chats: ChatRepository,
        removeFromPlayback: suspend (Set<String>) -> Unit,
        expectedTimelineRevision: Long? = null
    ): ChatMessage? = mutex.withLock {
        ensureInitializedLocked()
        withContext(NonCancellable) {
            val committed = chats.updateMessageIfUnchanged(expected, replacement, expectedTimelineRevision)
                ?: return@withContext null
            val oldIds = _voices.value.filter { VoicePlaybackQueuePolicy.ownsDisplayedVersion(expected, it) }
                .mapTo(mutableSetOf(), GeneratedVoiceMessage::id)
            removeFromPlayback(oldIds)
            committed
        }
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
        val targeted = persistedReplyVoices(message.sessionId, message.id, versionId, includeUnassigned = true)
        val removed = targeted.filter { it.messageVersionId == versionId && it.versionOwnershipConfirmed }
        val key = VoiceAnchorStateKey(message.sessionId, message.id, versionId)
        deleteVoiceRecordsLocked(removed)
        restrictUnconfirmedReplyVoices(targeted)
        deleteAnchorLocked(key)
        removed
    }

    /** Capture only audio with confirmed ownership before changing a reply. */
    internal suspend fun prepareReplyRemoval(
        message: ChatMessage,
        versionId: String?,
        removeFromPlayback: suspend (Set<String>) -> Unit,
        commit: suspend (suspend () -> List<String>) -> Boolean
    ): Boolean = mutex.withLock {
        ensureInitializedLocked()
        var targeted = emptyList<GeneratedVoiceMessage>()
        val committed = commit {
            targeted = persistedReplyVoices(message.sessionId, message.id, versionId, includeUnassigned = true)
            targeted.filter { versionId == null || it.messageVersionId == versionId && it.versionOwnershipConfirmed }
                .map(GeneratedVoiceMessage::audioPath).distinct()
        }
        if (committed) {
            removeFromPlayback(targeted.mapTo(mutableSetOf(), GeneratedVoiceMessage::id))
            if (versionId != null) restrictUnconfirmedReplyVoices(targeted)
        }
        committed
    }

    /** The owner guard also holds the message write lock until all voice effects finish. */
    internal suspend fun finishReplyRemoval(
        removal: ReplyRemoval,
        whileOwnerRemoved: suspend (suspend () -> Unit) -> Boolean,
        removeFromPlayback: suspend (Set<String>) -> Unit,
        deleteAudio: (String) -> Boolean
    ): Boolean = mutex.withLock {
        ensureInitializedLocked()
        whileOwnerRemoved {
            val targeted = persistedReplyVoices(removal.sessionId, removal.messageId, removal.versionId,
                includeUnassigned = true)
            val removed = targeted.filter {
                removal.versionId == null || it.messageVersionId == removal.versionId && it.versionOwnershipConfirmed
            }
            removeFromPlayback(targeted.mapTo(mutableSetOf(), GeneratedVoiceMessage::id))
            val anchorKeys = mutableSetOf<VoiceAnchorStateKey>()
            storage.forEachUncached(ANCHOR_TYPE, VoiceAnchorState.serializer(), requireCompleteRead = true) { state ->
                if (state.messageId == removal.messageId &&
                    (state.sessionId == removal.sessionId || unscopedAnchorOwner(state) == removal.sessionId) &&
                    (removal.versionId == null || state.messageVersionId == removal.versionId)) {
                    anchorKeys += VoiceAnchorStateKey(state)
                }
            }
            deleteVoiceRecordsLocked(removed)
            if (removal.versionId != null) restrictUnconfirmedReplyVoices(targeted)
            anchorKeys.forEach { deleteAnchorLocked(it) }
            deleteUnownedReplyAudio(removal.audioPaths, deleteAudio)
        }
    }

    internal suspend fun deleteUnownedReplyAudio(paths: List<String>, deleteAudio: (String) -> Boolean) {
        pruneUnreferencedAudio(paths) { path ->
            check(deleteAudio(path)) { "回复已删除，语音文件清理失败，将在重试时继续" }
        }
    }

    internal suspend fun pruneUnreferencedAudio(paths: List<String>, deleteAudio: (String) -> Unit) {
        val candidates = paths.mapNotNullTo(linkedSetOf(), storage::canonicalMediaPath)
        storage.deleteUnreferencedRegisteredMedia(candidates, setOf(VOICE_TYPE, "save_slots"), deleteAudio)
    }

    private suspend fun persistedReplyVoices(
        sessionId: String,
        messageId: String,
        versionId: String?,
        includeUnassigned: Boolean = false
    ): List<GeneratedVoiceMessage> {
        val result = mutableListOf<GeneratedVoiceMessage>()
        storage.forEachUncached(VOICE_TYPE, GeneratedVoiceMessage.serializer(), requireCompleteRead = true) { voice ->
            if (voice.sessionId == sessionId && voice.messageId == messageId &&
                (versionId == null || voice.messageVersionId == versionId ||
                    includeUnassigned && voice.messageVersionId.isNullOrBlank())) result += voice
        }
        return result
    }

    private suspend fun restrictUnconfirmedReplyVoices(voices: List<GeneratedVoiceMessage>) {
        // Keep any recorded version ID for recovery. A deleted version's former
        // recording must never fall back to whichever alternative happens to be active.
        voices.filterNot(GeneratedVoiceMessage::versionOwnershipConfirmed).forEach { voice ->
            val retained = voice.copy(anchorId = null, requiresCompatibleVersion = true)
            if (retained != voice) {
                storage.saveEntity(VOICE_TYPE, retained.id, retained, GeneratedVoiceMessage.serializer())
                _voices.value = _voices.value.map { if (it.id == retained.id) retained else it }
            }
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
        storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, key.storageId, requireSuccess = true)
        deleteOldAnchorFileLocked(key)
        anchorsByVersion = anchorsByVersion - key
    }

    private suspend fun saveAnchorLocked(key: VoiceAnchorStateKey, state: VoiceAnchorState) {
        storage.saveEntity(ANCHOR_TYPE, key.storageId, state, VoiceAnchorState.serializer())
        anchorsByVersion = anchorsByVersion + (key to state)
        deleteOldAnchorFileLocked(key)
    }

    private suspend fun deleteOldAnchorFileLocked(key: VoiceAnchorStateKey) {
        if (key.storageId == key.legacyStorageId) return
        val old = storage.loadEntity(ANCHOR_TYPE, key.legacyStorageId, VoiceAnchorState.serializer()) ?: return
        if (VoiceAnchorStateKey(old) == key) {
            storage.deleteEntity<VoiceAnchorState>(ANCHOR_TYPE, key.legacyStorageId, requireSuccess = true)
        }
    }

    private fun unscopedAnchorOwner(state: VoiceAnchorState): String? {
        if (state.sessionId.isNotBlank()) return null
        val anchors = state.anchors.mapTo(hashSetOf(), VoiceAnchor::id)
        return _voices.value.filter { voice ->
            voice.messageId == state.messageId && voice.anchorId in anchors &&
                (state.messageVersionId == null || voice.messageVersionId == state.messageVersionId)
        }.map(GeneratedVoiceMessage::sessionId).distinct().singleOrNull()
    }

    private suspend fun adoptUnscopedAnchorLocked(key: VoiceAnchorStateKey): VoiceAnchorState? {
        val unscopedKey = key.copy(sessionId = "")
        val state = anchorsByVersion[unscopedKey]?.takeIf { unscopedAnchorOwner(it) == key.sessionId } ?: return null
        val scoped = state.copy(sessionId = key.sessionId)
        saveAnchorLocked(key, scoped)
        deleteAnchorLocked(unscopedKey)
        return scoped
    }

    suspend fun delete(id: String, removeFromPlayback: suspend (Set<String>) -> Unit = {}): GeneratedVoiceMessage? = mutex.withLock {
        ensureInitializedLocked()
        val existing = _voices.value.firstOrNull { it.id == id } ?: return@withLock null
        withContext(NonCancellable) {
            deleteVoiceRecordsLocked(listOf(existing))
            removeFromPlayback(setOf(id))
        }
        existing
    }

    suspend fun deleteForMessage(sessionId: String, messageId: String): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        val removed = _voices.value.filter { it.sessionId == sessionId && it.messageId == messageId }
        val anchorKeys = anchorsByVersion.filterValues { state ->
            state.messageId == messageId && (state.sessionId == sessionId || unscopedAnchorOwner(state) == sessionId)
        }.keys
        deleteVoiceRecordsLocked(removed)
        anchorKeys.forEach { deleteAnchorLocked(it) }
        removed
    }

    suspend fun deleteForSession(sessionId: String): List<GeneratedVoiceMessage> = mutex.withLock {
        ensureInitializedLocked()
        val removed = _voices.value.filter { it.sessionId == sessionId }
        val anchorKeys = anchorsByVersion.filterValues { state ->
            state.sessionId == sessionId || unscopedAnchorOwner(state) == sessionId
        }.keys
        deleteVoiceRecordsLocked(removed)
        anchorKeys.forEach { deleteAnchorLocked(it) }
        removed
    }

    suspend fun restoreForSession(
        sessionId: String,
        voices: List<GeneratedVoiceMessage>
    ) = mutex.withLock {
        ensureInitializedLocked()
        val restored = voices.map { archived ->
            val voice = archived.copy(sessionId = sessionId)
            if (!voice.requiresCompatibleVersion || voice.anchorId == null || loadCurrentMessage == null) return@map voice
            val message = loadCurrentMessage.invoke(voice.messageId, sessionId)
            val recorded = message?.let(MessageAlternativeVersionPolicy::versions)?.firstOrNull { it.id == voice.messageVersionId }
            if (recorded != null && VoiceMessageVersionPolicy.isCompatibleVersion(recorded, voice)) voice
            else voice.copy(anchorId = null)
        }
        replaceSessionVoicesLocked(sessionId, restored)
    }

    /** Modern v8 layouts carry exact anchor IDs; older packages use conservative placement recovery. */
    suspend fun restorePackagedForSession(
        sessionId: String,
        voices: List<GeneratedVoiceMessage>,
        streamAnchors: suspend (suspend (VoiceAnchorState) -> Unit) -> Unit = {}
    ) = mutex.withLock {
        ensureInitializedLocked()
        val archivedLayouts = mutableSetOf<VoiceAnchorStateKey>()
        streamAnchors anchor@{ state ->
            val key = VoiceAnchorStateKey(state)
            check(key.sessionId == sessionId) { "恢复的语音锚点归属无效" }
            val message = loadCurrentMessage?.invoke(state.messageId, sessionId)
            val version = message?.let(MessageAlternativeVersionPolicy::versions)?.firstOrNull { it.id == state.messageVersionId }
            check(version != null && version.content == state.displayContentSnapshot) { "恢复的语音锚点与回复内容不一致" }
            // A later parser can change segmentation while the archived reply and recording remain valid.
            if (!VoiceAnchorPolicy.hasExactLayout(state, version.content)) return@anchor
            check(archivedLayouts.add(key)) { "恢复的语音锚点重复" }
            if (anchorsByVersion[key] != state) saveAnchorLocked(key, state)
        }
        val restored = voices.groupBy(GeneratedVoiceMessage::messageId).flatMap { (messageId, recordings) ->
            val message = loadCurrentMessage?.invoke(messageId, sessionId)
            val versions = message?.let(MessageAlternativeVersionPolicy::versions).orEmpty().associateBy { it.id }
            recordings.groupBy(GeneratedVoiceMessage::messageVersionId).flatMap { (versionId, versionVoices) ->
                val version = versions[versionId]
                val key = VoiceAnchorStateKey(sessionId, messageId, versionId)
                val exactArchivedLayout = key in archivedLayouts
                val state = if (exactArchivedLayout) anchorsByVersion.getValue(key)
                else if (message != null && version != null && versionVoices.any { it.anchorId != null }) {
                    ensureVersionAnchorsLocked(message, version.id, version.content)
                    anchorsByVersion[key]
                } else null
                versionVoices.map { archived ->
                    val voice = archived.copy(sessionId = sessionId)
                    val ownerKnown = exactArchivedLayout || voice.versionOwnershipConfirmed || message != null &&
                        VoiceMessageVersionPolicy.compatibleVersionId(message, voice) == versionId
                    voice.copy(anchorId = state?.takeIf { ownerKnown }
                        ?.let { VoiceMessageVersionPolicy.archivedAnchorIdForVoice(voice, it, exactArchivedLayout) })
                }
            }
        }
        replaceSessionVoicesLocked(sessionId, restored)
    }

    private suspend fun replaceSessionVoicesLocked(sessionId: String, voices: List<GeneratedVoiceMessage>) {
        val old = _voices.value.filter { it.sessionId == sessionId }
        old.forEach { storage.deleteEntity<GeneratedVoiceMessage>(VOICE_TYPE, it.id) }
        voices.forEach { voice ->
            storage.saveEntity(VOICE_TYPE, voice.id, voice, GeneratedVoiceMessage.serializer())
        }
        _voices.value = _voices.value.filterNot { it.sessionId == sessionId } + voices
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
            val versions = message?.let(MessageAlternativeVersionPolicy::versions).orEmpty()
            val recorded = versions.firstOrNull { it.id == voice.messageVersionId }
            val versionId = when {
                message == null || voice.versionOwnershipConfirmed -> voice.messageVersionId
                recorded != null && (!voice.requiresCompatibleVersion || VoiceMessageVersionPolicy.isCompatibleVersion(recorded, voice)) -> recorded.id
                voice.requiresCompatibleVersion || !voice.messageVersionId.isNullOrBlank() ->
                    VoiceMessageVersionPolicy.recoverCompatibleVersionId(message, voice) ?: voice.messageVersionId
                else -> VoiceMessageVersionPolicy.inferLegacyVersionId(message, voice, legacyState = null)
            }
            val version = versions.firstOrNull { it.id == versionId }
            val state = version?.takeIf { !voice.requiresCompatibleVersion || VoiceMessageVersionPolicy.isCompatibleVersion(it, voice) }
                ?.let { anchorStatesByMessageVersion[voice.messageId to it.id] }
            val resolved = voice.copy(
                sessionId = sessionId,
                messageVersionId = versionId,
                versionOwnershipConfirmed = voice.versionOwnershipConfirmed && versionId == voice.messageVersionId,
                legacyVersionContentHash = VoiceMessageVersionPolicy.legacyVersionContentHash(voice, version),
                updatedAt = System.currentTimeMillis()
            )
            resolved.copy(anchorId = state?.let {
                VoiceMessageVersionPolicy.archivedAnchorIdForVoice(
                    resolved, it, recoverUnversionedAnchor = voice.messageVersionId.isNullOrBlank()
                )
            })
        }
        restoreForSession(sessionId, remapped)
    }

    private suspend fun ensureInitializedLocked() {
        if (initialized) return
        _voices.value = storage.loadAll(VOICE_TYPE, GeneratedVoiceMessage.serializer())
        anchorsByVersion = storage.loadAllById(ANCHOR_TYPE, VoiceAnchorState.serializer()).entries
            .groupBy { VoiceAnchorStateKey(it.value) }
            .mapValues { (key, entries) -> (entries.firstOrNull { it.key == key.storageId } ?: entries.first()).value }
        initialized = true
    }

    private suspend fun migrateLegacyVoiceVersionsLocked(message: ChatMessage) {
        val legacyKey = VoiceAnchorStateKey(message.sessionId, message.id, null)
        val legacyState = anchorsByVersion[legacyKey] ?: adoptUnscopedAnchorLocked(legacyKey)
        val versions = MessageAlternativeVersionPolicy.versions(message)
        val versionsById = versions.associateBy { it.id }
        val legacyVoiceIds = _voices.value.filter { voice ->
            if (voice.sessionId != message.sessionId || voice.messageId != message.id || voice.versionOwnershipConfirmed) {
                return@filter false
            }
            if (voice.messageVersionId.isNullOrBlank()) return@filter true
            val recorded = versionsById[voice.messageVersionId]
            voice.requiresCompatibleVersion && (recorded == null || !VoiceMessageVersionPolicy.isCompatibleVersion(recorded, voice))
        }.mapTo(mutableSetOf(), GeneratedVoiceMessage::id)
        if (legacyState == null && legacyVoiceIds.isEmpty()) return

        val activeVersionId = MessageAlternativeVersionPolicy.activeVersionId(message)
        val legacyStateVersionId = legacyState?.let { state ->
            versions.firstOrNull { it.content == state.displayContentSnapshot }?.id
                ?: activeVersionId
        }
        if (legacyState != null && legacyStateVersionId != null) {
            val migratedKey = VoiceAnchorStateKey(message.sessionId, message.id, legacyStateVersionId)
            if (anchorsByVersion[migratedKey] == null) {
                val migratedState = legacyState.copy(
                    messageVersionId = legacyStateVersionId,
                    sessionId = message.sessionId
                )
                saveAnchorLocked(migratedKey, migratedState)
            }
        }

        val changed = _voices.value.map { voice ->
            if (voice.id !in legacyVoiceIds) {
                voice
            } else {
                val versionId = if (voice.requiresCompatibleVersion) {
                    VoiceMessageVersionPolicy.recoverCompatibleVersionId(message, voice)
                        ?: return@map voice.copy(anchorId = null)
                } else {
                    VoiceMessageVersionPolicy.inferLegacyVersionId(message, voice, legacyState)
                }
                val version = versions.firstOrNull { it.id == versionId }
                val key = VoiceAnchorStateKey(message.sessionId, message.id, versionId)
                var state = anchorsByVersion[key] ?: adoptUnscopedAnchorLocked(key)
                if (state == null && version != null) {
                    state = VoiceAnchorPolicy.initialState(
                        messageId = message.id,
                        content = version.content,
                        messageVersionId = versionId,
                        sessionId = message.sessionId,
                        includeNarration = true
                    )
                    saveAnchorLocked(key, state)
                }
                val resolved = voice.copy(
                    messageVersionId = versionId,
                    versionOwnershipConfirmed = false,
                    legacyVersionContentHash = VoiceMessageVersionPolicy.legacyVersionContentHash(voice, version),
                    updatedAt = System.currentTimeMillis()
                )
                resolved.copy(anchorId = state?.let {
                    if (voice.requiresCompatibleVersion) VoiceMessageVersionPolicy.archivedAnchorIdForVoice(
                        resolved, it, recoverUnversionedAnchor = voice.messageVersionId.isNullOrBlank()
                    ) else VoiceMessageVersionPolicy.anchorIdForVoice(voice, it)
                })
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

    private companion object {
        const val VOICE_TYPE = "generated_voice_messages"
        const val ANCHOR_TYPE = "voice_anchor_states"
    }
}

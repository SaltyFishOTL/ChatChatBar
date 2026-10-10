package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotImagePolicy
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.MemoryRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.memory.LongTermMemoryService
import com.example.chatbar.domain.rag.RagRepository
import com.example.chatbar.domain.voice.FishAudioStorage
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Uses a temporary streaming package; settings are copied directly so new fields survive. */
class SessionCopyService(
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryService: LongTermMemoryService,
    private val ragRepository: RagRepository,
    private val voiceRepository: VoiceMessageRepository,
    private val audioStorage: FishAudioStorage,
    private val packages: SaveSlotPackageStorage
) {
    private val mutex = Mutex()

    suspend fun copySession(sourceId: String, onProgress: (String) -> Unit = {}): ChatSession =
        mutex.withLock {
            com.example.chatbar.domain.backup.LocalDataMaintenance.access {
                withContext(Dispatchers.IO) {
                    onProgress("正在读取会话与记忆…")
                    // Also upgrades old source-turn/fingerprint records before IDs and paths change.
                    val memory = memoryService.snapshot(sourceId)
                    val source = chatRepository.getSession(sourceId) ?: error("会话已不存在")
                    val ids = chatRepository.getMessageIds(sourceId)
                    val voices = voiceRepository.listForSession(sourceId)
                    val anchors = voiceRepository.snapshotAnchorsForMessages(ids)
                    val draft = chatRepository.getSessionDraft(sourceId)
                    val targetId = UUID.randomUUID().toString()
                    val policy = SessionCopyPolicy(targetId, ids)
                    val target = policy.session(
                        source, targetId,
                        SessionCopyPolicy.copyName(source, chatRepository.getAllSessions()),
                        System.currentTimeMillis()
                    )
                    val base = SaveSlot.create(sourceId, "复制会话").copy(
                        schemaVersion = SaveSlotPackageStorage.SCHEMA_VERSION,
                        chatBackground = source.chatBackground,
                        memorySnapshot = memory
                    )
                    var slot: SaveSlot? = null
                    var reader: SaveSlotPackageStorage.Reader? = null
                    try {
                        val packaged = packages.createPackage(
                            baseSlot = base,
                            imagePolicy = SaveSlotImagePolicy.ORIGINAL,
                            includeAudio = true,
                            messageSource = { emit -> chatRepository.forEachMessage(sourceId, action = emit) },
                            ragSource = { emit -> ragRepository.forEachChunkForSession(sourceId, emit) },
                            voices = voices,
                            onProgress = onProgress
                        )
                        slot = packaged
                        check(chatRepository.getSession(sourceId) == source &&
                            chatRepository.getMessageIds(sourceId) == ids &&
                            voiceRepository.listForSession(sourceId) == voices) {
                            "原会话在复制期间发生变化，请待生成或编辑结束后重试"
                        }
                        val packageReader = packages.openReader(packaged)
                        reader = packageReader
                        onProgress("正在校验并复制图片、语音…")
                        packageReader.validate()
                        val background = packageReader.materializeBackground()
                        val copiedVoices = packageReader.restoreVoices(targetId, audioStorage).map { voice ->
                            voice.copy(messageId = policy.messageId(voice.messageId))
                        }
                        onProgress("正在复制消息与检索记忆…")
                        chatRepository.replaceMessagesForSessionStreaming(targetId) { emit ->
                            packageReader.streamMessages(targetId) { emit(policy.message(it, targetId)) }
                        }
                        ragRepository.replaceChunksForSessionStreaming(targetId) { emit ->
                            packageReader.streamRag(targetId) { emit(policy.chunk(it)) }
                        }
                        voiceRepository.restoreCopiedAnchors(anchors.map { anchor ->
                            anchor.copy(sessionId = targetId, messageId = policy.messageId(anchor.messageId))
                        })
                        // Keep each committed record visible to rollback even if a later write fails.
                        copiedVoices.forEach { voiceRepository.save(it) }
                        chatRepository.updateSessionDraft(targetId, draft)
                        onProgress("正在保存会话设置与长期记忆…")
                        chatRepository.createSession(target.copy(chatBackground = background))
                        memoryService.loadSnapshot(targetId, memory)
                        requireNotNull(chatRepository.getSession(targetId))
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            // Each cleanup is independent: one failed store must not skip the others.
                            val cleanups: List<suspend () -> Unit> = listOf(
                                { chatRepository.deleteSessionRecord(targetId) },
                                { chatRepository.deleteMessagesForSession(targetId); Unit },
                                { memoryRepository.deleteForSession(targetId) },
                                { ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, targetId) },
                                { voiceRepository.deleteForSession(targetId); Unit },
                                { reader?.cleanupCreatedFiles(audioStorage) }
                            )
                            cleanups.forEach { cleanup ->
                                try { cleanup() } catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
                            }
                        }
                        if (error.suppressed.isNotEmpty()) {
                            throw IllegalStateException("复制失败且部分临时数据清理失败：${error.message}", error)
                        }
                        throw error
                    } finally {
                        try {
                            reader?.close()
                        } finally {
                            slot?.let(packages::delete)
                        }
                    }
                }
            }
        }
}

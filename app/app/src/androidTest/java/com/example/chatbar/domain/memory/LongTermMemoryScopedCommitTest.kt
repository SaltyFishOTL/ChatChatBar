package com.example.chatbar.domain.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryCompressionKind
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.MemoryRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.chat.ContextWindowManager
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongTermMemoryScopedCommitTest {
    @Test
    fun applicationOwnedBackfillCommitsAfterPageObserverIsCancelled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = JsonFileStorage(context)
        val chatRepository = ChatRepository(storage)
        val memoryRepository = MemoryRepository(storage)
        val settingsRepository = SettingsRepository(storage)
        val originalSettings = settingsRepository.getAppSettings()
        val session = ChatSession.create(
            characterCardId = "test-character",
            title = "application-backfill-${UUID.randomUUID()}"
        )
        val ai = PausingMemoryAiClient()
        val service = LongTermMemoryService(
            chatRepository = chatRepository,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            ai = ai,
            contextWindowManager = ContextWindowManager()
        )
        val applicationJob = SupervisorJob()
        val applicationScope = CoroutineScope(applicationJob + Dispatchers.Default)
        val pageScope = CoroutineScope(Job() + Dispatchers.Default)
        val visibleProgress = MutableStateFlow<MemoryBackfillProgress?>(null)

        try {
            settingsRepository.saveAppSettings(
                originalSettings.copy(
                    defaultContextWindowSize = 1,
                    episodeMaxSourceTurns = 1
                )
            )
            chatRepository.createSession(session.copy(longTermMemoryEnabled = true))
            service.ensureMigrated(session.id)
            chatRepository.replaceMessagesForSession(session.id, stableTurns(session.id))
            val initialState = requireNotNull(memoryRepository.getState(session.id))
            memoryRepository.saveState(
                initialState.copy(
                    timeline = listOf(
                        MemoryTimelineEntry("s0", 0, 0),
                        MemoryTimelineEntry("s1", 1, 1)
                    ),
                    gaps = listOf(
                        MemoryGap(
                            id = "gap-s0",
                            sourceTurnIds = listOf("s0"),
                            startSourceOrder = 0,
                            endSourceOrder = 0,
                            reason = MemoryGapReason.LEGACY_UNKNOWN
                        )
                    )
                )
            )

            val observer = pageScope.launch { visibleProgress.collect { } }
            val backfill = applicationScope.async {
                service.startBackfill(session.id, model()) { visibleProgress.value = it }
            }
            withTimeout(5_000) { ai.episodeStarted.await() }
            withTimeout(5_000) { ai.summaryStreamed.await() }

            pageScope.cancel()
            assertTrue(observer.isCancelled)
            ai.releaseEpisode.complete(Unit)
            withTimeout(5_000) { backfill.await() }

            val persisted = requireNotNull(MemoryRepository(storage).getState(session.id))
            assertEquals(MemoryBackfillStatus.IDLE, persisted.backfill.status)
            assertTrue(persisted.backfill.pendingSourceTurnIds.isEmpty())
            assertEquals(listOf("s0"), service.activeNodes(session.id).single().sourceTurnIds)
        } finally {
            ai.releaseEpisode.complete(Unit)
            pageScope.cancel()
            applicationJob.cancel()
            settingsRepository.saveAppSettings(originalSettings)
            memoryRepository.deleteForSession(session.id)
            chatRepository.deleteSession(session.id)
        }
    }

    @Test
    fun unrelatedHeadUpdateDuringEpisodeGenerationIsRebasedAndPreserved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = JsonFileStorage(context)
        val chatRepository = ChatRepository(storage)
        val memoryRepository = MemoryRepository(storage)
        val settingsRepository = SettingsRepository(storage)
        val originalSettings = settingsRepository.getAppSettings()
        val session = ChatSession.create(
            characterCardId = "test-character",
            title = "scoped-commit-${UUID.randomUUID()}"
        )
        val ai = PausingMemoryAiClient()
        val service = LongTermMemoryService(
            chatRepository = chatRepository,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            ai = ai,
            contextWindowManager = ContextWindowManager()
        )

        try {
            settingsRepository.saveAppSettings(
                originalSettings.copy(
                    defaultContextWindowSize = 1,
                    episodeMaxSourceTurns = 1
                )
            )
            chatRepository.createSession(session)
            service.ensureMigrated(session.id)
            chatRepository.replaceMessagesForSession(session.id, stableTurns(session.id))

            val update = async(Dispatchers.Default) {
                service.updateArchiveAfterReply(
                    sessionId = session.id,
                    modelConfig = model(),
                    contextWindowSize = 1
                )
            }
            withTimeout(5_000) { ai.episodeStarted.await() }

            val duringGeneration = requireNotNull(memoryRepository.getState(session.id))
            memoryRepository.saveState(
                duringGeneration.copy(
                    head = duringGeneration.head.copy(
                        location = "无关HEAD修改",
                        version = duringGeneration.head.version + 1
                    ),
                    revision = duringGeneration.revision + 1
                )
            )
            ai.releaseEpisode.complete(Unit)
            withTimeout(5_000) { update.await() }

            val after = requireNotNull(memoryRepository.getState(session.id))
            val episodes = service.activeNodes(session.id)
            assertEquals("无关HEAD修改", after.head.location)
            assertTrue(after.pendingSourceTurnIds.isEmpty())
            assertEquals(1, episodes.size)
            assertEquals(listOf("s0"), episodes.single().sourceTurnIds)
        } finally {
            ai.releaseEpisode.complete(Unit)
            settingsRepository.saveAppSettings(originalSettings)
            memoryRepository.deleteForSession(session.id)
            chatRepository.deleteSession(session.id)
        }
    }

    private fun stableTurns(sessionId: String): List<ChatMessage> = listOf(
        message("u0", sessionId, MessageRole.USER, "s0", 0, 10),
        message("a0", sessionId, MessageRole.ASSISTANT, "s0", 0, 20),
        message("u1", sessionId, MessageRole.USER, "s1", 1, 30),
        message("a1", sessionId, MessageRole.ASSISTANT, "s1", 1, 40)
    )

    private fun message(
        id: String,
        sessionId: String,
        role: MessageRole,
        sourceTurnId: String,
        sourceTurnOrder: Long,
        createdAt: Long
    ) = ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        content = id,
        createdAt = createdAt,
        updatedAt = createdAt,
        orderKey = createdAt,
        sourceTurnId = sourceTurnId,
        sourceTurnOrder = sourceTurnOrder,
        timelineTurn = sourceTurnOrder
    )

    private fun model() = ModelConfig(
        id = "test-model",
        displayName = "test-model",
        baseUrl = "https://invalid.example/v1",
        apiKey = "",
        modelName = "test-model",
        createdAt = 1
    )

    private class PausingMemoryAiClient : MemoryAiClient {
        val episodeStarted = CompletableDeferred<Unit>()
        val summaryStreamed = CompletableDeferred<Unit>()
        val releaseEpisode = CompletableDeferred<Unit>()

        override suspend fun episode(
            model: ModelConfig,
            renderedTurns: String,
            summaryPromptMaxChars: Int,
            onStreamingSummary: ((String) -> Unit)?,
            validate: (EpisodeResponse) -> Unit
        ): EpisodeResponse {
            episodeStarted.complete(Unit)
            onStreamingSummary?.invoke("Episode摘要")
            summaryStreamed.complete(Unit)
            releaseEpisode.await()
            return EpisodeResponse("Episode摘要").also(validate)
        }

        override suspend fun compression(
            model: ModelConfig,
            kind: MemoryCompressionKind,
            forcedConsumedChildIds: List<String>,
            renderedChildren: String,
            onStreamingSummary: ((String) -> Unit)?,
            validate: (CompressionResponse) -> Unit
        ): CompressionResponse = error("测试不应触发压缩")

        override suspend fun head(
            model: ModelConfig,
            mode: MemoryHeadUpdateMode,
            throughT: Long,
            currentHead: String,
            archive: String,
            sourceTurns: String,
            validate: (HeadResponse) -> Unit
        ): HeadResponse = HeadResponse(
            throughT = throughT,
            location = "测试地点"
        ).also(validate)
    }
}

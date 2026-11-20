package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ActionOptionsFormat
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemoryHeadActionProjection
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySessionSnapshot
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemorySnapshot
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotImagePolicy
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.repository.MemoryRepository
import com.example.chatbar.domain.memory.MemorySourceFingerprint
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionMemoryProjectionRuntimeTest {
    @Test
    fun firstMenuMutationWhileMemoryIsDisabledProtectsLegacyDataAndInvalidatesDependentChunks() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
        val card = CharacterCard.create("synthetic-action-memory")
        app.characterRepository.save(card)
        try {
            verifyFirstMutation(app, card.id, deleteReply = false)
            verifyFirstMutation(app, card.id, deleteReply = true)
        } finally {
            app.characterRepository.delete(card.id)
        }
    }

    private suspend fun verifyFirstMutation(app: ChatBarApp, cardId: String, deleteReply: Boolean) {
        val session = ChatSession.create(cardId, "synthetic-action-memory").copy(longTermMemoryEnabled = false)
        val store = ViewModelStore()
        app.chatRepository.createSession(session)
        try {
            val earlier = if (deleteReply) {
                val question = app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "阁楼里有灯吗？"))
                val answer = app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "窗边亮着一盏油灯。"))
                listOf(question, answer)
            } else {
                emptyList()
            }
            val user = app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "查看星图"))
            val menu = app.chatRepository.addMessage(ChatMessage.create(
                session.id, MessageRole.ASSISTANT,
                "[choices]\nChoices\n1. 封好地图\n2. 交出铜钥匙\n[/choices]",
            ).copy(actionOptionsFormat = ActionOptionsFormat("[choices]", "[/choices]", "Choices")))
            val sourceId = requireNotNull(menu.sourceTurnId)
            assertEquals(user.sourceTurnId, sourceId)
            assertFalse(ChatMemoryIndexPolicy.contributesToIndex(menu))

            val reference = MemoryNode(
                id = MemoryNode.newId(), sessionId = session.id, tier = MemoryTier.LEGACY_REFERENCE,
                sourceTurnIds = listOf(sourceId), content = "合成旧档案里的选项正文",
            )
            val oldHead = if (deleteReply) {
                val tail = app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "收好已经看过的星图"))
                val tailId = requireNotNull(tail.sourceTurnId)
                val earlyId = requireNotNull(earlier.first().sourceTurnId)
                MemoryHead(
                    throughSourceTurnId = tailId,
                    goals = "合成旧HEAD里的选项目标",
                    sourceFingerprints = mapOf(
                        earlyId to MemorySourceFingerprint.semantic(earlyId, earlier, session),
                        tailId to MemorySourceFingerprint.semantic(tailId, listOf(tail), session),
                    ),
                )
            } else {
                MemoryHead(throughSourceTurnId = sourceId, goals = "合成旧HEAD里的选项目标")
            }
            app.memoryRepository.saveNodes(listOf(reference))
            app.memoryRepository.saveState(MemorySessionState(
                sessionId = session.id, head = oldHead, legacyReferenceNodeIds = listOf(reference.id),
            ))
            val independent = VectorChunk.create(ChunkSourceType.CHAT_MEMORY, session.id, "独立卡片", listOf(1f))
            val dependent = VectorChunk.create(
                sourceType = ChunkSourceType.CHAT_MEMORY,
                sourceId = session.id,
                content = "选择衍生卡片",
                embedding = listOf(1f),
                metadata = mapOf(ChatMemoryIndexPolicy.ACTION_CHOICE_SOURCE_IDS to menu.id),
            )
            app.ragRepository.saveChunks(listOf(independent, dependent))

            val vm = withContext(Dispatchers.Main) {
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(session.id) as T
                }
                ViewModelProvider(store, factory)[ChatViewModel::class.java]
            }
            withTimeout(30_000) { vm.messages.first { messages -> messages.any { it.id == menu.id } } }
            assertFalse(requireNotNull(app.memoryRepository.getNode(reference.id)).actionProjectionWithheld)
            assertFalse(requireNotNull(app.memoryRepository.getState(session.id)).head.stale)

            val replacement = "钟声从远处响起。"
            withContext(Dispatchers.Main) {
                if (deleteReply) vm.deleteMessage(menu.id) else vm.editMessage(menu.id, replacement, emptyList())
            }
            withTimeout(30_000) {
                while (app.ragRepository.getAllChunksForSession(session.id).any { it.id == dependent.id }) delay(25)
            }
            val durableReply = app.chatRepository.getMessage(menu.id, session.id)
            assertEquals(if (deleteReply) null else replacement, durableReply?.displayContent)
            assertEquals(listOf(independent.id), app.ragRepository.getAllChunksForSession(session.id).map { it.id })

            val fresh = MemoryRepository(JsonFileStorage(app))
            assertEquals(reference.copy(actionProjectionWithheld = true), fresh.getNode(reference.id))
            assertEquals(oldHead.copy(stale = true, actionProjection = MemoryHeadActionProjection.WITHHELD),
                requireNotNull(fresh.getState(session.id)).head)
            val prompt = app.longTermMemoryService.promptView(session.id)
            assertFalse(prompt.archive.contains(reference.body))
            assertFalse(prompt.headAndTimeline.contains(oldHead.goals))
            assertTrue(requireNotNull(fresh.getNode(reference.id)).actionProjectionWithheld)
            if (deleteReply) restoreOlderPackage(app, vm, session.id, reference, oldHead)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.saveSlotRepository.deleteBySessionId(session.id)
            app.ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, session.id)
            app.memoryRepository.deleteForSession(session.id)
            app.chatRepository.deleteSession(session.id)
        }
    }

    private suspend fun restoreOlderPackage(
        app: ChatBarApp,
        vm: ChatViewModel,
        sessionId: String,
        reference: MemoryNode,
        oldHead: MemoryHead
    ) {
        val state = requireNotNull(app.memoryRepository.getState(sessionId))
        val session = requireNotNull(app.chatRepository.getSession(sessionId))
        val oldSnapshot = MemorySnapshot(
            state = MemorySessionSnapshot(
                legacyReferenceNodeIds = listOf(reference.id), head = oldHead, timeline = state.timeline,
                memoryWasEnabled = false,
            ),
            nodes = listOf(reference),
        )
        val oldPackage = app.saveSlotPackageStorage.createPackage(
            baseSlot = SaveSlot.create(sessionId, "合成旧存档").copy(
                memorySnapshot = oldSnapshot, longTermMemoryEnabled = false,
                nextSourceTurnOrder = session.nextSourceTurnOrder, nextTimelineTurn = session.nextTimelineTurn,
            ),
            imagePolicy = SaveSlotImagePolicy.NONE,
            includeAudio = false,
            messageSource = { emit -> app.chatRepository.forEachMessage(sessionId, action = emit) },
            ragSource = {},
            voices = emptyList(),
        )
        app.saveSlotRepository.save(oldPackage)
        assertEquals(oldSnapshot, app.saveSlotRepository.getById(oldPackage.id)?.memorySnapshot)
        val summary = requireNotNull(app.saveSlotRepository.getSummaryById(oldPackage.id))
        withContext(Dispatchers.Main) { vm.loadSaveSlot(summary) }
        val result = withTimeout(30_000) { vm.saveSlotOperation.first { !it.busy && it.status != null } }
        assertEquals("读档完成。", result.status)

        val fresh = MemoryRepository(JsonFileStorage(app))
        assertEquals(reference.copy(actionProjectionWithheld = true), fresh.getNode(reference.id))
        assertEquals(oldHead.copy(stale = true, actionProjection = MemoryHeadActionProjection.WITHHELD),
            requireNotNull(fresh.getState(sessionId)).head)
        val prompt = app.longTermMemoryService.promptView(sessionId)
        assertFalse(prompt.archive.contains(reference.body))
        assertFalse(prompt.headAndTimeline.contains(oldHead.goals))
    }
}

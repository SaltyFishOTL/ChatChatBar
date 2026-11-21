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
import com.example.chatbar.data.local.entity.MemoryCommit
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
import com.example.chatbar.data.repository.SaveSlotRepository
import com.example.chatbar.domain.chat.SaveSlotJsonTransfer
import com.example.chatbar.domain.memory.MemorySourceFingerprint
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
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
        awaitPhase("starting the app") { app.dependenciesReady.first { it } }
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
        val action = if (deleteReply) "delete" else "edit"
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
                MemoryHead(throughSourceTurnId = sourceId, goals = "合成旧HEAD里的选项目标", version = 1)
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
                metadata = mapOf(
                    "sessionId" to session.id,
                    "indexMode" to ChatMemoryIndexPolicy.INDEX_MODE,
                    "contentVersion" to ChatMemoryIndexPolicy.CONTENT_VERSION,
                    ChatMemoryIndexPolicy.ACTION_CHOICE_SOURCE_IDS to menu.id,
                ),
            )
            app.ragRepository.saveChunks(listOf(independent, dependent))

            val vm = withContext(Dispatchers.Main) {
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(session.id) as T
                }
                ViewModelProvider(store, factory)[ChatViewModel::class.java]
            }
            awaitPhase("loading the menu for $action") {
                vm.messages.first { messages -> messages.any { it.id == menu.id } }
            }
            assertFalse(requireNotNull(app.memoryRepository.getNode(reference.id)).actionProjectionWithheld)
            assertFalse(requireNotNull(app.memoryRepository.getState(session.id)).head.stale)

            val replacement = "钟声从远处响起。"
            withContext(Dispatchers.Main) {
                if (deleteReply) vm.deleteMessage(menu.id) else vm.editMessage(menu.id, replacement, emptyList())
            }
            awaitPhase("removing the dependent index after menu $action") {
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
            else restoreOlderInlineHead(app, vm, session.id, oldHead)
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
        suspend fun createLegacyPackage(owner: String, snapshot: MemorySnapshot): SaveSlot =
            app.saveSlotPackageStorage.createPackage(
                baseSlot = SaveSlot.create(owner, "合成旧存档").copy(
                    memorySnapshot = snapshot, longTermMemoryEnabled = false,
                    nextSourceTurnOrder = session.nextSourceTurnOrder, nextTimelineTurn = session.nextTimelineTurn,
                ),
                imagePolicy = SaveSlotImagePolicy.NONE,
                includeAudio = false,
                messageSource = { emit ->
                    app.chatRepository.forEachMessage(sessionId) { emit(it.copy(sessionId = owner)) }
                },
                ragSource = {},
                voices = emptyList(),
            )

        val oldPackage = createLegacyPackage(sessionId, oldSnapshot)
        app.saveSlotRepository.save(oldPackage)
        assertEquals(oldSnapshot, app.saveSlotRepository.getById(oldPackage.id)?.memorySnapshot)
        loadArchive(app, vm, oldPackage.id, "same-session archive and HEAD")

        val fresh = MemoryRepository(JsonFileStorage(app))
        assertEquals(reference.copy(actionProjectionWithheld = true), fresh.getNode(reference.id))
        assertEquals(oldHead.copy(stale = true, actionProjection = MemoryHeadActionProjection.WITHHELD),
            requireNotNull(fresh.getState(sessionId)).head)
        val prompt = app.longTermMemoryService.promptView(sessionId)
        assertFalse(prompt.archive.contains(reference.body))
        assertFalse(prompt.headAndTimeline.contains(oldHead.goals))

        val headOnly = MemorySnapshot(
            state = MemorySessionSnapshot(head = oldHead, timeline = state.timeline, memoryWasEnabled = false),
        )
        val localHeadOnly = createLegacyPackage(sessionId, headOnly)
        app.saveSlotRepository.save(localHeadOnly)
        loadArchive(app, vm, localHeadOnly.id, "same-session HEAD only")
        assertEquals(MemoryHeadActionProjection.WITHHELD,
            MemoryRepository(JsonFileStorage(app)).getState(sessionId)?.head?.actionProjection)

        val external = createLegacyPackage("foreign-$sessionId", headOnly)
        try {
            val bytes = ByteArrayOutputStream()
            app.saveSlotPackageStorage.export(external, bytes)
            val imported = vm.importSaveSlotJson(bytes.toByteArray().inputStream())
            assertEquals(sessionId, imported.sessionId)
            loadArchive(app, vm, imported.id, "imported foreign HEAD only")
            assertEquals(oldHead, MemoryRepository(JsonFileStorage(app)).getState(sessionId)?.head)
        } finally {
            app.saveSlotPackageStorage.delete(external)
        }
    }

    private suspend fun restoreOlderInlineHead(
        app: ChatBarApp,
        vm: ChatViewModel,
        sessionId: String,
        oldHead: MemoryHead
    ) {
        val coldMemory = { MemoryRepository(JsonFileStorage(app)) }
        val state = requireNotNull(coldMemory().getState(sessionId))
        val session = requireNotNull(app.chatRepository.getSession(sessionId))
        val messages = app.chatRepository.getMessages(sessionId)
        val snapshot = MemorySnapshot(
            state = MemorySessionSnapshot(head = oldHead, timeline = state.timeline, memoryWasEnabled = false),
        )
        fun legacySnapshot(owner: String) = MemorySnapshot(
            schemaVersion = 3, legacyCommit = MemoryCommit("commit-$owner", owner, head = oldHead),
        )
        fun archive(owner: String, memory: MemorySnapshot = snapshot) = SaveSlot.create(
            owner, "合成旧内联存档", messages = messages.map { it.copy(sessionId = owner) },
        ).copy(
            memorySnapshot = memory, longTermMemoryEnabled = false,
            nextSourceTurnOrder = session.nextSourceTurnOrder, nextTimelineTurn = session.nextTimelineTurn,
        )

        suspend fun importFile(write: suspend (OutputStream) -> Unit): SaveSlot {
            val file = File.createTempFile("synthetic-inline-memory-", ".json", app.cacheDir)
            try {
                file.outputStream().buffered().use { write(it) }
                val imported = file.inputStream().buffered().use { vm.importSaveSlotJson(it) }
                assertEquals(sessionId, imported.sessionId)
                val coldSlots = SaveSlotRepository(JsonFileStorage(app), app.saveSlotPackageStorage)
                return requireNotNull(coldSlots.getById(imported.id))
            } finally {
                file.delete()
            }
        }

        val same = importFile { SaveSlotJsonTransfer.write(archive(sessionId, legacySnapshot(sessionId)), it) }
        assertEquals(sessionId, same.memorySnapshot?.legacyCommit?.sessionId)
        loadArchive(app, vm, same.id, "same-session inline legacy commit")
        val preserved = requireNotNull(coldMemory().getState(sessionId))
        assertEquals(oldHead.copy(stale = true, actionProjection = MemoryHeadActionProjection.WITHHELD), preserved.head)
        assertFalse(app.longTermMemoryService.promptView(sessionId).headAndTimeline.contains(oldHead.goals))

        val foreignOwner = "foreign-$sessionId"
        val foreign = importFile { SaveSlotJsonTransfer.write(archive(foreignOwner, legacySnapshot(foreignOwner)), it) }
        val rebound = importFile { vm.exportSaveSlotJson(foreign.id, it) }
        assertEquals(foreignOwner, rebound.memorySnapshot?.legacyCommit?.sessionId)
        // Older builds stored and re-exported the destination IDs without independent HEAD ownership.
        val oldForward = archive(foreignOwner).copy(sessionId = sessionId, messages = messages)
        val unknown = importFile { SaveSlotJsonTransfer.write(oldForward, it) }
        assertEquals(snapshot, unknown.memorySnapshot)
        for ((description, imported) in listOf("foreign commit re-exported" to rebound, "old forwarded HEAD" to unknown)) {
            app.memoryRepository.saveState(preserved)
            loadArchive(app, vm, imported.id, description)
            assertEquals(description, oldHead, requireNotNull(coldMemory().getState(sessionId)).head)
        }
    }

    private suspend fun loadArchive(app: ChatBarApp, vm: ChatViewModel, slotId: String, description: String) {
        val summary = requireNotNull(app.saveSlotRepository.getSummaryById(slotId))
        awaitPhase("restoring $description") {
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                vm.saveSlotOperation.dropWhile { !it.busy }.first { !it.busy }
            }
            withContext(Dispatchers.Main) { vm.loadSaveSlot(summary) }
            assertEquals(description, "读档完成。", result.await().status)
        }
    }

    private suspend fun <T> awaitPhase(phase: String, block: suspend CoroutineScope.() -> T): T =
        try {
            withTimeout(30_000, block)
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Timed out while $phase", error)
        }
}

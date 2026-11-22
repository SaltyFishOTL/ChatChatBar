package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import com.example.chatbar.domain.rag.RagRepository
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** Exercise real edit, deletion, RAG rebuild and reopen using a synthetic embedding endpoint. */
class ReplyRagPublicationRuntimeTest {
    @Test fun lateUndoEmbeddingCannotOverwriteSurvivorRebuiltByDeletion() = withFixture {
        assertTrue(rebuild())
        assertTrue(recalledText().contains(REPAIRED))
        val staleRequest = CountDownLatch(1)
        val releaseStaleRequest = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.body.clone().readUtf8().contains(ORIGINAL)) {
                    staleRequest.countDown()
                    if (!releaseStaleRequest.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                }
                return embeddingResponse()
            }
        }
        val undo = launchAction { restoreMessageFormatOriginal(reply.id) }
        try {
            assertTrue("The discarded candidate's embedding must be waiting", staleRequest.await(15, TimeUnit.SECONDS))
            val undone = vm.messages.value.single { it.id == reply.id }
            assertEquals(ORIGINAL, undone.displayContent)
            withTimeout(20_000) { launchAction { deleteMessageAlternative(undone, VERSION) }.join() }
            assertEquals(SURVIVOR, vm.messages.value.single { it.id == reply.id }.displayContent)
            assertTrue(recalledText().contains(SURVIVOR))
        } finally {
            releaseStaleRequest.countDown()
        }
        withTimeout(20_000) { undo.join() }
        val cold = RagRepository(JsonFileStorage(app)).getAllChunksForSession(session.id)
        val indexed = cold.single { it.messageId == reply.id }
        assertTrue(indexed.content.contains(SURVIVOR))
        assertFalse(indexed.content.contains(ORIGINAL))
        assertFalse(recalledText().contains(ORIGINAL))
    }

    @Test fun rebuildingAfterVectorReadFailureSurvivesRetryAndVoiceCleanupStillCompletes() = withFixture {
        assertTrue(rebuild())
        val audio = File(app.filesDir, "audio/generated/${session.id}/removed.mp3")
        check(audio.parentFile!!.mkdirs())
        audio.writeText("synthetic private audio")
        val voice = GeneratedVoiceMessage.create(
            sessionId = session.id,
            messageId = reply.id,
            messageVersionId = VERSION,
            anchorId = null,
            sourceOrder = 0,
            sourceSegmentKind = VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND,
            sourceSpeakerName = "",
            sourceText = REPAIRED,
            taggedText = REPAIRED,
            characterId = session.characterCardId,
            characterName = "Synthetic",
            voice = FishAudioVoiceBinding("synthetic-voice", "Synthetic"),
            fishModelId = "synthetic-model",
            audioPath = audio.path,
            durationMs = 1,
            byteLength = audio.length()
        )
        app.voiceMessageRepository.save(voice)
        val corrupt = File(app.filesDir, "entities/vector_chunks/${UUID.randomUUID()}.json")
        val journal = File(app.filesDir, "entities/pending_reply_removals")
        corrupt.writeText("{")
        try {
            withTimeout(20_000) { launchAction { deleteMessageAlternative(reply, VERSION) }.join() }
            assertEquals(SURVIVOR, vm.messages.value.single { it.id == reply.id }.displayContent)
            val pending = requireNotNull(journal.listFiles())
                .single { it.readText().contains(session.id) }
            assertTrue(pending.readText().contains("\"invalidateAutomaticMemory\": true"))
            assertNull(VoiceMessageRepository(JsonFileStorage(app)).get(voice.id))
            assertFalse(audio.exists())
            assertTrue(rebuild())
            assertTrue(recalledText().contains(SURVIVOR))
            assertFalse(recalledText().contains(REPAIRED))

            // Persist only the edit, as if a process exited before starting the follow-up embed.
            val current = requireNotNull(app.chatRepository.getMessage(reply.id, session.id))
            val edited = MessageAlternativeVersionPolicy.editCurrentContent(current, EDITED_SURVIVOR)
            assertNotNull(app.chatRepository.updateMessageIfUnchanged(current, edited))
            assertFalse("A further edit invalidates the earlier authorization", recalledText().contains(SURVIVOR))
            assertTrue(rebuild())
            assertTrue(recalledText().contains(EDITED_SURVIVOR))

            check(corrupt.delete())
            val reopened = ViewModelStore()
            try {
                val coldVm = withContext(Dispatchers.Main) {
                    ViewModelProvider(reopened, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
                }
                withTimeout(20_000) { coldVm.messages.first { messages -> messages.any { it.id == reply.id } } }
                assertFalse(pending.exists())
                assertTrue(recalledText().contains(EDITED_SURVIVOR))
                assertFalse(recalledText().contains(SURVIVOR))
                assertFalse(recalledText().contains(REPAIRED))
            } finally {
                withContext(Dispatchers.Main) { reopened.clear() }
            }
        } finally {
            corrupt.delete()
            audio.delete()
            audio.parentFile?.delete()
        }
    }

    @Test fun aPendingVectorFailureDoesNotBlockRemovingAnotherVersion() = withFixture {
        val laterVersion = "later-index-version"
        val appended = MessageAlternativeVersionPolicy.append(reply, "Later alternative with its own text", laterVersion)
        val selected = MessageAlternativeVersionPolicy.select(appended, 1)
        val before = requireNotNull(app.chatRepository.updateMessageIfUnchanged(reply, selected))
        withContext(Dispatchers.Main) { vm.refreshMessages() }
        withTimeout(20_000) { vm.messages.first { before in it } }
        assertTrue(rebuild())
        val corrupt = File(app.filesDir, "entities/vector_chunks/${UUID.randomUUID()}.json").apply { writeText("{") }
        val journal = File(app.filesDir, "entities/pending_reply_removals")
        try {
            withTimeout(20_000) { launchAction { deleteMessageAlternative(before, VERSION) }.join() }
            val remaining = vm.messages.value.single { it.id == reply.id }
            assertEquals(laterVersion, MessageAlternativeVersionPolicy.activeVersionId(remaining))
            withTimeout(20_000) { launchAction { deleteMessageAlternative(remaining, laterVersion) }.join() }
            assertEquals(listOf(SURVIVOR), vm.messages.value.single { it.id == reply.id }.alternatives)
            assertEquals(2, journal.listFiles().orEmpty().count { it.readText().contains(session.id) })
            assertTrue(recalledText().contains(SURVIVOR))
            check(corrupt.delete())
            app.replyRemovalCoordinator.resumePending(session.id)
            assertEquals(0, journal.listFiles().orEmpty().count { it.readText().contains(session.id) })
            assertTrue(recalledText().contains(SURVIVOR))
        } finally {
            corrupt.delete()
        }
    }

    @Test fun persistedFormatToggleDoesNotRecallOldIndexBeforeRebuild() = withFixture {
        assertTrue(rebuild())
        val coordinator = app.replyRemovalCoordinator
        val cached = coordinator.getChunksForRetrieval(null, session.id)
        assertTrue(cached.any { it.content.contains(REPAIRED) })
        val current = requireNotNull(app.chatRepository.getMessage(reply.id, session.id))
        val undone = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(current))
        assertNotNull(app.chatRepository.updateMessageIfUnchanged(current, undone))

        val disk = RagRepository(JsonFileStorage(app)).getAllChunksForSession(session.id)
        assertTrue(disk.any { it.content.contains(REPAIRED) })
        assertTrue(coordinator.selectCurrentRecallIds(session.id, cached, 10) { it }.isEmpty())

        assertTrue(rebuild())
        val rebuilt = coordinator.getChunksForRetrieval(null, session.id)
        val admitted = coordinator.selectCurrentRecallIds(session.id, rebuilt, 10) { it }
        assertTrue(rebuilt.any { it.id in admitted && it.content.contains(ORIGINAL) })
        assertFalse(rebuilt.any { it.id in admitted && it.content.contains(REPAIRED) })
    }

    @Test fun pendingRemovalCannotBeCopiedIntoAnotherSession() = withFixture {
        assertTrue(rebuild())
        val corrupt = File(app.filesDir, "entities/vector_chunks/${UUID.randomUUID()}.json").apply { writeText("{") }
        try {
            withTimeout(20_000) { launchAction { deleteMessageAlternative(reply, VERSION) }.join() }
            val blocked = runCatching { app.sessionCopyService.copySession(session.id) }
            blocked.getOrNull()?.let { app.deletionCoordinator.deleteSession(it.id) }
            assertTrue("Copy must not rekey data while its cleanup still fails", blocked.isFailure)

            check(corrupt.delete())
            val copied = app.sessionCopyService.copySession(session.id)
            try {
                val chunks = RagRepository(JsonFileStorage(app)).getAllChunksForSession(copied.id)
                assertTrue(chunks.any { it.content.contains(SURVIVOR) })
                assertFalse(chunks.any { it.content.contains(REPAIRED) })
            } finally {
                app.deletionCoordinator.deleteSession(copied.id)
            }
        } finally {
            corrupt.delete()
        }
    }

    @Test fun restoreCannotAuthorizeRetiredVectorFromPackagedArchive() = verifyArchiveSourceProof(packaged = true)
    @Test fun restoreCannotAuthorizeRetiredVectorFromLegacyArchive() = verifyArchiveSourceProof(packaged = false)

    private fun verifyArchiveSourceProof(packaged: Boolean) = withFixture {
        assertTrue(rebuild())
        val retired = app.ragRepository.getAllChunksForSession(session.id).single { it.messageId == reply.id }
        val corrupt = File(app.filesDir, "entities/vector_chunks/${UUID.randomUUID()}.json").apply { writeText("{") }
        var archived: SaveSlot? = null
        try {
            withTimeout(20_000) { launchAction { deleteMessageAlternative(reply, VERSION) }.join() }
            val live = app.ragRepository.getAllChunksForSession(session.id).single { it.messageId == reply.id }
            val manual = live.copy(
                id = UUID.randomUUID().toString(), content = "Handwritten memory",
                metadata = mapOf("indexMode" to "manual"), messageId = null
            )
            val vectors = listOf(live, retired.copy(id = UUID.randomUUID().toString()), manual)
            val messages = app.chatRepository.getMessages(session.id)
            val inline = SaveSlot.create(session.id, "Synthetic archived source proof", messages = messages).copy(
                longTermMemoryEnabled = false, vectorChunks = vectors
            )
            val slot = if (packaged) app.saveSlotPackageStorage.createPackage(
                baseSlot = inline.copy(schemaVersion = SaveSlotPackageStorage.SCHEMA_VERSION),
                imagePolicy = SaveSlotImagePolicy.NONE,
                includeAudio = false,
                messageSource = { emit -> messages.forEach { emit(it) } },
                ragSource = { emit -> vectors.forEach { emit(it) } },
                voices = emptyList()
            ) else inline
            archived = slot
            app.saveSlotRepository.save(slot)
            withContext(Dispatchers.Main) { vm.loadSaveSlot(slot.toSummary()) }
            val result = withTimeout(20_000) { vm.saveSlotOperation.first { !it.busy && !it.status.isNullOrBlank() } }
            assertEquals("读档完成。", result.status)

            val restored = RagRepository(JsonFileStorage(app)).getAllChunksForSession(session.id)
            assertTrue(restored.any { it.content == live.content })
            assertTrue(restored.any { it.content == manual.content })
            assertFalse(restored.any { it.content == retired.content })
            check(corrupt.delete())
            app.replyRemovalCoordinator.resumePending(session.id)
            assertTrue(recalledText().contains(SURVIVOR))
            assertTrue(recalledText().contains(manual.content))
            assertFalse(recalledText().contains(REPAIRED))

            val candidates = app.replyRemovalCoordinator.getChunksForRetrieval(null, session.id)
            val currentEmbeddingKey = requireNotNull(live.metadata["embeddingKey"])
            val compatible = rankChunksByMultiRoute(candidates, live.embedding, SURVIVOR, currentEmbeddingKey, 20)
                .single { it.chunk.content == live.content }
            val differentModel = rankChunksByMultiRoute(candidates, live.embedding, SURVIVOR, "other-model", 20)
                .single { it.chunk.content == live.content }
            assertNotNull(compatible.vectorRank)
            assertNull(differentModel.vectorRank)
            assertTrue(differentModel.lexicalScore > 0f)
        } finally {
            corrupt.delete()
            archived?.let { app.saveSlotRepository.delete(it.id) }
        }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(30_000) {
            app.dependenciesReady.first { it }
            app.initializePersistentState().join()
        }
        val settings = app.settingsRepository.getAppSettings()
        val previousEmbedding = app.modelRepository.getEmbeddingModel()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = embeddingResponse()
            }
            start()
        }
        val store = ViewModelStore()
        val card = CharacterCard.create("Synthetic RAG publication")
        val session = ChatSession.create(card.id, "Synthetic RAG publication").copy(
            contextWindowSize = 0, longTermMemoryEnabled = false, automaticImageGenerationEnabled = false
        )
        try {
            app.settingsRepository.saveAppSettings(settings.copy(
                allowCleartextModelApi = true, defaultContextWindowSize = 0
            ))
            app.modelRepository.saveEmbeddingModel(
                EmbeddingConfig.create("Synthetic vector endpoint", server.url("/v1").toString(), "synthetic-key", "synthetic-model", 2)
            )
            app.characterRepository.save(card)
            app.chatRepository.createSession(session)
            app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "Older indexed prompt"))
            val base = ChatMessage.create(session.id, MessageRole.ASSISTANT, SURVIVOR)
            val alternative = MessageAlternativeVersionPolicy.append(base, ORIGINAL, VERSION)
            val reply = app.chatRepository.addMessage(MessageFormatRepairPolicy.replaceCurrentDisplayContent(
                alternative, REPAIRED, MessageFormatRepairPolicy.completedRepairNotice(ORIGINAL, REPAIRED)
            ))
            app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "Latest prompt stays in context"))
            app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "Latest response stays in context"))
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
            }
            withTimeout(20_000) { vm.messages.first { messages -> messages.any { it.id == reply.id } } }
            Fixture(app, vm, session, reply, server).block()
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.replyRemovalCoordinator.resumePending(session.id)
            app.longTermMemoryAutoMaintenanceCoordinator.cancelAndJoinForSession(session.id)
            app.ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, session.id)
            app.voiceMessageRepository.deleteForSession(session.id)
            app.chatRepository.deleteMessagesForSession(session.id)
            app.chatRepository.deleteSessionRecord(session.id)
            app.characterRepository.delete(card.id)
            if (previousEmbedding != null) app.modelRepository.saveEmbeddingModel(previousEmbedding)
            else app.modelRepository.deleteEmbeddingModel()
            app.settingsRepository.saveAppSettings(settings)
            server.shutdown()
        }
    }

    private data class Fixture(
        val app: ChatBarApp,
        val vm: ChatViewModel,
        val session: ChatSession,
        val reply: ChatMessage,
        val server: MockWebServer
    ) {
        suspend fun rebuild(): Boolean = withContext(Dispatchers.Main) { vm.rebuildLegacyRagMemoryChunks() }

        suspend fun recalledText(): String = app.replyRemovalCoordinator.getChunksForRetrieval(null, session.id)
            .joinToString("\n") { it.content }

        suspend fun launchAction(action: ChatViewModel.() -> Unit): Job = withContext(Dispatchers.Main) {
            val scope = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            val before = scope.children.toSet()
            vm.action()
            scope.children.single { it !in before }
        }
    }

    private companion object {
        const val VERSION = "removed-index-version"
        const val SURVIVOR = "Surviving reply remains after the alternative is deleted"
        const val EDITED_SURVIVOR = "The surviving reply was edited again before retrying deletion cleanup"
        const val ORIGINAL = "Unformatted text from the deleted version"
        const val REPAIRED = "Formatted text from the deleted version"

        fun embeddingResponse() = MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"index":0,"embedding":[1.0,0.0]}]}""")
    }
}

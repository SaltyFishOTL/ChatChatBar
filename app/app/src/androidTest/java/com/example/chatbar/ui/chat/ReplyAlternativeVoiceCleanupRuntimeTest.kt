package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import android.content.ContextWrapper
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.backup.*
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import com.example.chatbar.domain.rag.RagRepository
import com.example.chatbar.domain.rag.VectorSearchEngine
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/** Actual VM, private JSON/audio ownership, cold reads and export. No speech or image service. */
class ReplyAlternativeVoiceCleanupRuntimeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun finishStartupAudioCleanupBeforeCreatingFixtures() = runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
            withTimeout(30_000) {
                app.dependenciesReady.first { it }
                // The dependency signal precedes the queued persistent-state orphan sweep.
                app.initializePersistentState().join()
            }
        }
    }

    @Test fun deletingCurrentVersionCleansVoiceAndAnchorFromColdStorageAndBackup() = verify()
    @Test fun clearingLastTextSegmentCleansRemovedVersionVoiceAndAnchorFromBackup() = verify(viaSegmentEdit = true)
    @Test fun clearingLastTextSegmentWithSharedImageKeepsImageAndSurvivingVoice() =
        verify(viaSegmentEdit = true, segmentWithImage = true)
    @Test fun clearingOnlyAlternativeThroughSegmentDeletesMessageAndVoice() = verify(viaSegmentEdit = true, last = true)
    @Test fun clearingOnlyAlternativePreservesAudioReferencedByIndependentMessage() = verify(viaSegmentEdit = true, last = true, sharedAudio = true)
    @Test fun clearingLastTextSegmentRejectsLateVoiceAndAnchorRecreation() = verify(viaSegmentEdit = true, lateVoice = true, lateAnchor = true, staleUi = true)
    @Test fun clearingLastTextSegmentReportsCleanupFailureAfterRefreshingMessage() = verify(viaSegmentEdit = true, cleanupFailure = true)
    @Test fun deletingInactiveLegacyVersionPreservesUnconfirmedAudio() = verify(legacy = true)
    @Test fun survivingVoiceKeepsSharedAudioAndMessageImage() = verify(sharedAudio = true)
    @Test fun deletingLastVersionStillDeletesItsVoiceAndMessage() = verify(last = true)
    @Test fun lastVersionDeletionFinishesVoiceCleanupAfterEditorCancellation() = verify(last = true, cancelAfterCommit = true)

    @Test fun canceledAlternativeDeletionInvalidatesIndexedTextOnColdReopen() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        val card = CharacterCard.create("Synthetic old indexed reply")
        val session = ChatSession.create(card.id, "Synthetic old indexed reply").copy(
            longTermMemoryEnabled = false, automaticImageGenerationEnabled = false
        )
        val store = ViewModelStore()
        val reopenStore = ViewModelStore()
        val vectorDirectory = File(app.filesDir, "entities/vector_chunks")
        var restoreWriteAccess = false
        try {
            app.characterRepository.save(card)
            app.chatRepository.createSession(session)
            val user = app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "Indexed user turn"))
            val original = ChatMessage.create(session.id, MessageRole.ASSISTANT, "Surviving alternative")
            val reply = app.chatRepository.addMessage(
                MessageAlternativeVersionPolicy.append(original, "Deleted indexed alternative", "indexed-version")
            )
            val turnId = requireNotNull(reply.sourceTurnId)
            val query = listOf(1f, 0f)
            fun indexedChunk(anchor: String?, metadata: Map<String, String>) = VectorChunk.create(
                ChunkSourceType.CHAT_MEMORY, session.id, reply.displayContent, query,
                messageId = anchor, metadata = metadata + mapOf(
                    "indexMode" to ChatMemoryIndexPolicy.INDEX_MODE,
                    "contentVersion" to ChatMemoryIndexPolicy.CONTENT_VERSION
                )
            )
            val direct = indexedChunk(reply.id, emptyMap())
            val byTurn = indexedChunk(null, mapOf("sourceTurnId" to turnId))
            val byMembers = indexedChunk(user.id, mapOf("messageIds" to "${user.id},${reply.id}"))
            val unaffected = indexedChunk(null, mapOf("sourceTurnId" to "another-turn")).copy(content = "Unrelated indexed text")
            val manual = VectorChunk.create(ChunkSourceType.CHAT_MEMORY, session.id, "Manual memory", query, reply.id)
            app.ragRepository.saveChunks(listOf(direct, byTurn, byMembers, unaffected, manual))
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
            }
            withTimeout(30_000) { vm.messages.first { rows -> rows.any { it.id == reply.id } } }
            assertEquals(reply.displayContent, app.chatRepository.getSession(session.id)?.lastMessagePreview)

            check(vectorDirectory.setWritable(false))
            restoreWriteAccess = true
            val canceledAfterCommit = CompletableDeferred<Unit>()
            val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                app.chatRepository.sessions.first { sessions ->
                    sessions.any { it.id == session.id && it.lastMessagePreview == original.displayContent }
                }
                vm.viewModelScope.cancel()
                canceledAfterCommit.complete(Unit)
            }
            try {
                withContext(Dispatchers.Main) { vm.deleteMessageAlternative(reply, "indexed-version") }
                withTimeout(15_000) { canceledAfterCommit.await() }
                LocalDataMaintenance.acquire().close()
            } finally {
                watcher.cancel()
            }
            assertEquals(original.displayContent, ChatRepository(JsonFileStorage(app)).getMessage(reply.id, session.id)?.displayContent)
            assertNotNull("Injected write failure should keep the old vector on disk", app.ragRepository.getChunkById(direct.id))
            check(vectorDirectory.setWritable(true))
            restoreWriteAccess = false

            val reopened = withContext(Dispatchers.Main) {
                ViewModelProvider(reopenStore, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
            }
            withTimeout(30_000) { reopened.messages.first { rows -> rows.any { it.id == reply.id } } }
            val coldRag = RagRepository(JsonFileStorage(app))
            val recalled = VectorSearchEngine().search(query, coldRag.getChunksForRetrieval(null, session.id))
            assertEquals("Cold retrieval must exclude every chunk for the deleted alternative",
                setOf(unaffected.id, manual.id), recalled.mapTo(mutableSetOf()) { it.id })
            assertEquals(original.displayContent, reopened.messages.value.single { it.id == reply.id }.displayContent)
        } finally {
            if (restoreWriteAccess) check(vectorDirectory.setWritable(true))
            withContext(Dispatchers.Main) { store.clear(); reopenStore.clear() }
            app.longTermMemoryAutoMaintenanceCoordinator.cancelAndJoinForSession(session.id)
            app.replyRemovalCoordinator.resumePending(session.id)
            app.ragRepository.deleteChunksBySource(ChunkSourceType.CHAT_MEMORY, session.id)
            app.voiceMessageRepository.deleteForSession(session.id)
            app.chatRepository.deleteMessagesForSession(session.id)
            app.chatRepository.deleteSessionRecord(session.id)
            app.characterRepository.delete(card.id)
        }
    }

    @Test fun deletingLastVersionKeepsAudioReferencedByIndependentMessage() = verify(last = true, sharedAudio = true)
    @Test fun deletingLastVersionReportsCleanupFailureWithoutCrashing() = verify(last = true, cleanupFailure = true)
    @Test fun deletingOneVersionReportsCleanupFailureAfterRefreshingMessage() = verify(cleanupFailure = true)
    @Test fun missingVersionDoesNotCleanAnyVoice() = verify(missing = true)
    @Test fun lateSynthesisCannotRestoreDeletedVersionVoice() = verify(lateVoice = true)
    @Test fun lateLegacyRegenerationCannotRestoreDeletedVersionVoice() = verify(legacy = true, lateVoice = true)
    @Test fun lateAnchorResolutionCannotRestoreDeletedVersionAnchor() = verify(lateAnchor = true)
    @Test fun staleUiPlacementReadCannotRecreateDeletedVersionAnchor() = verify(staleUi = true)

    @Test fun deletingRepliesKeepsSharedSaveSlotImageUntilItsLastReference() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        val card = CharacterCard.create("Synthetic shared archive image")
        val session = ChatSession.create(card.id, "Synthetic shared archive image").copy(
            longTermMemoryEnabled = false, automaticImageGenerationEnabled = false
        )
        val source = File.createTempFile("shared-archive-", ".png", app.cacheDir)
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        source.writeBytes(imageBytes)
        val first = ChatMessage.create(session.id, MessageRole.ASSISTANT, "First reply").copy(images = listOf(source.path))
        val second = ChatMessage.create(session.id, MessageRole.ASSISTANT, "Second reply").copy(images = listOf(source.path))
        val slot = app.saveSlotPackageStorage.createPackage(
            baseSlot = SaveSlot.create(session.id, "Shared image slot").copy(longTermMemoryEnabled = false),
            imagePolicy = SaveSlotImagePolicy.ORIGINAL, includeAudio = false,
            messageSource = { emit -> emit(first); emit(second) }, ragSource = {}, voices = emptyList()
        )
        val store = ViewModelStore()
        var restoredFile: File? = null
        try {
            app.characterRepository.save(card)
            app.chatRepository.createSession(session)
            app.saveSlotRepository.save(slot)
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
            }
            withTimeout(30_000) { vm.session.first { it?.id == session.id } }
            withContext(Dispatchers.Main) { vm.loadSaveSlot(slot.toSummary()) }
            withTimeout(30_000) { vm.saveSlotOperation.first { it.status == "读档完成。" } }
            withTimeout(30_000) { vm.messages.first { rows -> rows.count { it.id == first.id || it.id == second.id } == 2 } }
            suspend fun coldMessage(id: String) = ChatRepository(JsonFileStorage(app)).getMessage(id, session.id)
            val restoredFirst = requireNotNull(coldMessage(first.id))
            val restoredSecond = requireNotNull(coldMessage(second.id))
            val shared = File(restoredSecond.images.single())
            restoredFile = shared
            assertEquals(restoredFirst.images.single(), shared.path)
            assertArrayEquals(imageBytes, shared.readBytes())

            withContext(Dispatchers.Main) {
                vm.deleteMessageAlternative(first.id, MessageAlternativeVersionPolicy.activeVersionId(restoredFirst))
            }
            withTimeout(15_000) { vm.messages.first { rows -> rows.none { it.id == first.id } } }
            assertNull(coldMessage(first.id))
            assertEquals(shared.path, coldMessage(second.id)?.images?.single())
            assertArrayEquals(imageBytes, shared.readBytes())

            withContext(Dispatchers.Main) { vm.deleteMessage(second.id) }
            withTimeout(15_000) { vm.messages.first { rows -> rows.none { it.id == second.id } } }
            assertNull(coldMessage(second.id))
            assertFalse("The final owner should release the stored file", shared.exists())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.longTermMemoryAutoMaintenanceCoordinator.cancelAndJoinForSession(session.id)
            app.chatRepository.deleteMessagesForSession(session.id)
            app.chatRepository.deleteSessionRecord(session.id)
            app.characterRepository.delete(card.id)
            app.saveSlotRepository.delete(slot.id)
            restoredFile?.delete()
            source.delete()
        }
    }

    @Test fun failedMessageFileDeletionPreservesTheMessageIndex() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        val root = File(app.cacheDir, "delete-message-failure-" + UUID.randomUUID()).also(File::mkdirs)
        val context = object : ContextWrapper(app) { override fun getFilesDir() = root }
        val directory = File(root, "entities/chat_messages")
        try {
            val repository = ChatRepository(JsonFileStorage(context))
            val session = repository.createSession(ChatSession.create("fixture", "Fixture"))
            val message = repository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "Fixture reply"))
            assertTrue(directory.setWritable(false))
            val result = runCatching { repository.deleteMessageIfUnchanged(message) }
            assertEquals("未能删除消息文件，请稍后重试", result.exceptionOrNull()?.message)
            val cold = ChatRepository(JsonFileStorage(context))
            assertEquals(message, cold.getMessage(message.id, session.id))
            assertEquals(listOf(message.id), cold.getMessages(session.id).map { it.id })

            assertTrue(directory.setWritable(true))
            val messageFile = File(directory, "${session.id}_${message.id}.json")
            assertTrue(messageFile.setReadable(false))
            try {
                assertNull(repository.getMessage(message.id, session.id))
                assertFalse(repository.deleteMessageIfUnchanged(message.id, session.id, null))
                assertTrue(messageFile.isFile)
            } finally {
                messageFile.setReadable(true)
            }
            assertTrue(messageFile.delete())
            assertTrue(repository.deleteMessageIfUnchanged(message.id, session.id, null))
            assertTrue(repository.getMessages(session.id).isEmpty())
            repository.replaceMessagesForSession(session.id, listOf(message))
            assertFalse(repository.deleteMessageIfUnchanged(message.id, session.id, null))
            assertEquals(message, repository.getMessage(message.id, session.id))
        } finally {
            directory.setWritable(true)
            root.deleteRecursively()
        }
    }

    @Test fun cleanupChecksPersistedMessagesEvenWhenTheLiveIndexMissesOne() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        val root = File(app.cacheDir, "unindexed-image-" + UUID.randomUUID()).also(File::mkdirs)
        val context = object : ContextWrapper(app) { override fun getFilesDir() = root }
        val storage = JsonFileStorage(context)
        val repository = ChatRepository(storage)
        val image = File(root, "shared.png").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val session = repository.createSession(ChatSession.create("fixture", "Fixture"))
            val indexed = repository.addMessage(
                ChatMessage.create(session.id, MessageRole.ASSISTANT, "First").copy(images = listOf(image.path))
            )
            val unindexed = ChatMessage.create(session.id, MessageRole.ASSISTANT, "Pending index write")
                .copy(images = listOf(image.path))
            storage.saveEntityUncached(
                "chat_messages", "${session.id}_${unindexed.id}", unindexed, ChatMessage.serializer()
            )
            assertTrue(repository.deleteMessageIfUnchanged(indexed))
            assertTrue(repository.getMessages(session.id).isEmpty())
            repository.deleteUnreferencedChatImages(listOf(image.path)) { File(it).delete() }
            assertTrue(image.isFile)
            assertEquals(unindexed.id, ChatRepository(JsonFileStorage(context)).getMessages(session.id).single().id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun confirmedVoiceLateWriteAndUiReadWaitUntilCasCleanupCompletes() = runBlocking<Unit> { withTimeout(40_000) {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        val root = File(app.cacheDir, "atomic-version-voice-" + UUID.randomUUID()).also(File::mkdirs)
        val context = object : ContextWrapper(app) { override fun getFilesDir() = root }
        val storage = JsonFileStorage(context)
        val chats = ChatRepository(storage)
        val voices = VoiceMessageRepository(storage, chats::getMessage)
        val first = ChatMessage.create("synthetic-session", MessageRole.ASSISTANT, "old candidate")
        val message = MessageAlternativeVersionPolicy.append(first, "kept candidate", "kept-version")
        val removedId = MessageAlternativeVersionPolicy.versions(message).first().id
        val voice = GeneratedVoiceMessage(id = "confirmed", sessionId = message.sessionId, messageId = message.id,
            messageVersionId = removedId, versionOwnershipConfirmed = true,
            sourceOrder = 0, sourceSegmentKind = VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND,
            sourceSpeakerName = "", sourceText = first.content, taggedText = first.content,
            characterId = "fixture", characterName = "Fixture", voice = FishAudioVoiceBinding("fixture", "Fixture"),
            fishModelId = "fixture", audioPath = File(root, "synthetic.mp3").path,
            durationMs = 1000, byteLength = 1, createdAt = 1, updatedAt = 1)
        try {
            val persistedMessage = chats.addMessage(message)
            voices.save(voice)
            val rejected = runCatching { voices.deleteForVersion(message, removedId) { false } }
            assertTrue(rejected.exceptionOrNull() is IllegalStateException)
            assertEquals(voice, voices.get(voice.id))
            assertTrue(voices.snapshotAnchorsForMessages(setOf(message.id)).isEmpty())
            val afterCas = CompletableDeferred<Unit>()
            val finishCommit = CompletableDeferred<Unit>()
            val attempt = CompletableDeferred<Unit>()
            val mutation = async(Dispatchers.Default) {
                voices.deleteForVersion(persistedMessage, removedId) {
                    val committed = chats.updateMessageIfUnchanged(persistedMessage,
                        requireNotNull(MessageAlternativeVersionPolicy.remove(persistedMessage, removedId)))
                    afterCas.complete(Unit)
                    finishCommit.await()
                    committed != null
                }
            }
            withTimeout(15_000) { afterCas.await() }
            val late = async(Dispatchers.Default) {
                attempt.complete(Unit)
                runCatching {
                    voices.saveForExistingVersion(voice, replacing = voice) { chats.getMessage(message.id, message.sessionId) }
                }
            }
            val ui = async(Dispatchers.Default) { voices.placementsForMessage(first) }
            withTimeout(15_000) { attempt.await() }
            finishCommit.complete(Unit)
            assertEquals(listOf(voice.id), withTimeout(15_000) { mutation.await() }.map { it.id })
            assertTrue(withTimeout(15_000) { late.await() }.exceptionOrNull() is IllegalStateException)
            assertTrue(withTimeout(15_000) { ui.await() }.isEmpty())
            val cold = VoiceMessageRepository(JsonFileStorage(context))
            assertTrue(cold.listForSession(message.sessionId).isEmpty())
            assertFalse(cold.snapshotAnchorsForMessages(setOf(message.id)).any { it.messageVersionId == removedId })
        } finally { root.deleteRecursively() }
    } }

    private fun verify(
        legacy: Boolean = false, sharedAudio: Boolean = false, last: Boolean = false,
        missing: Boolean = false, lateVoice: Boolean = false, lateAnchor: Boolean = false, staleUi: Boolean = false,
        cleanupFailure: Boolean = false, viaSegmentEdit: Boolean = false, cancelAfterCommit: Boolean = false,
        segmentWithImage: Boolean = false
    ) = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
        val store = ViewModelStore()
        val card = CharacterCard.create("Synthetic voice retention")
        val session = ChatSession.create(card.id, "Synthetic voice retention").copy(
            longTermMemoryEnabled = false, automaticImageGenerationEnabled = false)
        val workspace = File(app.cacheDir, "version-voice-" + UUID.randomUUID()).also(File::mkdirs)
        val image = File(app.filesDir, "images/" + UUID.randomUUID() + ".png").also {
            it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val audioDir = File(app.filesDir, "audio/generated/${session.id}").also(File::mkdirs)
        fun audio(name: String) = File(audioDir, "$name.mp3").also {
            // Opaque synthetic original bytes for persistence/backup, never played or decoded.
            it.writeBytes("synthetic audio $name".toByteArray())
        }
        val deletedAudio = audio("deleted")
        if (cleanupFailure) {
            // Deterministic owned-path deletion failure; no host permission changes.
            check(deletedAudio.delete()); check(deletedAudio.mkdir())
            File(deletedAudio, "synthetic-child").writeText("blocks directory deletion")
        }
        val retainedAudio = if (sharedAudio) deletedAudio else audio("retained")
        val otherAudio = if (last && sharedAudio) deletedAudio else audio("other")
        val first = ChatMessage.create(session.id, MessageRole.ASSISTANT, "synthetic first voice")
            .copy(images = if (!viaSegmentEdit || segmentWithImage) listOf(image.path) else emptyList())
        val reply = if (last) first else MessageAlternativeVersionPolicy.append(first,
            "synthetic second voice", "second-version")
        val versions = MessageAlternativeVersionPolicy.versions(reply)
        val deletedVersion = if (legacy) versions.first() else versions.last()
        val retainedVersion = versions.firstOrNull { it.id != deletedVersion.id }
        val other = ChatMessage.create(session.id, MessageRole.ASSISTANT, "independent voice").let {
            if (cancelAfterCommit) {
                it.copy(createdAt = reply.createdAt - 1, orderKey = reply.orderKey - MESSAGE_ORDER_STEP)
            } else it
        }
        fun voice(message: ChatMessage, version: String?, text: String, file: File) = GeneratedVoiceMessage(
            id = UUID.randomUUID().toString(), sessionId = session.id, messageId = message.id,
            messageVersionId = version, versionOwnershipConfirmed = version != null,
            sourceOrder = 0, sourceSegmentKind = VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND,
            sourceSpeakerName = "", sourceText = text, taggedText = text, characterId = "synthetic-character",
            characterName = "Synthetic", voice = FishAudioVoiceBinding("synthetic-reference", "Synthetic"),
            fishModelId = "synthetic-model", audioPath = file.path, durationMs = 1000, byteLength = file.length(),
            createdAt = 1, updatedAt = 1)
        val removedVoice = voice(reply, if (legacy) null else deletedVersion.id, deletedVersion.content, deletedAudio)
        val survivor = retainedVersion?.let { voice(reply, it.id, it.content, retainedAudio) }
        val independent = voice(other, MessageAlternativeVersionPolicy.activeVersionId(other), other.content, otherAudio)
        try {
            app.characterRepository.save(card)
            app.chatRepository.createSession(session)
            val ordered = if (cancelAfterCommit) listOf(other, reply) else listOf(reply, other)
            ordered.forEach { app.chatRepository.addMessage(it) }
            for (v in versions) app.voiceMessageRepository.ensureAnchorsForVersion(reply, v.id, v.content)
            app.voiceMessageRepository.ensureAnchors(other)
            app.voiceMessageRepository.save(removedVoice)
            if (legacy) app.voiceMessageRepository.restoreCopiedAnchors(listOf(VoiceAnchorState(
                messageId = reply.id, sessionId = session.id, displayContentSnapshot = deletedVersion.content)))
            survivor?.let { app.voiceMessageRepository.save(it) }
            app.voiceMessageRepository.save(independent)
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
            }
            withTimeout(30_000) { vm.messages.first { rows -> rows.any { it.id == reply.id } } }
            val cancelledAfterDelete = CompletableDeferred<Unit>()
            if (cancelAfterCommit) {
                assertEquals(reply.displayContent, app.chatRepository.getSession(session.id)?.lastMessagePreview)
            }
            // The session preview is published after the message commit and before voice cleanup.
            val watcher = if (cancelAfterCommit) launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                app.chatRepository.sessions.first { rows ->
                    rows.any { it.id == session.id && it.lastMessagePreview == other.displayContent }
                }
                vm.viewModelScope.cancel()
                cancelledAfterDelete.complete(Unit)
            } else null
            try {
                withContext(Dispatchers.Main) {
                    if (viaSegmentEdit) {
                        val expected = vm.messages.value.single { it.id == reply.id }
                        vm.editMessageSegment(expected, 0, expected.displayContent.length, "")
                    } else {
                        vm.deleteMessageAlternative(reply.id, if (missing) "does-not-exist" else deletedVersion.id)
                    }
                }
                if (cancelAfterCommit) {
                    withTimeout(15_000) { cancelledAfterDelete.await() }
                    // A backup started now must wait for any in-flight data mutation.
                    LocalDataMaintenance.acquire().close()
                } else if (missing) {
                    withTimeout(15_000) { vm.messageActionError.first { it != null } }
                } else {
                    withTimeout(15_000) { vm.messages.first { rows ->
                        val current = rows.firstOrNull { it.id == reply.id }
                        if (last) current == null else current != null &&
                            MessageAlternativeVersionPolicy.versions(current).none { it.id == deletedVersion.id }
                    } }
                }
            } finally {
                watcher?.cancel()
            }
            val cold = VoiceMessageRepository(JsonFileStorage(app))
            val coldVoices = cold.listForSession(session.id)
            val coldAnchors = cold.snapshotAnchorsForMessages(setOf(reply.id, other.id))
            if (missing) {
                assertEquals(setOfNotNull(removedVoice.id, survivor?.id, independent.id), coldVoices.map { it.id }.toSet())
                assertTrue(deletedAudio.exists())
                assertTrue(coldAnchors.any { it.messageVersionId == deletedVersion.id })
                return@runBlocking
            }
            assertEquals("Only confirmed version ownership permits voice deletion", legacy, coldVoices.any { it.id == removedVoice.id })
            assertFalse("Deleted alternative must not retain its anchor state", coldAnchors.any {
                it.messageId == reply.id && it.messageVersionId == deletedVersion.id })
            if (cleanupFailure) {
                val error = withTimeout(15_000) { vm.messageActionError.first { it != null } }
                assertTrue(error!!.contains("语音文件清理失败"))
                val current = ChatRepository(JsonFileStorage(app)).getMessage(reply.id, session.id)
                if (last) assertNull(current) else assertFalse(MessageAlternativeVersionPolicy.versions(requireNotNull(current))
                    .any { it.id == deletedVersion.id })
                assertTrue(deletedAudio.exists())
                assertTrue(otherAudio.exists())
                return@runBlocking
            }
            assertEquals("Shared and unconfirmed audio must remain available", sharedAudio || legacy, deletedAudio.exists())
            assertTrue(otherAudio.exists())
            assertTrue(coldVoices.any { it == independent })
            assertTrue(coldAnchors.any { it.messageId == other.id })
            if (survivor != null) {
                assertTrue(coldVoices.any { it == survivor })
                assertTrue(coldAnchors.any { it.messageId == reply.id && it.messageVersionId == survivor.messageVersionId })
                assertTrue(retainedAudio.exists())
            }
            val coldMessage = ChatRepository(JsonFileStorage(app)).getMessage(reply.id, session.id)
            if (last) assertNull(coldMessage) else {
                assertNotNull(coldMessage)
                assertEquals(reply.images, coldMessage!!.images)
                assertTrue(image.exists())
            }
            if (lateVoice) {
                var rejected = false
                try { app.voiceMessageRepository.saveForExistingVersion(removedVoice) {
                    app.chatRepository.getMessage(reply.id, session.id)
                } } catch (_: IllegalStateException) { rejected = true }
                assertTrue("Late TTS result must be rejected", rejected)
                assertEquals(legacy, VoiceMessageRepository(JsonFileStorage(app)).get(removedVoice.id) != null)
            }
            if (lateAnchor) {
                var rejected = false
                try { app.voiceMessageRepository.ensureAnchorsForVersion(reply, deletedVersion.id, deletedVersion.content) {
                    app.chatRepository.getMessage(reply.id, session.id)
                } } catch (_: IllegalStateException) { rejected = true }
                assertTrue("Late anchor resolution must be rejected", rejected)
                assertFalse(VoiceMessageRepository(JsonFileStorage(app)).snapshotAnchorsForMessages(setOf(reply.id))
                    .any { it.messageVersionId == deletedVersion.id })
            }
            if (staleUi) {
                assertTrue(app.voiceMessageRepository.placementsForMessage(reply).isEmpty())
                assertTrue(app.voiceMessageRepository.listForCurrentVersion(reply).isEmpty())
                assertFalse(VoiceMessageRepository(JsonFileStorage(app)).snapshotAnchorsForMessages(setOf(reply.id))
                    .any { it.messageVersionId == deletedVersion.id })
            }
            val plan = BackupExportCatalog.read(app.filesDir) {}.plan(AppBackupSelection(
                characterIds = setOf(card.id), sessions = mapOf(session.id to BackupSessionChoice(archives = false))))
            fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
            val archive = File(workspace, "synthetic.cbbackup")
            archive.outputStream().use { engine().create(app.filesDir, File(workspace, "encode"), it, null,
                1, "synthetic-version-voice", BackupPreferences(), plan) }
            val restore = File(workspace, "restore")
            val target = File(workspace, "target").also(File::mkdirs)
            archive.inputStream().use { engine().extract(it, null, restore, target, 1) }
            val exported = File(restore, "files/entities/generated_voice_messages").listFiles().orEmpty()
                .map { backupJson.decodeFromString<GeneratedVoiceMessage>(it.readText()) }
            val exportedAnchors = File(restore, "files/entities/voice_anchor_states").listFiles().orEmpty()
                .map { backupJson.decodeFromString<VoiceAnchorState>(it.readText()) }
            assertEquals(coldVoices.map { it.id }.toSet(), exported.map { it.id }.toSet())
            assertFalse(exportedAnchors.any { it.messageId == reply.id && it.messageVersionId == deletedVersion.id })
            val exportedAudio = File(restore, "files/audio/generated/${session.id}")
            assertEquals(sharedAudio || legacy, File(exportedAudio, "deleted.mp3").exists())
            assertArrayEquals(otherAudio.readBytes(), File(exportedAudio, otherAudio.name).readBytes())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.longTermMemoryAutoMaintenanceCoordinator.cancelAndJoinForSession(session.id)
            app.voiceMessageRepository.deleteForSession(session.id)
            app.chatRepository.deleteMessagesForSession(session.id)
            app.chatRepository.deleteSessionRecord(session.id)
            app.characterRepository.delete(card.id)
            image.delete()
            audioDir.deleteRecursively()
            app.replyRemovalCoordinator.resumePending(session.id)
            workspace.deleteRecursively()
        }
    }
}

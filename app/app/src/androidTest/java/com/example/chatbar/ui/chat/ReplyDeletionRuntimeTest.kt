package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real ViewModel -> real JSON storage and cold read; screen controls are tested separately. */
class ReplyDeletionRuntimeTest {
    private lateinit var app: ChatBarApp
    private lateinit var session: ChatSession
    private lateinit var vm: ChatViewModel
    private val store = ViewModelStore()

    @Before fun setup() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
        withTimeout(30_000) { app.settingsRepository.isInitialized.first { it } }
        session = ChatSession.create("synthetic-missing-card", "synthetic deletion runtime").copy(longTermMemoryEnabled = false)
        app.chatRepository.createSession(session)
    }

    @After fun cleanup() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { store.clear() }
        app.voiceMessageRepository.deleteForSession(session.id)
        app.chatRepository.deleteMessagesForSession(session.id)
        app.chatRepository.deleteSessionRecord(session.id)
    }

    @Test fun actualViewModelWholeDeleteRemovesSoleRepairedReplyAndColdReadStaysEmpty() = runBlocking<Unit> {
        val original = ChatMessage.create(session.id, MessageRole.ASSISTANT, "synthetic before repair")
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(original,
            "synthetic repaired sole reply", MessageFormatRepairPolicy.completedRepairNotice(
                original.content, "synthetic repaired sole reply"))
        app.chatRepository.addMessage(repaired)
        openModel(repaired.id)
        withContext(Dispatchers.Main) { vm.deleteMessage(repaired.id) }
        assertGone(repaired.id)
    }

    @Test fun unreadableReplyProducesShareableDeletionDiagnostic() = verifyUnreadableDeletion("删除消息失败") { reply ->
        deleteMessage(reply)
    }

    @Test fun unreadableSegmentProducesShareableDeletionDiagnostic() = verifyUnreadableDeletion("删除片段失败") { reply ->
        editMessageSegment(reply, 0, reply.displayContent.length, "")
    }

    @Test fun unreadableEditorReplyProducesShareableDeletionDiagnostic() =
        verifyUnreadableDeletion("读取待编辑的消息失败") { reply ->
            editMessage(reply.id, reply.content, emptyList())
        }

    private fun verifyUnreadableDeletion(title: String, delete: ChatViewModel.(ChatMessage) -> Unit) = runBlocking<Unit> {
        val reply = app.chatRepository.addMessage(
            ChatMessage.create(session.id, MessageRole.ASSISTANT, "Synthetic private reply")
        )
        openModel(reply.id)
        val file = File(app.filesDir, "entities/chat_messages/${session.id}_${reply.id}.json")
        val held = File(file.parentFile, "${file.name}.held")
        assertTrue(file.renameTo(held))
        try {
            assertTrue(file.mkdir())
            withContext(Dispatchers.Main) { vm.delete(reply) }
            val report = stage("deletion diagnostic") { vm.deletionFailure.first { it != null } }!!
            assertEquals(title, report.title)
            assertTrue(report.content.contains("IOException"))
            assertFalse(report.content.contains(reply.content))
            assertNull(vm.messageActionError.value)
        } finally {
            if (file.isDirectory) assertTrue(file.delete())
            assertTrue(held.renameTo(file))
        }
    }

    @Test fun actualViewModelCurrentCandidateDeleteRemovesLastReplyAndPreservesUser() = runBlocking<Unit> {
        val user = ChatMessage.create(session.id, MessageRole.USER, "synthetic user question")
        app.chatRepository.addMessage(user)
        val reply = ChatMessage.create(session.id, MessageRole.ASSISTANT, "synthetic only candidate")
        app.chatRepository.addMessage(reply)
        openModel(reply.id)
        val current = requireNotNull(app.chatRepository.getMessage(reply.id, session.id))
        withContext(Dispatchers.Main) {
            vm.deleteMessageAlternative(reply.id, MessageAlternativeVersionPolicy.activeVersionId(current))
        }
        assertGone(reply.id)
        assertNotNull(app.chatRepository.getMessage(user.id, session.id))
    }

    @Test fun actualViewModelWholeDeleteRemovesAllTwentySixCandidatesWithoutTouchingSeparateReply() = runBlocking<Unit> {
        var reply = ChatMessage.create(session.id, MessageRole.ASSISTANT, "synthetic first candidate")
        repeat(25) { reply = MessageAlternativeVersionPolicy.append(reply, "synthetic candidate $it", "synthetic-version-$it") }
        val separate = ChatMessage.create(session.id, MessageRole.ASSISTANT, "synthetic separate reply")
        app.chatRepository.addMessage(reply)
        app.chatRepository.addMessage(separate)
        openModel(reply.id)
        withContext(Dispatchers.Main) { vm.deleteMessage(reply.id) }
        assertGone(reply.id)
        assertEquals(separate.content, app.chatRepository.getMessage(separate.id, session.id)?.content)
    }


    @Test fun staleSegmentDeletionCannotEraseShorterRepairedReply() = verifyStaleSegmentDeletion(switchCandidate = false)
    @Test fun staleSegmentDeletionCannotEraseNewlySelectedCandidate() = verifyStaleSegmentDeletion(switchCandidate = true)

    private fun verifyStaleSegmentDeletion(switchCandidate: Boolean) = runBlocking<Unit> {
        val original = ChatMessage.create(session.id, MessageRole.ASSISTANT, "A long synthetic reply to confirm")
        val reply = if (switchCandidate) {
            MessageAlternativeVersionPolicy.select(
                MessageAlternativeVersionPolicy.append(original, "New", "next-candidate"), 0
            )
        } else original
        app.chatRepository.addMessage(reply)
        openModel(reply.id)
        val confirmation = vm.messages.value.single { it.id == reply.id }
        val replacement = if (switchCandidate) MessageAlternativeVersionPolicy.select(confirmation, 1)
        else MessageFormatRepairPolicy.replaceCurrentDisplayContent(
            confirmation, "New", MessageFormatRepairPolicy.completedRepairNotice(confirmation.displayContent, "New")
        )
        assertNotNull(app.chatRepository.updateMessageIfUnchanged(confirmation, replacement))
        val saved = requireNotNull(app.chatRepository.getMessage(reply.id, session.id))

        val conflict = async(start = CoroutineStart.UNDISPATCHED) {
            stage("stale segment confirmation rejected") { vm.deletionNotices.first() }
        }
        withContext(Dispatchers.Main) {
            vm.editMessageSegment(confirmation, 0, confirmation.displayContent.length, "")
        }
        conflict.await()
        assertNull(vm.deletionFailure.value)
        val cold = com.example.chatbar.data.repository.ChatRepository(com.example.chatbar.data.local.JsonFileStorage(app))
        assertEquals(saved, cold.getMessage(reply.id, session.id))
        assertEquals("New", saved.displayContent)
    }

    @Test fun legacyReadbackKeepsMediaOwnedByAnotherConversation() = verifySaveSlotMediaOwnership(packaged = false)
    @Test fun packagedReadbackKeepsMediaOwnedByAnotherConversation() = verifySaveSlotMediaOwnership(packaged = true)

    private fun verifySaveSlotMediaOwnership(packaged: Boolean) = runBlocking<Unit> {
        val other = ChatSession.create("synthetic-missing-card", "synthetic shared owner").copy(longTermMemoryEnabled = false)
        val sharedImage = app.novelAiImageStorage.save(session.id, byteArrayOf(1, 4, 7))
        val unusedImage = app.novelAiImageStorage.save(session.id, byteArrayOf(2, 5, 8))
        val audioDir = File(app.filesDir, "audio/generated/${session.id}").also { it.mkdirs() }
        val sharedAudio = File(audioDir, "shared-reply.mp3").also { it.writeText("shared reply audio") }
        val unusedAudio = File(audioDir, "unused-reply.mp3").also { it.writeText("obsolete reply audio") }
        val old = ChatMessage.create(session.id, MessageRole.ASSISTANT, "Replace this reply", listOf(sharedImage, unusedImage))
        val retained = ChatMessage.create(other.id, MessageRole.ASSISTANT, "Keep this reply", listOf(sharedImage))
        val restored = ChatMessage.create(session.id, MessageRole.ASSISTANT, "Restored reply")
        val inline = SaveSlot.create(session.id, "Synthetic readback", messages = listOf(restored))
            .copy(longTermMemoryEnabled = false)
        val slot = if (packaged) app.saveSlotPackageStorage.createPackage(
            baseSlot = inline, imagePolicy = SaveSlotImagePolicy.NONE, includeAudio = false,
            messageSource = { emit -> emit(restored) }, ragSource = {}, voices = emptyList()
        ) else inline

        fun voice(id: String, reply: ChatMessage, file: File) = GeneratedVoiceMessage(
            id = id, sessionId = reply.sessionId, messageId = reply.id,
            sourceOrder = 0, sourceSegmentKind = VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND,
            sourceSpeakerName = "", sourceText = reply.content, taggedText = reply.content,
            characterId = "synthetic", characterName = "Synthetic",
            voice = FishAudioVoiceBinding("synthetic-reference", "Synthetic"), fishModelId = "synthetic-model",
            audioPath = file.path, durationMs = 1000, byteLength = file.length(), createdAt = 1, updatedAt = 1
        )
        val remainingVoice = voice("${retained.id}-shared", retained, sharedAudio)
        val playingVoice = voice("${old.id}-shared", old, sharedAudio).copy(
            messageVersionId = MessageAlternativeVersionPolicy.activeVersionId(old), requiresCompatibleVersion = true,
        )
        try {
            app.chatRepository.createSession(other)
            app.chatRepository.addMessage(old)
            app.chatRepository.addMessage(retained)
            app.voiceMessageRepository.save(playingVoice)
            app.voiceMessageRepository.save(voice("${old.id}-unused", old, unusedAudio))
            app.voiceMessageRepository.save(remainingVoice)
            app.saveSlotRepository.save(slot)
            openModel(old.id)
            app.fishAudioGenerationCoordinator.playFrom(playingVoice, listOf(playingVoice)) { false }.join()
            assertTrue(app.voicePlaybackController.state.value.queueVoiceIds.isEmpty())

            withContext(Dispatchers.Main) {
                app.voicePlaybackController.playSingle(playingVoice)
                assertEquals(playingVoice.id, app.voicePlaybackController.state.value.currentVoiceId)
                vm.loadSaveSlot(slot.toSummary())
            }
            val result = stageSaveSlotResult()
            assertEquals("读档完成。", result.status)
            assertTrue(app.voicePlaybackController.state.value.queueVoiceIds.isEmpty())
            app.fishAudioGenerationCoordinator.playFrom(playingVoice, listOf(playingVoice)).join()
            assertTrue(app.voicePlaybackController.state.value.queueVoiceIds.isEmpty())
            val data = com.example.chatbar.data.local.JsonFileStorage(app)
            val chats = com.example.chatbar.data.repository.ChatRepository(data)
            val voices = com.example.chatbar.data.repository.VoiceMessageRepository(data)
            assertNull(chats.getMessage(old.id, session.id))
            assertEquals(restored.content, chats.getMessages(session.id).single().displayContent)
            assertEquals(listOf(sharedImage), chats.getMessage(retained.id, other.id)?.images)
            assertEquals(listOf(remainingVoice), voices.listForSession(other.id))
            assertArrayEquals(byteArrayOf(1, 4, 7), File(sharedImage).readBytes())
            assertEquals("shared reply audio", sharedAudio.readText())
            assertFalse(File(unusedImage).exists())
            assertFalse(unusedAudio.exists())
        } finally {
            withContext(Dispatchers.Main) { app.voicePlaybackController.stop() }
            app.saveSlotRepository.delete(slot.id)
            app.voiceMessageRepository.deleteForSession(other.id)
            app.chatRepository.deleteMessagesForSession(other.id)
            app.chatRepository.deleteSessionRecord(other.id)
            listOf(File(sharedImage), File(unusedImage), sharedAudio, unusedAudio).forEach { it.delete() }
        }
    }

    private suspend fun stageSaveSlotResult() = withTimeout(20_000) {
        vm.saveSlotOperation.first { !it.busy && !it.status.isNullOrBlank() }
    }

    @Test fun deletedCandidateRefreshesBeforeVectorCleanup() = competingSwitch("delete")
    @Test fun staleSwitchCannotOverwriteUndoBeforeUiRefresh() = competingSwitch("undo")
    @Test fun staleSwitchCannotOverwriteRedoBeforeUiRefresh() = competingSwitch("redo")

    private fun competingSwitch(operation: String) = runBlocking<Unit> {
        var reply = ChatMessage.create(session.id, MessageRole.ASSISTANT, "candidate A")
        reply = MessageAlternativeVersionPolicy.append(reply, "candidate B", "version-B")
        reply = MessageAlternativeVersionPolicy.append(reply, "candidate C repaired", "version-C")
        reply = MessageFormatRepairPolicy.replaceCurrentDisplayContent(reply, "candidate C repaired",
            MessageFormatRepairPolicy.completedRepairNotice("candidate C original", "candidate C repaired"))
        if (operation == "redo") reply = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(reply))
        app.chatRepository.addMessage(reply)
        openModel(reply.id)
        val initial = vm.messages.value.single { it.id == reply.id }
        val storage = app.jsonFileStorage
        val storageMutex = storage.javaClass.getDeclaredMethod("mutexFor", String::class.java).let {
            it.isAccessible = true
            it.invoke(storage, if (operation == "delete") "vector_chunks" else "chat_sessions") as Mutex
        }
        val appendMutex = app.chatRepository.javaClass.getDeclaredField("messageAppendMutex").let {
            it.isAccessible = true; it.get(app.chatRepository) as kotlinx.coroutines.sync.Mutex }
        var switchJob: Job? = null
        storageMutex.lock()
        try {
            withContext(Dispatchers.Main) {
                when (operation) {
                    "delete" -> vm.deleteMessageAlternative(reply.id, "version-C")
                    "undo" -> vm.restoreMessageFormatOriginal(reply.id)
                    else -> vm.redoMessageFormatRepair(reply.id)
                }
            }
            val cold = com.example.chatbar.data.repository.ChatRepository(com.example.chatbar.data.local.JsonFileStorage(app))
            stage("first mutation persisted") {
                while (cold.getMessage(reply.id, session.id).let { it == null || it == initial }) delay(20)
            }
            if (operation == "delete") {
                stage("UI reflected delete while vector cleanup was blocked") {
                    vm.messages.first { messages -> messages.single { it.id == reply.id }.alternatives.size == 2 }
                }
                assertNull(vm.deletionFailure.value)
            } else {
                // The first transaction is persisted but blocked finishing its session update.
                assertEquals(initial, vm.messages.value.single { it.id == reply.id })
                assertTrue(appendMutex.isLocked)
                withContext(Dispatchers.Main) {
                    val scopeJob = requireNotNull(vm.viewModelScope.coroutineContext[Job])
                    val before = scopeJob.children.toSet()
                    vm.switchAssistantAlternative(reply.id, -1)
                    switchJob = scopeJob.children.single { it !in before }
                }
            }
        } finally { storageMutex.unlock() }
        stage("competing switch completed") { switchJob?.join() }
        val cold = com.example.chatbar.data.repository.ChatRepository(com.example.chatbar.data.local.JsonFileStorage(app))
        val saved = requireNotNull(cold.getMessage(reply.id, session.id))
        if (operation == "delete") {
            assertEquals(2, saved.alternatives.size)
            assertFalse(saved.alternativeVersionIds.contains("version-C"))
            assertEquals("candidate B", saved.displayContent)
        } else {
            assertEquals("version-C", MessageAlternativeVersionPolicy.activeVersionId(saved))
            assertEquals(operation == "undo", saved.formatRepairNotice?.restored)
            assertEquals(if (operation == "undo") "candidate C original" else "candidate C repaired", saved.displayContent)
        }
        stage("UI refreshed from persisted message") { vm.messages.first { list -> list.any { it.id == reply.id && it == saved } } }
        if (operation != "delete") stage("conflict visible") { vm.messageActionError.first { it != null } }
        withContext(Dispatchers.Main) { vm.clearMessageActionError(); vm.switchAssistantAlternative(reply.id, -1) }
        withTimeout(10_000) { vm.messages.first { list -> list.any {
            it.id == reply.id && it.currentAlternativeIndex == saved.currentAlternativeIndex - 1 } } }
        withContext(Dispatchers.Main) { vm.switchAssistantAlternative(reply.id, 1) }
        withTimeout(10_000) { vm.messages.first { list -> list.any {
            it.id == reply.id && it.currentAlternativeIndex == saved.currentAlternativeIndex } } }
        assertNull(vm.messageActionError.value)
        val final = requireNotNull(cold.getMessage(reply.id, session.id))
        assertEquals(saved.alternatives, final.alternatives)
        assertEquals(saved.formatRepairNotice, final.formatRepairNotice)
        if (operation != "delete") assertNotNull(MessageFormatRepairPolicy.applicableNotice(final))
    }

    @Test fun deletingOpeningPublishesTimelineBeforeBlockedCleanup() = runBlocking<Unit> {
        val opening = app.chatRepository.addMessage(
            ChatMessage.create(session.id, MessageRole.ASSISTANT, "Synthetic opening")
        )
        assertEquals(0L, opening.sourceTurnOrder)
        openModel(opening.id)
        val storage = app.jsonFileStorage
        val vectorLock = storage.javaClass.getDeclaredMethod("mutexFor", String::class.java).let {
            it.isAccessible = true
            it.invoke(storage, "vector_chunks") as Mutex
        }
        vectorLock.lock()
        try {
            withContext(Dispatchers.Main) { vm.deleteMessage(opening) }
            stage("opening gone with vectors still locked") { vm.messages.first { it.none { row -> row.id == opening.id } } }
            val next = stage("append while cleanup is blocked") {
                app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "Next turn"))
            }
            assertEquals(1L, next.sourceTurnOrder)
            val context = app.chatRepository.getContextCandidateSnapshot(session.id, recentTurnCount = 10)
            assertEquals(setOf(next.id), context.completeRecentMessageIds)
        } finally {
            vectorLock.unlock()
        }
        assertTrue(app.replyRemovalCoordinator.resumePending(session.id))
    }

    private suspend fun <T> stage(name: String, block: suspend () -> T): T = try {
        withTimeout(10_000) { block() }
    } catch (timeout: TimeoutCancellationException) { throw AssertionError("Fixture timed out at: $name", timeout) }

    private suspend fun openModel(messageId: String) {
        vm = withContext(Dispatchers.Main) {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(session.id) as T
            }
            ViewModelProvider(store, factory)[ChatViewModel::class.java]
        }
        withTimeout(30_000) { vm.messages.first { list -> list.any { it.id == messageId } } }
    }

    private suspend fun assertGone(messageId: String) {
        withTimeout(10_000) { vm.messages.first { list -> list.none { it.id == messageId } } }
        withTimeout(10_000) {
            while (app.chatRepository.getMessage(messageId, session.id) != null) delay(20)
        }
        assertNull(vm.messageActionError.value)
        assertNull(app.chatRepository.getMessage(messageId, session.id))
        withContext(Dispatchers.Main) { vm.refreshMessages() }
        delay(300)
        assertTrue(vm.messages.value.none { it.id == messageId })
        // A fresh storage/repository must not reconstruct the deleted message from an index.
        val cold = com.example.chatbar.data.repository.ChatRepository(
            com.example.chatbar.data.local.JsonFileStorage(app))
        assertNull(cold.getMessage(messageId, session.id))
        assertTrue(cold.getInitialMessagePage(session.id).messages.none { it.id == messageId })
    }
}

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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


    @Test fun staleSwitchCannotResurrectCandidateDeletedBeforeUiRefresh() = competingSwitch("delete")
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
            it.isAccessible = true; it.invoke(storage, "chat_sessions") as kotlinx.coroutines.sync.Mutex }
        val appendMutex = app.chatRepository.javaClass.getDeclaredField("messageAppendMutex").let {
            it.isAccessible = true; it.get(app.chatRepository) as kotlinx.coroutines.sync.Mutex }
        lateinit var switchJob: Job
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
            // The first transaction is persisted but blocked finishing its session update.
            assertEquals(initial, vm.messages.value.single { it.id == reply.id })
            assertTrue(appendMutex.isLocked)
            withContext(Dispatchers.Main) {
                val scopeJob = requireNotNull(vm.viewModelScope.coroutineContext[Job])
                val before = scopeJob.children.toSet()
                vm.switchAssistantAlternative(reply.id, -1)
                switchJob = scopeJob.children.single { it !in before }
            }
        } finally { storageMutex.unlock() }
        stage("competing switch completed") { switchJob.join() }
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
        stage("conflict visible") { vm.messageActionError.first { it != null } }
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

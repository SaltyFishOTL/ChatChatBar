package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionCharacterRebindTest {
    private lateinit var app: ChatBarApp
    private val cards = mutableListOf<String>()
    private val sessions = mutableListOf<String>()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        runBlocking {
            app = ApplicationProvider.getApplicationContext()
            withTimeout(30_000) { app.dependenciesReady.first { it } }
        }
    }

    @After fun cleanup() {
        runBlocking {
            withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
            for (id in sessions) {
                app.saveSlotRepository.deleteBySessionId(id)
                app.chatRepository.deleteMessagesForSession(id)
                app.chatRepository.deleteSessionRecord(id)
            }
            for (id in cards) app.characterRepository.delete(id)
        }
    }

    @Test fun normalRebindPreservesAllSessionFieldsAndResetsWorldbookClock() = runBlocking<Unit> {
        val old = card()
        val next = card()
        val source = session(old.id).copy(longTermMemory = "synthetic-memory", replyLength = 900,
            supplementarySetting = "synthetic-setting", extraWorldBookIds = listOf("synthetic-book"),
            timedWorldInfo = mapOf("e" to TimedEffectState("e", 9, 12)),
            characterParticipation = listOf(SessionCharacterParticipation(old.id, setOf("synthetic-person"))))
        app.chatRepository.updateSession(source)
        val baseline = requireNotNull(app.chatRepository.getSession(source.id))
        app.chatRepository.rebindSession(baseline, next.id, app.characterRepository)
        val changed = requireNotNull(app.chatRepository.getSession(source.id))
        assertEquals(baseline.copy(characterCardId = next.id, timedWorldInfo = emptyMap(), updatedAt = changed.updatedAt), changed)
    }

    @Test fun staleRebindCannotOverwriteNewerSessionSettings() = runBlocking<Unit> {
        val old = card()
        val next = card()
        val source = session(old.id)
        val baseline = requireNotNull(app.chatRepository.getSession(source.id))
        app.chatRepository.updateSession(baseline.copy(title = "synthetic-newer-title"))
        val latest = requireNotNull(app.chatRepository.getSession(source.id))
        var failed = false
        try { app.chatRepository.rebindSession(baseline, next.id, app.characterRepository) }
        catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(latest, app.chatRepository.getSession(source.id))
    }

    @Test fun actualViewModelCreatesRecoveryArchiveBeforeNormalRebind() = runBlocking<Unit> {
        val old = card()
        val next = card()
        val source = session(old.id)
        val vm = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also { stores += it }
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(source.id) as T
            }
            ViewModelProvider(store, factory)[ChatViewModel::class.java]
        }
        withTimeout(30_000) { vm.session.first { it?.id == source.id } }
        withContext(Dispatchers.Main) { vm.relinkCharacterCard(next.id) }
        assertEquals(next.id, app.chatRepository.getSession(source.id)!!.characterCardId)
        val summaries = app.saveSlotRepository.getBySessionId(source.id)
        assertEquals(1, summaries.size)
        val slot = requireNotNull(app.saveSlotRepository.getById(summaries.single().id))
        assertEquals(source.id, slot.sessionId)
        assertEquals(SaveSlotImagePolicy.ORIGINAL, slot.imagePolicy)
        assertNotNull(slot.packageRef)
    }

    private suspend fun card(): CharacterCard = CharacterCard.create("synthetic-rebind-card")
        .also { cards += it.id; app.characterRepository.save(it) }

    private suspend fun session(cardId: String): ChatSession = ChatSession.create(cardId, "synthetic-rebind-session")
        .also { sessions += it.id; app.chatRepository.createSession(it) }
}

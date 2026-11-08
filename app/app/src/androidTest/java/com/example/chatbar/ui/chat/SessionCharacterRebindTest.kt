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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
        val vm = editor(source.id)
        withContext(Dispatchers.Main) { vm.relinkCharacterCard(next.id) }
        assertEquals(next.id, app.chatRepository.getSession(source.id)!!.characterCardId)
        val summaries = app.saveSlotRepository.getBySessionId(source.id)
        assertEquals(1, summaries.size)
        val slot = requireNotNull(app.saveSlotRepository.getById(summaries.single().id))
        assertEquals(source.id, slot.sessionId)
        assertEquals(SaveSlotImagePolicy.ORIGINAL, slot.imagePolicy)
        assertNotNull(slot.packageRef)
    }

    @Test fun actualSettingsSavePersistsManualParticipation() = runBlocking<Unit> {
        val card = card()
        val source = session(card.id)
        val vm = editor(source.id)
        val baseline = requireNotNull(app.chatRepository.getSession(source.id))
        val states = listOf(SessionCharacterParticipation(card.id, setOf("synthetic-person")))
        val done = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        withContext(Dispatchers.Main) {
            vm.updateSessionConfig(
                modelId = baseline.modelId, imageModelId = baseline.imageModelId,
                novelAiImageModel = baseline.novelAiImageModel,
                novelAiNaturalLanguageMode = baseline.novelAiNaturalLanguageMode,
                automaticImageGenerationEnabled = baseline.automaticImageGenerationEnabled,
                formatCardId = baseline.formatCardId, replyLength = baseline.replyLength,
                replyLanguage = baseline.replyLanguage, supplementarySetting = baseline.supplementarySetting,
                playerName = baseline.playerName, playerSetting = baseline.playerSetting,
                chatBackground = baseline.chatBackground, audiobookModeEnabled = baseline.audiobookModeEnabled,
                voiceLanguage = baseline.voiceLanguage, longTermMemoryEnabled = baseline.longTermMemoryEnabled,
                longTermMemory = baseline.longTermMemory, extraWorldBookIds = baseline.extraWorldBookIds,
                baseline = baseline, characterParticipation = states, onComplete = { done.complete(it) }
            )
        }
        withTimeout(10_000) { done.await() }.getOrThrow()
        assertEquals(states, app.chatRepository.getSession(source.id)!!.characterParticipation)
    }

    @Test fun defaultGalleryFieldDoesNotInvalidateLegacyDraftFingerprint() {
        val base = CharacterCard.create("synthetic-legacy-draft").copy(ragIndexStatus = "", updatedAt = 0L)
        val codec = app.jsonFileStorage.json
        val currentShape = codec.encodeToJsonElement(CharacterCard.serializer(), base).jsonObject
        val legacyShape = JsonObject(currentShape.filterKeys { it != "chatBackgroundImages" })
        val legacyBytes = codec.encodeToString(JsonObject.serializer(), legacyShape).toByteArray(Charsets.UTF_8)
        val legacyHash = java.security.MessageDigest.getInstance("SHA-256").digest(legacyBytes)
            .joinToString("") { "%02x".format(it) }
        val repository = app.editorDraftRepository
        val draft = repository.characterDraft(base.id, "synthetic-legacy-session",
            base.copy(greeting = "synthetic-unsaved-text"), base, emptyList(), emptyList(), emptyList())
            .copy(baseHash = legacyHash)
        assertFalse(repository.isChanged(base, draft))
        assertTrue(repository.isChanged(base.copy(chatBackgroundImages = listOf("synthetic-background.png")), draft))
    }

    private suspend fun editor(id: String): ChatViewModel {
        val vm = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also { stores += it }
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(id) as T
            }
            ViewModelProvider(store, factory)[ChatViewModel::class.java]
        }
        withTimeout(30_000) { vm.session.first { it?.id == id } }
        return vm
    }

    private suspend fun card(): CharacterCard = CharacterCard.create("synthetic-rebind-card")
        .also { cards += it.id; app.characterRepository.save(it) }

    private suspend fun session(cardId: String): ChatSession = ChatSession.create(cardId, "synthetic-rebind-session")
        .also { sessions += it.id; app.chatRepository.createSession(it) }
}

package com.example.chatbar.ui.character

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.domain.image.NovelAiCharacterImageSettings
import com.example.chatbar.domain.image.NovelAiImageModel
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterDraftDiscardTest {
    private lateinit var app: ChatBarApp
    private val stores = mutableListOf<ViewModelStore>()
    private val cardIds = mutableListOf<String>()
    private var openedNewCard = false

    @Before
    fun awaitDependencies() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
        Unit
    }

    @After
    fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
        cardIds.forEach {
            app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, it)
            app.characterRepository.delete(it)
        }
        if (openedNewCard) {
            app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, null)
        }
    }

    @Test
    fun discardRestoresSavedFieldsAndCancelsPendingAutosave() = runBlocking {
        val saved = fixture()
        val vm = editor(saved.id)
        withContext(Dispatchers.Main) {
            vm.name = "Discarded synthetic edit"
            vm.defaultImageGenerationSettings = NovelAiCharacterImageSettings(guidance = 8.3f, cfgRescale = 0.52f)
            vm.scheduleDraftSave()
        }

        discard(vm)

        // Reuse the same instance, as happens when reopening the same card in one process.
        withContext(Dispatchers.Main) {
            assertEquals(saved.name, vm.name)
            assertEquals(saved.defaultImageGenerationSettings, vm.defaultImageGenerationSettings)
            assertFalse(vm.hasLocalChanges)
            assertFalse(vm.hasUnsavedDraftChanges)
            assertNull(vm.draftSavedAt)
            vm.scheduleDraftSave()
        }
        delay(1_000)
        assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, saved.id))
        assertEquals(saved, app.characterRepository.getById(saved.id))
    }

    @Test
    fun discardOfRestoredDraftReturnsToSavedCardWithoutExiting() = runBlocking {
        val saved = fixture()
        val first = editor(saved.id)
        withContext(Dispatchers.Main) {
            first.creatorNotes = "Unsaved synthetic note"
            first.defaultImageGenerationSettings = NovelAiCharacterImageSettings(guidance = 8.3f, cfgRescale = 0.52f)
            first.scheduleDraftSave()
        }
        val exited = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { first.saveDraftAndExit { exited.complete(Unit) } }
        withTimeout(10_000) { exited.await() }

        val restored = editor(saved.id)
        withContext(Dispatchers.Main) {
            assertEquals(saved.id, restored.restoreDraft?.targetId)
            restored.restoreDraft()
            assertEquals(0.52f, restored.defaultImageGenerationSettings!!.cfgRescale, 0f)
        }
        discard(restored)
        withContext(Dispatchers.Main) {
            assertEquals(saved.creatorNotes, restored.creatorNotes)
            assertEquals(saved.defaultImageGenerationSettings, restored.defaultImageGenerationSettings)
            assertNull(restored.restoreDraft)
            assertFalse(restored.hasLocalChanges)
        }
        assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, saved.id))
        assertEquals(saved, app.characterRepository.getById(saved.id))
    }

    @Test
    fun discardReloadsLatestSavedCardInsteadOfStaleOpenSnapshot() = runBlocking {
        val saved = fixture()
        val vm = editor(saved.id)
        withContext(Dispatchers.Main) {
            vm.name = "Discarded local edit"
            vm.scheduleDraftSave()
        }
        val latest = saved.copy(name = "Newer synthetic saved card", updatedAt = saved.updatedAt + 1)
        app.characterRepository.save(latest)

        discard(vm)

        withContext(Dispatchers.Main) { assertEquals(latest.name, vm.name) }
        assertEquals(latest, app.characterRepository.getById(saved.id))
    }

    @Test
    fun discardNewCardClearsEditableFieldsBeforeSameEditorIsReused() = runBlocking {
        openedNewCard = true
        val vm = editor(null)
        withContext(Dispatchers.Main) {
            vm.name = "Unsaved new synthetic card"
            vm.greeting = "Synthetic greeting"
            vm.editMode = CharacterEditMode.FREEFORM
            vm.freeformCharacterText = "Synthetic editor content"
            vm.defaultNovelAiImageModel = NovelAiImageModel.V5_FULL
            vm.defaultImageGenerationSettings = NovelAiCharacterImageSettings(guidance = 8.3f, cfgRescale = 0.52f)
            vm.selectedWorldBookIds.add("synthetic-book")
            vm.scheduleDraftSave()
        }

        discard(vm)

        withContext(Dispatchers.Main) {
            assertEquals("", vm.name)
            assertEquals("", vm.greeting)
            assertEquals("", vm.freeformCharacterText)
            assertEquals(CharacterEditMode.STRUCTURED, vm.editMode)
            assertNull(vm.defaultImageGenerationSettings)
            assertNull(vm.defaultNovelAiImageModel)
            assertEquals(emptyList<String>(), vm.selectedWorldBookIds.toList())
            assertNull(vm.characterCard.value)
            assertFalse(vm.hasLocalChanges)
            vm.scheduleDraftSave()
        }
        delay(1_000)
        assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
    }

    private suspend fun fixture(): CharacterCard {
        val card = CharacterCard.create("Synthetic discard regression", "Synthetic greeting").copy(
            momentsEnabled = false,
            defaultNovelAiImageModel = NovelAiImageModel.V5_FULL,
            defaultImageGenerationSettings = NovelAiCharacterImageSettings(guidance = 6.75321f, cfgRescale = 0.257891f)
        )
        cardIds.add(card.id)
        app.characterRepository.save(card)
        return card
    }

    private suspend fun editor(id: String?): CharacterEditViewModel = withContext(Dispatchers.Main) {
        val store = ViewModelStore().also { stores.add(it) }
        val vm = ViewModelProvider(store, CharacterEditViewModelFactory(id, UUID.randomUUID().toString()))[
            CharacterEditViewModel::class.java
        ]
        withTimeout(30_000) {
            while (!vm.draftReady) delay(10)
        }
        vm
    }

    private suspend fun discard(vm: CharacterEditViewModel) {
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { vm.discardDraft { done.complete(Unit) } }
        withTimeout(10_000) { done.await() }
    }
}

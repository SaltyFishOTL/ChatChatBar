package com.example.chatbar.ui.manage

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.ui.character.CharacterEditViewModel
import com.example.chatbar.ui.character.CharacterEditViewModelFactory
import com.example.chatbar.ui.worldbook.WorldBookEditViewModel
import com.example.chatbar.ui.worldbook.WorldBookEditViewModelFactory
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ManagementDraftDiscardTest {
    private lateinit var app: ChatBarApp
    private val stores = mutableListOf<ViewModelStore>()
    private val cards = mutableListOf<String>()
    private val books = mutableListOf<String>()

    @Before fun setup() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
    }

    @After fun cleanup() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
        for (id in cards) {
            app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, id)
            app.characterRepository.delete(id)
        }
        for (id in books) {
            app.editorDraftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, id)
            app.worldBookRepository.delete(id)
        }
        app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, null)
        app.editorDraftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, null)
    }

    @Test fun managementDiscardResetsRetainedCardAndDoesNotRecreateItsDraft() = runBlocking<Unit> {
        val saved = card()
        val vm = characterEditor(saved.id)
        saveCardDraft(vm)
        managerDiscard(EditorDraftType.CHARACTER_CARD, saved.id)
        awaitMain { vm.draftReady && vm.name == saved.name && !vm.hasLocalChanges }
        withContext(Dispatchers.Main) {
            assertNull(vm.restoreDraft); assertNull(vm.draftSavedAt)
            vm.scheduleDraftSave()
        }
        delay(900)
        assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, saved.id))
        assertEquals(saved, app.characterRepository.getById(saved.id))
        val reopened = characterEditor(saved.id)
        withContext(Dispatchers.Main) { assertEquals(saved.name, reopened.name); assertNull(reopened.restoreDraft) }
    }

    @Test fun managementDiscardResetsRetainedNewCard() = runBlocking<Unit> {
        val vm = characterEditor(null)
        saveCardDraft(vm)
        managerDiscard(EditorDraftType.CHARACTER_CARD, null)
        awaitMain { vm.draftReady && vm.name.isEmpty() && !vm.hasLocalChanges }
        withContext(Dispatchers.Main) { assertNull(vm.characterCard.value); assertNull(vm.draftSavedAt) }
        val reopened = characterEditor(null)
        withContext(Dispatchers.Main) { assertEquals("", reopened.name); assertNull(reopened.restoreDraft) }
    }

    @Test fun managementDiscardClearsPendingCardRestoreWithoutTouchingOtherDraft() = runBlocking<Unit> {
        val saved = card()
        saveCardDraft(characterEditor(saved.id))
        val pending = characterEditor(saved.id)
        withContext(Dispatchers.Main) { assertNotNull(pending.restoreDraft) }
        val other = book()
        val otherVm = worldEditor(other.id)
        saveBookDraft(otherVm)
        val otherDraft = app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, other.id)
        managerDiscard(EditorDraftType.CHARACTER_CARD, saved.id)
        awaitMain { pending.draftReady && pending.restoreDraft == null && pending.name == saved.name }
        assertEquals(otherDraft, app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, other.id))
        withContext(Dispatchers.Main) { assertEquals("synthetic unsaved book", otherVm.name) }
    }

    @Test fun managementDiscardResetsRetainedBookAndDoesNotRecreateItsDraft() = runBlocking<Unit> {
        val saved = book()
        val vm = worldEditor(saved.id)
        saveBookDraft(vm)
        managerDiscard(EditorDraftType.WORLD_BOOK, saved.id)
        awaitMain { vm.draftReady && vm.name == saved.name && !vm.hasLocalChanges }
        withContext(Dispatchers.Main) {
            assertEquals(saved.entries, vm.entries.toList())
            assertNull(vm.restoreDraft); assertNull(vm.draftSavedAt)
            vm.scheduleDraftSave()
        }
        delay(900)
        assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, saved.id))
        assertEquals(saved, app.worldBookRepository.getById(saved.id))
        val reopened = worldEditor(saved.id)
        withContext(Dispatchers.Main) { assertEquals(saved.name, reopened.name); assertNull(reopened.restoreDraft) }
    }

    @Test fun managementDiscardResetsRetainedNewBook() = runBlocking<Unit> {
        val vm = worldEditor(null)
        saveBookDraft(vm)
        managerDiscard(EditorDraftType.WORLD_BOOK, null)
        awaitMain { vm.draftReady && vm.name.isEmpty() && !vm.hasLocalChanges }
        withContext(Dispatchers.Main) { assertTrue(vm.entries.isEmpty()); assertNull(vm.draftSavedAt) }
        val reopened = worldEditor(null)
        withContext(Dispatchers.Main) { assertEquals("", reopened.name); assertNull(reopened.restoreDraft) }
    }

    @Test fun managementDiscardClearsPendingBookRestoreWithoutTouchingOtherDraft() = runBlocking<Unit> {
        val saved = book()
        saveBookDraft(worldEditor(saved.id))
        val pending = worldEditor(saved.id)
        withContext(Dispatchers.Main) { assertNotNull(pending.restoreDraft) }
        val other = card()
        val otherVm = characterEditor(other.id)
        saveCardDraft(otherVm)
        val otherDraft = app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, other.id)
        managerDiscard(EditorDraftType.WORLD_BOOK, saved.id)
        awaitMain { pending.draftReady && pending.restoreDraft == null && pending.name == saved.name }
        assertEquals(otherDraft, app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, other.id))
        withContext(Dispatchers.Main) { assertEquals("synthetic unsaved card", otherVm.name) }
    }

    private suspend fun managerDiscard(type: EditorDraftType, id: String?) {
        val draft = requireNotNull(app.editorDraftRepository.getForTarget(type, id))
        withContext(Dispatchers.Main) {
            val store = ViewModelStore().also { stores += it }
            ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ManageViewModel::class.java]
                .discardEditorDraft(draft)
        }
        withTimeout(10_000) {
            while (app.editorDraftRepository.getForTarget(type, id) != null) delay(10)
        }
    }

    private suspend fun saveCardDraft(vm: CharacterEditViewModel) {
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            vm.name = "synthetic unsaved card"; vm.greeting = "synthetic greeting"
            vm.scheduleDraftSave(); vm.saveDraftAndExit { done.complete(Unit) }
        }
        withTimeout(10_000) { done.await() }
    }

    private suspend fun saveBookDraft(vm: WorldBookEditViewModel) {
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            vm.name = "synthetic unsaved book"
            vm.entries.add(WorldBookEntry(id = UUID.randomUUID().toString(), name = "synthetic", content = "synthetic"))
            vm.scheduleDraftSave(); vm.saveDraftAndExit { done.complete(Unit) }
        }
        withTimeout(10_000) { done.await() }
    }

    private suspend fun awaitMain(predicate: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(10_000) { while (!predicate()) delay(10) }
    }

    private suspend fun characterEditor(id: String?): CharacterEditViewModel = withContext(Dispatchers.Main) {
        val store = ViewModelStore().also { stores += it }
        ViewModelProvider(store, CharacterEditViewModelFactory(id, UUID.randomUUID().toString()))[
            CharacterEditViewModel::class.java
        ].also { vm -> withTimeout(30_000) { while (!vm.draftReady) delay(10) } }
    }

    private suspend fun worldEditor(id: String?): WorldBookEditViewModel = withContext(Dispatchers.Main) {
        val store = ViewModelStore().also { stores += it }
        ViewModelProvider(store, WorldBookEditViewModelFactory(id, UUID.randomUUID().toString()))[
            WorldBookEditViewModel::class.java
        ].also { vm -> withTimeout(30_000) { while (!vm.draftReady) delay(10) } }
    }

    private suspend fun card(): CharacterCard = CharacterCard.create("synthetic saved card", "synthetic greeting")
        .copy(momentsEnabled = false).also { cards += it.id; app.characterRepository.save(it) }

    private suspend fun book(): WorldBook {
        val book = WorldBook.create("synthetic saved book")
        books += book.id; app.worldBookRepository.save(book)
        return requireNotNull(app.worldBookRepository.getById(book.id))
    }
}

package com.example.chatbar.ui.character

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.repository.EditorDraftRepository
import java.util.UUID
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Real retained editors, formal saves and private draft files. No model or image requests. */
class CharacterDraftSaveLifecycleTest {
    private lateinit var app: ChatBarApp
    private val stores = mutableListOf<ViewModelStore>()
    private val cardIds = mutableListOf<String>()
    private val files = mutableListOf<File>()

    @Before fun setup() = runBlocking<Unit> {
        assumeTrue("Use the repository-supported isolated Android test build", BuildConfig.ISOLATED_ANDROID_TEST)
        val id = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        check(id == BuildConfig.APPLICATION_ID && id.startsWith("com.example.chatbar."))
        app = ApplicationProvider.getApplicationContext()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (!::app.isInitialized) return@runBlocking
        withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
        cardIds.forEach {
            app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, it)
            app.characterRepository.delete(it)
        }
        files.forEach { it.delete() }
    }

    @Test fun formalSaveDoesNotRecreateDraftWhenOpeningAnotherCard() = runBlocking<Unit> {
        verifyCardSwitch(otherEdited = false)
    }

    @Test fun formalSaveDoesNotRecreateDraftOrDeleteOtherCardsRealDraft() = runBlocking<Unit> {
        verifyCardSwitch(otherEdited = true)
    }

    @Test fun successfulFormalSaveClearsSavedDraftStatus() = runBlocking<Unit> {
        val card = card()
        val vm = editor(card.id)
        editAndSaveDraft(vm, "saved name-${UUID.randomUUID()}")
        withContext(Dispatchers.Main) { assertNotNull(vm.draftSavedAt) }
        action { vm.saveCharacterCard(it) }
        withContext(Dispatchers.Main) {
            assertNull("Formal save must clear the old draft banner", vm.draftSavedAt)
            assertFalse(vm.hasLocalChanges)
            assertFalse(vm.hasUnsavedDraftChanges)
        }
        assertNull(coldDrafts().getForTarget(EditorDraftType.CHARACTER_CARD, card.id))
    }

    @Test fun retainedEditorCanCreateAndRestoreAGenuineDraftAfterFormalSave() = runBlocking<Unit> {
        val card = card()
        val vm = editor(card.id)
        val formalName = "formal-${UUID.randomUUID()}"
        editAndSaveDraft(vm, formalName)
        action { vm.saveCharacterCard(it) }
        val secondName = "unsaved-${UUID.randomUUID()}"
        editAndSaveDraft(vm, secondName)
        assertEquals(secondName, coldDrafts().getForTarget(EditorDraftType.CHARACTER_CARD, card.id)?.characterPayload?.name)
        assertEquals(formalName, app.characterRepository.getById(card.id)?.name)
        val reopened = editor(card.id)
        withContext(Dispatchers.Main) {
            assertNotNull(reopened.restoreDraft)
            reopened.restoreDraft()
            assertEquals(secondName, reopened.name)
            assertTrue(reopened.hasLocalChanges)
        }
    }

    @Test fun rejectedFormalSaveStillAutosavesTheActualUnsavedEdit() = runBlocking<Unit> {
        val card = card()
        val other = card()
        val vm = editor(card.id)
        var saved = false
        withContext(Dispatchers.Main) {
            vm.name = other.name
            vm.scheduleDraftSave()
            vm.saveCharacterCard({ saved = true })
        }
        withTimeout(15_000) { while (withContext(Dispatchers.Main) { vm.isSaving.value }) delay(20) }
        withTimeout(15_000) {
            while (app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, card.id)?.characterPayload?.name != other.name) delay(40)
        }
        withContext(Dispatchers.Main) { assertFalse(saved) }
        assertEquals(card.name, app.characterRepository.getById(card.id)?.name)
        assertEquals(other.name, app.characterRepository.getById(other.id)?.name)
    }

    @Test fun derivedDocumentIndexUpdatesDoNotCreateADraftButRealDocumentEditsDo() = runBlocking<Unit> {
        val file = File(app.filesDir, "synthetic-doc-${UUID.randomUUID()}.txt").also {
            it.writeText("synthetic reference document")
            files += it
        }
        val doc = DocumentInfo.create("original.txt", file.path, "txt")
        val card = card(doc)
        val vm = editor(card.id)
        withContext(Dispatchers.Main) {
            vm.documentsList[0] = doc.copy(contentHash = "derived-content-hash", indexedHash = "derived-index-hash",
                ragStatus = "INDEXED", ragChunkCount = 4, ragIndexedAt = 123L, ragError = "derived status")
            vm.scheduleDraftSave()
        }
        delay(1_100)
        assertNull("Background index progress must not create a character draft",
            coldDrafts().getForTarget(EditorDraftType.CHARACTER_CARD, card.id))
        withContext(Dispatchers.Main) {
            assertFalse(vm.hasLocalChanges)
            vm.documentsList[0] = vm.documentsList[0].copy(fileName = "user-renamed.txt")
            vm.scheduleDraftSave()
        }
        action { vm.saveDraftAndExit(it) }
        val savedDoc = requireNotNull(coldDrafts().getForTarget(EditorDraftType.CHARACTER_CARD, card.id))
            .characterPayload!!.customDocuments.single()
        assertEquals("user-renamed.txt", savedDoc.fileName)
        assertEquals("derived-content-hash", savedDoc.contentHash)
        assertEquals("original.txt", app.characterRepository.getById(card.id)?.customDocuments?.single()?.fileName)
        assertEquals("synthetic reference document", file.readText())
    }

    private suspend fun verifyCardSwitch(otherEdited: Boolean) = coroutineScope {
        val a = card()
        val b = card()
        val vmA = editor(a.id)
        val formalName = "formal-${UUID.randomUUID()}"
        editAndSaveDraft(vmA, formalName)
        val oldDraft = requireNotNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, a.id))
        val attempted = CompletableDeferred<Unit>()
        // Deliver the same autosave scheduling that a Compose snapshot can deliver
        // while formal save suspends deleting its consumed draft. No production hooks.
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            app.editorDraftRepository.drafts.first { rows ->
                vmA.isSaving.value && rows.none { it.id == oldDraft.id }
            }
            assertTrue(vmA.isSaving.value)
            vmA.scheduleDraftSave()
            attempted.complete(Unit)
        }
        try {
            action { vmA.saveCharacterCard(it) }
            withTimeout(10_000) { attempted.await() }
            val vmB = editor(b.id)
            if (otherEdited) editAndSaveDraft(vmB, "real other-card draft")
            else withContext(Dispatchers.Main) { vmB.scheduleDraftSave() }
            // Let any incorrect debounce from A complete while B is the active editor.
            delay(1_100)
            assertNull("Saving A must not recreate its draft after switching to B",
                coldDrafts().getForTarget(EditorDraftType.CHARACTER_CARD, a.id))
            assertFalse(app.editorDraftRepository.getAll().any { it.targetId == a.id })
            val bDraft = coldDrafts().getForTarget(EditorDraftType.CHARACTER_CARD, b.id)
            if (otherEdited) assertEquals("real other-card draft", bDraft?.characterPayload?.name)
            else assertNull(bDraft)
            assertEquals(formalName, app.characterRepository.getById(a.id)?.name)
            val reopenedA = editor(a.id)
            withContext(Dispatchers.Main) { assertNull(reopenedA.restoreDraft); assertEquals(formalName, reopenedA.name) }
        } finally { observer.cancel() }
    }

    private fun coldDrafts() = EditorDraftRepository(JsonFileStorage(app))

    private suspend fun card(document: DocumentInfo? = null): CharacterCard = CharacterCard.create(
        "synthetic-${UUID.randomUUID()}", greeting = "synthetic main greeting"
    ).copy(momentsEnabled = false, customDocuments = listOfNotNull(document)).also {
        cardIds += it.id
        app.characterRepository.save(it)
    }

    private suspend fun editor(id: String): CharacterEditViewModel {
        val store = ViewModelStore().also(stores::add)
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, CharacterEditViewModelFactory(id, UUID.randomUUID().toString()))[CharacterEditViewModel::class.java]
        }
        withTimeout(15_000) { while (!withContext(Dispatchers.Main) { vm.draftReady }) delay(20) }
        return vm
    }

    private suspend fun editAndSaveDraft(vm: CharacterEditViewModel, text: String) {
        withContext(Dispatchers.Main) { vm.name = text; vm.scheduleDraftSave() }
        action { vm.saveDraftAndExit(it) }
    }

    private suspend fun action(start: (() -> Unit) -> Unit) {
        val complete = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { start { complete.complete(Unit) } }
        withTimeout(30_000) { complete.await() }
    }
}

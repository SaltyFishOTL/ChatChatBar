package com.example.chatbar.ui.worldbook

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
class WorldBookQuickToggleViewModelTest {
    private lateinit var app: ChatBarApp
    private val stores = mutableListOf<ViewModelStore>()
    private val ids = mutableListOf<String>()
    private var openedNew = false

    @Before fun setup() {
        runBlocking {
            app = ApplicationProvider.getApplicationContext()
            withTimeout(30_000) { app.dependenciesReady.first { it } }
        }
    }

    @After fun cleanup() {
        runBlocking {
            withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
            for (id in ids) {
                app.editorDraftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, id)
                app.worldBookRepository.delete(id)
            }
            if (openedNew) app.editorDraftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, null)
        }
    }

    @Test fun quickToggleSaveAndNewEditorKeepFullEntryData() = runBlocking<Unit> {
        val saved = fixture()
        val vm = editor(saved.id)
        withContext(Dispatchers.Main) {
            vm.toggleEntry(0)
            assertTrue(vm.hasLocalChanges)
            assertEquals(saved.entries[0].copy(enabled = false), vm.entries[0])
        }
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { vm.save({ done.complete(Unit) }) }
        withTimeout(10_000) { done.await() }
        val reopened = editor(saved.id)
        withContext(Dispatchers.Main) {
            assertFalse(reopened.entries[0].enabled)
            assertEquals(saved.entries[0].copy(enabled = false), reopened.entries[0])
            assertNull(reopened.restoreDraft)
        }
        assertEquals(saved.entries[0].copy(enabled = false), app.worldBookRepository.getById(saved.id)!!.entries[0])
    }

    @Test fun discardRestoresEnabledBeforeSameEditorReopens() = runBlocking<Unit> {
        val saved = fixture()
        val vm = editor(saved.id)
        withContext(Dispatchers.Main) { vm.toggleEntry(0) }
        discard(vm)
        withContext(Dispatchers.Main) {
            assertTrue("Discarded entry enabled flag must be restored", vm.entries[0].enabled)
            assertEquals(saved.entries, vm.entries.toList())
            assertFalse(vm.hasLocalChanges)
            assertNull(vm.draftSavedAt)
            vm.scheduleDraftSave()
        }
        delay(1_000)
        assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, saved.id))
        assertEquals(saved, app.worldBookRepository.getById(saved.id))
    }

    @Test fun savedDraftRestoresToggleAndCanThenBeDiscarded() = runBlocking<Unit> {
        val saved = fixture()
        val vm = editor(saved.id)
        withContext(Dispatchers.Main) { vm.toggleEntry(0) }
        val exited = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { vm.saveDraftAndExit { exited.complete(Unit) } }
        withTimeout(10_000) { exited.await() }
        val reopened = editor(saved.id)
        withContext(Dispatchers.Main) {
            assertNotNull(reopened.restoreDraft)
            reopened.restoreDraft()
            assertFalse(reopened.entries[0].enabled)
        }
        discard(reopened)
        withContext(Dispatchers.Main) { assertEquals(saved.entries, reopened.entries.toList()) }
        assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, saved.id))
    }

    @Test fun discardNewBookLeavesNoEntriesOrPhantomDraft() = runBlocking<Unit> {
        openedNew = true
        val vm = editor(null)
        withContext(Dispatchers.Main) {
            vm.name = "未保存的合成世界书"
            vm.entries.add(entry())
            vm.toggleEntry(0)
        }
        discard(vm)
        withContext(Dispatchers.Main) {
            assertEquals("", vm.name)
            assertTrue(vm.entries.isEmpty())
            assertEquals(10, vm.scanDepth)
            assertNull(vm.worldBook.value)
            vm.scheduleDraftSave()
            assertFalse(vm.hasLocalChanges)
        }
        delay(1_000)
        assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, null))
    }

    private fun entry() = WorldBookEntry(
        id = "synthetic-entry", name = "合成条目", keys = listOf("alpha"),
        content = "合成世界书正文", enabled = true, sticky = 2, cooldown = 3,
        secondaryKeys = listOf("beta"), selective = true, comment = "保留全部字段"
    )

    private suspend fun fixture(): WorldBook {
        val book = WorldBook.create("合成快捷开关测试").copy(entries = listOf(entry()))
        ids.add(book.id)
        app.worldBookRepository.save(book)
        return book
    }

    private suspend fun editor(id: String?): WorldBookEditViewModel = withContext(Dispatchers.Main) {
        val store = ViewModelStore().also { stores.add(it) }
        val vm = ViewModelProvider(store, WorldBookEditViewModelFactory(id, UUID.randomUUID().toString()))[
            WorldBookEditViewModel::class.java
        ]
        withTimeout(30_000) { while (!vm.draftReady) delay(10) }
        vm
    }

    private suspend fun discard(vm: WorldBookEditViewModel) {
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { vm.discardDraft { done.complete(Unit) } }
        withTimeout(10_000) { done.await() }
    }
}

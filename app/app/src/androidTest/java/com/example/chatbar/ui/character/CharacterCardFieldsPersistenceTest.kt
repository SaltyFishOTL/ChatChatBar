package com.example.chatbar.ui.character

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.BuildConfig
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CharacterCardFieldsPersistenceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ChatBarApp.instance
    private val json = Json { ignoreUnknownKeys = true }
    private val opaque = " {\"future\":{\"unknown\":[null,\" raw \"]}} \n"

    @Before fun requireIsolatedSyntheticApplication() {
        org.junit.Assume.assumeTrue(
            "Run connectedDebugAndroidTest with -PCHATBAR_ISOLATED_ANDROID_TEST=true on a clean emulator",
            BuildConfig.ISOLATED_ANDROID_TEST
        )
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        check(packageName == BuildConfig.APPLICATION_ID && packageName.startsWith("com.example.chatbar."))
        runBlocking { withTimeout(30_000) { app.dependenciesReady.first { it } } }
    }

    @Test fun existingCardsBothModesRetainFieldsThroughDraftSaveColdReadAndNativeTransfer() {
        for (mode in CharacterEditMode.entries) {
            val image = File(app.filesDir, "card-fields-${UUID.randomUUID()}.png")
            image.outputStream().use { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            val originalImage = image.readBytes()
            val card = CharacterCard.create("synthetic-${UUID.randomUUID()}", greeting = "main").copy(
                editMode = mode, freeformCharacterText = "synthetic body", momentsEnabled = false,
                avatar = image.absolutePath, chatBackground = image.absolutePath,
                mesExample = "old example", alternateGreetings = listOf("", "alternate one", "alternate two"),
                tags = listOf(" imported ", "same", "same"), creator = "old creator", characterVersion = "old version",
                extensions = opaque, defaultImageNegativePrompt = "synthetic negative")
            runBlocking { app.characterRepository.save(card) }
            var store = ViewModelStore()
            val importedIds = mutableListOf<String>()
            try {
                var vm = editor(store, card.id)
                rule.runOnIdle {
                    assertEquals(card.tags, vm.tags); assertEquals(card.creator, vm.creator)
                    assertEquals(card.characterVersion, vm.characterVersion); assertEquals(opaque, vm.extensions)
                    vm.mesExample = "new example"; vm.tags = listOf(" imported ", "tag,with,commas")
                    vm.creator = " creator 2 "; vm.characterVersion = "v2"
                    vm.alternateGreetings = listOf("alternate two", "", "alternate one")
                    vm.scheduleDraftSave(); assertTrue(vm.hasLocalChanges)
                }
                awaitAction { done -> vm.saveDraftAndExit(done) }
                val draft = runBlocking { app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, card.id) }
                assertEquals(opaque, draft?.characterPayload?.extensions)
                assertEquals("v2", draft?.characterPayload?.characterVersion)
                rule.runOnIdle { store.clear() }; store = ViewModelStore()
                vm = editor(store, card.id)
                rule.waitUntil(10_000) { vm.restoreDraft != null }
                rule.runOnIdle { vm.restoreDraft() }
                awaitAction { done -> vm.saveCharacterCard(done) }
                rule.runOnIdle { store.clear() }; store = ViewModelStore()
                vm = editor(store, card.id)
                rule.runOnIdle {
                    assertEquals("new example", vm.mesExample); assertEquals(" creator 2 ", vm.creator)
                    assertEquals(listOf(" imported ", "tag,with,commas"), vm.tags)
                    assertEquals("v2", vm.characterVersion); assertEquals(opaque, vm.extensions)
                    vm.creator = "discard me"; vm.scheduleDraftSave()
                }
                awaitAction { done -> vm.discardDraft(done) }
                rule.runOnIdle { assertEquals(" creator 2 ", vm.creator); assertFalse(vm.hasLocalChanges) }
                val packageData = runBlocking { json.decodeFromString(CharacterCardPackage.serializer(), app.characterCardTransferService.exportJson(card.id)) }
                assertEquals(opaque, packageData.card.extensions)
                assertEquals(listOf("alternate two", "", "alternate one"), packageData.card.alternateGreetings)
                assertEquals("", packageData.card.systemPrompt); assertEquals("", packageData.card.postHistoryInstructions)
                assertArrayEquals(originalImage, image.readBytes())
                val copy = runBlocking { app.characterCardTransferService.importNew(packageData) }
                importedIds += copy.id
                assertEquals(packageData.card.tags, copy.tags); assertEquals(opaque, copy.extensions)
                assertEquals(" creator 2 ", copy.creator); assertEquals("v2", copy.characterVersion)
                assertArrayEquals(originalImage, File(requireNotNull(copy.avatar)).readBytes())
                assertArrayEquals(originalImage, File(requireNotNull(copy.chatBackground)).readBytes())
            } finally {
                rule.runOnIdle { store.clear() }
                runBlocking {
                    app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, card.id)
                    app.characterRepository.delete(card.id)
                    importedIds.forEach { app.characterRepository.delete(it) }
                }
                image.delete()
            }
        }
    }

    @Test fun newCardMetadataSurvivesDraftAndNewEntityConstruction() {
        val store = ViewModelStore()
        val name = "synthetic-new-${UUID.randomUUID()}"
        try {
            val vm = editor(store, null)
            rule.runOnIdle {
                vm.name = name; vm.greeting = "main"; vm.momentsEnabled = false
                vm.creator = "new creator"; vm.characterVersion = "new version"; vm.tags = listOf("new tag")
                vm.mesExample = "new example"; vm.alternateGreetings = listOf("other")
                vm.scheduleDraftSave()
            }
            awaitAction { done -> vm.saveDraftAndExit(done) }
            val draft = runBlocking { app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null) }
            assertEquals("new creator", draft?.characterPayload?.creator)
            awaitAction { done -> vm.saveCharacterCard(done) }
            val saved = runBlocking { app.characterRepository.getAll().single { it.name == name } }
            assertEquals("new creator", saved.creator); assertEquals("new version", saved.characterVersion)
            assertEquals(listOf("new tag"), saved.tags); assertEquals("new example", saved.mesExample)
            assertEquals(listOf("other"), saved.alternateGreetings)
            assertEquals("", saved.systemPrompt); assertEquals("", saved.postHistoryInstructions)
        } finally {
            rule.runOnIdle { store.clear() }
            runBlocking {
                app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, null)
                app.characterRepository.getAll().filter { it.name == name }.forEach { app.characterRepository.delete(it.id) }
            }
        }
    }

    @Test fun actualExampleFullscreenBackDiscardsAndConfirmCommitsWithoutLeavingCard() {
        val card = CharacterCard.create("synthetic-${UUID.randomUUID()}", greeting = "main").copy(momentsEnabled = false, mesExample = "original example")
        runBlocking { app.characterRepository.save(card) }
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        var exits = 0
        try {
            val vm = editor(store, card.id)
            rule.setContent { if (visible.value) ChatBarTheme { Box { CharacterEditScreen(card.id, onBack = { exits++ }, viewModel = vm) } } }
            fun open() {
                rule.onNodeWithTag("character-dialogue-examples").performScrollTo()
                rule.onNode(hasContentDescription("全屏编辑") and hasAnyAncestor(hasTestTag("character-dialogue-examples"))).performClick()
            }
            open()
            rule.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("discarded example")
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithContentDescription("退出").assertDoesNotExist()
            rule.runOnIdle { assertEquals(0, exits); assertEquals("original example", vm.mesExample) }
            open()
            rule.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("confirmed example")
            rule.onNodeWithContentDescription("确认").performClick()
            rule.runOnIdle { assertEquals("confirmed example", vm.mesExample); assertEquals(0, exits) }
        } finally {
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            rule.runOnIdle { store.clear() }
            runBlocking {
                app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, card.id)
                app.characterRepository.delete(card.id)
            }
        }
    }

    @Test fun actualBackAfterRevertingTheLastEditRemovesThePreviousDraft() {
        val card = CharacterCard.create("synthetic-${UUID.randomUUID()}", greeting = "main").copy(momentsEnabled = false)
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        val coldDrafts = EditorDraftRepository(JsonFileStorage(app))
        val exited = java.util.concurrent.atomic.AtomicBoolean(false)
        runBlocking { app.characterRepository.save(card) }
        try {
            val vm = editor(store, card.id)
            rule.setContent {
                if (visible.value) ChatBarTheme {
                    CharacterEditScreen(card.id, onBack = { exited.set(true); visible.value = false }, viewModel = vm)
                }
            }
            rule.runOnIdle { vm.name = "temporary draft name" }
            rule.waitUntil(15_000) { vm.draftSavedAt != null }
            assertNotNull(runBlocking { coldDrafts.getForTarget(EditorDraftType.CHARACTER_CARD, card.id) })
            rule.runOnIdle {
                vm.name = card.name
                rule.activity.onBackPressedDispatcher.onBackPressed()
            }
            rule.waitUntil(15_000) { exited.get() }
            runBlocking {
                val reopened = EditorDraftRepository(JsonFileStorage(app))
                assertNull(reopened.getForTarget(EditorDraftType.CHARACTER_CARD, card.id))
                assertEquals(card, app.characterRepository.getById(card.id))
            }
        } finally {
            rule.runOnIdle { visible.value = false; store.clear() }
            runBlocking {
                app.editorDraftRepository.deleteForTarget(EditorDraftType.CHARACTER_CARD, card.id)
                app.characterRepository.delete(card.id)
            }
        }
    }

    private fun editor(store: ViewModelStore, id: String?): CharacterEditViewModel {
        lateinit var vm: CharacterEditViewModel
        rule.runOnIdle { vm = ViewModelProvider(store, CharacterEditViewModelFactory(id, UUID.randomUUID().toString()))[CharacterEditViewModel::class.java] }
        rule.waitUntil(15_000) { vm.draftReady }
        return vm
    }

    private fun awaitAction(start: (() -> Unit) -> Unit) {
        val complete = java.util.concurrent.atomic.AtomicBoolean(false)
        rule.runOnIdle { start { complete.set(true) } }
        rule.waitUntil(20_000) { complete.get() }
    }
}

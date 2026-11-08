package com.example.chatbar.domain.card

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.domain.draft.EditorDraftAssetService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterBackgroundGalleryTransferTest {
    private lateinit var app: ChatBarApp
    private val cards = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val sessions = mutableListOf<String>()

    @Before fun setup() {
        runBlocking {
            app = ApplicationProvider.getApplicationContext()
            withTimeout(30_000) { app.dependenciesReady.first { it } }
        }
    }

    @After fun cleanup() {
        runBlocking {
            for (id in sessions) app.chatRepository.deleteSessionRecord(id)
            for (id in cards) app.characterCardTransferService.deleteCard(id)
            files.forEach { it.delete() }
        }
    }

    @Test fun defaultAndGalleryShareOneMaterializedPathAndTransferAllOriginalImages() = runBlocking<Unit> {
        val assets = EditorDraftAssetService(app)
        val draft = UUID.randomUUID().toString()
        try {
            val first = assets.newDraftFile(draft, "synthetic-first.png").also { fill(it, Color.RED) }
            val second = assets.newDraftFile(draft, "synthetic-second.png").also { fill(it, Color.BLUE) }
            val materialized = assets.materializeCharacterAssets(CharacterCard.create("synthetic-gallery").copy(
                chatBackground = first.absolutePath, chatBackgroundImages = listOf(first.absolutePath, second.absolutePath)))
            cards += materialized.id
            app.characterRepository.save(materialized)
            assertEquals(materialized.chatBackground, materialized.chatBackgroundImages.first())
            val imported = app.characterCardTransferService.importNew(app.characterCardTransferService.decode(
                app.characterCardTransferService.exportJson(materialized.id))).also { cards += it.id }
            assertEquals(imported.chatBackground, imported.chatBackgroundImages.first())
            assertEquals(2, imported.chatBackgroundImages.size)
            assertArrayEquals(first.readBytes(), File(imported.chatBackgroundImages[0]).readBytes())
            assertArrayEquals(second.readBytes(), File(imported.chatBackgroundImages[1]).readBytes())
        } finally { assets.deleteDraft(draft) }
    }

    @Test fun overwriteAndDeleteKeepBackgroundUsedByExistingConversation() = runBlocking<Unit> {
        val picture = File(app.cacheDir, "synthetic-gallery-${UUID.randomUUID()}.png").also { fill(it, Color.RED); files += it }
        val source = CharacterCard.create("synthetic-gallery-retention").copy(chatBackground = picture.absolutePath,
            chatBackgroundImages = listOf(picture.absolutePath))
        app.characterRepository.save(source)
        cards += source.id
        val session = ChatSession.create(source.id, "synthetic-gallery-session").copy(chatBackground = picture.absolutePath)
        app.chatRepository.createSession(session)
        sessions += session.id
        val packageData = app.characterCardTransferService.decode(app.characterCardTransferService.exportJson(source.id))
        app.characterCardTransferService.overwrite(source.id, packageData)
        assertTrue(picture.isFile)
        app.characterCardTransferService.deleteCard(source.id)
        cards.remove(source.id)
        assertTrue(picture.isFile)
        assertEquals(picture.absolutePath, app.chatRepository.getSession(session.id)!!.chatBackground)
    }

    private fun fill(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(37, 23, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(color); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
